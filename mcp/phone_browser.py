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

import json
import os
import re
import threading
import time
import urllib.error

from phone_cdp import CdpError, CdpSession, DevtoolsLink

from browser_common import AGENT_JS, BrowserError, ref_id
from browser_session import BrowserSession
from browser_actions import BrowserActions
from browser_accessibility import BrowserAccessibility

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
        "Sensitive actions follow trusted host permissions: full permits; auto/ask requires host approval; deny refuses. "
        "Model confirm/allow_sensitive flags do not grant permissions."
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
            "confirm": {"type": "boolean", "description": "Legacy compatibility flag; authorization comes from the host."},
            "key": {"type": "string"},
            "to": {"type": "string", "description": "scroll: top | bottom."},
            "dy": {"type": "integer", "description": "scroll: CSS pixels, negative = up."},
            "op": {"type": "string", "enum": ["inspect", "fill"]},
            "values": {"type": "object", "description": "form fill: {label, name or placeholder: value}. Checkbox: true/false. Radio/select: option text."},
            "allow_sensitive": {"type": "boolean", "description": "Legacy compatibility flag; cannot grant host permission."},
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


class PhoneBrowser(BrowserSession, BrowserActions, BrowserAccessibility):
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
        with open(AGENT_JS, encoding="utf-8") as helper:
            self.agent_js = helper.read()
        self.shown = False


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
            out["code"] = "ATTACH_AVAILABLE" if any(r.get("kind") == "webview" for r in rows) else "NO_DEVTOOLS"
            out["attachable"] = [r for r in rows if r.get("kind") == "webview"]
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
                return {"ok": d.get("ok", False), "code": d.get("code"), "errmsg": d.get("errmsg"),
                        "mode": "accessibility", "content": d.get("ui_tree") or d.get("stdout", "")}
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
        rows = [r.copy() for r in list(self.requests.values()) if r["t"] >= since and (not flt or flt in r["url"].lower())]
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
