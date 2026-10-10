"""Screenshot archive on this computer: every phone screenshot is kept, citable, and cleaned up.

Each screenshot a phone tool returns is saved under ~/.phone-use/shots/<date>/<id>.jpg with an
index line (time, app, action, display, note). Tool results carry the id, the file path and a
Markdown snippet, so a report can cite exactly the frame it is talking about. `phone_shots` lists,
exports (copies into a report folder and returns relative Markdown), annotates, pins, deletes and
cleans up; unpinned shots older than the retention period (default 7 days) are removed once a day.
"""
from __future__ import annotations

import base64
import json
import os
import shutil
import threading
import time
import uuid

ROOT = os.path.join(os.path.expanduser("~"), ".phone-use", "shots")
INDEX = os.path.join(ROOT, "index.jsonl")
CONFIG = os.path.join(ROOT, "config.json")
DEFAULT_CONFIG = {"retention_days": 7, "auto_cleanup": True, "last_cleanup": 0}

SHOTS_TOOL = {
    "name": "phone_shots",
    "description": (
        "The archive of phone screenshots on this computer. Every screenshot a phone tool returns is saved "
        "and its result carries shot.id, shot.path and shot.markdown (web pages also keep their URL and title). "
        "action=list (filters: app, since "
        "'YYYY-MM-DD' or hours, session, limit) | get (id: returns the image) | export (ids, dest folder: "
        "copies them next to a report and returns relative Markdown image links) | annotate (id, note) | "
        "pin / unpin (ids: pinned shots are never cleaned up) | delete (ids) | cleanup (older_than_days, "
        "default the retention) | config (retention_days, auto_cleanup) | cite (ids, dest?: a numbered References "
        "list - image link, source page link and capture time - ready to paste at the end of a report)."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["list", "get", "export", "annotate", "pin", "unpin",
                                                  "delete", "cleanup", "config", "cite"]},
            "id": {"type": "string"},
            "ids": {"type": "array", "items": {"type": "string"}},
            "dest": {"type": "string", "description": "For export: folder to copy into (default ./report-assets)."},
            "note": {"type": "string"},
            "app": {"type": "string"},
            "since": {"type": "string", "description": "YYYY-MM-DD, or a number of hours back."},
            "session": {"type": "string"},
            "limit": {"type": "integer"},
            "older_than_days": {"type": "number"},
            "retention_days": {"type": "number"},
            "auto_cleanup": {"type": "boolean"},
        },
        "required": ["action"],
        "additionalProperties": False,
    },
}


class ShotStore:
    def __init__(self, session_id: str) -> None:
        self.session_id = session_id
        self.lock = threading.Lock()
        os.makedirs(ROOT, exist_ok=True)
        threading.Thread(target=self.auto_cleanup, daemon=True).start()

    # --- storage --------------------------------------------------------------------------

    def config(self) -> dict:
        try:
            with open(CONFIG, encoding="utf-8") as fh:
                return {**DEFAULT_CONFIG, **json.load(fh)}
        except (OSError, ValueError):
            return dict(DEFAULT_CONFIG)

    def save_config(self, cfg: dict) -> None:
        with open(CONFIG, "w", encoding="utf-8") as fh:
            json.dump(cfg, fh, indent=2)

    def entries(self) -> list[dict]:
        rows: dict[str, dict] = {}
        try:
            with open(INDEX, encoding="utf-8") as fh:
                for line in fh:
                    try:
                        row = json.loads(line)
                    except ValueError:
                        continue
                    if row.get("deleted"):
                        rows.pop(row.get("id"), None)
                    else:
                        rows[row["id"]] = {**rows.get(row["id"], {}), **row}
        except OSError:
            pass
        return [r for r in rows.values() if os.path.isfile(r.get("path", ""))]

    def append(self, row: dict) -> None:
        with self.lock, open(INDEX, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")

    def compact(self) -> None:
        """Rewrite the index with only live rows (after deletes and edits)."""
        live = self.entries()
        with self.lock:
            tmp = INDEX + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                for row in sorted(live, key=lambda r: r.get("ts", 0)):
                    fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            os.replace(tmp, INDEX)

    def save(self, data_b64: str, mime: str, meta: dict) -> dict:
        now = time.time()
        day = time.strftime("%Y-%m-%d", time.localtime(now))
        shot_id = time.strftime("%Y%m%d-%H%M%S", time.localtime(now)) + "-" + uuid.uuid4().hex[:4]
        ext = ".png" if "png" in (mime or "") else ".jpg"
        folder = os.path.join(ROOT, day)
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, shot_id + ext)
        with open(path, "wb") as fh:
            fh.write(base64.b64decode(data_b64))
        row = {"id": shot_id, "path": path, "ts": now, "time": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now)),
               "session": self.session_id, **{k: v for k, v in meta.items() if v not in (None, "")}}
        self.append(row)
        return row

    @staticmethod
    def cite(row: dict, path: str | None = None) -> str:
        """Image Markdown; for a web page also the source link, so a report cites both."""
        title = (row.get("title") or "").replace("[", "(").replace("]", ")")
        label = " ".join(x for x in (title or row.get("app") or row.get("package") or "phone", row.get("time", "")) if x)
        target = (path or row["path"]).replace("\\", "/")
        image = f"![{label}]({target})"
        if row.get("url"):
            return image + f"\n\nSource: [{title or row['url']}]({row['url']}) · captured {row.get('time', '')}"
        return image

    # --- cleanup --------------------------------------------------------------------------

    def cleanup(self, older_than_days: float | None = None) -> dict:
        days = older_than_days if older_than_days is not None else float(self.config()["retention_days"])
        cutoff = time.time() - days * 86400
        removed = 0
        freed = 0
        for row in self.entries():
            if row.get("pinned") or row.get("ts", 0) >= cutoff:
                continue
            try:
                freed += os.path.getsize(row["path"])
                os.remove(row["path"])
                removed += 1
            except OSError:
                pass
        for day in os.listdir(ROOT):
            folder = os.path.join(ROOT, day)
            if os.path.isdir(folder) and not os.listdir(folder):
                os.rmdir(folder)
        self.compact()
        cfg = self.config()
        cfg["last_cleanup"] = time.time()
        self.save_config(cfg)
        return {"ok": True, "removed": removed, "freed_mb": round(freed / 1e6, 1), "older_than_days": days}

    def auto_cleanup(self) -> None:
        try:
            cfg = self.config()
            if cfg.get("auto_cleanup") and time.time() - float(cfg.get("last_cleanup", 0)) > 86400:
                self.cleanup()
        except Exception:  # noqa: BLE001 - housekeeping must never break the bridge
            pass

    # --- tool -----------------------------------------------------------------------------

    def handle(self, args: dict) -> dict:
        action = args.get("action", "list")
        ids = list(args.get("ids") or []) + ([args["id"]] if args.get("id") else [])
        rows = {r["id"]: r for r in self.entries()}
        if action == "list":
            items = sorted(rows.values(), key=lambda r: r.get("ts", 0), reverse=True)
            if args.get("app"):
                needle = args["app"].lower()
                items = [r for r in items if needle in (r.get("app", "") + r.get("package", "")).lower()]
            if args.get("session"):
                items = [r for r in items if r.get("session") == args["session"]]
            since = str(args.get("since") or "")
            if since:
                try:
                    cutoff = time.time() - float(since) * 3600
                except ValueError:
                    cutoff = time.mktime(time.strptime(since[:10], "%Y-%m-%d"))
                items = [r for r in items if r.get("ts", 0) >= cutoff]
            items = items[: int(args.get("limit") or 50)]
            total_mb = sum(os.path.getsize(r["path"]) for r in rows.values()) / 1e6
            return text({"ok": True, "count": len(items), "archive_total": len(rows),
                         "archive_mb": round(total_mb, 1), "root": ROOT,
                         "shots": [{k: r.get(k) for k in ("id", "time", "app", "package", "action", "display_mode",
                                                          "note", "pinned", "path")} for r in items]})
        if action == "get":
            row = rows.get(ids[0]) if ids else None
            if not row:
                return text({"ok": False, "errmsg": "unknown id"}, error=True)
            with open(row["path"], "rb") as fh:
                data = base64.b64encode(fh.read()).decode()
            return {"content": [{"type": "text", "text": json.dumps({**row, "markdown": self.cite(row)}, ensure_ascii=False)},
                                {"type": "image", "data": data, "mimeType": "image/jpeg"}]}
        if action == "export":
            dest = os.path.abspath(args.get("dest") or os.path.join(os.getcwd(), "report-assets"))
            os.makedirs(dest, exist_ok=True)
            out = []
            for shot_id in ids:
                row = rows.get(shot_id)
                if not row:
                    out.append({"id": shot_id, "ok": False})
                    continue
                target = os.path.join(dest, os.path.basename(row["path"]))
                shutil.copy2(row["path"], target)
                rel = os.path.relpath(target, os.getcwd())
                out.append({"id": shot_id, "ok": True, "file": target, "markdown": self.cite(row, rel),
                            **({"url": row["url"], "title": row.get("title", "")} if row.get("url") else {})})
            return text({"ok": True, "dest": dest, "exported": out})
        if action == "cite":
            dest = args.get("dest")
            lines, missing = [], []
            for n, shot_id in enumerate(ids, 1):
                row = rows.get(shot_id)
                if not row:
                    missing.append(shot_id)
                    continue
                path = row["path"]
                if dest:
                    os.makedirs(os.path.abspath(dest), exist_ok=True)
                    path = os.path.join(os.path.abspath(dest), os.path.basename(row["path"]))
                    shutil.copy2(row["path"], path)
                    path = os.path.relpath(path, os.getcwd())
                path = path.replace("\\", "/")
                what = row.get("title") or row.get("note") or row.get("app") or row.get("package") or "phone screen"
                source = f" — [{row['url']}]({row['url']})" if row.get("url") else ""
                lines.append(f"{n}. [{what}]({path}){source}, captured {row.get('time', '')}")
            return text({"ok": not missing, "missing": missing, "references": "## References" + chr(10) * 2 + chr(10).join(lines)})
        if action in ("annotate", "pin", "unpin"):
            changed = []
            for shot_id in ids:
                if shot_id not in rows:
                    continue
                patch = {"id": shot_id}
                if action == "annotate":
                    patch["note"] = args.get("note", "")
                else:
                    patch["pinned"] = action == "pin"
                self.append(patch)
                changed.append(shot_id)
            return text({"ok": True, "changed": changed})
        if action == "delete":
            deleted = []
            for shot_id in ids:
                row = rows.get(shot_id)
                if row:
                    try:
                        os.remove(row["path"])
                    except OSError:
                        pass
                    self.append({"id": shot_id, "deleted": True})
                    deleted.append(shot_id)
            self.compact()
            return text({"ok": True, "deleted": deleted})
        if action == "cleanup":
            older = args.get("older_than_days")
            return text(self.cleanup(float(older) if older is not None else None))
        if action == "config":
            cfg = self.config()
            if args.get("retention_days") is not None:
                cfg["retention_days"] = max(0.0, float(args["retention_days"]))
            if args.get("auto_cleanup") is not None:
                cfg["auto_cleanup"] = bool(args["auto_cleanup"])
            self.save_config(cfg)
            return text({"ok": True, **cfg})
        return text({"ok": False, "errmsg": f"unknown action {action}"}, error=True)


def text(payload: dict, error: bool = False) -> dict:
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False, indent=1)}], "isError": error}
