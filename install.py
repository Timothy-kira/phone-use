"""Install the Phone Use plugin into the agent harnesses on this computer - asking first.

    python install.py                 detect harnesses, ask for each one, install where you say yes
    python install.py --list          report what is detected / installed, as JSON; changes nothing
    python install.py --host claude --host minimax --yes
                                      install into those without asking (for an agent that already
                                      asked the user in its own chat)
    python install.py --uninstall [--host ...]

Like EverMe's onboarding step, nothing is installed until the user answers yes for that harness;
"no" skips it. Supported: Claude Code, MiniMax Code, Codex, Cursor. Standard library only.
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
HOME = os.path.expanduser("~")
NAME = "phone-use"
SERVER = os.path.join(ROOT, "mcp", "phone_mcp.py")


def plugin_version() -> str:
    with open(os.path.join(ROOT, ".claude-plugin", "plugin.json"), encoding="utf-8") as fh:
        return json.load(fh).get("version", "?")


def read_json(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def write_json(path: str, data: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def run(cmd: list[str], timeout: float = 300) -> tuple[int, str]:
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=timeout, stdin=subprocess.DEVNULL)
        return done.returncode, ((done.stdout or "") + (done.stderr or "")).strip()
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 1, str(exc)


# --- Claude Code ---------------------------------------------------------------------------

class ClaudeCode:
    key, label = "claude", "Claude Code"
    settings = os.path.join(HOME, ".claude", "settings.json")

    def cli(self) -> str | None:
        found = shutil.which("claude")
        if found:
            return found
        appdata = os.environ.get("APPDATA", os.path.join(HOME, "AppData", "Roaming"))
        bundled = sorted(glob.glob(os.path.join(appdata, "Claude", "claude-code", "*", "*", "claude.exe")),
                         key=os.path.getmtime)
        return bundled[-1] if bundled else None

    def detected(self) -> bool:
        return os.path.isdir(os.path.join(HOME, ".claude")) or bool(self.cli())

    def installed(self) -> str | None:
        enabled = read_json(self.settings).get("enabledPlugins") or {}
        hit = next((k for k, v in enabled.items() if k.startswith(NAME + "@") and v), None)
        return hit

    def install(self) -> str:
        cli = self.cli()
        if cli:
            code, out = run([cli, "plugin", "marketplace", "add", ROOT])
            if code != 0 and "already" not in out.lower():
                raise RuntimeError(out[-400:])
            code, out = run([cli, "plugin", "install", f"{NAME}@{NAME}"])
            if code != 0:
                raise RuntimeError(out[-400:])
            return "installed with the claude CLI; start a new Claude Code session to load it"
        settings = read_json(self.settings)
        settings.setdefault("extraKnownMarketplaces", {})[NAME] = {"source": {"source": "directory", "path": ROOT}}
        settings.setdefault("enabledPlugins", {})[f"{NAME}@{NAME}"] = True
        write_json(self.settings, settings)
        return "registered in ~/.claude/settings.json; restart Claude Code to load it"

    def uninstall(self) -> str:
        hit = self.installed()
        cli = self.cli()
        if cli and hit:
            run([cli, "plugin", "uninstall", hit])
        settings = read_json(self.settings)
        for k in [k for k in (settings.get("enabledPlugins") or {}) if k.startswith(NAME + "@")]:
            settings["enabledPlugins"].pop(k)
        (settings.get("extraKnownMarketplaces") or {}).pop(NAME, None)
        write_json(self.settings, settings)
        return "removed"


def tree_digest(root: str, ignore) -> str:
    """Content digest of a package tree, honouring the same ignore rules as the copy."""
    import hashlib
    digest = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(root):
        skipped = ignore(dirpath, dirnames + filenames)
        dirnames[:] = sorted(d for d in dirnames if d not in skipped)
        for name in sorted(f for f in filenames if f not in skipped):
            path = os.path.join(dirpath, name)
            digest.update(os.path.relpath(path, root).replace("\\", "/").encode())
            with open(path, "rb") as fh:
                digest.update(hashlib.sha256(fh.read()).digest())
    return digest.hexdigest()


# --- MiniMax Code --------------------------------------------------------------------------

class MiniMaxCode:
    key, label = "minimax", "MiniMax Code"
    target = os.path.join(HOME, ".minimax", "plugins", NAME)

    def detected(self) -> bool:
        return os.path.isdir(os.path.join(HOME, ".minimax"))

    # The server/ sources and build outputs stay out: the runtime only needs bin/phone-use-server.apk,
    # and MiniMax caps a local package at 1,024 files.
    ignore = staticmethod(shutil.ignore_patterns(".git", "__pycache__", "*.pyc", "server", ".gradle", "build"))

    def installed(self) -> str | None:
        manifest = read_json(os.path.join(self.target, ".minimax-plugin", "plugin.json"))
        version = manifest.get("version")
        if version and tree_digest(self.target, self.ignore) != tree_digest(ROOT, self.ignore):
            return f"{version}, changed"  # same version, different files: offer the update
        return version

    def install(self) -> str:
        if os.path.abspath(self.target) == ROOT:
            return "this copy already is the MiniMax install"
        if os.path.isdir(self.target):
            shutil.rmtree(self.target)
        # copytree writes fresh files: MiniMax refuses packages that contain hardlinks or .git.
        shutil.copytree(ROOT, self.target, ignore=self.ignore)
        return f"copied to {self.target}; MiniMax Code picks it up without a restart"

    def uninstall(self) -> str:
        if os.path.isdir(self.target):
            shutil.rmtree(self.target)
        return "removed"


# --- Codex -------------------------------------------------------------------------------

class Codex:
    key, label = "codex", "Codex"
    config = os.path.join(HOME, ".codex", "config.toml")
    header = "[mcp_servers.phone]"

    def detected(self) -> bool:
        return os.path.isdir(os.path.join(HOME, ".codex")) or bool(shutil.which("codex"))

    def _text(self) -> str:
        try:
            with open(self.config, encoding="utf-8") as fh:
                return fh.read()
        except OSError:
            return ""

    def installed(self) -> str | None:
        text = self._text()
        if re.search(r'\[plugins\."phone-use@[^"\]]+"\]\s*enabled\s*=\s*true', text):
            return "native plugin enabled"
        return "configured" if self.header in text else None

    def install(self) -> str:
        if self.installed() == "native plugin enabled":
            # Do not register a second standalone MCP server beside an enabled native plugin.
            return "native plugin already enabled; no duplicate MCP registration added"
        if self.installed():
            return "already configured"
        block = (f"\n{self.header}\ncommand = {json.dumps(sys.executable)}\n"
                 f"args = [\"-B\", {json.dumps(SERVER)}]\n")
        os.makedirs(os.path.dirname(self.config), exist_ok=True)
        with open(self.config, "a", encoding="utf-8") as fh:
            fh.write(block)
        return f"added {self.header} to {self.config}"

    def uninstall(self) -> str:
        lines, out, skip = self._text().splitlines(keepends=True), [], False
        for line in lines:
            if line.strip().startswith("["):
                skip = line.strip() == self.header
            if not skip:
                out.append(line)
        with open(self.config, "w", encoding="utf-8") as fh:
            fh.write("".join(out))
        return "removed"


# --- Cursor ------------------------------------------------------------------------------

class Cursor:
    key, label = "cursor", "Cursor"
    config = os.path.join(HOME, ".cursor", "mcp.json")

    def detected(self) -> bool:
        return os.path.isdir(os.path.join(HOME, ".cursor"))

    def installed(self) -> str | None:
        return "configured" if "phone" in (read_json(self.config).get("mcpServers") or {}) else None

    def install(self) -> str:
        data = read_json(self.config)
        data.setdefault("mcpServers", {})["phone"] = {"command": sys.executable, "args": ["-B", SERVER]}
        write_json(self.config, data)
        return f"added the phone server to {self.config}"

    def uninstall(self) -> str:
        data = read_json(self.config)
        (data.get("mcpServers") or {}).pop("phone", None)
        write_json(self.config, data)
        return "removed"


HOSTS = [ClaudeCode(), MiniMaxCode(), Codex(), Cursor()]


def report() -> list[dict]:
    return [{"host": h.key, "name": h.label, "detected": h.detected(), "installed": h.installed()} for h in HOSTS]


def ask(question: str) -> bool:
    try:
        answer = input(question + " [Y/n] ").strip().lower()
    except EOFError:
        return False
    return answer in ("", "y", "yes", "是", "好", "要")


def choose(question: str, options: list[str], default: int = 0) -> int:
    """Numbered single choice; Enter takes the default, no terminal (EOF) takes it too."""
    print(question)
    for i, text in enumerate(options, 1):
        print(f"  {i}) {text}{'  (推荐)' if i - 1 == default else ''}")
    try:
        answer = input(f"选择 [1-{len(options)}，回车={default + 1}] ").strip()
    except EOFError:
        return default
    return int(answer) - 1 if answer.isdigit() and 1 <= int(answer) <= len(options) else default


PYTHON_FILES = (".claude-plugin/plugin.json", "servers.mcp.json", "hooks/hooks.json", "hooks/minimax-hooks.json")


def pin_python(root: str) -> None:
    """Start the plugin's server and hooks with this interpreter instead of a `python` command."""
    exe = sys.executable
    for rel in PYTHON_FILES:
        path = os.path.join(root, rel)
        try:
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
        except OSError:
            continue
        quoted = json.dumps(exe)[1:-1]  # as it must appear inside a JSON string
        text = text.replace('"command": "python"', f'"command": "{quoted}"') \
                   .replace('"command":  "python"', f'"command":  "{quoted}"') \
                   .replace('"command": "python -B', f'"command": "\\"{quoted}\\" -B')
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)


def python_command(assume_yes: bool) -> None:
    """Linux/macOS without a `python` command: the plugin starts its server and hook with `python`."""
    if os.name == "nt" or shutil.which("python"):
        return
    pick = 0 if assume_yes else choose(
        "这台电脑没有 `python` 命令（只有 python3），插件的服务和启动检查默认用 `python` 启动：",
        ["让插件直接用当前的 python3（不需要管理员权限）",
         "安装 python-is-python3，提供 `python` 命令（需要管理员密码）",
         "跳过（之后自己处理）"])
    if pick == 0:
        pin_python(ROOT)
        print(f"  ✓ 插件改为用 {sys.executable} 启动")
    elif pick == 1:
        apt = shutil.which("apt-get")
        cmd = (["sudo", "apt-get", "install", "-y", "python-is-python3"] if apt else None)
        if cmd is None:
            print("  ! 没找到 apt-get；请用你的包管理器提供 `python` 命令（如 dnf: python-unversioned-command）")
            return
        print("  $ " + " ".join(cmd))
        code = subprocess.call(cmd)  # sudo asks for the password in this terminal, from the user
        print("  ✓ 已安装" if code == 0 else f"  ✗ 退出码 {code}")
    else:
        print("  - 跳过：没有 `python` 命令时插件无法启动")


def main() -> int:
    parser = argparse.ArgumentParser(description="Install Phone Use into this computer's agent harnesses.")
    parser.add_argument("--list", action="store_true", help="report detected harnesses as JSON and exit")
    parser.add_argument("--host", action="append", choices=[h.key for h in HOSTS], help="limit to these harnesses")
    parser.add_argument("--yes", action="store_true", help="do not ask (the user already agreed elsewhere)")
    parser.add_argument("--uninstall", action="store_true")
    args = parser.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass

    if args.list:
        print(json.dumps({"plugin": NAME, "version": plugin_version(), "root": ROOT, "hosts": report()},
                         ensure_ascii=False, indent=2))
        return 0

    chosen = [h for h in HOSTS if (not args.host or h.key in args.host)]
    if args.yes and not args.host:
        parser.error("--yes needs --host: name the harnesses the user agreed to")
    failures = 0
    print(f"Phone Use {plugin_version()}  ({ROOT})")
    if not args.uninstall:
        python_command(args.yes)
    for host in chosen:
        if not host.detected() and not args.host:
            continue
        current = host.installed()
        if args.uninstall:
            if not current:
                continue
            if not args.yes and not ask(f"从 {host.label} 卸载 Phone Use 插件？"):
                print(f"  - {host.label}: 跳过")
                continue
            print(f"  ✓ {host.label}: {host.uninstall()}")
            continue
        if current and (host.key != "minimax" or current == plugin_version()):
            print(f"  = {host.label}: 已安装 ({current})")
            continue
        verb = f"更新 (当前 {current} → {plugin_version()})" if current else "安装"
        if not args.yes and not ask(f"检测到 {host.label}，要帮你把 Phone Use 插件{verb}进去吗？"):
            print(f"  - {host.label}: 跳过")
            continue
        try:
            print(f"  ✓ {host.label}: {host.install()}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"  ✗ {host.label}: {exc}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
