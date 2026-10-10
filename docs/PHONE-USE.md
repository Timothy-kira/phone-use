# phone-use

Phone Use for desktop agent harnesses. The agent on your computer drives the apps on your Android
phone - launch, read the screen, tap, swipe, type - on a **virtual display** that is shown live in
a window on your computer, where you can take over at any time. The phone needs **only USB (or
wireless) debugging**. Nothing is installed on it, no root, no Shizuku.

```
harness ──stdio MCP──▶ mcp/phone_mcp.py ──adb forward──▶ phone-use server (app_process, shell uid)
 (Claude Code, MiniMax     (bridge, stdlib)                  /data/local/tmp/phone-use/server.apk
  Code, Codex, Cursor)          │                            virtual display, input, a11y tree,
                                └── phone window (Edge/Chrome app window) ◀── /ui live MJPEG + takeover
```

How it runs: on first use the bridge pushes `bin/phone-use-server.apk` to the phone's temp
directory (only when it changed) and starts it with `app_process` under the shell uid, as scrcpy
does. The server (virtual display, gestures, accessibility tree, safety gates, learning, flows) is a
standalone Kotlin program; its sources are in `server/`.

| File | What it is |
|---|---|
| `.claude-plugin/plugin.json` | Claude Code manifest (MCP server inline) |
| `.minimax-plugin/plugin.json` + `servers.mcp.json` | MiniMax Code local plugin V1 |
| `skills/phone-use/SKILL.md` | workflow and safety rules, shared by all hosts |
| `mcp/phone_mcp.py` | the bridge; any MCP host can launch it directly |
| `bin/phone-use-server.apk` | the phone-side server (built from `server/`, never installed) |
| `server/` | its Kotlin sources and Gradle build (`server/tools/build.ps1`) |
| `install.py` | asks per harness, then installs the plugin into it |

## Tools

| Tool | Where it runs |
|---|---|
| `phone_status` | computer: adb, server health (starts it if needed), window URL |
| `phone_setup` | computer: readiness check, adb install after the user agrees |
| `phone_window` | computer: (re)opens the live phone window |
| `phone_browser` | computer→phone: the phone's browser over DevTools - open, snapshot with refs, click, fill, form inspect/fill, extract, cited screenshots; accessibility fallback without DevTools |
| `phone_shots` | computer: screenshot archive - list, export, cite (image + source URL), pin, cleanup |
| `agent_display` | phone: the full action set - launch, tap, swipe, text, dump_tree, click_node, teaching, ... |
| `list_apps` | phone: installed apps |
| `recall_gui_flows`, `run_gui_flow`, `run_gui_pipeline`, learned flows | phone: verified GUI flows |
| `phone_task` | phone: begin / report / commit_teaching - frames a task so it can be learned |
| `phone_record` | phone: MP4 recording of the agent's screen + `actions.jsonl` timeline |
| `phone_lanes` | phone: parallel lanes (one virtual display each), queue state, which apps can run in parallel |
| `phone_sops` | phone: learned flows - list, edit, history, trash, export/import; privacy rules, scrub, audit |
| `phone_native` | computer→phone: system functions fitted to this phone after a read-only profile (settings, toggles, notifications, deep links, files) |
| `phone_jobs` | computer: background calls started with `async:true` |

## The phone window

It opens by itself on the first phone action (`PHONE_USE_WINDOW=0` turns that off) as a
standalone Edge/Chrome app window, ~15 fps. Click = tap, drag = swipe. Doing either starts a
takeover: the gestures run on the phone and are recorded as teaching; **End takeover** saves them as
a reusable flow. When the agent asks for teaching, its hint is shown above the screen.

## Requirements

- `python` (3.9+) and `adb` on PATH (or `PHONE_USE_ADB`); a Chromium-based browser for the window.
- The phone attached over USB or wireless debugging and authorized for this computer.
- Virtual displays need Android 10+; on phones that refuse one, the real screen is driven instead.

Optional environment: `PHONE_USE_SERIAL` (needed with several devices), `PHONE_USE_WINDOW=0`,
`PHONE_USE_URL` (talk to a running server directly), `PHONE_USE_TIMEOUT` (seconds, default 90).

Learned flows and settled skills live on the phone under `/data/local/tmp/phone-use/`; settled
skills are mirrored to `~/.phone-use/skills`. Screenshots saved with `persist_path` are pulled into
the agent's working directory.

## Install

```bash
python install.py
```

It detects Claude Code, MiniMax Code, Codex and Cursor on this computer and asks, for each one,
whether to install Phone Use into it - nothing is installed without a yes. `--list` reports what is
detected and installed; `--host <claude|minimax|codex|cursor> --yes` installs without asking (for an
agent that already asked the user); `--uninstall` removes it again.

What it does per harness: Claude Code - registers this directory as the `phone-use` marketplace and
installs the plugin (CLI if found, else `~/.claude/settings.json`); MiniMax Code - copies the package
to `~/.minimax/plugins/phone-use`; Codex - adds `[mcp_servers.phone]` to `~/.codex/config.toml`;
Cursor - adds `phone` to `~/.cursor/mcp.json`.


## Check by hand

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"phone_status","arguments":{}}}' | python -B mcp/phone_mcp.py
```


### Direct text input (0.8.25)

`phone_text` exposes read/set/insert/replace/delete/clear/undo/submit through one verified edit protocol. Native editors use accessibility. Custom editors can use the optional background InputConnection companion (`phone_setup action=install step=owned_input`, after agreement). It never creates a keyboard view or accesses the clipboard; it captures and restores the user's default/enabled input services, with a persistent ten-second lease watchdog. No active connection, partial text, wrong field, wrong hash or split surrogate range is rejected before writing. Submission acknowledgement requires separate observed result verification. Legacy text/search/clear/undo actions delegate to this same protocol.

Exact text copies can use `phone_text` with `source_refs` (complete existing `@aN` observations) and optional `separator`, without retyping whitespace. Completion checks require result evidence at or after the latest mutation; historical source facts remain available for comparison.

Action `refs` defaults to a compact receipt (actual backend, dispatch, stability and result summary). Use `detail:"full"` for retained complete facts and receipt only when needed; verification and source_refs always use the immutable stored facts.
