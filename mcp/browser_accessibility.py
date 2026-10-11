"""BrowserAccessibility: one browser subsystem shared by PhoneBrowser."""
from __future__ import annotations
import re
from host_policy import require_sensitive
from browser_common import BrowserError, RISKY_WORDS, SENSITIVE_WORDS

class BrowserAccessibility:
    def a11y_snapshot(self) -> dict:
        data = self.display_call({"action": "dump_tree"})
        return {"ok": data.get("ok", False), "mode": "accessibility", "code": data.get("code") or "ACCESSIBILITY_FALLBACK",
                "page": data.get("ui_tree") or data.get("stdout", ""), "snapshot_id": data.get("snapshot_id"),
                "hint": "Refs here are #n accessibility nodes: click {ref:'#n'} / fill {ref:'#n'} work, or use agent_display."}


    def a11y_screenshot(self, state: dict | None = None) -> dict:
        data = self.display_call({"action": "screenshot"})
        shot = data.get("shot") or {}
        out = {"ok": data.get("ok", False), "mode": "screen", "shot": shot,
               "summary": "screenshot of the agent screen"}
        if state:
            out["cite"] = self.page_cite(state, {"id": shot.get("id", ""), "path": shot.get("path", "")} if shot else None)
            if shot.get("id"):
                self.shots.append({"id": shot["id"], "url": state.get("url"), "title": state.get("title")})
        if data.get("_image"):
            out["_image"] = data["_image"]
        return out


    def a11y_click(self, args: dict) -> dict:
        query = str(args.get("ref") or args.get("text") or "")
        if not query:
            raise BrowserError("BAD_ARGS", "click needs ref or text.")
        if RISKY_WORDS.search(query) and require_sensitive():
            return require_sensitive()
        data = self.display_call({"action": "click_node", "query": query, **({"confirm": True} if args.get("confirm") else {})})
        return {"ok": data.get("ok", False), **{k: data.get(k) for k in ("code", "errmsg", "dispatched", "used_backend") if k in data}, "mode": "accessibility",
                "summary": (data.get("tree_diff") or {}).get("summary", "") if isinstance(data.get("tree_diff"), dict) else ""}


    def a11y_fill(self, query, value: str) -> dict:
        if not query:
            raise BrowserError("BAD_ARGS", "fill needs an accessibility ref or text.")
        # The common text protocol focuses, replaces once, and reads back the exact value.
        typed = self.text_call({"action": "set", "query": str(query), "text": value})
        return {**typed, "mode": "accessibility", "summary": f"{query}: " + ("verified write" if typed.get("text_verified") else typed.get("code", "unverified"))}


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
            field, _ = self.match_field(key, fields, used)
            if not field:
                results.append({"key": key, "status": "not_found"})
                continue
            used.add(field["ref"])
            if SENSITIVE_WORDS.search(field.get("label", "")) and require_sensitive():
                results.append({"key": key, "ref": field["ref"], "status": "skipped_sensitive"})
                continue
            if field["type"] in ("check", "switch", "radio"):
                want = self.truthy(value)
                if want != field.get("checked"):
                    clicked = self.a11y_click({"ref": field["ref"]})
                    if not clicked.get("ok"):
                        results.append({"key": key, "ref": field["ref"], "status": "rejected", "code": clicked.get("code")})
                        continue
                current = next((f for f in self.a11y_fields() if f["ref"] == field["ref"]), None)
                results.append({"key": key, "ref": field["ref"], "status": "filled" if current and current.get("checked") == want else "unverified"})
            else:
                res = self.a11y_fill(field["ref"], str(value))
                results.append({"key": key, "ref": field["ref"], "status": "filled" if res.get("ok") else "rejected"})
        return {"ok": all(r["status"] == "filled" for r in results), "mode": "accessibility", "results": results,
                "summary": ", ".join(f"{r['key']}: {r['status']}" for r in results)[:200]}


