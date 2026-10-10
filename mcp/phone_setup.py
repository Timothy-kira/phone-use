"""Readiness of this computer and phone for Phone Use, and the installs it may offer.

  python      - a `python` command (the plugin's MCP server and hook start with it; some Linux
                distributions ship only `python3`)
  adb         - Android platform-tools on this computer
  usb_access  - Linux only: permission to open the phone's USB device (udev rules + plugdev group)
  device      - a phone attached over USB / wireless debugging and authorized for this computer

Core Phone Use needs no app. Custom text fields optionally use the owned input companion. Nothing here installs without confirm=true;
without it, install() reports exactly what it would run, so the agent can ask the user first. Steps
that need administrator rights go through pkexec, which shows the system's own password dialog to
the user - the agent never handles the password. Without pkexec (or a desktop to show it on), the
answer is the command for the user to run in their terminal.
A "no" is remembered in ~/.phone-use/setup.json and the session-start offer stops until the user
asks again (action="reset").
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
import time

STATE_DIR = os.path.join(os.path.expanduser("~"), ".phone-use")
STATE_PATH = os.path.join(STATE_DIR, "setup.json")
STEPS = ("python", "adb", "usb_access", "device")
INSTALLABLE = ("python", "adb", "usb_access", "owned_input")

SETUP_TOOL = {
    "name": "phone_setup",
    "description": (
        "Check whether this computer and phone are ready for Phone Use. action=check lists what is "
        "missing (a `python` command, adb on this computer, on Linux USB access to the phone, an attached "
        "and authorized phone). action=install step=python|adb|usb_access|owned_input installs that piece; without "
        "confirm=true it only describes what it would run - ask the user first (offer: install it for me / "
        "I'll do it myself / skip), then repeat with confirm=true. Admin steps open the system's own password "
        "dialog for the user. action=decline records that the user does not want setup now (stops the "
        "session-start offer); action=reset clears that."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["check", "install", "decline", "reset"]},
            "step": {"type": "string", "enum": list(INSTALLABLE)},
            "confirm": {"type": "boolean", "description": "true only after the user agreed to this step."},
        },
        "required": ["action"],
        "additionalProperties": False,
    },
}

# Linux package managers: (binary, install command prefix, {piece: package}).
LINUX_PACKAGES = (
    ("apt-get", ["apt-get", "install", "-y"],
     {"python": "python-is-python3", "adb": "adb", "usb_access": "android-sdk-platform-tools-common"}),
    ("dnf", ["dnf", "install", "-y"],
     {"python": "python-unversioned-command", "adb": "android-tools", "usb_access": "android-tools"}),
    ("pacman", ["pacman", "-S", "--noconfirm"],
     {"python": "python", "adb": "android-tools", "usb_access": "android-udev"}),
    ("zypper", ["zypper", "--non-interactive", "install"],
     {"python": "python-is-python3", "adb": "android-tools", "usb_access": "android-udev-rules"}),
)


def load_state() -> dict:
    try:
        with open(STATE_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def save_state(state: dict) -> None:
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(STATE_PATH, "w", encoding="utf-8") as fh:
        json.dump(state, fh, ensure_ascii=False, indent=2)


def run(cmd: list[str], timeout: float) -> tuple[int, str]:
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=timeout, stdin=subprocess.DEVNULL)
    except FileNotFoundError:
        return 127, f"{cmd[0]} not found"
    except subprocess.TimeoutExpired:
        return 124, f"{' '.join(cmd[:3])} timed out after {int(timeout)}s"
    return done.returncode, ((done.stdout or "") + (done.stderr or "")).strip()


def linux_plan(piece: str) -> list[list[str]] | None:
    """The root commands that provide [piece] on this Linux, or None without a known package manager."""
    for binary, prefix, packages in LINUX_PACKAGES:
        if shutil.which(binary):
            cmds = [prefix + [packages[piece]]]
            if piece == "usb_access":
                user = os.environ.get("SUDO_USER") or os.environ.get("USER") or ""
                if user:
                    cmds.append(["usermod", "-aG", "plugdev", user])
                cmds.append(["udevadm", "control", "--reload-rules"])
            return cmds
    return None


def elevate(cmd: list[str]) -> list[str] | None:
    """cmd with administrator rights the user grants in the system dialog, or None if not possible here."""
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        return cmd
    if shutil.which("pkexec") and (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        return ["pkexec"] + cmd
    return None


class Setup:
    def __init__(self, link) -> None:  # link: phone_mcp.PhoneLink
        self.link = link

    def check(self) -> dict:
        steps: list[dict] = []
        linux = platform.system() == "Linux"

        def add(step: str, ok: bool, detail: str) -> bool:
            steps.append({"step": step, "ok": ok, "detail": detail})
            return ok

        # This code runs inside the plugin, so its interpreter evidently started; a missing `python`
        # command is caught earlier, by install.py, which can point the plugin at python3.
        add("python", True, sys.executable)
        if self.link.base_override:
            return self._summary(steps, order=["python"])
        try:
            add("adb", True, self.link.adb_path())
        except Exception as exc:  # noqa: BLE001
            add("adb", False, str(exc))
            return self._summary(steps)
        try:
            devices = self.link.devices()
        except Exception as exc:  # noqa: BLE001
            add("device", False, str(exc))
            return self._summary(steps)
        blocked = [d["serial"] for d in devices if d["state"] == "no permissions"]
        if blocked:
            add("usb_access", False, "adb sees the phone but may not open it (no permissions): this computer "
                "needs Android udev rules and the user in the plugdev group, then unplug/replug the phone")
            return self._summary(steps)
        if linux:
            add("usb_access", True, "adb can open attached phones")
        ready = [d["serial"] for d in devices if d["state"] == "device"]
        unauthorized = [d["serial"] for d in devices if d["state"] == "unauthorized"]
        if self.link.serial and self.link.serial not in ready:
            add("device", False, f"PHONE_USE_SERIAL={self.link.serial} is not attached and authorized")
        elif ready and (self.link.serial or len(ready) == 1):
            self.link.serial = self.link.serial or ready[0]
            add("device", True, self.link.serial)
        elif len(ready) > 1:
            add("device", False, "several devices attached (" + ", ".join(ready) + "); set PHONE_USE_SERIAL")
        elif unauthorized:
            add("device", False, "the phone is attached but this computer is not authorized: "
                "unlock it and tap Allow on the USB debugging prompt")
        else:
            add("device", False, "no phone attached: connect by USB with USB debugging on, "
                "or pair wireless debugging and run adb connect <ip:port>")
        summary = self._summary(steps)
        if self.link.serial in ready:
            try:
                package = self.link.adb("shell", "dumpsys package kira.phoneuse.server")
                summary["owned_input"] = {"optional": True, "installed": "kira.phoneuse.agentmode.OwnedInputService" in package,
                    "lease_permission": "android.permission.WRITE_SECURE_SETTINGS: granted=true" in package,
                    "setup": "phone_setup action=install step=owned_input confirm=true after user agreement"}
            except Exception as exc:
                summary["owned_input"] = {"optional": True, "error": str(exc)}
        return summary

    def _summary(self, steps: list[dict], order: list[str] | tuple[str, ...] = STEPS) -> dict:
        done = {s["step"] for s in steps if s["ok"]}
        order = [s for s in order if s != "usb_access" or platform.system() == "Linux"]
        pending = [s for s in order if s not in done]
        return {
            "ready": not pending,
            "steps": steps,
            "next": pending[0] if pending else None,
            "not_checked_yet": pending[1:],
            "declined": bool(load_state().get("declined")),
            "ask_user": ("For each missing step offer: install it for me / I'll do it myself / skip. "
                         "Install only after a yes (phone_setup action=install step=<step> confirm=true)."),
        }

    def _plan(self, step: str) -> tuple[list[list[str]], str] | dict:
        system = platform.system()
        if step == "owned_input":
            serial = self.link.serial or self.link.connect()
            adb = self.link.adb_path()
            apk = os.path.join(os.path.dirname(os.path.dirname(__file__)), "bin", "phone-use-server.apk")
            prefix = [adb, "-s", serial]
            return [prefix + ["install", "-r", apk], prefix + ["shell", "pm", "grant", "kira.phoneuse.server", "android.permission.WRITE_SECURE_SETTINGS"]], \
                "Installs Phone Use's background InputConnection companion and grants permission to restore the user's input service after each ten-second lease. It creates no keyboard and uses no clipboard."
        if step == "adb" and system == "Windows" and shutil.which("winget"):
            return [["winget", "install", "--id", "Google.PlatformTools", "-e", "--disable-interactivity",
                     "--accept-source-agreements", "--accept-package-agreements"]], \
                "Installs Google's Android platform-tools with winget; this accepts the winget source and package agreements."
        if step == "adb" and system == "Darwin" and shutil.which("brew"):
            return [["brew", "install", "--cask", "android-platform-tools"]], "Installs Google's Android platform-tools with Homebrew."
        if step == "python" and system == "Darwin":
            return {"ok": False, "note": "macOS: install Python 3 from python.org or `brew install python`, which provide `python3`; "
                    "then add `alias python=python3` - or reinstall the plugin with install.py, which can point it at python3."}
        if system == "Linux":
            cmds = linux_plan(step)
            if cmds is None:
                return {"ok": False, "note": f"No known package manager here; ask the user to install {step} "
                        "(https://developer.android.com/tools/releases/platform-tools for adb)."}
            notes = {"python": "Adds the `python` command pointing at python3.",
                     "adb": "Installs Android platform-tools (adb).",
                     "usb_access": "Installs Android udev rules and adds the user to the plugdev group; the user must "
                                   "then unplug and replug the phone (a group change may also need a new login)."}
            return cmds, notes[step] + " Needs administrator rights."
        return {"ok": False, "note": f"Nothing to install for {step} on {system}."}

    def install(self, step: str, confirm: bool) -> dict:
        if step not in INSTALLABLE:
            return {"ok": False, "error": "installable steps: " + ", ".join(INSTALLABLE) + "; the device step is the user's"}
        plan = self._plan(step)
        if isinstance(plan, dict):
            return plan
        cmds, note = plan
        root = platform.system() == "Linux" and step != "owned_input"
        runnable = [elevate(c) if root else c for c in cmds]
        shown = " && ".join(("sudo " if root else "") + " ".join(c) for c in cmds)
        if any(c is None for c in runnable):
            # No way to show a password dialog here (no pkexec / no desktop): the user runs it.
            return {"ok": False, "user_runs": shown, "note": note + " Ask the user to run this in their terminal, "
                    "then call phone_setup action=check."}
        if not confirm:
            return {"would_run": shown, "note": note + (" The system will ask the user for their password." if root else ""),
                    "confirm_required": True}
        outputs = []
        for cmd in runnable:
            code, out = run(cmd, 900)
            outputs.append(out[-800:])
            if code != 0:
                return {"ok": False, "ran": " ".join(cmd), "output": "\n".join(outputs)[-2000:],
                        "hint": "If the password dialog was cancelled, ask the user before trying again."}
        return {"ok": True, "ran": shown, "output": "\n".join(outputs)[-2000:], "check": self.check()}

    def handle(self, args: dict) -> dict:
        action = args.get("action", "check")
        if action == "check":
            result = self.check()
        elif action == "install":
            result = self.install(args.get("step", ""), bool(args.get("confirm")))
        elif action == "decline":
            save_state({**load_state(), "declined": True, "declined_at": time.strftime("%Y-%m-%d %H:%M:%S")})
            result = {"ok": True, "note": "Recorded. The session-start setup offer stays off until the user asks (action=reset)."}
        elif action == "reset":
            state = load_state()
            state.pop("declined", None)
            save_state(state)
            result = {"ok": True}
        else:
            result = {"ok": False, "error": f"unknown action {action}"}
        is_error = result.get("ok") is False
        return {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False, indent=2)}], "isError": is_error}
