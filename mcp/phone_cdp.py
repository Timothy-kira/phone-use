"""Chrome DevTools Protocol over adb, standard library only.

Chrome on Android listens on the abstract unix socket `@chrome_devtools_remote` (other Chromium
browsers: `@<package>_devtools_remote`; debuggable WebViews: `@webview_devtools_remote_<pid>`).
Chromium accepts connections from the shell uid, so `adb forward tcp:N localabstract:<name>` gives
full CDP with nothing installed on the phone and no prompt - the mechanism chromedriver,
Playwright's Android support and chrome-devtools-mcp use too.

This module finds those sockets, forwards them, talks to the /json endpoints and holds one
websocket per tab (RFC 6455 client, text frames, ping/pong, fragmentation).
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import queue
import socket
import struct
import threading
import time
import urllib.error
import urllib.request

BROWSER_PACKAGES = {
    "chrome_devtools_remote": "com.android.chrome",
}


class CdpError(RuntimeError):
    def __init__(self, message: str, code: str = "CDP_ERROR") -> None:
        super().__init__(message)
        self.code = code


# --- websocket ---------------------------------------------------------------------------------

class WebSocket:
    """Minimal client side of RFC 6455: enough for CDP (text frames, no extensions)."""

    def __init__(self, host: str, port: int, path: str, timeout: float = 10) -> None:
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        # No Origin header: Chrome 111+ refuses websocket origins it was not told to allow.
        request = (f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
                   f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.sock.sendall(request.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise CdpError("websocket handshake closed", "CDP_CONNECT")
            head += chunk
        header, self.buffer = head.split(b"\r\n\r\n", 1)
        status = header.split(b"\r\n", 1)[0]
        if b" 101 " not in status + b" ":
            raise CdpError(f"websocket handshake refused: {status.decode(errors='replace')}", "CDP_CONNECT")
        expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        if expected not in header:
            raise CdpError("websocket handshake: bad accept key", "CDP_CONNECT")
        self.sock.settimeout(None)
        self.send_lock = threading.Lock()
        self.closed = False

    def _read(self, n: int) -> bytes:
        while len(self.buffer) < n:
            chunk = self.sock.recv(max(65536, n - len(self.buffer)))
            if not chunk:
                raise ConnectionError("websocket closed")
            self.buffer += chunk
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        header = bytes([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 1 << 16:
            header += bytes([0x80 | 126]) + struct.pack(">H", length)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", length)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload)) if length < 4096 else _mask_fast(payload, mask)
        with self.send_lock:
            self.sock.sendall(header + mask + masked)

    def send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode("utf-8"))

    def recv_text(self) -> str:
        parts: list[bytes] = []
        while True:
            b1, b2 = self._read(2)
            opcode, fin = b1 & 0x0F, b1 & 0x80
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if b2 & 0x80 else b""
            payload = self._read(length)
            if mask:
                payload = _mask_fast(payload, mask)
            if opcode == 0x8:
                self.closed = True
                raise ConnectionError("websocket closed by peer")
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            parts.append(payload)
            if fin:
                return b"".join(parts).decode("utf-8", "replace")

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self._send_frame(0x8, b"")
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def _mask_fast(payload: bytes, mask: bytes) -> bytes:
    n = len(payload)
    key = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(payload, "little") ^ int.from_bytes(key, "little")).to_bytes(n, "little")


# --- one CDP target (tab) ----------------------------------------------------------------------

class CdpSession:
    """A websocket to one target: request/response by id, events kept in a ring buffer."""

    def __init__(self, port: int, ws_path: str, target_id: str) -> None:
        self.target_id = target_id
        self.ws = WebSocket("127.0.0.1", port, ws_path)
        self.next_id = 0
        self.pending: dict[int, queue.Queue] = {}
        self.lock = threading.Lock()
        self.events: list[dict] = []
        self.listeners: list = []
        self.alive = True
        threading.Thread(target=self._reader, daemon=True, name="cdp-reader").start()

    def _reader(self) -> None:
        try:
            while True:
                msg = json.loads(self.ws.recv_text())
                if "id" in msg:
                    q = self.pending.pop(msg["id"], None)
                    if q:
                        q.put(msg)
                else:
                    msg["_t"] = time.time()
                    self.events.append(msg)
                    if len(self.events) > 2000:
                        del self.events[:500]
                    for listener in list(self.listeners):
                        try:
                            listener(msg)
                        except Exception:  # noqa: BLE001
                            pass
        except (ConnectionError, OSError, ValueError):
            pass
        finally:
            self.alive = False
            for q in list(self.pending.values()):
                q.put({"error": {"message": "connection to the tab closed"}})
            self.pending.clear()

    def call(self, method: str, params: dict | None = None, timeout: float = 15) -> dict:
        if not self.alive:
            raise CdpError("the tab connection is closed", "CDP_CLOSED")
        with self.lock:
            self.next_id += 1
            msg_id = self.next_id
        q: queue.Queue = queue.Queue()
        self.pending[msg_id] = q
        self.ws.send_text(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
        try:
            reply = q.get(timeout=timeout)
        except queue.Empty as exc:
            self.pending.pop(msg_id, None)
            raise CdpError(f"{method} did not answer within {timeout:.0f}s", "CDP_TIMEOUT") from exc
        if "error" in reply:
            raise CdpError(f"{method}: {reply['error'].get('message')}", "CDP_ERROR")
        return reply.get("result") or {}

    def evaluate(self, expression: str, timeout: float = 15, await_promise: bool = False):
        res = self.call("Runtime.evaluate", {"expression": expression, "returnByValue": True,
                                             "awaitPromise": await_promise, "userGesture": True}, timeout)
        if res.get("exceptionDetails"):
            detail = res["exceptionDetails"]
            text = (detail.get("exception") or {}).get("description") or detail.get("text") or "script error"
            raise CdpError(text.splitlines()[0][:300], "JS_ERROR")
        return (res.get("result") or {}).get("value")

    def close(self) -> None:
        self.alive = False
        self.ws.close()


# --- sockets and forwards ----------------------------------------------------------------------

def http_json(port: int, path: str, method: str = "GET", timeout: float = 5):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", "replace")
    try:
        return json.loads(body)
    except ValueError:
        return body


class DevtoolsLink:
    """Finds devtools sockets on the phone and keeps one forward per socket."""

    def __init__(self, adb) -> None:
        self.adb = adb  # PhoneLink.adb(*args, serial=True, timeout=30) -> stdout
        self.serial = ""
        self.ports: dict[str, int] = {}

    def sockets(self) -> list[dict]:
        out = self.adb("shell", "cat /proc/net/unix", timeout=10)
        names = sorted({line.split("@", 1)[1].strip() for line in out.splitlines()
                        if "@" in line and "devtools_remote" in line.split("@", 1)[1]})
        rows = []
        for name in names:
            row = {"socket": name}
            if name.startswith("webview_devtools_remote_") or "_webview_devtools_remote_" in name:
                pid = name.rsplit("_", 1)[-1]
                row["kind"] = "webview"
                row["pid"] = pid
                try:
                    row["package"] = self.adb("shell", f"cat /proc/{pid}/cmdline", timeout=5).split("\x00")[0].strip()
                except Exception:  # noqa: BLE001
                    row["package"] = ""
            else:
                row["kind"] = "browser"
                row["package"] = BROWSER_PACKAGES.get(name) or name.removesuffix("_devtools_remote")
            rows.append(row)
        return rows

    def forward(self, serial: str, name: str) -> int:
        remote = f"localabstract:{name}"
        listing = self.adb("forward", "--list", serial=False, timeout=10).splitlines()
        for line in listing:
            parts = line.split()
            if len(parts) == 3 and parts[0] == serial and parts[2] == remote and parts[1].startswith("tcp:"):
                return int(parts[1][4:])
        preferred = 29000 + int(hashlib.sha1((serial + name).encode()).hexdigest(), 16) % 800
        try:
            self.adb("forward", f"tcp:{preferred}", remote, timeout=10)
            return preferred
        except Exception:  # noqa: BLE001 - port taken: any free port
            port = self.adb("forward", "tcp:0", remote, timeout=10).strip()
            return int(port)

    def unforward(self, port: int) -> None:
        try:
            self.adb("forward", "--remove", f"tcp:{port}", timeout=10)
        except Exception:  # noqa: BLE001
            pass

    @staticmethod
    def version(port: int) -> dict:
        data = http_json(port, "/json/version")
        return data if isinstance(data, dict) else {}

    @staticmethod
    def targets(port: int) -> list[dict]:
        data = http_json(port, "/json/list")
        return [t for t in data if t.get("type") == "page"] if isinstance(data, list) else []

    @staticmethod
    def new_tab(port: int, url: str) -> dict:
        path = "/json/new?" + urllib.request.quote(url, safe=":/?&=#%")
        try:
            return http_json(port, path, method="PUT")  # Chrome 111+ refuses GET
        except urllib.error.HTTPError:
            return http_json(port, path, method="GET")

    @staticmethod
    def activate(port: int, target_id: str) -> None:
        try:
            http_json(port, f"/json/activate/{target_id}")
        except (urllib.error.URLError, OSError):
            pass

    @staticmethod
    def close_tab(port: int, target_id: str) -> None:
        http_json(port, f"/json/close/{target_id}")
