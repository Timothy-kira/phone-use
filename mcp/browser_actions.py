"""BrowserActions: one browser subsystem shared by PhoneBrowser."""
from __future__ import annotations
import time
from phone_cdp import CdpError
from host_policy import require_sensitive
from browser_common import BrowserError, ref_id, norm, KEYS

class BrowserActions:
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
                "dispatched": True, "effect": "unknown" if not after else ("changed" if before != after else "unchanged"),
                "evidence": {"before": before, "after": after},
                "summary": "; ".join(parts) or "no visible change (check with snapshot or screenshot)"}


    def do_click(self, args: dict) -> dict:
        try:
            rid = self.resolve(args)
        except BrowserError as exc:
            if exc.code in ("NO_DEVTOOLS", "A11Y_REF"):
                return self.a11y_click(args)
            raise
        change = self.before_after(lambda: self.press(rid))
        return {"ok": True, "dispatched": True, "ref": f"e{rid}", "via": "touch", **change}


    def type_into(self, rid: int, value: str) -> dict:
        sensitive = bool(self.call_pu("sensitive", self.field_meta(rid)))
        if sensitive:
            denied = require_sensitive()
            if denied: return denied
        box = self.call_pu("box", rid)
        if not box.get("ok"): return box
        if not box.get("hit"):
            return {"ok": False, "code": "TARGET_OCCLUDED", "dispatched": False, "covered_by": box.get("covered_by")}
        before = self.call_pu("valueOf", rid)
        focus = self.call_pu("focusAndSelect", rid)
        if not focus.get("ok") or not focus.get("active"):
            return {"ok": False, "code": "INPUT_FOCUS_FAILED", "dispatched": False}
        typ = focus.get("type", "")
        if typ in ("date", "time", "datetime-local", "month", "week", "color", "range"):
            res = self.call_pu("setValue", rid, value)
            now, via = res.get("value"), "value setter"
        else:
            session = self.tab()
            if value == "":
                for phase in ("rawKeyDown", "keyUp"):
                    session.call("Input.dispatchKeyEvent", {"type": phase, "key": "Backspace", "code": "Backspace", "windowsVirtualKeyCode": 8}, timeout=5)
            else:
                session.call("Input.insertText", {"text": value}, timeout=10)
            self.call_pu("fireChange", rid)
            now, via = self.call_pu("valueOf", rid), "insertText"
        matched = now == value
        return {"ok": matched, "code": "TEXT_VERIFIED" if matched else "TEXT_NOT_APPLIED",
                "dispatched": True, "text_verified": matched, "effect": ("changed" if now != before else "unchanged") if matched else "unknown",
                "value": "•••" if sensitive else now, "via": via,
                "errmsg": "" if matched else "Exact read-back differs; inspect the value before another operation."}


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
        res = self.type_into(rid, value)
        if not res.get("ok"):
            return res
        out = {"ok": True, "ref": f"e{rid}", "field": info.get("name"), **res, "summary": f"filled {info.get('name') or 'field'}"}
        if args.get("submit"):
            out.update(self.submit_from(rid))
        return out


    def field_meta(self, rid: int) -> str:
        return self.js(f"(function(){{const e=window.__pu.get({rid}); if(!e) return ''; "
                       f"return [e.name,e.id,e.autocomplete,e.type,(e.labels&&e.labels[0]?e.labels[0].innerText:''),e.placeholder,e.getAttribute('aria-label')].join(' ');}})()") or ""


    def submit_from(self, rid: int) -> dict:
        info = self.call_pu("submitInfo", rid)
        if info.get("ok") and info.get("risky") and require_sensitive():
            return {"submitted": False, "code": "CONFIRM_REQUIRED",
                    "errmsg": "Submitting this sensitive form requires host approval; model confirmation cannot grant it."}
        session = self.tab()

        def act() -> None:
            session.call("Input.dispatchKeyEvent", {"type": "rawKeyDown", "key": "Enter", "code": "Enter",
                                                     "windowsVirtualKeyCode": 13}, timeout=5)
            session.call("Input.dispatchKeyEvent", {"type": "char", "key": "Enter", "text": "\r"}, timeout=5)
            session.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": "Enter", "code": "Enter",
                                                     "windowsVirtualKeyCode": 13}, timeout=5)
        change = self.before_after(act)
        return {"submitted": True, "submission_verified": False, **change}


    def do_select(self, args: dict) -> dict:
        rid = self.resolve(args)
        box = self.call_pu("box", rid)
        if not box.get("ok"): return box
        if not box.get("hit"):
            return {"ok": False, "code": "TARGET_OCCLUDED", "dispatched": False, "covered_by": box.get("covered_by")}
        if box.get("risky") or self.call_pu("sensitive", self.field_meta(rid)):
            denied = require_sensitive()
            if denied: return denied
        res = self.call_pu("selectOption", rid, args.get("value", ""))
        res["dispatched"] = res.get("dispatched", res.get("ok", False))
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


    def do_form(self, args: dict) -> dict:
        op = args.get("op") or ("fill" if args.get("values") else "inspect")
        try:
            info = self.call_pu("form", ref_id(args.get("ref")), timeout=20)
        except BrowserError as exc:
            if exc.code == "NO_DEVTOOLS":
                result = self.a11y_form(op, args.get("values") or {})
                if args.get("submit"):
                    result.update(ok=False, submitted=False, code="SUBMIT_UNSUPPORTED",
                                  errmsg="Accessibility form values were handled, but submission needs an observed button click.")
                return result
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
                            **self.fill_field(field, value)})
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
                if sub.get("ok") and (sub.get("risky") or (info.get("form") or {}).get("risky")) and require_sensitive():
                    out.update(submitted=False, code="CONFIRM_REQUIRED",
                               errmsg="Submitting this sensitive form requires host approval; model confirmation cannot grant it.")
                elif sub.get("ok") and sub.get("button"):
                    clicked = self.do_click({"ref": sub["button"]})
                    out.update(clicked, submitted=clicked.get("ok", False))
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
        """One real touch; obstruction is returned rather than bypassed."""
        box = self.call_pu("box", rid)
        if not box.get("ok"):
            raise BrowserError(box.get("code", "REF_STALE"), box.get("errmsg", "Target is unavailable."))
        if box.get("risky"):
            denied = require_sensitive()
            if denied:
                raise BrowserError(denied["code"], denied["errmsg"], dispatched=False)
        if not box.get("hit") or not box.get("w") or not box.get("h"):
            raise BrowserError("TARGET_OCCLUDED", "The target cannot receive a real touch. Observe the covering element.",
                               dispatched=False, covered_by=box.get("covered_by"), ref=f"e{rid}")
        self.touch(box["x"], box["y"])
        time.sleep(0.15)
        return "touch"


    def fill_field(self, field: dict, value) -> dict:
        typ = field.get("type")
        if field.get("readonly"):
            return {"status": "rejected", "message": "read-only field"}
        if field.get("sensitive") and require_sensitive():
            return {"status": "skipped_sensitive", "message": "host approval required for sensitive field"}
        rid = ref_id(field["ref"])
        if typ == "select":
            res = self.do_select({"ref": field["ref"], "value": str(value)})
            return {"status": "filled", "selected": res.get("selected")} if res.get("ok") else {"status": "rejected", "message": res.get("code"), "options": res.get("options")}
        if typ in ("checkbox", "switch"):
            want = self.truthy(value)
            if bool(field.get("checked")) == want:
                return {"status": "unchanged", "checked": want}
            via = self.press(rid)
            now = self.call_pu("valueOf", rid)
            return {"status": "filled" if now == want else "rejected", "checked": now, "via": via}
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
            return {"status": "filled" if ok else "rejected", "selected": pick.get("label"), "via": via}
        res = self.type_into(rid, str(value))
        if not res.get("ok"):
            return {"status": "rejected", "message": res.get("code") or res.get("errmsg")}
        return {"status": "filled", "via": res.get("via")}


