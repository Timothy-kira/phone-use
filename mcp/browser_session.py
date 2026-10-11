"""BrowserSession: one browser subsystem shared by PhoneBrowser."""
from __future__ import annotations
import json
import os
import time
import urllib.error
from phone_cdp import CdpError, CdpSession, DevtoolsLink
from browser_common import BrowserError, CHROME_PACKAGES, REFERENCE_EPOCHS, INTERACTION_POLICY

class BrowserSession:
    def find_browser_socket(self) -> dict | None:
        # PHONE_USE_BROWSER_CDP=0: never use DevTools, read and act on pages through accessibility only
        # (for browsers whose DevTools misbehave, or to keep the browser untouched by remote debugging).
        if os.environ.get("PHONE_USE_BROWSER_CDP", "1").strip().lower() in ("0", "false", "no"):
            return None
        rows = self.dev.sockets()
        browsers = [r for r in rows if r["kind"] == "browser"]
        if self.socket_name:
            same = [r for r in rows if r["socket"] == self.socket_name]
            if same:
                return same[0]
        for pkg in CHROME_PACKAGES:
            for r in browsers:
                if r["package"] == pkg:
                    return r
        return browsers[0] if browsers else None


    def attach_socket(self, row: dict) -> None:
        self.link.connect()  # makes sure the serial is picked
        port = self.dev.forward(self.link.serial, row["socket"])
        if port != self.port or row["socket"] != self.socket_name:
            self.drop_session()
        self.port, self.socket_name, self.package = port, row["socket"], row.get("package", "")


    def drop_session(self) -> None:
        if self.session:
            try:
                self.session.close()
            except Exception:  # noqa: BLE001
                pass
        self.session = None
        self.target_id = ""
        self.dialog = None
        self.requests.clear()
        self.inflight.clear()


    def ensure_port(self) -> int:
        if self.port:
            try:
                self.dev.version(self.port)
                return self.port
            except (urllib.error.URLError, OSError, ValueError):
                self.drop_session()
                self.port = None
        row = self.find_browser_socket()
        if not row:
            raise BrowserError("NO_DEVTOOLS", "No browser with DevTools is running on the phone.")
        self.attach_socket(row)
        return self.port  # type: ignore[return-value]


    def targets(self) -> list[dict]:
        return DevtoolsLink.targets(self.ensure_port())


    def connect_target(self, target: dict) -> CdpSession:
        self.drop_session()
        ws = target.get("webSocketDebuggerUrl", "")
        path = "/" + ws.split("/", 3)[3] if ws.count("/") >= 3 else f"/devtools/page/{target['id']}"
        session = CdpSession(self.port, path, target["id"])  # type: ignore[arg-type]
        session.listeners.append(self.on_event)
        for domain in ("Page.enable", "Runtime.enable", "Network.enable", "DOM.enable"):
            try:
                session.call(domain, {}, timeout=8)
            except CdpError:
                pass
        self.session, self.target_id = session, target["id"]
        return session


    def tab(self) -> CdpSession:
        """The session of the current tab, reconnecting when the tab or the browser went away."""
        if self.session and self.session.alive:
            return self.session
        targets = self.targets()
        if not targets:
            raise BrowserError("NO_TAB", "The browser has no open tab. Use open with a url.")
        current = next((t for t in targets if t["id"] == self.target_id), None) or targets[0]
        return self.connect_target(current)


    def on_event(self, msg: dict) -> None:
        method, p = msg.get("method", ""), msg.get("params") or {}
        if method == "Page.javascriptDialogOpening":
            self.dialog = {"type": p.get("type"), "message": p.get("message", "")[:500], "default": p.get("defaultPrompt", "")}
        elif method == "Page.javascriptDialogClosed":
            self.dialog = None
        elif method == "Network.requestWillBeSent":
            rid = p.get("requestId")
            req = p.get("request") or {}
            self.inflight.add(rid)
            self.requests[rid] = {"t": msg["_t"], "url": req.get("url", "")[:300], "method": req.get("method"), "type": p.get("type")}
            if len(self.requests) > 600:
                for key in list(self.requests)[:200]:
                    self.requests.pop(key, None)
        elif method == "Network.responseReceived":
            row = self.requests.get(p.get("requestId"))
            resp = p.get("response") or {}
            if row is not None:
                row.update(status=resp.get("status"), mime=resp.get("mimeType"))
        elif method in ("Network.loadingFinished", "Network.loadingFailed"):
            self.inflight.discard(p.get("requestId"))
            if method == "Network.loadingFailed":
                row = self.requests.get(p.get("requestId"))
                if row is not None:
                    row["failed"] = p.get("errorText", "failed")


    def js(self, expr: str, timeout: float = 15):
        """Evaluate in the page with the helper installed."""
        if self.dialog:
            raise BrowserError("DIALOG_OPEN", "The page shows a dialog; answer it with dialog {accept}.", dialog=self.dialog)
        session = self.tab()
        try:
            installed = session.evaluate("!!(window.__pu && window.__pu.v === 4)", timeout=timeout)
            if not installed:
                epoch = next(REFERENCE_EPOCHS)
                session.evaluate("window.__pu_ref_base = " + str(epoch * 1000000) + ";window.__pu_policy = " + json.dumps(INTERACTION_POLICY, ensure_ascii=False) + ";" + self.agent_js, timeout=timeout)
            return session.evaluate(expr, timeout=timeout)
        except CdpError as exc:
            if exc.code == "CDP_TIMEOUT" and self.dialog:
                raise BrowserError("DIALOG_OPEN", "The page shows a dialog; answer it with dialog {accept}.", dialog=self.dialog) from exc
            raise


    def call_pu(self, fn: str, *args, timeout: float = 15):
        return self.js(f"window.__pu.{fn}({', '.join(json.dumps(a, ensure_ascii=False) for a in args)})", timeout)


    def settle(self, timeout: float = 12, min_wait: float = 0.3) -> dict:
        """Wait for the document to load and the network to go quiet (bounded)."""
        deadline = time.time() + timeout
        time.sleep(min_wait)
        quiet_since = None
        state: dict = {}
        while time.time() < deadline:
            try:
                state = self.call_pu("pageState", timeout=5) or {}
            except (CdpError, BrowserError):
                time.sleep(0.3)
                continue
            if state.get("ready") == "complete":
                if not self.inflight:
                    quiet_since = quiet_since or time.time()
                    if time.time() - quiet_since > 0.5:
                        break
                else:
                    quiet_since = None
                    if len(self.inflight) <= 2 and time.time() > deadline - timeout / 2:
                        break  # long-polling / analytics: good enough
            time.sleep(0.25)
        return state


