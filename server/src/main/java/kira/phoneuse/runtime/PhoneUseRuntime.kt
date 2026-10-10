package kira.phoneuse.runtime

import android.content.Context
import kira.phoneuse.agentmode.AetherAgentModeShizukuService
import kira.phoneuse.data.AetherDiagnosticLogger
import kira.phoneuse.data.AgentModeController
import kira.phoneuse.data.AgentModeLearningRuntime
import kira.phoneuse.data.AgentModeSopStore
import kira.phoneuse.data.AgentSkillManager
import kira.phoneuse.data.AppSettings
import kira.phoneuse.data.GuiSceneStore
import kira.phoneuse.data.GuiSignalStore
import kira.phoneuse.data.GuiTrajectoryStore
import kira.phoneuse.data.PhoneAppCatalogStore
import kira.phoneuse.data.PhoneAppFlowExecutor
import kira.phoneuse.data.PhoneAppFlowMcp
import kira.phoneuse.data.PhoneSettlementRuntime
import kira.phoneuse.data.PhoneSettlementStore
import kira.phoneuse.data.PhoneTaskMcp
import kira.phoneuse.data.RuntimeWorkspaceFileBridge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** The lane used when a call names none: the one the phone window shows and teaching runs in. */
const val MainLane = "main"

fun laneKey(name: String?): String =
    name.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(32).ifBlank { MainLane }

/** The agent's workspace as Agent Mode sees it; mapped to [PhoneUseRuntime.workspaceRoot] on the phone. */
const val PhoneUseWorkspaceDirectory = "/workspace"

/**
 * The Agent Mode object graph, built for one `app_process`.
 * All state lives under [dataDir] on the phone (/data/local/tmp/phone-use by default), which the
 * bridge can pull back and delete; nothing is installed.
 */
class PhoneUseRuntime(private val context: Context, val dataDir: File) {
    init {
        // Before any store is opened: everything they write goes through the redactor.
        kira.phoneuse.data.PrivacyRedactor.init(dataDir)
        kira.phoneuse.data.AppConcurrencyMemory.init(dataDir)
        // App maps and session reference graphs: kept per phone environment and app version.
        kira.phoneuse.data.AgentDisplayShaper.memoryRoot = File(dataDir, "memory").apply { mkdirs() }
        kira.phoneuse.data.AgentDisplayShaper.appVersion = { pkg ->
            runCatching {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                code to info.versionName.orEmpty()
            }.getOrNull()
        }
        kira.phoneuse.data.AgentDisplayShaper.deviceInfo = runCatching {
            val m = android.content.res.Resources.getSystem().displayMetrics
            org.json.JSONObject()
                .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("build", android.os.Build.FINGERPRINT)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("screen", "${m.widthPixels}x${m.heightPixels}@${m.densityDpi}")
                .put("locale", java.util.Locale.getDefault().toLanguageTag())
        }.getOrDefault(org.json.JSONObject())
    }

    private val settings = AppSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val workspaceRoot = File(dataDir, "workspace").apply { mkdirs() }
    private val diagnostics = AetherDiagnosticLogger(File(dataDir, "diagnostics"))

    private val signalStore = GuiSignalStore(File(dataDir, "agent-mode-trajectories/signals"))
    val sopStore = AgentModeSopStore(File(dataDir, "agent-mode-sops"))
    val catalogStore = PhoneAppCatalogStore(File(dataDir, "agent-mode-apps"))
    private val sceneStore = GuiSceneStore(File(dataDir, "agent-mode-scenes"))
    private val settlementStore = PhoneSettlementStore(File(dataDir, "agent-mode-settlements"))
    private val trajectoryStore = GuiTrajectoryStore(File(dataDir, "agent-mode-trajectories"))
    private val skillManager = AgentSkillManager(File(dataDir, "skills"))

    val service = AetherAgentModeShizukuService(context)
    val controller = AgentModeController(
        context = context,
        service = service,
        runtimeWorkspaceFileBridge = RuntimeWorkspaceFileBridge(workspaceRoot),
        diagnosticLogger = diagnostics,
        signalStore = signalStore,
        displayOptionsFile = File(dataDir, "display-options/main.json"),
    )
    private val flowExecutor = PhoneAppFlowExecutor(
        catalogStore = catalogStore,
        sopStore = sopStore,
        agentModeController = controller,
        sceneStore = sceneStore,
        signalStore = signalStore,
    )
    private val settlement = PhoneSettlementRuntime(
        store = settlementStore,
        catalogStore = catalogStore,
        skillManager = skillManager,
        scope = scope,
        sopStore = sopStore,
        replayExecutor = flowExecutor,
        replaySettings = { settings },
        replayWorkspaceDirectory = { PhoneUseWorkspaceDirectory },
    )
    val learning = AgentModeLearningRuntime(
        trajectoryStore = trajectoryStore,
        sopStore = sopStore,
        settlementRuntime = settlement,
        sceneStore = sceneStore,
        guiStepStoreDir = File(dataDir, "agent-mode-gui-steps"),
        diagnosticLogger = diagnostics,
        isTeachingActive = { controller.isTeachingActive },
        signalStore = signalStore,
    ).also(controller::addUserGestureListener)

    // --- lanes: one virtual display per agent, all sharing what was learned ------------------

    /** One agent's workspace on the phone: its own display, controller and flow replayer. */
    inner class Lane(val name: String, val controller: AgentModeController, val flows: PhoneAppFlowExecutor) {
        @Volatile var lastUsedMillis: Long = System.currentTimeMillis()
        @Volatile var lastAction: String = ""
        /** The last action, as the phone window's pill and pointer show it. */
        @Volatile var lastEvent: JSONObject? = null
    }

    private val eventSeq = java.util.concurrent.atomic.AtomicLong()

    /** click / scroll / type / open ... plus a short, redacted detail - what the window shows. */
    private fun windowEvent(lane: Lane, arguments: JSONObject, raw: String) {
        val action = arguments.optString("action").lowercase().removePrefix("browser.")
        val result = runCatching { JSONObject(raw) }.getOrNull()
        val verb = when (action) {
            "click_node", "tap", "click", "double_tap" -> "click"
            "long_press" -> "long press"
            "swipe", "swipe_up", "swipe_down", "swipe_left", "swipe_right", "fling", "scroll_until", "scroll" -> "scroll"
            "text", "search", "fill", "form" -> "type"
            "clear_text" -> "clear"
            "launch", "open_url", "open", "url", "browse" -> "open"
            "back" -> "back"
            "home" -> "home"
            "key", "press" -> "key"
            "drag", "set_progress", "slider" -> "drag"
            "switch_display", "display" -> "switch screen"
            "screenshot", "dump_tree", "list_targets", "snapshot", "extract", "status" -> "look"
            "wait_idle", "wait_for_label", "wait_for" -> "wait"
            else -> action.ifBlank { return }
        }
        val dir = arguments.optString("direction").ifBlank { action.substringAfter("swipe_", "") }
        val detail = when (verb) {
            "type" -> arguments.optString("text").ifBlank { arguments.optString("value") }.let { t ->
                // What was typed is not shown: it may be a password or a phone number.
                if (t.isBlank()) "" else "${t.length} chars"
            }
            "scroll" -> when (dir) { "up" -> "↑"; "down" -> "↓"; "left" -> "←"; "right" -> "→"; else -> "" }
            "open" -> result?.optString("app_name").orEmpty().ifBlank {
                arguments.optString("target").ifBlank { arguments.optString("url").substringAfter("://").substringBefore('/') }
            }
            "key" -> arguments.optString("key")
            "click", "long press" -> arguments.optString("query").takeUnless { it.startsWith("#") || it.startsWith("@") }
                ?: arguments.optString("text").ifBlank { result?.optString("clicked").orEmpty() }
            else -> ""
        }
        val state = lane.controller.displayState.value
        val event = JSONObject()
            .put("seq", eventSeq.incrementAndGet())
            .put("verb", verb)
            .put("detail", kira.phoneuse.data.PrivacyRedactor.text(detail.orEmpty()).take(28))
            .put("ok", result?.optBoolean("ok", true) ?: true)
            .put("at", System.currentTimeMillis())
        // Where the pointer goes: the spot this call touched (taps, clicks, presses).
        if (verb in setOf("click", "long press") && state.cursorX != null && state.cursorY != null) {
            event.put("x", state.cursorX).put("y", state.cursorY)
        }
        lane.lastEvent = event
    }

    /** Steps of host-side tools (phone_browser) shown in the main lane's window. */
    fun noteHostAction(arguments: JSONObject, result: JSONObject) {
        windowEvent(mainLane, arguments, result.toString())
    }

    val scheduler = LaneScheduler()
    private val lanes = java.util.concurrent.ConcurrentHashMap<String, Lane>()
    val mainLane = Lane(MainLane, controller, flowExecutor).also { lanes[MainLane] = it }

    @Volatile
    var maxLanes: Int = 4

    fun laneNames(): List<String> = lanes.keys.sorted()

    /** Package a launch target names (label or package); resolution does not depend on the lane. */
    fun resolvePackage(target: String, @Suppress("UNUSED_PARAMETER") laneName: String? = null): String? =
        runBlocking { controller.resolvePackageFor(settings, target) }

    fun existingLane(name: String?): Lane? = lanes[laneKey(name)]

    /** The lane called [name], created (with its own virtual display, on first use) if needed. */
    fun lane(name: String?): Lane {
        val key = laneKey(name)
        lanes[key]?.let { return it }
        synchronized(lanes) {
            lanes[key]?.let { return it }
            if (lanes.size >= maxLanes) {
                error("LANES_FULL: $maxLanes lanes are open (${lanes.keys.sorted().joinToString()}). " +
                    "Close one with phone_lanes action=close, or reuse a lane.")
            }
            val laneController = AgentModeController(
                context = context,
                service = service,
                runtimeWorkspaceFileBridge = RuntimeWorkspaceFileBridge(workspaceRoot),
                diagnosticLogger = diagnostics,
                signalStore = signalStore,
                displayOptionsFile = File(dataDir, "display-options/$key.json"),
            )
            val laneFlows = PhoneAppFlowExecutor(
                catalogStore = catalogStore,
                sopStore = sopStore,
                agentModeController = laneController,
                sceneStore = sceneStore,
                signalStore = signalStore,
            )
            return Lane(key, laneController, laneFlows).also { lanes[key] = it }
        }
    }

    /** Frees a lane's display and everything it held. The main lane only gives its display back. */
    fun closeLane(name: String): JSONObject {
        val key = laneKey(name)
        val lane = lanes[key] ?: return JSONObject().put("ok", false).put("errmsg", "No lane '$key'.")
        runCatching { lane.controller.stopDisplay() }
        scheduler.releaseLane(key)
        if (key != MainLane) lanes.remove(key)
        return JSONObject().put("ok", true).put("closed", key)
    }

    fun lanesJson(): JSONObject {
        val arr = org.json.JSONArray()
        laneNames().forEach { name ->
            val lane = lanes[name] ?: return@forEach
            arr.put(JSONObject()
                .put("lane", name)
                .put("display_id", lane.controller.activeDisplayId ?: JSONObject.NULL)
                .put("display_mode", lane.controller.displayMode)
                .put("app", lane.controller.currentPackage)
                .put("last_action", lane.lastAction)
                .put("idle_s", (System.currentTimeMillis() - lane.lastUsedMillis) / 1000))
        }
        return JSONObject().put("ok", true).put("max_lanes", maxLanes).put("lanes", arr).put("scheduler", scheduler.snapshot())
    }

    /** Which lane the recording follows (the main lane unless phone_record start names one). */
    @Volatile
    var recordLane: String = MainLane

    val recorder = PhoneUseRecorder(
        service,
        File(dataDir, "recordings"),
        currentDisplay = { (lanes[recordLane] ?: mainLane).controller.activeDisplayId },
        currentSize = { (lanes[recordLane] ?: mainLane).controller.displayState.value.let { it.width to it.height } },
    )

    fun phoneRecord(arguments: JSONObject, laneName: String? = null): JSONObject {
        if (arguments.optString("action") == "start") {
            recordLane = laneKey(laneName)
            // Recording from the very start of a run is the common case: bring the display up first.
            val lane = lane(laneName)
            if (lane.controller.activeDisplayId == null) {
                runCatching { runBlocking { lane.controller.ensureDisplayForWindow(settings) } }
            }
        }
        return PhoneRecordMcp.call(arguments, recorder)
    }

    /** The session that last called a phone tool; the window's takeover is recorded against it. */
    @Volatile
    var lastSessionId: String = "window"

    init {
        runCatching { settlement.resumePending() }
    }

    // --- tools (AetherApplication's attach* executors) -------------------------------------

    fun agentDisplay(arguments: JSONObject, sessionId: String, laneName: String? = null,
        shouldStop: () -> Boolean = { false }, onProgress: (JSONObject) -> Unit = {}): String = runBlocking {
        val lane = lane(laneName)
        val controller = lane.controller
        lane.lastUsedMillis = System.currentTimeMillis()
        lane.lastAction = arguments.optString("action")
        val raw = controller.execute(
            settings = settings,
            workspaceDirectory = PhoneUseWorkspaceDirectory,
            termuxWorkspaceDirectory = PhoneUseWorkspaceDirectory,
            argumentsJson = arguments.toString(), shouldStop = shouldStop, onProgress = onProgress,
        )
        runCatching { windowEvent(lane, arguments, raw) }
        if (lane.name == recordLane) recorder.logAction(arguments, raw) else
            recorder.logAction(JSONObject(arguments.toString()).put("lane", lane.name), raw)
        // Each lane learns as its own session, so parallel trajectories do not interleave.
        val learnSession = if (lane.name == MainLane) sessionId else "$sessionId/${lane.name}"
        learning.recordAgentDisplayCall(sessionId = learnSession, arguments = arguments, rawOutput = raw)
    }

    fun flowTools(): JSONObject = PhoneAppFlowMcp.listToolsResult(catalogStore.loadAll())

    fun flowCall(name: String, arguments: JSONObject, sessionId: String, laneName: String? = null): String = runBlocking {
        val lane = lane(laneName)
        lane.lastUsedMillis = System.currentTimeMillis()
        lane.lastAction = "flow:$name"
        val raw = lane.flows.invoke(
            toolName = name,
            arguments = arguments,
            settings = settings,
            workspaceDirectory = PhoneUseWorkspaceDirectory,
        )
        recorder.logAction(JSONObject(arguments.toString()).put("action", "flow:$name"), raw)
        learning.recordPhoneAppFlowCall(sessionId = sessionId, toolName = name, arguments = arguments, rawOutput = raw)
    }

    fun deviceCatalog(arguments: JSONObject): String = runBlocking {
        controller.listInstalledAppsCatalog(
            settings = settings,
            arguments = arguments,
            workspaceDirectory = PhoneUseWorkspaceDirectory,
        )
    }

    fun phoneTask(arguments: JSONObject, sessionId: String, laneName: String = MainLane): JSONObject =
        PhoneTaskMcp.call(arguments, if (laneName == MainLane) sessionId else "$sessionId/$laneName", learning, lane(laneName).controller, laneName)

    // --- the computer-side phone window ----------------------

    private val windowSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun windowState(laneName: String? = null, monitor: Boolean = false): JSONObject {
        val key = laneKey(laneName)
        if (monitor) windowSeenAt[key] = System.currentTimeMillis()
        val lane = existingLane(laneName) ?: mainLane
        val controller = lane.controller
        val state = controller.displayState.value
        return JSONObject()
            .put("event", lane.lastEvent ?: JSONObject.NULL)
            .put("lane", lane.name)
            .put("ok", true)
            .put("active", state.isActive)
            .put("display_id", state.displayId ?: JSONObject.NULL)
            .put("width", state.width)
            .put("height", state.height)
            .put("status", state.status)
            .put("real_screen", controller.realScreenFallback)
            .put("teaching", controller.isTeachingActive)
            .put("teaching_hint", state.teachingHint)
            .put("cursor_x", state.cursorX ?: JSONObject.NULL)
            .put("cursor_y", state.cursorY ?: JSONObject.NULL)
            .put("updated", state.lastUpdatedMillis)
            .put("display_mode", controller.displayMode)
            .put("notification_protection", controller.notificationProtectionEnabled)
            .put("recording", recorder.isRecording)
            .put("window_seen_at", windowSeenAt[key] ?: 0L)
    }

    fun windowNotificationProtection(laneName: String?, enabled: Boolean?): JSONObject {
        val lane = existingLane(laneName) ?: return JSONObject().put("ok", false).put("code", "LANE_NOT_FOUND")
        val result = lane.controller.notificationProtection(enabled)
        return if (result.optBoolean("ok")) windowState(lane.name) else result
    }

    fun windowFrame(laneName: String? = null): ByteArray? =
        (existingLane(laneName) ?: mainLane).controller.windowFrameJpeg(maxEdge = 1280, quality = 70)

    fun windowStartDisplay(): JSONObject = runCatching {
        runBlocking { controller.ensureDisplayForWindow(settings) }
        windowState()
    }.getOrElse { JSONObject().put("ok", false).put("errmsg", it.message.orEmpty()) }

    /** Takeover ("接管"): the user's gestures on the preview are executed and recorded as teaching. */
    fun windowStartTakeover(): JSONObject {
        learning.ensureTurn(lastSessionId.ifBlank { "window" }, "用户示教")
        controller.startUserTeaching()
        return windowState()
    }

    /**
     * Gestures and "finish" are serialized: a drag is still executing on the phone when the user
     * clicks finish, and the gesture must be recorded before teaching closes, not dropped after.
     */
    private val takeoverLock = Any()

    fun windowFinishTakeover(): JSONObject = synchronized(takeoverLock) {
        controller.finishTeaching()
        val completion = learning.commitTeaching(lastSessionId.ifBlank { "window" })
        windowState().put("saved_sop_id", completion?.sop?.id.orEmpty())
    }

    fun windowTap(x: Int, y: Int): JSONObject = synchronized(takeoverLock) {
        if (!controller.isTeachingActive) windowStartTakeover()
        runBlocking { controller.injectUserTap(settings, x, y) }
        windowState()
    }

    fun windowSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject = synchronized(takeoverLock) {
        if (!controller.isTeachingActive) windowStartTakeover()
        runBlocking { controller.injectUserSwipe(settings, x1, y1, x2, y2, durationMs) }
        windowState()
    }
}
