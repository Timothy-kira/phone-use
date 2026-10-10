"""phone_browser: drive the phone's Chrome (or any debuggable Chromium browser / WebView) over CDP.

The page is read and operated through the DevTools Protocol: a compact text snapshot with element
refs (e12), clicks as real touches at the element's centre, typing with input events that web
frameworks see, form inspection and auto-fill, extraction (text, Markdown, links, tables) and
screenshots that are archived and citable together with the page's URL. The browser runs on the
agent's screen (the virtual display by default), so the user watches it in the phone window and
recordings include it.

Phones whose browser exposes no DevTools socket (Huawei Browser, most OEM browsers) still get
`open` (VIEW intent on the agent's screen) and an accessibility fallback for reading, clicking,
typing and forms through agent_display.
"""
from __future__ import annotations

import base64
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse

from phone_cdp import CdpError, CdpSession, DevtoolsLink

AGENT_JS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "browser_agent.js")
CHROME_PACKAGES = ("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
                   "com.microsoft.emmx", "com.brave.browser", "com.kiwibrowser.browser", "org.chromium.chrome")
RISKY_WORDS = re.compile(r"(\bpay\b|payment|purchase|\bbuy\b|checkout|place.?order|order.?now|confirm.?order|subscribe|"
                         r"donate|transfer|sign.?up|register|create.?account|delete|remove.?account|unsubscribe|支付|付款|购买|"
                         r"立即购|下单|提交订单|确认订单|结算|充值|转账|注册|开通|订阅|删除|注销|解绑)", re.I)
SENSITIVE_WORDS = re.compile(r"(pass(word|wd)?|pwd|passcode|card.?(number|no)|cvv|cvc|security.?code|ssn|iban|身份证|证件号|"
                             r"护照|密码|口令|银行卡|卡号|安全码|验证码)", re.I)
KEYS = {
    "enter": ("Enter", 13, "\r"), "tab": ("Tab", 9, "\t"), "backspace": ("Backspace", 8, ""),
    "delete": ("Delete", 46, ""), "escape": ("Escape", 27, ""), "esc": ("Escape", 27, ""),
    "arrowdown": ("ArrowDown", 40, ""), "down": ("ArrowDown", 40, ""), "arrowup": ("ArrowUp", 38, ""),
    "up": ("ArrowUp", 38, ""), "arrowleft": ("ArrowLeft", 37, ""), "left": ("ArrowLeft", 37, ""),
    "arrowright": ("ArrowRight", 39, ""), "right": ("ArrowRight", 39, ""), "pagedown": ("PageDown", 34, ""),
    "pageup": ("PageUp", 33, ""), "home": ("Home", 36, ""), "end": ("End", 35, ""), "space": (" ", 32, " "),
}

BROWSER_TOOL = {
    "name": "phone_browser",
    "description": (
        "Use the web on the phone: Chrome (or another Chromium browser, or a debuggable in-app WebView) driven through "
        "the DevTools Protocol, on the agent's screen shown in the phone window. Prefer it over agent_display for web "
        "pages. Actions:\n"
        "- open {url, new_tab?}: open a page (starts the browser on the agent's screen if needed).\n"
        "- snapshot {max_chars?, offset?, interactive_only?, ref?}: the page as text - headings, text, and every "
        "interactive element as [eN] kind \"name\" (·off = outside the viewport). Includes content below the fold.\n"
        "- click {ref | text, confirm?}: tap an element by ref, or by its visible text.\n"
        "- fill {ref | text, value, submit?}: replace a field's content (input events fire, any language).\n"
        "- select {ref, value} | press {key: enter|tab|escape|arrowdown|...} | scroll {to: top|bottom | ref | dy}\n"
        "- form {op: inspect | fill, values: {label or name: value}, ref?, submit?, confirm?, allow_sensitive?}: "
        "inspect lists every field (label, type, required, options, value) and the buttons; fill maps values to fields "
        "by label/name/placeholder and reports filled / not_found / invalid per field. Never submits unless submit:true.\n"
        "- extract {what: text|markdown|links|tables, ref?, max_chars?, offset?, quote?}: page content for reports. "
        "quote:\"exact sentence\" returns a link that opens the page at that sentence.\n"
        "- screenshot {full_page?, ref?}: archived; the result's cite block has the image Markdown + source URL.\n"
        "- tabs | switch_tab {tab} | close_tab {tab} | back | forward | reload | wait_for {text|url_contains|gone, timeout}\n"
        "- dialog {accept, text?}: answer an alert/confirm/prompt the page opened.\n"
        "- network {since_s?, filter?}: recent requests (url, status, type).\n"
        "- status: browsers/WebViews with DevTools on the phone. attach {socket}: work in another one (e.g. a WebView).\n"
        "Purchases, payments, sign-ups, deletions: click/submit need confirm:true, set only after the user agreed. "
        "Passwords, card and ID numbers are only typed when the user gave them in this conversation (allow_sensitive)."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": [
                "status", "open", "snapshot", "click", "fill", "select", "press", "scroll", "form", "extract",
                "screenshot", "tabs", "switch_tab", "close_tab", "back", "forward", "reload", "wait_for", "dialog",
                "network", "attach", "eval"]},
            "url": {"type": "string"},
            "new_tab": {"type": "boolean"},
            "ref": {"type": "string", "description": "Element ref from snapshot/form, e.g. e12."},
            "text": {"type": "string", "description": "click/fill: find the element by visible text. wait_for: text to wait for."},
            "value": {"description": "fill: text to put in. select: option text or value."},
            "submit": {"type": "boolean"},
            "confirm": {"type": "boolean", "description": "Required for pay/buy/order/sign-up/delete controls, after the user agreed."},
            "key": {"type": "string"},
            "to": {"type": "string", "description": "scroll: top | bottom."},
            "dy": {"type": "integer", "description": "scroll: CSS pixels, negative = up."},
            "op": {"type": "string", "enum": ["inspect", "fill"]},
            "values": {"type": "object", "description": "form fill: {label, name or placeholder: value}. Checkbox: true/false. Radio/select: option text."},
            "allow_sensitive": {"type": "boolean", "description": "form/fill: the user typed these password/card/ID values themselves in this conversation."},
            "what": {"type": "string", "enum": ["text", "markdown", "links", "tables"]},
            "quote": {"type": "string"},
            "max_chars": {"type": "integer"},
            "offset": {"type": "integer"},
            "interactive_only": {"type": "boolean"},
            "full_page": {"type": "boolean"},
            "tab": {"type": "string", "description": "Tab id or index from tabs."},
            "url_contains": {"type": "string"},
            "gone": {"type": "string"},
            "timeout": {"type": "number", "description": "Seconds (wait_for, open)."},
            "accept": {"type": "boolean"},
            "since_s": {"type": "number"},
            "filter": {"type": "string"},
            "socket": {"type": "string"},
            "package": {"type": "string", "description": "open: browser package to use."},
            "js": {"type": "string", "description": "eval: only when the user asked for it."},
        },
        "required": ["action"],
        "additionalProperties": False,
    },
}


class BrowserError(Exception):
    def __init__(self, code: str, message: str, **extra) -> None:
        super().__init__(message)
        self.code = code
        self.extra = extra


def ref_id(ref) -> int | None:
    if ref is None or ref == "":
        return None
    m = re.fullmatch(r"\s*\[?e?(\d+)\]?\s*", str(ref))
    if not m:
        raise BrowserError("BAD_REF", f"'{ref}' is not an element ref like e12.")
    return int(m.group(1))


def norm(s: str) -> str:
    return re.sub(r"[\s_\-:：*()（）\[\]]+", "", str(s or "").lower())


class PhoneBrowser:
    def __init__(self, link, shots, display_call, record_log) -> None:
        self.link = link                    # PhoneLink
        self.shots = shots                  # ShotStore
        self.display_call = display_call    # (args) -> dict parsed agent_display result
        self.record_log = record_log        # (arguments, result) -> None
        self.dev = DevtoolsLink(link.adb)
        self.lock = threading.RLock()
        self.port: int | None = None
        self.socket_name = ""
        self.package = ""
        self.session: CdpSession | None = None
        self.target_id = ""
        self.dialog: dict | None = None
        self.requests: dict[str, dict] = {}
        self.inflight: set[str] = set()
        self.agent_js = open(AGENT_JS, encoding="utf-8").read()
        self.shown = False

    # --- plumbing --------------------------------------------------------------------------

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
            installed = session.evaluate("!!(window.__pu && window.__pu.v === 3)", timeout=timeout)
            if not installed:
                session.evaluate(self.agent_js, timeout=timeout)
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

    def page_cite(self, state: dict, shot: dict | None = None) -> dict:
        captured = time.strftime("%Y-%m-%d %H:%M")
        title = (state.get("title") or state.get("url") or "page").replace("]", ")").replace("[", "(")
        cite = {"url": state.get("url", ""), "title": state.get("title", ""), "captured_at": captured}
        source = f"[{title}]({state.get('url', '')}) · captured {captured}"
        if shot:
            cite.update(shot_id=shot["id"], shot_path=shot["path"])
            cite["markdown"] = f"![{title}]({shot['path'].replace(os.sep, '/')})\n\nSource: {source}"
        else:
            cite["markdown"] = source
        return cite

    # --- actions ---------------------------------------------------------------------------

    def handle(self, args: dict) -> dict:
        action = (args.get("action") or "").strip()
        started = time.time()
        with self.lock:
            try:
                fn = getattr(self, "do_" + action, None)
                if fn is None:
                    raise BrowserError("BAD_ACTION", f"Unknown action '{action}'.")
                data = fn(args)
            except BrowserError as exc:
                data = {"ok": False, "code": exc.code, "errmsg": str(exc), **exc.extra}
                if exc.code == "NO_DEVTOOLS":
                    data.setdefault("hint", "This phone's browser exposes no DevTools. open still works (it uses the "
                                             "phone's browser on the agent screen); read and act with agent_display "
                                             "(dump_tree, click_node, text), or form/click/fill here, which fall back to it.")
            except CdpError as exc:
                data = {"ok": False, "code": exc.code, "errmsg": str(exc)}
            except (urllib.error.URLError, OSError, ConnectionError) as exc:
                self.drop_session()
                self.port = None
                data = {"ok": False, "code": "BROWSER_UNREACHABLE", "errmsg": f"Lost the browser: {exc}. Call open or status."}
        image = data.pop("_image", None)
        try:
            summary = data.get("summary") or data.get("errmsg") or ""
            self.record_log({"action": "browser." + action, **{k: v for k, v in args.items()
                                                                if k in ("url", "ref", "text", "key", "to", "op", "what")}},
                            {"ok": data.get("ok", True), "code": data.get("code", ""), "tree_diff": {"summary": str(summary)[:200]}})
        except Exception:  # noqa: BLE001
            pass
        data["ms"] = int((time.time() - started) * 1000)
        content = [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}]
        if image:
            content.append({"type": "image", "data": image[0], "mimeType": image[1]})
        return {"content": content, "isError": not data.get("ok", True)}

    def do_status(self, args: dict) -> dict:
        self.link.connect()
        rows = self.dev.sockets()
        out = {"ok": True, "sockets": rows, "attached": self.socket_name or None}
        row = self.find_browser_socket()
        if row:
            try:
                self.attach_socket(row)
                version = DevtoolsLink.version(self.port)  # type: ignore[arg-type]
                out.update(browser=version.get("Browser"), package=version.get("Android-Package") or row.get("package"),
                           tabs=[{"id": t["id"], "title": t.get("title", "")[:80], "url": t.get("url", "")[:200]}
                                 for t in DevtoolsLink.targets(self.port)])  # type: ignore[arg-type]
            except (urllib.error.URLError, OSError, ValueError) as exc:
                out["error"] = str(exc)
        else:
            out["code"] = "NO_DEVTOOLS"
            out["hint"] = ("No running browser exposes DevTools. open {url} starts Chrome if it is installed; browsers "
                           "without DevTools (e.g. Huawei Browser) work through the accessibility fallback.")
        return out

    def do_attach(self, args: dict) -> dict:
        name = args.get("socket") or ""
        rows = self.dev.sockets()
        row = next((r for r in rows if r["socket"] == name or (name and r.get("package") == name)), None)
        if not row:
            raise BrowserError("NO_SUCH_SOCKET", f"No DevTools socket '{name}'.", sockets=rows)
        self.attach_socket(row)
        self.tab()
        return {"ok": True, "attached": row, "summary": f"attached to {row.get('package') or row['socket']}"}

    def show_browser(self, url: str, package: str | None) -> dict:
        """Open url with a VIEW intent on the agent's screen (also the no-DevTools path)."""
        args = {"action": "open_url", "url": url}
        if package:
            args["package"] = package
        data = self.display_call(args)
        if not data.get("ok", True) and "open_url" in str(data.get("errmsg", "")):
            # A phone server from before open_url: launch the browser, then navigate over CDP.
            data = self.display_call({"action": "launch", "target": package or "com.android.chrome"})
        self.shown = True
        return data

    def do_open(self, args: dict) -> dict:
        url = (args.get("url") or "").strip()
        if not url:
            raise BrowserError("BAD_ARGS", "open needs url.")
        if not re.match(r"^[a-z][a-z0-9+.-]*:", url, re.I):
            url = "https://" + url
        timeout = float(args.get("timeout") or 20)
        row = self.find_browser_socket()
        if row is None or not self.shown or args.get("package"):
            shown = self.show_browser(url, args.get("package"))
            if not shown.get("ok", True) and shown.get("code"):
                return {**{k: shown.get(k) for k in ("ok", "code", "errmsg", "hint")}, "url": url}
            deadline = time.time() + 12
            while row is None and time.time() < deadline:
                time.sleep(0.6)
                row = self.find_browser_socket()
            if row is None:
                return {"ok": True, "mode": "accessibility", "code": "NO_DEVTOOLS", "url": url,
                        "browser_package": shown.get("browser_package") or shown.get("package_name"),
                        "summary": f"opened {url} in the phone's browser (no DevTools)",
                        "hint": "This browser exposes no DevTools: read it with agent_display dump_tree / screenshot; "
                                "click/fill/form here fall back to accessibility."}
            self.attach_socket(row)
            # The intent opened the page in some tab; attach to it.
            time.sleep(0.8)
            tabs = self.targets()
            match = next((t for t in tabs if t.get("url", "").rstrip("/") == url.rstrip("/")), None)
            self.connect_target(match or tabs[0]) if tabs else None
            if match is None and tabs:
                self.tab().call("Page.navigate", {"url": url}, timeout=15)
        else:
            self.attach_socket(row)
            if args.get("new_tab"):
                target = DevtoolsLink.new_tab(self.port, url)  # type: ignore[arg-type]
                if isinstance(target, dict) and target.get("id"):
                    self.connect_target(target)
            else:
                res = self.tab().call("Page.navigate", {"url": url}, timeout=15)
                if res.get("errorText"):
                    return {"ok": False, "code": "NAVIGATION_FAILED", "errmsg": res["errorText"], "url": url}
        self.inflight.clear()
        state = self.settle(timeout)
        return {"ok": True, "url": state.get("url", url), "title": state.get("title", ""), "ready": state.get("ready"),
                "browser": self.package, "tab": self.target_id,
                "summary": f"opened {state.get('title') or url}",
                "next": "snapshot to read the page (refs eN), then click / fill / form / extract."}

    # --- reading ---------------------------------------------------------------------------

    def do_snapshot(self, args: dict) -> dict:
        try:
            snap = self.call_pu("snapshot", {"maxChars": int(args.get("max_chars") or 15000),
                                             "offset": int(args.get("offset") or 0),
                                             "interactiveOnly": bool(args.get("interactive_only")),
                                             "root": ref_id(args.get("ref"))}, timeout=20)
        except BrowserError as exc:
            if exc.code == "NO_DEVTOOLS":
                return self.a11y_snapshot()
            raise
        if not snap.get("ok"):
            return snap
        text = snap.pop("text", "")
        snap["page"] = "<untrusted_web_content>\n" + text + "\n</untrusted_web_content>"
        snap["cite"] = self.page_cite(snap)
        if snap.get("truncated"):
            snap["more"] = f"snapshot offset={snap['offset'] + int(args.get('max_chars') or 15000)} for the rest"
        snap["summary"] = f"snapshot of {snap.get('title') or snap.get('url')} ({snap.get('interactive')} interactive)"
        return snap

    def a11y_snapshot(self) -> dict:
        data = self.display_call({"action": "dump_tree"})
        return {"ok": data.get("ok", True), "mode": "accessibility", "code": "NO_DEVTOOLS",
                "page": data.get("ui_tree") or data.get("stdout", ""), "snapshot_id": data.get("snapshot_id"),
                "hint": "Refs here are #n accessibility nodes: click {ref:'#n'} / fill {ref:'#n'} work, or use agent_display."}

    def do_extract(self, args: dict) -> dict:
        what = args.get("what") or "text"
        if args.get("quote"):
            q = self.call_pu("quote", args["quote"])
            q["cite"] = {"url": q["url"], "title": q.get("title", ""), "captured_at": time.strftime("%Y-%m-%d %H:%M"),
                         "markdown": f"[“{q['text'][:80]}”]({q['url']})"}
            q["summary"] = "quote " + ("found" if q.get("found") else "NOT found on the page")
            if not q.get("found"):
                q["ok"] = False
                q["code"] = "QUOTE_NOT_FOUND"
                q["errmsg"] = "That exact text is not on the page; quote it as it appears."
            return q
        try:
            data = self.call_pu("extract", what, ref_id(args.get("ref")), int(args.get("max_chars") or 20000),
                                int(args.get("offset") or 0), timeout=25)
        except BrowserError as exc:
            if exc.code == "NO_DEVTOOLS":
                d = self.display_call({"action": "dump_tree", "observe": "text"})
                return {"ok": True, "mode": "accessibility", "content": d.get("ui_tree") or d.get("stdout", "")}
            raise
        if data.get("content") is not None:
            data["content"] = "<untrusted_web_content>\n" + data["content"] + "\n</untrusted_web_content>"
        data["cite"] = self.page_cite(data)
        data["summary"] = f"extract {what} from {data.get('title') or data.get('url')}"
        return data

    def do_screenshot(self, args: dict) -> dict:
        try:
            session = self.tab()
        except BrowserError as exc:
            if exc.code != "NO_DEVTOOLS":
                raise
            return self.a11y_screenshot()
        DevtoolsLink.activate(self.port, self.target_id)  # type: ignore[arg-type]
        state = self.call_pu("pageState")
        if args.get("ref") is not None:
            self.call_pu("box", ref_id(args.get("ref")))
        params: dict = {"format": "jpeg", "quality": 75}
        if args.get("full_page"):
            metrics = session.call("Page.getLayoutMetrics", {}, timeout=8)
            size = metrics.get("cssContentSize") or metrics.get("contentSize") or {}
            width = size.get("width") or 400
            height = min(size.get("height") or 2000, 12000)
            params.update(captureBeyondViewport=True, clip={"x": 0, "y": 0, "width": width, "height": height, "scale": 1})
        try:
            shot = session.call("Page.captureScreenshot", params, timeout=15)
        except CdpError:
            # A tab the browser does not draw (not on screen) never produces a frame.
            return self.a11y_screenshot(state)
        b64 = shot.get("data", "")
        row = self.shots.save(b64, "image/jpeg", {"app": "browser", "package": self.package, "action": "browser.screenshot",
                                                   "url": state.get("url"), "title": state.get("title")})
        cite = self.page_cite(state, row)
        return {"ok": True, "url": state.get("url"), "title": state.get("title"), "full_page": bool(args.get("full_page")),
                "shot": {"id": row["id"], "path": row["path"], "markdown": self.shots.cite(row)}, "cite": cite,
                "summary": "screenshot of " + (state.get("title") or state.get("url") or "page"), "_image": (b64, "image/jpeg")}

    def a11y_screenshot(self, state: dict | None = None) -> dict:
        data = self.display_call({"action": "screenshot"})
        shot = data.get("shot") or {}
        out = {"ok": data.get("ok", True), "mode": "screen", "shot": shot,
               "summary": "screenshot of the agent screen"}
        if state:
            out["cite"] = self.page_cite(state, {"id": shot.get("id", ""), "path": shot.get("path", "")} if shot else None)
            if shot.get("id"):
                self.shots.append({"id": shot["id"], "url": state.get("url"), "title": state.get("title")})
        if data.get("_image"):
            out["_image"] = data["_image"]
        return out

    def do_tabs(self, args: dict) -> dict:
        tabs = self.targets()
        return {"ok": True, "current": self.target_id,
                "tabs": [{"index": i, "id": t["id"], "title": t.get("title", "")[:100], "url": t.get("url", "")[:200]}
                         for i, t in enumerate(tabs)]}

    def pick_tab(self, spec) -> dict:
        tabs = self.targets()
        spec = str(spec if spec is not None else "")
        for i, t in enumerate(tabs):
            if spec in (t["id"], str(i)):
                return t
        raise BrowserError("NO_SUCH_TAB", f"No tab '{spec}'.", tabs=[{"index": i, "id": t["id"], "title": t.get("title", "")[:60]} for i, t in enumerate(tabs)])

    def do_switch_tab(self, args: dict) -> dict:
        t = self.pick_tab(args.get("tab"))
        DevtoolsLink.activate(self.port, t["id"])  # type: ignore[arg-type]
        self.connect_target(t)
        return {"ok": True, "tab": t["id"], "title": t.get("title"), "url": t.get("url"), "summary": "switched tab"}

    def do_close_tab(self, args: dict) -> dict:
        t = self.pick_tab(args.get("tab") if args.get("tab") is not None else self.target_id)
        DevtoolsLink.close_tab(self.port, t["id"])  # type: ignore[arg-type]
        if t["id"] == self.target_id:
            self.drop_session()
        return {"ok": True, "closed": t["id"], "summary": "closed tab"}

    def history(self, delta: int) -> dict:
        session = self.tab()
        hist = session.call("Page.getNavigationHistory", {}, timeout=8)
        index = hist.get("currentIndex", 0) + delta
        entries = hist.get("entries") or []
        if not 0 <= index < len(entries):
            return {"ok": False, "code": "NO_HISTORY", "errmsg": "Nothing to go " + ("back" if delta < 0 else "forward") + " to."}
        session.call("Page.navigateToHistoryEntry", {"entryId": entries[index]["id"]}, timeout=10)
        state = self.settle(10)
        return {"ok": True, "url": state.get("url"), "title": state.get("title"), "summary": "went " + ("back" if delta < 0 else "forward")}

    def do_back(self, args: dict) -> dict:
        return self.history(-1)

    def do_forward(self, args: dict) -> dict:
        return self.history(1)

    def do_reload(self, args: dict) -> dict:
        self.tab().call("Page.reload", {}, timeout=10)
        state = self.settle(15)
        return {"ok": True, "url": state.get("url"), "title": state.get("title"), "summary": "reloaded"}

    def do_wait_for(self, args: dict) -> dict:
        spec = {k: args[k] for k in ("text", "url_contains", "gone") if args.get(k)}
        if args.get("ref") is not None:
            spec["ref"] = ref_id(args["ref"])
        if not spec:
            state = self.settle(float(args.get("timeout") or 10))
            return {"ok": True, "url": state.get("url"), "title": state.get("title"), "summary": "page settled"}
        deadline = time.time() + float(args.get("timeout") or 10)
        while time.time() < deadline:
            try:
                if self.call_pu("waitCheck", spec, timeout=5):
                    state = self.call_pu("pageState")
                    return {"ok": True, "url": state.get("url"), "title": state.get("title"), "summary": "condition met"}
            except (CdpError, BrowserError) as exc:
                if isinstance(exc, BrowserError) and exc.code == "DIALOG_OPEN":
                    raise
            time.sleep(0.4)
        return {"ok": False, "code": "WAIT_TIMEOUT", "errmsg": f"Not met within {args.get('timeout') or 10}s: {spec}"}

    def do_dialog(self, args: dict) -> dict:
        if not self.dialog:
            return {"ok": False, "code": "NO_DIALOG", "errmsg": "No dialog is open."}
        if "accept" not in args:
            return {"ok": False, "code": "BAD_ARGS", "errmsg": "dialog needs accept true/false.", "dialog": self.dialog}
        params = {"accept": bool(args["accept"])}
        if args.get("text") is not None:
            params["promptText"] = str(args["text"])
        shown = self.dialog
        self.session.call("Page.handleJavaScriptDialog", params, timeout=8)  # type: ignore[union-attr]
        self.dialog = None
        return {"ok": True, "dialog": shown, "accepted": params["accept"], "summary": "dialog answered"}

    def do_network(self, args: dict) -> dict:
        since = time.time() - float(args.get("since_s") or 60)
        flt = (args.get("filter") or "").lower()
        rows = [r for r in self.requests.values() if r["t"] >= since and (not flt or flt in r["url"].lower())]
        rows.sort(key=lambda r: r["t"])
        out = [{"t": round(r["t"] - since, 2), "method": r.get("method"), "url": r["url"], "status": r.get("status"),
                "type": r.get("type"), "mime": r.get("mime"), **({"failed": r["failed"]} if r.get("failed") else {})}
               for r in rows[-int(args.get("max_chars") or 80):]]
        return {"ok": True, "count": len(out), "inflight": len(self.inflight), "requests": out}

    def do_eval(self, args: dict) -> dict:
        if os.environ.get("PHONE_USE_BROWSER_EVAL", "") != "1":
            return {"ok": False, "code": "EVAL_DISABLED",
                    "errmsg": "eval is off. Use snapshot/extract/form. The user can enable it with PHONE_USE_BROWSER_EVAL=1."}
        value = self.js(str(args.get("js") or ""), timeout=20)
        return {"ok": True, "value": value}

    # --- acting ----------------------------------------------------------------------------

    def resolve(self, args: dict) -> int:
        ref = args.get("ref")
        if ref is not None and str(ref).strip().startswith("#"):
            raise BrowserError("A11Y_REF", "That is an accessibility ref; it only works without DevTools.")
        rid = ref_id(ref)
        if rid is not None:
            return rid
        if args.get("text"):
            found = self.call_pu("findText", args["text"], True)
            if not found.get("ok"):
                raise BrowserError(found.get("code", "NOT_FOUND"), found.get("errmsg", "not found"))
            return found["id"]
        raise BrowserError("BAD_ARGS", "Pass ref (from snapshot) or text.")

    def touch(self, x: float, y: float) -> None:
        session = self.tab()
        point = [{"x": x, "y": y, "radiusX": 4, "radiusY": 4, "force": 1, "id": 1}]
        session.call("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": point}, timeout=8)
        time.sleep(0.05)
        session.call("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []}, timeout=8)

    def before_after(self, act) -> dict:
        before = self.call_pu("pageState")
        tabs_before = {t["id"] for t in self.targets()}
        self.inflight.clear()
        act()
        time.sleep(0.35)
        if self.dialog:
            return {"dialog": self.dialog, "summary": f"page opened a {self.dialog.get('type')} dialog"}
        try:
            after = self.call_pu("pageState", timeout=5)
        except (CdpError, BrowserError):
            after = {}
        navigated = bool(after) and after.get("url") != before.get("url")
        if navigated or (after and after.get("ready") != "complete") or self.inflight:
            after = self.settle(10, min_wait=0.1) or after
            navigated = after.get("url") != before.get("url")
        new_tabs = [t for t in self.targets() if t["id"] not in tabs_before]
        parts = []
        if navigated:
            parts.append(f"navigated to {after.get('title') or after.get('url')}")
        if new_tabs:
            parts.append(f"opened a new tab: {new_tabs[0].get('url', '')[:100]}")
        if after and not navigated and after.get("textLength") != before.get("textLength"):
            parts.append(f"page text changed ({before.get('textLength')} → {after.get('textLength')} chars)")
        if after and after.get("y") != before.get("y") and not navigated:
            parts.append(f"scrolled to y={after.get('y')}")
        if after.get("active") and after.get("active") != before.get("active"):
            parts.append(f"focus: {after.get('active')}")
        return {"url": after.get("url", before.get("url")), "title": after.get("title"), "navigated": navigated,
                "new_tab": new_tabs[0]["id"] if new_tabs else None,
                "summary": "; ".join(parts) or "no visible change (check with snapshot or screenshot)"}

    def do_click(self, args: dict) -> dict:
        try:
            rid = self.resolve(args)
        except BrowserError as exc:
            if exc.code in ("NO_DEVTOOLS", "A11Y_REF"):
                return self.a11y_click(args)
            raise
        box = self.call_pu("box", rid)
        if not box.get("ok"):
            return box
        if box.get("risky") and not args.get("confirm"):
            return {"ok": False, "code": "CONFIRM_REQUIRED", "ref": f"e{rid}", "name": box.get("name"),
                    "errmsg": f"'{box.get('name')}' looks like a purchase/payment/sign-up/delete control. Ask the user; "
                              "repeat with confirm:true only after they agree."}

        def act() -> None:
            if box.get("hit"):
                self.touch(box["x"], box["y"])
            else:
                # Covered (sticky header, overlay) or zero-size: the element's own click.
                self.js(f"(function(){{const e=window.__pu.get({rid}); if(e){{e.click();}}}})()")
        change = self.before_after(act)
        return {"ok": True, "ref": f"e{rid}", "clicked": box.get("name"), "kind": box.get("kind"),
                "via": "touch" if box.get("hit") else "element.click (covered by " + (box.get("covered_by") or "?") + ")",
                **change}

    def type_into(self, rid: int, value: str) -> dict:
        focus = self.call_pu("focusAndSelect", rid)
        if not focus.get("ok"):
            return focus
        session = self.tab()
        kind, typ = focus.get("kind"), focus.get("type", "")
        if typ in ("date", "time", "datetime-local", "month", "week", "color", "range"):
            res = self.call_pu("setValue", rid, value)
            return {"ok": res.get("ok", False), "value": res.get("value"), "via": "value setter"}
        if value == "":
            session.call("Input.dispatchKeyEvent", {"type": "rawKeyDown", "key": "Backspace", "code": "Backspace",
                                                     "windowsVirtualKeyCode": 8}, timeout=5)
            session.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": "Backspace", "code": "Backspace",
                                                     "windowsVirtualKeyCode": 8}, timeout=5)
        else:
            session.call("Input.insertText", {"text": value}, timeout=10)
        now = self.call_pu("valueOf", rid)
        via = "insertText"
        if kind != "editable" and isinstance(now, str) and now != value:
            # Masked inputs and some frameworks rewrite or drop typed text: set the value directly.
            res = self.call_pu("setValue", rid, value)
            now, via = res.get("value"), "value setter"
        self.call_pu("fireChange", rid)
        return {"ok": True, "value": now if not SENSITIVE_WORDS.search(typ) else "•••", "via": via}

    def do_fill(self, args: dict) -> dict:
        value = args.get("value", args.get("text") if args.get("ref") is not None else None)
        if value is None:
            raise BrowserError("BAD_ARGS", "fill needs value (and ref, or text to find the field).")
        value = str(value)
        try:
            rid = self.resolve({"ref": args.get("ref"), "text": args.get("text") if args.get("ref") is None else None})
        except BrowserError as exc:
            if exc.code in ("NO_DEVTOOLS", "A11Y_REF"):
                return self.a11y_fill(args.get("ref") or args.get("text"), value)
            raise
        info = self.call_pu("box", rid)
        if not info.get("ok"):
            return info
        if not args.get("allow_sensitive") and self.call_pu("sensitive", self.field_meta(rid)):
            return {"ok": False, "code": "SENSITIVE_FIELD", "ref": f"e{rid}",
                    "errmsg": "This is a password/card/ID field. Type it only with a value the user gave in this "
                              "conversation (allow_sensitive:true), or let the user type it themselves."}
        res = self.type_into(rid, value)
        if not res.get("ok"):
            return res
        out = {"ok": True, "ref": f"e{rid}", "field": info.get("name"), **res, "summary": f"filled {info.get('name') or 'field'}"}
        if args.get("submit"):
            out.update(self.submit_from(rid, bool(args.get("confirm"))))
        return out

    def field_meta(self, rid: int) -> str:
        return self.js(f"(function(){{const e=window.__pu.get({rid}); if(!e) return ''; "
                       f"return [e.name,e.id,e.autocomplete,e.type,(e.labels&&e.labels[0]?e.labels[0].innerText:''),e.placeholder,e.getAttribute('aria-label')].join(' ');}})()") or ""

    def submit_from(self, rid: int, confirm: bool) -> dict:
        info = self.call_pu("submitInfo", rid)
        if info.get("ok") and info.get("risky") and not confirm:
            return {"submitted": False, "code": "CONFIRM_REQUIRED",
                    "errmsg": f"Submitting looks like a purchase/payment/sign-up ('{info.get('text')}'). Ask the user first; then submit with confirm:true."}
        session = self.tab()

        def act() -> None:
            session.call("Input.dispatchKeyEvent", {"type": "rawKeyDown", "key": "Enter", "code": "Enter",
                                                     "windowsVirtualKeyCode": 13}, timeout=5)
            session.call("Input.dispatchKeyEvent", {"type": "char", "key": "Enter", "text": "\r"}, timeout=5)
            session.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": "Enter", "code": "Enter",
                                                     "windowsVirtualKeyCode": 13}, timeout=5)
        change = self.before_after(act)
        return {"submitted": True, **{k: change[k] for k in ("url", "navigated", "summary") if k in change}}

    def do_select(self, args: dict) -> dict:
        rid = self.resolve(args)
        res = self.call_pu("selectOption", rid, args.get("value", ""))
        res["ref"] = f"e{rid}"
        res["summary"] = f"selected {res.get('selected')}" if res.get("ok") else res.get("code")
        return res

    def do_press(self, args: dict) -> dict:
        key = str(args.get("key") or "").lower().replace(" ", "")
        if key not in KEYS:
            raise BrowserError("BAD_KEY", f"Unknown key '{args.get('key')}'. Use one of: {', '.join(sorted(KEYS))}.")
        name, code, text = KEYS[key]
        session = self.tab()

        def act() -> None:
            session.call("Input.dispatchKeyEvent", {"type": "rawKeyDown", "key": name, "code": name, "windowsVirtualKeyCode": code}, timeout=5)
            if text:
                session.call("Input.dispatchKeyEvent", {"type": "char", "key": name, "text": text}, timeout=5)
            session.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": name, "code": name, "windowsVirtualKeyCode": code}, timeout=5)
        return {"ok": True, "key": name, **self.before_after(act)}

    def do_scroll(self, args: dict) -> dict:
        spec: dict = {}
        if args.get("ref") is not None:
            spec["ref"] = ref_id(args["ref"])
        elif args.get("to") in ("top", "bottom"):
            spec["to"] = args["to"]
        else:
            spec["dy"] = int(args.get("dy") or 0)
        res = self.call_pu("scrollPage", spec)
        previous = None
        stable_since = time.monotonic()
        deadline = stable_since + 2
        stable = False
        while time.monotonic() < deadline:
            current = self.call_pu("pageState")
            position = (current.get("x"), current.get("y"), current.get("textLength"))
            if position != previous:
                stable_since = time.monotonic()
            elif time.monotonic() - stable_since >= .18:
                stable = True
                break
            previous = position
            time.sleep(.06)
        shot = self.do_screenshot({})
        for key in ("shot", "cite", "_image"):
            if key in shot:
                res[key] = shot[key]
        res["observation"] = {"after": "scroll", "stable": stable}
        res["summary"] = f"scrolled to y={res.get('y')} of {res.get('height')}; screenshot attached"
        if res.get("ok") and res.get("moved") is False:
            res.update(ok=False, code="SCROLL_NO_CHANGE", errmsg="At a scroll boundary or no movement; use the attached screenshot rather than repeating.")
        return res

    # --- forms -----------------------------------------------------------------------------

    def do_form(self, args: dict) -> dict:
        op = args.get("op") or ("fill" if args.get("values") else "inspect")
        try:
            info = self.call_pu("form", ref_id(args.get("ref")), timeout=20)
        except BrowserError as exc:
            if exc.code == "NO_DEVTOOLS":
                return self.a11y_form(op, args.get("values") or {})
            raise
        if op == "inspect":
            info["summary"] = f"{len(info.get('fields') or [])} fields, {len(info.get('buttons') or [])} buttons"
            info["next"] = "form {op:'fill', values:{<label>: <value>}} fills them; nothing is submitted unless submit:true."
            return info
        values = args.get("values") or {}
        if not isinstance(values, dict) or not values:
            raise BrowserError("BAD_ARGS", "form fill needs values: {label or name: value}.")
        fields = info.get("fields") or []
        results = []
        used: set[str] = set()
        for key, value in values.items():
            field, score = self.match_field(key, fields, used)
            if not field:
                results.append({"key": key, "status": "not_found",
                                "candidates": [f.get("label") or f.get("name") for f in fields if f["ref"] not in used][:12]})
                continue
            used.add(field["ref"])
            results.append({"key": key, "ref": field["ref"], "label": field.get("label") or field.get("name"),
                            **self.fill_field(field, value, bool(args.get("allow_sensitive")))})
        filled_ids = [ref_id(r["ref"]) for r in results if r.get("ref")]
        checks = self.call_pu("validity", filled_ids) if filled_ids else {}
        for r in results:
            c = checks.get(r.get("ref") or "", {})
            if r.get("status") == "filled" and c and not c.get("valid", True):
                r["status"] = "invalid"
                r["message"] = c.get("message")
            if c and "value" in c and r.get("status") in ("filled", "invalid"):
                r["now"] = c["value"]
        missing = [f.get("label") or f.get("name") for f in fields
                   if f.get("required") and f["ref"] not in used and not f.get("value") and not f.get("checked")
                   and not any(o.get("checked") for o in f.get("options") or [] if isinstance(o, dict))]
        out = {"ok": all(r["status"] in ("filled", "unchanged") for r in results), "results": results,
               "required_still_empty": missing,
               "summary": ", ".join(f"{r['key']}: {r['status']}" for r in results)[:200]}
        if args.get("submit"):
            anchor = info.get("form", {}) and info["form"].get("ref") if info.get("form") else (results[0].get("ref") if results else None)
            if not anchor:
                out["submitted"] = False
                out["errmsg"] = "No form to submit; click its button instead."
            elif missing:
                out["submitted"] = False
                out["errmsg"] = "Required fields are still empty: " + ", ".join(map(str, missing))
            else:
                rid = ref_id(anchor)
                sub = self.call_pu("submitInfo", rid)
                if sub.get("ok") and (sub.get("risky") or (info.get("form") or {}).get("risky")) and not args.get("confirm"):
                    out.update(submitted=False, code="CONFIRM_REQUIRED",
                               errmsg=f"Submitting looks like a purchase/payment/sign-up ('{sub.get('text')}'). Ask the user first, then form {{submit:true, confirm:true}}.")
                elif sub.get("ok") and sub.get("button"):
                    out.update(self.do_click({"ref": sub["button"], "confirm": True}) | {"submitted": True})
                    out["ok"] = True
                else:
                    change = self.before_after(lambda: self.call_pu("requestSubmit", ref_id(sub.get("form") or anchor)))
                    out.update(submitted=True, **change)
        return out

    @staticmethod
    def match_field(key: str, fields: list[dict], used: set[str]) -> tuple[dict | None, float]:
        k = norm(key)
        best, best_score = None, 0.0
        for f in fields:
            if f["ref"] in used:
                continue
            names = [f.get("label"), f.get("name"), f.get("id"), f.get("placeholder"), f.get("autocomplete")]
            score = 0.0
            for n in names:
                n = norm(n or "")
                if not n:
                    continue
                if n == k:
                    score = max(score, 1.0)
                elif k in n or n in k:
                    score = max(score, 0.75 * min(len(k), len(n)) / max(len(k), len(n)) + 0.2)
            if score > best_score:
                best, best_score = f, score
        return (best, best_score) if best_score >= 0.45 else (None, 0.0)

    @staticmethod
    def truthy(value) -> bool:
        return value is True or str(value).strip().lower() in ("1", "true", "yes", "y", "on", "checked", "是", "对", "勾选", "同意")

    def press(self, rid: int) -> str:
        """Touch the element where it is now (a real tap), or click it in the DOM when it is not hittable."""
        box = self.call_pu("box", rid)
        if box.get("hit"):
            self.touch(box["x"], box["y"])
            via = "touch"
        else:
            self.js(f"window.__pu.get({rid}).click()")
            via = "click"
        time.sleep(0.15)
        return via

    def fill_field(self, field: dict, value, allow_sensitive: bool) -> dict:
        typ = field.get("type")
        if field.get("readonly"):
            return {"status": "rejected", "message": "read-only field"}
        if field.get("sensitive") and not allow_sensitive:
            return {"status": "skipped_sensitive", "message": "password/card/ID field: only with values the user gave (allow_sensitive)"}
        rid = ref_id(field["ref"])
        if typ == "select":
            res = self.call_pu("selectOption", rid, str(value))
            return {"status": "filled", "selected": res.get("selected")} if res.get("ok") else {"status": "rejected", "message": res.get("code"), "options": res.get("options")}
        if typ in ("checkbox", "switch"):
            want = self.truthy(value)
            if bool(field.get("checked")) == want:
                return {"status": "unchanged", "checked": want}
            via = self.press(rid)
            now = self.call_pu("valueOf", rid)
            if now is not None and now != want and via == "touch":
                self.js(f"window.__pu.get({rid}).click()")  # touch missed (see radio below)
                time.sleep(0.15)
                now, via = self.call_pu("valueOf", rid), "click"
            return {"status": "filled" if now == want or now is None else "rejected", "checked": now, "via": via}
        if typ == "radio":
            want = norm(str(value))
            opts = field.get("options") or []
            pick = next((o for o in opts if norm(o.get("label")) == want or norm(o.get("value")) == want), None) or \
                next((o for o in opts if want and (want in norm(o.get("label")) or norm(o.get("label")) in want)), None)
            if not pick:
                return {"status": "rejected", "message": "no such option", "options": [o.get("label") for o in opts]}
            if pick.get("checked"):
                return {"status": "unchanged", "selected": pick.get("label")}
            orid = ref_id(pick["ref"])
            via = self.press(orid)
            ok = bool(self.call_pu("valueOf", orid))
            if not ok and via == "touch":
                # The touch can miss when the page moved after the box was measured (keyboard closing,
                # late layout); a DOM click on the same option is the same user choice.
                self.js(f"window.__pu.get({orid}).click()")
                time.sleep(0.15)
                ok, via = bool(self.call_pu("valueOf", orid)), "click"
            return {"status": "filled" if ok else "rejected", "selected": pick.get("label"), "via": via}
        res = self.type_into(rid, str(value))
        if not res.get("ok"):
            return {"status": "rejected", "message": res.get("code") or res.get("errmsg")}
        return {"status": "filled", "via": res.get("via")}

    # --- accessibility fallback (no DevTools) ----------------------------------------------

    def a11y_click(self, args: dict) -> dict:
        query = str(args.get("ref") or args.get("text") or "")
        if not query:
            raise BrowserError("BAD_ARGS", "click needs ref or text.")
        if RISKY_WORDS.search(query) and not args.get("confirm"):
            return {"ok": False, "code": "CONFIRM_REQUIRED", "errmsg": "Looks like a purchase/payment/sign-up/delete control; ask the user first."}
        data = self.display_call({"action": "click_node", "query": query, **({"confirm": True} if args.get("confirm") else {})})
        return {**{k: data.get(k) for k in ("ok", "code", "errmsg") if k in data}, "mode": "accessibility",
                "summary": (data.get("tree_diff") or {}).get("summary", "") if isinstance(data.get("tree_diff"), dict) else ""}

    def a11y_fill(self, query, value: str) -> dict:
        if not query:
            raise BrowserError("BAD_ARGS", "fill needs ref (#n) or text.")
        clicked = self.display_call({"action": "click_node", "query": str(query)})
        if clicked.get("ok") is False:
            return {"ok": False, "code": clicked.get("code"), "errmsg": clicked.get("errmsg"), "mode": "accessibility"}
        self.display_call({"action": "clear_text"})
        typed = self.display_call({"action": "text", "text": value})
        return {"ok": typed.get("ok", True), "mode": "accessibility", "summary": f"typed into {query}"}

    A11Y_FIELD = re.compile(r"^#(\d+) \[(input|check|switch|radio|select|spinner)\] (.*?)(?: tap| long| scroll| focus| hint=| \(|$)")

    def a11y_fields(self) -> list[dict]:
        data = self.display_call({"action": "dump_tree"})
        tree = data.get("ui_tree") or ""
        fields = []
        for line in tree.splitlines():
            m = self.A11Y_FIELD.match(line.strip())
            if not m:
                continue
            hint = re.search(r"hint=(.*?)(?: \(|$)", line)
            fields.append({"ref": "#" + m.group(1), "type": m.group(2), "label": (hint.group(1) if hint else m.group(3)).strip(),
                           "value": m.group(3).strip(), "name": "", "checked": "checked" in line})
        return fields

    def a11y_form(self, op: str, values: dict) -> dict:
        fields = self.a11y_fields()
        if op == "inspect":
            return {"ok": True, "mode": "accessibility", "fields": fields,
                    "summary": f"{len(fields)} fields (accessibility view)"}
        results, used = [], set()
        for key, value in values.items():
            field, _ = self.match_field(key, [dict(f, ref=f["ref"]) for f in fields], used)
            if not field:
                results.append({"key": key, "status": "not_found"})
                continue
            used.add(field["ref"])
            if SENSITIVE_WORDS.search(field.get("label", "")):
                results.append({"key": key, "ref": field["ref"], "status": "skipped_sensitive"})
                continue
            if field["type"] in ("check", "switch", "radio"):
                if self.truthy(value) != field.get("checked"):
                    self.display_call({"action": "click_node", "query": field["ref"]})
                results.append({"key": key, "ref": field["ref"], "status": "filled"})
            else:
                res = self.a11y_fill(field["ref"], str(value))
                results.append({"key": key, "ref": field["ref"], "status": "filled" if res.get("ok") else "rejected"})
        return {"ok": all(r["status"] == "filled" for r in results), "mode": "accessibility", "results": results,
                "summary": ", ".join(f"{r['key']}: {r['status']}" for r in results)[:200]}
