# Separating Phone Use from Ditto

Goal: the plugin **replaces** Ditto's Phone Use. Nothing on the phone except what the plugin itself
pushes over adb; no Ditto app, no Shizuku. Every Ditto Phone Use feature survives, except audio
listening (listen_*), which the user dropped on 2026-10-02.

## Why it can work

Ditto's privileged half, `agentmode/AetherAgentModeShizukuService.kt` (+ `AgentModeUiAutomation.kt`),
already runs as a **separate process under the shell uid** (Shizuku UserService, or `app_process`
through `com.rosan.app_process` in root mode). `adb shell` has exactly that uid. So the same code can
be started by the plugin with `adb shell CLASSPATH=... app_process / <Main>` - the way scrcpy runs.

## Target architecture

```
harness ──stdio MCP──▶ mcp/phone_mcp.py (PC) ──adb forward──▶ phone-use server (app_process, shell uid)
                         │  push + start server on demand          ├─ service: virtual display, input, capture,
                         │  preview / takeover web page            │   UiAutomation tree, IME guard
                         │  (teaching on the PC)                   ├─ controller + MCP endpoints (agent_display,
                         │                                         │   device_catalog, phone_app, phone_ui)
                         │                                         └─ stores under /data/local/tmp/phone-use/
```

- **Server artifact**: an Android application module built to an APK that is never installed;
  pushed to `/data/local/tmp/phone-use/server.apk` and run with `app_process` (CLASSPATH=apk).
  Resources (the desk theme, layouts) load from the same APK through `AssetManager.addAssetPath`,
  so `R.style.Theme_Aether_AgentDesk` and the desk Presentation keep working.
- **Context**: `ActivityThread.systemMain()` → system context (scrcpy-style), package identity
  `com.android.shell` (already handled by `AgentModeVirtualDisplayPolicy`).
- **Transport**: the same loopback HTTP MCP gateway code (`UpaMcpHttpGateway`, Agent Mode endpoints
  only) on a plugin-owned port (18793, not Ditto's 18792), reached through `adb forward`.
- **Settings** (`AppSettings`) → `/data/local/tmp/phone-use/config.json`, written by the bridge.

## Feature map - nothing dropped

| Ditto feature | Ditto code | Lives in the plugin as |
|---|---|---|
| Virtual display, launch, tap/swipe/long-press/double-tap/pinch/fling, keys, text paste, clear/undo, IME guard | `AetherAgentModeShizukuService`, `AgentModeImeGuard`, `AgentModeVirtualDisplayPolicy`, `AgentModeDisplaySpec` | server (ported as-is) |
| Desk / home on the virtual display | `AgentModeDeskActivity` (+ Presentation in the service) | server, resources from server.apk |
| Screenshot, crop, SoM overlay, black-frame detection | controller capture path, `AgentModeCapture` | server |
| Accessibility tree, paging, click_node, snapshot_id / STALE_SNAPSHOT, tree_diff receipts | `AgentModeUiAutomation`, `AgentModeUiTree`, `AgentModePortal` | server |
| Safety gates (protected windows, confirm for pay/delete/install/grant) | `AgentModeSafety`, `AgentModeDisplayGate` | server (enforced on the phone, not by prompt) |
| `agent_display` tool contract | `AgentDisplayMcp`, `AgentModeController.execute` | server, unchanged schema |
| `phone_ui` (open_app/type_text/clear_text/undo), `device_catalog` | `PhoneUiMcp`, `DeviceCatalogMcp` | server |
| GUI flow macros: recall / run / pipeline, learned primitives | `PhoneAppFlowMcp`, `PhoneAppCatalog`, `GuiFlowSegmenter`, `GuiSceneStore`, `GuiSignalStore`, `GuiFailureSignal` | server, stores on the phone |
| Learning: trajectories, SOP distillation | `AgentModeLearningRuntime`, `AgentModeSopStore`, `TeachingTraceDistiller` | server |
| Learning → EverMe long-term memory | learning runtime hands a fact to Kimi's EverMe plugin | tool results carry the distilled fact; the skill tells the host agent to commit it to the host's memory (MiniMax has EverMe; others use their own memory). Optional direct EverMe write when an EverMe token is configured |
| Teaching: request_teaching / teaching_status, user demonstrates, gestures recorded | controller teaching state + in-app preview `AgentModeComputerCard` | server records; **demonstration surface moves to a PC web page** (live frames + mouse/touch forwarding to `injectUserTap/Swipe`), opened by the bridge |
| Live preview and manual takeover | `AgentModeGlPreviewRenderer`, `AgentModeComputerCard` | same PC web page |
| Real-screen mode (operate the physical display) | `adoptDefaultDisplay` | server |
| listen_start / status / stop (audio capture + ASR) | `AgentModeInternalAudioCapture`, `AgentModeListenSession`, ASR engines | **Dropped by the user.** Not ported; the `listen_*` actions are removed from the `agent_display` schema and description, and the watch-a-video flow relies on screenshot / dump_tree |
| Multiple phone subagents in parallel | `SessionExecutionManager`, `KimiPhoneAgentProfile` | host subagents (Claude Code `agents/`, MiniMax `task`), profile prompt shipped as an agent definition; one shared virtual display; parallel callers get DISPLAY_BUSY and retry, as in Ditto |
| Shizuku / root provisioning | `ShizukuProvisioner` | not needed: adb is the privilege |

## Phases

0. Installer that asks per harness; readiness check without Ditto. **Done (0.2.0).**
1. Toolchain (JDK 17, Android SDK 36) - user installs. **Done.**
2. **Done (0.3.0)** `server/` Gradle module: port service + controller + MCP endpoints; `app_process` entry; build,
   push, start, call every `agent_display` action on the real phone.
3. **Done (0.3.0)** Bridge: push/start/version-check the server, forward 18793, relay; Ditto gateway path removed.
4. Learning, flows, teaching window: **done (0.3.0)**. Subagent definitions: open.
5. Verify every action on device U2P0223714004767 from Claude Code and MiniMax Code.
