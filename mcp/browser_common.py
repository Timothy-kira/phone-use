"""Shared browser constants and errors; no execution paths."""
import os
import json
import re
import itertools
import secrets

# Disjoint numeric ref ranges across documents, tabs, devices, lanes and bridge processes.
REFERENCE_EPOCHS = itertools.count(secrets.randbelow(1 << 30) + 1)

AGENT_JS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "browser_agent.js")
CHROME_PACKAGES = ("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
                   "com.microsoft.emmx", "com.brave.browser", "com.kiwibrowser.browser", "org.chromium.chrome")
with open(os.path.join(os.path.dirname(__file__), "interaction_policy.json"), encoding="utf-8") as source:
    INTERACTION_POLICY = json.load(source)
RISKY_WORDS = re.compile(INTERACTION_POLICY["risky"], re.I)
SENSITIVE_WORDS = re.compile(INTERACTION_POLICY["sensitive"], re.I)
KEYS = {
    "enter": ("Enter", 13, "\r"), "tab": ("Tab", 9, "\t"), "backspace": ("Backspace", 8, ""),
    "delete": ("Delete", 46, ""), "escape": ("Escape", 27, ""), "esc": ("Escape", 27, ""),
    "arrowdown": ("ArrowDown", 40, ""), "down": ("ArrowDown", 40, ""), "arrowup": ("ArrowUp", 38, ""),
    "up": ("ArrowUp", 38, ""), "arrowleft": ("ArrowLeft", 37, ""), "left": ("ArrowLeft", 37, ""),
    "arrowright": ("ArrowRight", 39, ""), "right": ("ArrowRight", 39, ""), "pagedown": ("PageDown", 34, ""),
    "pageup": ("PageUp", 33, ""), "home": ("Home", 36, ""), "end": ("End", 35, ""), "space": (" ", 32, " "),
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


