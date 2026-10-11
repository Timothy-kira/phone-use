package kira.phoneuse.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.getSystemService
import kira.phoneuse.agentmode.AgentModeDisplaySpec
import kira.phoneuse.agentmode.AgentModeImeGuard
import kira.phoneuse.agentmode.IAetherAgentModeService
import kira.phoneuse.agentmode.scaleAgentModeDisplaySpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private const val FallbackAgentDisplayWidth = 720
private const val FallbackAgentDisplayHeight = 1280
private const val FallbackAgentDisplayDensityDpi = 320
private const val AgentDisplayName = "aether-agent-mode"
private const val NeedsRealScreenCode = "NEEDS_REAL_SCREEN"
/** Prompts the system shows on the real screen for an app running elsewhere. */
private val RealScreenPromptPackages = listOf(
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.huawei.systemmanager",
)
private const val AgentModeCaptureExtension = "jpg"
private const val AgentModeCaptureMimeType = "image/jpeg"
private const val AgentModeCaptureMaxEdge = 720
private const val AgentModeCaptureJpegQuality = 55
/** zoom / crop: captured at full resolution, then cut, so the crop is sharp. */
private const val ZoomCaptureMaxEdge = 2400
private const val ZoomCaptureJpegQuality = 80
private const val AgentModeAppLogTag = "AetherAgentMode"
private const val CaptureJpegTimeoutMillis = 4_000L

data class AgentModeDisplayState(
    val isActive: Boolean = false,
    val displayId: Int? = null,
    val width: Int = FallbackAgentDisplayWidth,
    val height: Int = FallbackAgentDisplayHeight,
    val displays: List<AgentModeDisplayInfo> = emptyList(),
    val latestPreviewPath: String = "",
    val latestWorkspacePath: String = "",
    val cursorX: Int? = null,
    val cursorY: Int? = null,
    val cursorAnimationDurationMillis: Int = 220,
    val isLivePreviewActive: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
    val status: String = "",
    val teachingHint: String = "",
    val teachingActive: Boolean = false,
    val listening: Boolean = false,
    val listenTranscript: String = "",
    val listenVisualOnly: Boolean = false,
)

data class AgentModeDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAetherDisplay: Boolean,
)

data class AgentModeInstalledAppInfo(
    val packageName: String,
    val appName: String,
    val activityName: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
)

data class AgentModePreopenResult(
    val packageName: String,
    val appName: String,
)

enum class AgentModeAuthorizationIssue {
    Disabled,
    Ready,
    ShizukuNotInstalled,
    ShizukuNotRunning,
    ShizukuPermissionMissing,
    ShizukuPermissionDenied,
    RootUnavailable,
    RootPermissionMissing,
    RootPermissionDenied,
    Error,
}

data class AgentModeAuthorizationState(
    val issue: AgentModeAuthorizationIssue = AgentModeAuthorizationIssue.Disabled,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = issue == AgentModeAuthorizationIssue.Ready
}

class AgentModeController(
    private val context: Context,
    /** The privileged half, living in this same `app_process` (no Binder between them). */
    private val service: IAetherAgentModeService,
    private val runtimeWorkspaceFileBridge: RuntimeWorkspaceFileBridge,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val signalStore: GuiSignalStore? = null,
    private val displayOptionsFile: File? = null,
) {
    @Volatile
    var notificationProtectionEnabled: Boolean = runCatching {
        displayOptionsFile?.takeIf { it.isFile }?.readText()?.let { JSONObject(it).optBoolean("notification_protection", true) } ?: true
    }.getOrDefault(true)
        private set

    /** Per profile/lane preference; never changes the phone's global notification/DND settings. */
    @Synchronized
    fun notificationProtection(enabled: Boolean? = null): JSONObject {
        if (enabled != null && enabled != notificationProtectionEnabled) {
            try {
                displayOptionsFile?.let { file ->
                    check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "Cannot create display options directory" }
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(JSONObject().put("notification_protection", enabled).toString())
                    check(tmp.renameTo(file)) { "Cannot save display options" }
                }
                notificationProtectionEnabled = enabled
            } catch (e: Exception) {
                return JSONObject().put("ok", false).put("code", "CONFIG_SAVE_FAILED")
                    .put("errmsg", e.message).put("notification_protection", notificationProtectionEnabled)
            }
        }
        return JSONObject().put("ok", true).put("notification_protection", notificationProtectionEnabled)
            .put("scope", "profile_lane").put("display_mode", displayMode)
            .put("system_dnd_changed", false)
    }

    private val displayManager = context.getSystemService<DisplayManager>()!!
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val inFlightCaptureJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private val ensureDisplayMutex = Mutex()
    private data class ActionContext(
        var skipCapture: Boolean,
        var skipWorkspace: Boolean,
        var persistPath: String,
        var somOverlay: Boolean,
        var portalAction: String,
        var cropArguments: JSONObject?,
        var usedBackend: String = "",
        var targetNode: AgentModeUiTree.RichNode? = null,
        val deadline: Long = Long.MAX_VALUE,
        val shouldStop: () -> Boolean = { false },
    )
    // A mutable per-call value travels with the coroutine across delay/IO thread switches.
    private val activeActionContext = ThreadLocal<ActionContext?>()
    var userGestureListener: AgentModeUserGestureListener? = null
    private val additionalUserGestureListeners = CopyOnWriteArrayList<AgentModeUserGestureListener>()
    @Volatile
    private var lastLaunchedPackageName: String = ""
    @Volatile
    private var lastLaunchedAppName: String = ""
    @Volatile
    private var lastSnapshot: GuiSnapshot? = null
    private val observedSnapshots = LinkedHashMap<String, GuiSnapshot>()
    private val portalMutatingActions = setOf(
        "tap", "scroll", "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down",
        "key", "text", "edit_text", "search", "clear_text", "undo", "back", "home",
        "recents", "overview", "notifications", "force_stop", "click_node",
        "long_press", "double_tap", "pinch", "fling", "launch", "scroll_until",
        "gesture", "drag", "set_progress",
    )
    private val teachingActive = AtomicBoolean(false)
    private val teachingFinished = AtomicBoolean(false)
    private val teachingGestures = AtomicInteger(0)
    private val lastTeachingGestureAt = AtomicLong(0L)
    val isTeachingActive: Boolean get() = teachingActive.get()
    private val _displayState = MutableStateFlow(AgentModeDisplayState())
    private val installedPackageCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val _authorizationState = MutableStateFlow(AgentModeAuthorizationState())
    private var shizukuDisplayId: Int? = null
    private var displayOwnerMethod: AgentModeAuthorizationMethod? = null
    val displayState: StateFlow<AgentModeDisplayState> = _displayState.asStateFlow()
    val authorizationState: StateFlow<AgentModeAuthorizationState> = _authorizationState.asStateFlow()

    init {
        val spec = currentDeviceDisplaySpec()
        _displayState.value = AgentModeDisplayState(
            width = spec.width,
            height = spec.height,
        )
    }

    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
        shouldStop: () -> Boolean = { false },
        onProgress: (JSONObject) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (!settings.agentModeAuthorizationEnabled) {
            captureAgentModeFailed(
                settings = settings,
                action = "unknown",
                reason = "authorization_disabled",
                message = "Agent Mode is not authorized.",
            )
            return@withContext JSONObject().apply {
                put("ok", false)
                put("errmsg", "Agent Mode is not authorized. Enable it in Settings > Agent Mode first.")
            }.toString()
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = "unknown",
                    reason = "invalid_arguments",
                    message = "Arguments were not valid JSON.",
                )
            }
        AgentDisplayShaper.normalizeArguments(arguments)
        val deadline = arguments.optLong("_deadline_ms", Long.MAX_VALUE)
        if (shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline) return@withContext JSONObject(gatedError("OPERATION_STOPPED", "The operation deadline expired before the next gesture."))
            .put("dispatched", false).toString()
        val actionsArray = arguments.optJSONArray("actions")
        if (actionsArray != null && actionsArray.length() > 0) {
            if ((0 until actionsArray.length() - 1).any { i ->
                    val a = PhoneUiMcp.normalizeAction(actionsArray.optJSONObject(i)?.optString("action").orEmpty())
                    a == "scroll" || a == "scroll_until" || a.startsWith("swipe") || a == "fling"
                }) return@withContext invalidArguments("Scroll must end a batch: inspect its stable screenshot before the next small goal.")
            val observe = AgentModeCapture.wantsObserve(arguments)
            var last = ""
            val completed = JSONArray()
            for (index in 0 until actionsArray.length()) {
                if (shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline) return@withContext JSONObject()
                    .put("ok", false).put("code", "OPERATION_STOPPED").put("dispatched", completed.length() > 0)
                    .put("completed_steps", completed).put("running_step", -1).put("next_step", index)
                    .put("cancel_requested", shouldStop()).toString()
                onProgress(JSONObject().put("completed_steps", JSONArray(completed.toString())).put("running_step", index))
                val step = JSONObject(actionsArray.getJSONObject(index).toString())
                step.put("_deadline_ms", deadline)
                step.put("_host_permission", arguments.optString("_host_permission"))
                val lastStep = index == actionsArray.length() - 1
                val stepAction = PhoneUiMcp.normalizeAction(step.optString("action"))
                if (!step.has("skip_capture")) {
                    step.put(
                        "skip_capture",
                        !(AgentModeCapture.wantsJpeg(arguments) && lastStep) &&
                            stepAction != "screenshot" &&
                            stepAction != "start",
                    )
                }
                if (observe && lastStep && !step.has("observe")) {
                    step.put("observe", arguments.opt("observe") ?: true)
                }
                last = execute(
                    settings,
                    workspaceDirectory,
                    termuxWorkspaceDirectory,
                    step.toString(), shouldStop, {},
                )
                val parsed = runCatching { JSONObject(last) }.getOrNull()
                if (parsed != null) onProgress(JSONObject().put("_step_result", parsed).put("_step_arguments", step))
                if (parsed?.optBoolean("ok", true) == true) {
                    completed.put(
                        JSONObject()
                            .put("index", index)
                            .put("action", stepAction)
                            .put("ok", true).apply {
                                listOf("code", "snapshot_id", "used_backend", "dispatched", "submission_verified", "action_ref", "evidence_ref").forEach { k ->
                                    if (parsed.has(k)) put(k, parsed.get(k))
                                }
                                parsed.optJSONObject("tree_diff")?.let { diff ->
                                    put("tree_diff", JSONObject().put("summary", diff.optString("summary").take(400))
                                        .put("changed", diff.optBoolean("changed")))
                                }
                            },
                    )
                    onProgress(JSONObject().put("completed_steps", JSONArray(completed.toString())).put("running_step", -1))
                    continue
                }
                val failed = parsed ?: JSONObject().put("ok", false).put("errmsg", last)
                failed.put("completed_steps", completed)
                failed.put("failed_step", index)
                failed.put("failed_action", stepAction)
                if (failed.optString("code").isBlank()) {
                    failed.put("code", AgentModeSafety.MacroMismatchCode)
                }
                failed.put("consistent", "no")
                failed.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                GuiFailureSignals.attach(
                    result = failed,
                    action = stepAction,
                    snapshot = lastSnapshot,
                    previous = null,
                )
                return@withContext failed.toString()
            }
            return@withContext JSONObject(last).put("completed_steps", completed).put("running_step", -1).toString()
        }
        val action = PhoneUiMcp.normalizeAction(arguments.optString("action"))
        val wakeKey = action == "key" && arguments.optString("key").uppercase() in setOf("WAKEUP", "KEYCODE_WAKEUP", "224")
        if (displayMode == "real" && action in portalMutatingActions && !wakeKey &&
            context.getSystemService<android.os.PowerManager>()?.isInteractive == false) {
            return@withContext gatedError("SCREEN_ASLEEP", "The real screen is asleep. Wake it once with key KEYCODE_WAKEUP, then observe; do not repeat launch, refresh or scroll against AOD. Do not bypass a secure lock screen.")
        }
        val observe = AgentModeCapture.wantsObserve(arguments)
        val som = AgentModeCapture.wantsSom(arguments)
        val persistPath = resolveEvidencePersistPath(
            persist = arguments.optBoolean("persist") ||
                arguments.optString("persist_path").isNotBlank() ||
                arguments.optString("persistPath").isNotBlank(),
            persistPath = arguments.optString("persist_path").ifBlank {
                arguments.optString("persistPath")
            },
            nowMillis = System.currentTimeMillis(),
        )
        val skipCapture = if (action == "scroll" || action == "scroll_until" || action.startsWith("swipe") || action == "fling") {
            false // Scrolling is an observation boundary, even inside a macro.
        } else if (persistPath.isNotBlank()) {
            false
        } else if (arguments.has("skip_capture")) {
            arguments.optBoolean("skip_capture")
        } else {
            !AgentModeCapture.wantsJpeg(arguments) && action != "screenshot" && action != "start"
        }
        val skipWorkspace = arguments.optBoolean("skip_workspace")
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_start",
            details = mapOf(
                "action" to action.ifBlank { "unknown" },
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )

        withContext(activeActionContext.asContextElement(ActionContext(
            skipCapture, skipWorkspace, persistPath, som, action,
            if (action == "screenshot") arguments else null,
            deadline = deadline, shouldStop = shouldStop,
        ))) {
        runCatching {
            // A native accessibility action does not always reset the physical screen's idle timer.
            // Count an active real-screen GUI call as activity; never wake a sleeping/locked phone here.
            if (displayMode == "real" && action !in setOf("status", "recap", "refs", "map", "notification_protection")) {
                val power = context.getSystemService<android.os.PowerManager>()
                if (power?.isInteractive == true) runCatching {
                    power.javaClass.getMethod("userActivity", java.lang.Long.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                        .invoke(power, android.os.SystemClock.uptimeMillis(), 0, 0)
                }.onFailure { error ->
                    diagnosticLogger.event(category = "agent_mode", event = "screen_activity_unavailable",
                        details = mapOf("reason" to (error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)))
                }
            }
            val recoveryKey = action == "key" && arguments.optString("key").uppercase()
                .removePrefix("KEYCODE_") in setOf("BACK", "HOME", "ESCAPE", "WAKEUP", "4", "3", "111", "224")
            if (notificationProtectionEnabled && action in portalMutatingActions && shizukuDisplayId != null &&
                !recoveryKey && action !in setOf("launch", "force_stop", "notifications", "back", "home")) {
                val current = runCatching { loadPortalSnapshot(settings, alreadyStable = true) }.getOrNull()
                // collectRich contains visible, on-display nodes only. A collapsed status-bar
                // notification container is not evidence of a card covering the task.
                val interruption = current?.nodes?.firstOrNull { n ->
                    n.packageName == "com.android.systemui" && n.bottom - n.top > 60 && n.right - n.left > 100 &&
                        (n.id.contains("heads_up", true) || n.klass.contains("ExpandableNotificationRow", true) ||
                            n.id in setOf("notification_main_column", "status_bar_latest_event_content"))
                }
                val requestedNode = arguments.optString("query").ifBlank { arguments.optString("at") }
                    .takeIf { it.startsWith("#") }?.removePrefix("#")?.toIntOrNull()
                val explicitSystemControl = requestedNode != null && current?.nodes?.any {
                    it.clickIndex == requestedNode && it.packageName == "com.android.systemui"
                } == true
                if (interruption != null && !explicitSystemControl) {
                    return@withContext JSONObject(gatedError("SYSTEM_INTERRUPTION",
                        "A visible notification covers this display. No gesture was sent. Read the screen, wait for it to disappear or use BACK/HOME to recover; notification_protection enabled=false disables only this guard."))
                        .put("notification_protection", true).toString()
                }
            }
            when (action) {
            "start" -> {
                ensureDisplay(settings)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 0,
                    )
            }
            "status" -> statusResult(settings)
            "notification_protection" -> {
                if (arguments.has("enabled") && arguments.opt("enabled") !is Boolean) {
                    invalidArguments("notification_protection enabled must be a boolean; omit it to query.")
                } else notificationProtection(if (arguments.has("enabled")) arguments.getBoolean("enabled") else null).toString()
            }
            "list_apps", "apps", "installed_apps" -> listInstalledAppsResult(settings, arguments)
            "launch" -> {
                ensureDisplay(settings)
                val target = arguments.optString("target").trim()
                if (target.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else {
                    val packageName = launchTarget(settings, target)
                    if (AgentModeSafety.shouldBlockLaunch(packageName)) {
                        gatedError(
                            AgentModeSafety.ProtectedWindowCode,
                            "Refusing to launch protected package $packageName.",
                        )
                    } else {
                    lastLaunchedPackageName = packageName
                    lastLaunchedAppName = currentInstalledApps(settings)
                        .firstOrNull { it.packageName == packageName }
                        ?.appName
                        .orEmpty()
                        .ifBlank { target }

                    val displayId = currentManagedDisplayId(settings)
                    var visibleOnDisplay = displayId != null &&
                        runCatching {
                            requireAgentModeService(settings).packageVisibleOnDisplay(displayId, packageName)
                        }.getOrDefault(false)
                    // A cold-start window may arrive after launchPackage's visibility poll. Retry
                    // only observations on the active display; never replay the launch here.
                    val launchStability = if (!visibleOnDisplay && displayId != null) {
                        val settled = awaitStablePortalSnapshot(settings, displayId)
                        visibleOnDisplay = runCatching {
                            requireAgentModeService(settings).packageVisibleOnDisplay(displayId, packageName)
                        }.getOrDefault(false)
                        settled.details
                    } else null
                    val troubles = if (!visibleOnDisplay && displayMode == "virtual") {
                        AppConcurrencyMemory.noteVirtualRefusal(packageName).also { virtualTrouble[packageName] = it }
                    } else 0
                    if (!visibleOnDisplay && troubles >= 2) {
                        needsRealScreen(
                            packageName,
                            "$packageName does not stay on the virtual display (launched $troubles times). " +
                                "Some apps refuse a second screen or keep restarting on it.",
                        )
                    } else if (!visibleOnDisplay) {
                        JSONObject()
                            .put("ok", false)
                            .put("code", AgentModeSafety.StalledCode)
                            .put(
                                "errmsg",
                                "Launch of $packageName was not observed on the active $displayMode display " +
                                    "after bounded observation retries. Do not assume it is in the foreground. " +
                                    "Read the current screen before choosing a recovery action.",
                            )
                            .put("package_name", packageName)
                            .put("target", target)
                            .put("display_mode", displayMode)
                            .put("display_id", displayId)
                            .apply { launchStability?.let { put("layout_stability", it) } }
                            .put("consistent", "no")
                            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                            .toString()
                    } else {
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = if (skipCapture) 0 else 180,
                    )
                    }
                    }
                }
            }
            "open_url", "url", "browse" -> {
                val displayId = ensureDisplay(settings)
                val url = arguments.optString("url").ifBlank { arguments.optString("target") }.trim()
                val scheme = url.substringBefore(':', "").lowercase()
                // Web addresses and app deep links (weixin://, alipays://, geo:, market://...). Not
                // javascript: (runs in whatever page is open) or intent: (can name any component).
                if (url.isBlank() || !Regex("[a-z][a-z0-9+.-]*").matches(scheme) || scheme in setOf("javascript", "intent", "data", "vbscript")) {
                    invalidArguments("open_url needs 'url': a web address or an app link such as weixin://... (javascript:, intent: and data: are refused).")
                } else {
                    val requested = arguments.optString("package").trim().ifBlank { null }
                    if (requested != null && AgentModeSafety.shouldBlockLaunch(requested)) {
                        gatedError(AgentModeSafety.ProtectedWindowCode, "Refusing to open protected package $requested.")
                    } else {
                        val opened = requireAgentModeService(settings).openUrl(url, requested, displayId)
                        lastLaunchedPackageName = opened
                        lastLaunchedAppName = currentInstalledApps(settings)
                            .firstOrNull { it.packageName == opened }?.appName.orEmpty().ifBlank { opened }

                        val raw = captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 600)
                        runCatching { JSONObject(raw).put("opened_url", url).put("browser_package", opened).toString() }
                            .getOrDefault(raw)
                    }
                }
            }
            "tap" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    val observed = loadPortalSnapshot(settings, alreadyStable = true)
                    val settled = awaitStablePortalSnapshot(settings, displayId, observed = observed)
                    if (!settled.details.optBoolean("stable"))
                        return@withContext JSONObject(gatedError("LAYOUT_UNSTABLE", "The screen geometry is not quiet; no coordinate tap was sent."))
                            .put("target_stability", settled.details).put("dispatched", false).toString()
                    if (settled.snapshot.packageName != observed.packageName || settled.snapshot.displayId != observed.displayId ||
                        settled.snapshot.width != observed.width || settled.snapshot.height != observed.height)
                        return@withContext gatedError("STALE_SNAPSHOT", "The app or display changed; observe again before tapping.")
                    synchronized(observedSnapshots) {
                        observedSnapshots[observed.id] = observed
                        while (observedSnapshots.size > 8) observedSnapshots.remove(observedSnapshots.keys.first())
                    }
                    val px = (x.toLong() * 1000 / observed.width.coerceAtLeast(1)).toInt()
                    val py = (y.toLong() * 1000 / observed.height.coerceAtLeast(1)).toInt()
                    val candidates = AgentModeUiTree.controlsAt(settled.snapshot.nodes, px, py)
                    val node = candidates.firstOrNull()
                    AgentModeSafety.protectedWindowPackage(settled.snapshot.nodes, settled.snapshot.packageName)?.let {
                        return@withContext gatedError(AgentModeSafety.ProtectedWindowCode, "Refusing coordinate input into protected window $it.")
                    }
                    if (node != null && AgentModeSafety.looksSensitiveLabel(node.label.ifBlank { node.text }) && arguments.optString("_host_permission") != "full")
                        return@withContext gatedError("HOST_APPROVAL_REQUIRED", "This sensitive control requires host approval; model confirmation cannot grant it.")
                    // A coordinate is an exact point, never a request to click a node's centre.
                    // Native target clicks keep their stronger identity checks in click_node.
                    val pointTarget = node?.copy(left = px - 1, right = px + 1, top = py - 1, bottom = py + 1)
                    if (pointTarget != null && AgentModeUiTree.blockingOverlay(pointTarget, settled.snapshot.nodes) != null)
                        return@withContext gatedError("TARGET_OCCLUDED", "The requested point is covered by another window; no tap was sent.")
                    if (shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline)
                        return@withContext gatedError("OPERATION_STOPPED", "Stopped before coordinate dispatch.")
                    requireAgentModeService(settings).tap(displayId, x, y)

                    updateCursorPosition(x, y, animationDurationMillis = 80)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 40,
                    ).let { raw -> JSONObject(raw).put("dispatched", true)
                        .put("coordinate_space", "screen_0_1000")
                        .put("requested_point", JSONArray().put(arguments.optDouble("x")).put(arguments.optDouble("y")))
                        .put("dispatched_pixels", JSONArray().put(x).put(y)).put("used_backend", "coordinate_tap").toString() }
                }
            }
            "scroll" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("query")
                val direction = arguments.optString("direction").ifBlank { "up" }
                if (direction !in setOf("up", "down", "left", "right")) {
                    return@withContext invalidArguments("direction must be up, down, left or right (finger direction).")
                }
                val requested = arguments.optString("snapshot_id")
                val observed = if (requested.isBlank()) lastSnapshot ?: loadPortalSnapshot(settings, alreadyStable = true)
                    else lastSnapshot?.takeIf { it.id == requested } ?: synchronized(observedSnapshots) { observedSnapshots[requested] }
                if (observed == null) return@withContext JSONObject(gatedError(AgentModeSafety.StaleSnapshotCode,
                    "The bound observation is no longer cached. Read scroll_regions once; no gesture was sent."))
                    .put("failure_reason", "observation_expired").put("dispatched", false).toString()
                if (observed.displayId != displayId || observed.width != _displayState.value.width || observed.height != _displayState.value.height)
                    return@withContext JSONObject(gatedError(AgentModeSafety.StaleSnapshotCode, "The observed display changed. No scroll was sent."))
                        .put("failure_reason", "display_changed").put("dispatched", false).toString()
                val target = if (query.isNotBlank()) AgentModePortal.resolveTarget(observed.nodes, query) else
                    observed.nodes.filter { it.scrollable && it.enabled && (if (direction in setOf("up", "down")) it.bottom - it.top >= 180 else it.right - it.left >= 180) }
                        .singleOrNull()
                if (target == null || !target.scrollable) return@withContext invalidArguments("No unambiguous scroll container. Read scroll_regions and pass its query.")
                val current = loadPortalSnapshot(settings, alreadyStable = true)
                val live = AgentModeUiTree.findScrollContainer(observed.nodes, current.nodes, target)
                    ?: return@withContext JSONObject(gatedError(AgentModeSafety.StaleSnapshotCode, "The bound scroll container changed window, disappeared or became ambiguous. No gesture was sent."))
                        .put("failure_reason", "container_identity_changed").put("dispatched", false).toString()
                val settled = awaitStablePortalSnapshot(settings, displayId, timeoutMs = 1400, target = live, observed = current, scrollContainer = true)
                if (!settled.details.optBoolean("stable")) {
                    val blocker = settled.details.optJSONObject("diff")?.optBoolean("blocking_overlay") == true
                    return@withContext JSONObject(gatedError(if (blocker) "SCROLL_TARGET_OCCLUDED" else "SCROLL_STABILITY_UNVERIFIED",
                        "The scroll container could not be verified. Inspect target_stability; no gesture was sent."))
                        .put("failure_reason", if (blocker) "target_occluded" else settled.details.optJSONObject("native_stability")?.optString("reason"))
                        .put("target_stability", settled.details).put("dispatched", false).toString()
                }
                val stableTarget = AgentModeUiTree.findScrollContainer(current.nodes, settled.snapshot.nodes, live)
                    ?: return@withContext gatedError(AgentModeSafety.StaleSnapshotCode, "The scroll container changed during sampling; no gesture was sent.")
                if ((direction in setOf("up", "down") && stableTarget.bottom - stableTarget.top < 180) ||
                    (direction in setOf("left", "right") && stableTarget.right - stableTarget.left < 180))
                    return@withContext invalidArguments("Container is too small for this direction; use the matching scroll_regions reference.")
                val beforeScroll = settled.snapshot
                val beforePixels = runCatching { (service as? kira.phoneuse.agentmode.AetherAgentModeShizukuService)?.frameSignature(displayId) }.getOrNull()
                val ignored = settled.details.optJSONArray("volatile_regions")?.let { regions ->
                    val bounds = (0 until regions.length()).map { regions.getJSONObject(it).optString("bounds") }.toSet()
                    beforeScroll.nodes.filter { it.bounds in bounds && beforeScroll.nodes.none { c -> c.parent == it.index } }
                        .map { "${it.windowId}|${it.identityKey()}" }.toSet()
                }.orEmpty()
                fun evidenceFor(after: GuiSnapshot): JSONObject {
                    val evidence = AgentModeUiTree.scrollEvidence(beforeScroll.nodes, after.nodes, stableTarget, direction, ignored)
                    val afterPixels = runCatching { (service as? kira.phoneuse.agentmode.AetherAgentModeShizukuService)?.frameSignature(displayId) }.getOrNull()
                    val volatileNodes = beforeScroll.nodes.filter { "${it.windowId}|${it.identityKey()}" in ignored }
                    fun scoped(pixels: IntArray?): IntArray? = pixels?.clone()?.apply {
                        val cols = kira.phoneuse.agentmode.VisualCols; val rows = size / cols
                        for (i in indices) {
                            val x = (i % cols * 2 + 1) * 500 / cols; val y = (i / cols * 2 + 1) * 500 / rows
                            if (x < stableTarget.left || x >= stableTarget.right || y < stableTarget.top || y >= stableTarget.bottom ||
                                volatileNodes.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) this[i] = 0
                        }
                    }
                    val visual = visualDiff(scoped(beforePixels), scoped(afterPixels))
                    evidence.put("container_visual_changed", visual != null).put("visual_available", beforePixels != null && afterPixels != null)
                    if (visual != null) evidence.put("moved", true).put("visual", visual)
                    return evidence
                }
                suspend fun inject(container: AgentModeUiTree.RichNode, nodes: List<AgentModeUiTree.RichNode>): Boolean {
                    val x = container.centerX; val y = container.centerY
                    val lx = container.left + (container.right - container.left) / 4; val hx = container.right - (container.right - container.left) / 4
                    val ly = container.top + (container.bottom - container.top) / 4; val hy = container.bottom - (container.bottom - container.top) / 4
                    val horizontal = direction in setOf("left", "right")
                    val points = if (horizontal) listOf(lx to y, x to y, hx to y) else listOf(x to ly, x to y, x to hy)
                    if (points.any { (px, py) -> AgentModeUiTree.blockingOverlay(container.copy(left = px - 1, right = px + 1, top = py - 1, bottom = py + 1), nodes) != null }) return false
                    fun px(v: Int) = normalizedX(v.toDouble()) ?: v
                    fun py(v: Int) = normalizedY(v.toDouble()) ?: v
                    requireAgentModeService(settings).swipe(displayId,
                        px(if (direction == "left") hx else if (direction == "right") lx else x),
                        py(if (direction == "up") hy else if (direction == "down") ly else y),
                        px(if (direction == "left") lx else if (direction == "right") hx else x),
                        py(if (direction == "up") ly else if (direction == "down") hy else y), 350)
                    return true
                }
                activeActionContext.get()?.targetNode = stableTarget
                if (shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline) return@withContext gatedError("OPERATION_STOPPED", "Scroll cancelled before dispatch.")
                val native = requireAgentModeService(settings).scrollNode(displayId, observedNodeQuery(stableTarget), direction)
                activeActionContext.get()?.usedBackend = if (native) "a11y_scroll" else "inject_swipe"
                if (!native && !inject(stableTarget, beforeScroll.nodes)) return@withContext JSONObject(gatedError("SCROLL_TARGET_OCCLUDED",
                    "An overlay intercepts the scroll path. No gesture was injected."))
                    .put("dispatched", false).put("failure_reason", "target_occluded").toString()
                var output = JSONObject(captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 450))
                var after = lastSnapshot ?: beforeScroll
                var evidence = evidenceFor(after)
                var fallback = false
                // Accessibility may acknowledge a no-op. Try one guarded container gesture only
                // when the container advertises remaining content and its post-action layout is quiet.
                val hasMore = stableTarget.state.contains("more both") || stableTarget.state.contains(if (direction in setOf("up", "left")) "more ahead" else "more behind")
                val stillThere = AgentModeUiTree.findScrollContainer(beforeScroll.nodes, after.nodes, stableTarget)
                if (native && !evidence.optBoolean("moved") && hasMore && output.optBoolean("layout_stable") && stillThere != null &&
                    inject(stillThere, after.nodes)) {
                    fallback = true; activeActionContext.get()?.usedBackend = "inject_swipe_after_a11y_no_change"
                    output = JSONObject(captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 450))
                    after = lastSnapshot ?: after
                    evidence = evidenceFor(after)
                }
                output.put("scroll_direction", direction).put("content_direction", when (direction) {
                    "up" -> "forward"; "down" -> "backward"; "left" -> "right"; else -> "left"
                }).put("target_stability", settled.details).put("scroll_evidence", evidence)
                    .put("native_acknowledged", native).put("fallback_used", fallback)
                if (!evidence.optBoolean("moved")) output.put("ok", false).put("code", "SCROLL_NO_CHANGE")
                    .put("failure_reason", "container_no_movement").put("errmsg", "The container did not show scroll progress. No further retries; inspect its boundary or blocker.")
                else if (output.optString("code") == "SCROLL_NO_CHANGE") { output.put("ok", true); output.remove("code"); output.remove("errmsg") }
                output.toString()
            }
            "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down" -> {
                val displayId = ensureDisplay(settings)
                val durationMs = arguments.optInt("duration_ms", arguments.optInt("durationMs", 320))
                    .coerceIn(80, 10_000)
                val endpoints = resolveSwipeEndpoints(settings, arguments, action)
                if (endpoints == null) {
                    invalidArguments(
                        "Pass x1,y1,x2,y2 in 0..1000 coordinates, or use swipe_left/swipe_right/" +
                            "swipe_up/swipe_down (or direction=left|right|up|down).",
                    )
                } else {
                    val (start, end) = endpoints
                    updateCursorPosition(start.first, start.second, animationDurationMillis = 80)
                    controllerScope.launch {
                        delay(40)
                        updateCursorPosition(end.first, end.second, animationDurationMillis = durationMs)
                    }
                    requireAgentModeService(settings).swipe(
                        displayId,
                        start.first,
                        start.second,
                        end.first,
                        end.second,
                        durationMs,
                    )

                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = durationMs.toLong() + 80L,
                    )
                }
            }
            "search" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("text").ifBlank { arguments.optString("query") }
                if (query.isBlank()) {
                    invalidArguments("Missing required 'text' (or query) for search.")
                } else {
                    val snapshot = runCatching { loadPortalSnapshot(settings, alreadyStable = true) }.getOrNull()
                    if (arguments.optString("_host_permission") != "full" && snapshot != null && snapshot.nodes.any { it.focused && AgentModeSafety.sensitiveField(it) })
                        return@withContext JSONObject(gatedError("USER_TAKEOVER_REQUIRED", "This is a credential field. Ask the takeover questionnaire before continuing."))
                            .put("dispatched", false).put("user_takeover", AgentModeSafety.userTakeover(snapshot.nodes)).toString()
                    val field = AgentModeUiTree.findSearchField(snapshot?.nodes.orEmpty())
                    if (field != null && (!field.focused || !field.editable)) {
                        val settled = awaitStablePortalSnapshot(settings, displayId, target = field, observed = snapshot)
                        val current = AgentModeUiTree.findStableControl(snapshot?.nodes.orEmpty(), settled.snapshot.nodes, field)
                        if (!settled.details.optBoolean("stable") || current == null)
                            return@withContext JSONObject(gatedError("SEARCH_FIELD_UNAVAILABLE", "The search entry is moving or occluded, or native verification could not complete. Inspect target_stability; no submit was sent."))
                                .put("target_stability", settled.details).put("dispatched", false).toString()
                        if (!requireAgentModeService(settings).clickNode(displayId, observedNodeQuery(current)))
                            return@withContext gatedError("NATIVE_ACTION_REJECTED", "The search input could not be focused. No coordinate fallback or ENTER was sent.")
                        if (!current.editable) delay(200)
                    }
                    val inputArgs = JSONObject().put("edit_action", "set").put("text", query)
                        .put("_host_permission", arguments.optString("_host_permission"))
                        .put("package", snapshot?.packageName.orEmpty())
                        .put("window_identity", snapshot?.nodes?.filter { it.windowType == 1 }?.map { it.windowId }?.distinct()?.sorted()?.joinToString(",").orEmpty())
                    val input = JSONObject(requireAgentModeService(settings).editText(displayId, inputArgs.toString()) { shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline })
                    if (!input.optBoolean("ok") || !input.optBoolean("text_verified")) return@withContext input.toString()
                    val beforeSample = loadPortalSnapshot(settings, alreadyStable = true)
                    delay(180)
                    val beforeSubmit = loadPortalSnapshot(settings, alreadyStable = true)
                    // Learn this screen's background changes before dispatch, using the same node
                    // identities/diff. A ticker alone is not evidence that ENTER submitted anything.
                    val sampled = beforeSample.nodes.associateBy { "${it.windowId}|${it.identityKey()}" }
                    val volatileKeys = beforeSubmit.nodes.filter { node ->
                        sampled["${node.windowId}|${node.identityKey()}"]?.let { previous ->
                            previous.text != node.text || previous.desc != node.desc || previous.label != node.label ||
                                previous.state != node.state || previous.rangeText != node.rangeText
                        } == true
                    }.map { "${it.windowId}|${it.identityKey()}" }.toSet()
                    val submission = JSONObject(requireAgentModeService(settings).editText(displayId, inputArgs.put("edit_action", "submit").toString()) { shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline })
                    if (!submission.optBoolean("ok")) return@withContext submission.put("text_verified", true).toString()
                    val raw = captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 200)
                    JSONObject(raw).apply {
                        put("text_verified", true).put("submitted", true)
                        val after = lastSnapshot
                        val submitDiff = after?.let { current ->
                            AgentModeUiTree.treeDiff(
                                beforeSubmit.nodes.filterNot { "${it.windowId}|${it.identityKey()}" in volatileKeys },
                                current.nodes.filterNot { "${it.windowId}|${it.identityKey()}" in volatileKeys },
                            )
                        }
                        val changed = submitDiff?.changed == true
                        put("submission_evidence", submitDiff?.toJson() ?: JSONObject())
                        put("background_nodes_ignored", volatileKeys.size)
                        val fieldAfter = requireAgentModeService(settings).readText(displayId, "@focused")?.second
                        put("submission_verified", changed)
                        if (fieldAfter != null) put("field_now", fieldAfter)
                        if (!changed) {
                            put("ok", false).put("code", "SEARCH_SUBMIT_UNVERIFIED")
                            put("errmsg", "Text was written, but no search result transition was verified. Read the returned screen before choosing a submit control; do not repeat ENTER blindly.")
                        }
                    }.toString()
                }
            }
            "key" -> {
                val displayId = ensureDisplay(settings)
                val keyCode = arguments.optString("key").trim()
                if (keyCode.isBlank()) {
                    invalidArguments("Missing required 'key' argument.")
                } else {
                    requireAgentModeService(settings).key(displayId, keyCode)

                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 80,
                    )
                }
            }
            "text", "edit_text", "read_field", "clear_text", "undo" -> {
                val displayId = ensureDisplay(settings)
                val args = JSONObject(arguments.toString())
                val editAction = when(action) {
                    "clear_text" -> "clear"; "undo" -> "undo"; "read_field" -> "read"
                    "text" -> if (arguments.optString("position") in setOf("start", "end", "cursor")) "insert" else "set"
                    else -> arguments.optString("edit_action")
                }
                args.put("edit_action", editAction)
                if (args.optString("query").isNotBlank()) {
                    val focusArgs = JSONObject().put("action", "click_node").put("query", args.optString("query"))
                        .put("_host_permission", arguments.optString("_host_permission"))
                        .put("snapshot_id", args.optString("snapshot_id")).put("observe", false).put("_deadline_ms", deadline)
                    val focused = JSONObject(execute(settings, workspaceDirectory, termuxWorkspaceDirectory, focusArgs.toString(), shouldStop))
                    if (!focused.optBoolean("ok")) return@withContext focused.toString()
                }
                val snapshot = loadPortalSnapshot(settings, alreadyStable = true)
                val takeover = AgentModeSafety.userTakeover(snapshot.nodes)
                if (arguments.optString("_host_permission") != "full" && snapshot.nodes.any { it.focused && AgentModeSafety.sensitiveField(it) })
                    return@withContext JSONObject(gatedError("USER_TAKEOVER_REQUIRED", "Use the takeover questionnaire for credentials."))
                        .put("user_takeover", takeover).toString()
                args.put("package", snapshot.packageName).put("window_identity", snapshot.nodes.filter { it.windowType == 1 }.map { it.windowId }.distinct().sorted().joinToString(","))
                if (shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline) return@withContext gatedError("OPERATION_STOPPED", "Input stopped before dispatch.")
                val result = JSONObject(requireAgentModeService(settings).editText(displayId, args.toString()) { shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline })
                val observed = if (editAction == "read") applyPortalReceipt(JSONObject().put("ok", true), snapshot, "read_field")
                    else JSONObject(captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, 40))
                result.keys().forEach { key -> observed.put(key, result.get(key)) }
                observed.toString()
            }
            "read_text", "read" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("query").trim()
                val requested = arguments.optString("snapshot_id")
                val observed = if (requested.isBlank()) lastSnapshot else synchronized(observedSnapshots) { observedSnapshots[requested] }
                val original = if (query.startsWith("#")) observed?.let { AgentModePortal.findByIndexQuery(it.nodes, query) } else null
                if (query.startsWith("#") && original == null) return@withContext gatedError(AgentModeSafety.StaleSnapshotCode, "The read node is absent from its bound observation; read once before choosing another node.")
                val readSnapshot = loadPortalSnapshot(settings, alreadyStable = true)
                val live = if (original != null) {
                    val current = readSnapshot
                    AgentModeUiTree.findStableControl(observed!!.nodes, current.nodes, original)
                        ?: return@withContext gatedError(AgentModeSafety.StaleSnapshotCode, "The read node moved or changed; its old number was not applied to another element.")
                } else null
                val resolved = live?.let(::observedNodeQuery) ?: query
                val found = runCatching { requireAgentModeService(settings).readText(displayId, resolved) }.getOrNull()
                if (found == null) {
                    JSONObject().put("ok", false).put("code", "NO_TEXT")
                        .put("errmsg", "No element with text matched '${query.ifBlank { "(focused field)" }}'. Use dump_tree, then read_text with #n.")
                        .toString()
                } else {
                    val (label, text) = found
                    val lines = text.split("\n")
                    val offset = arguments.optInt("offset_lines", 0).coerceIn(0, lines.size)
                    val limit = arguments.optInt("limit_lines", 400).coerceIn(1, 2000)
                    val page = lines.drop(offset).take(limit)
                    var start = if (arguments.has("offset_chars")) arguments.optInt("offset_chars").coerceIn(0, text.length)
                        else lines.take(offset).sumOf { it.length + 1 }.coerceAtMost(text.length)
                    if (start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
                    val lineEnd = (start + page.joinToString("\n").length).coerceAtMost(text.length)
                    var end = minOf(text.length, start + arguments.optInt("limit_chars", 8192).coerceIn(1, 8192),
                        if (arguments.has("offset_chars")) text.length else lineEnd)
                    if (end > start && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end += if (end - start == 1) 1 else -1
                    val raw = text.substring(start, end)
                    val numbered = raw.split("\n").mapIndexed { i, line -> "${offset + i + 1}| $line" }.joinToString("\n")
                    JSONObject()
                        .put("ok", true)
                        .put("element", label).put("snapshot_id", readSnapshot.id)
                        .put("package_name", readSnapshot.packageName).put("nodes", JSONArray(readSnapshot.nodes.map(AgentModePortal::toNodeJson)))
                        .put("raw_text", raw).put("text_sha256", AgentDisplayShaper.textHash(text))
                        .put("char_unit", "UTF-16").put("offset_chars", start).put("returned_chars", raw.length)
                        .put("complete", start == 0 && end == text.length)
                        .apply { if (end < text.length) put("next_offset_chars", end) }
                        .put("total_lines", lines.size)
                        .put("total_chars", text.length)
                        .put("offset_lines", offset)
                        .put("returned_lines", page.size)
                        .put("more", end < text.length)
                        .put("text", "<untrusted_gui_content>\n$numbered\n</untrusted_gui_content>")
                        .let { applyPortalReceipt(it, readSnapshot, "read_text") }.toString()
                }
            }
            "back" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_BACK",
            )
            "home" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_HOME",
            )
            "recents", "overview" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_APP_SWITCH",
            )
            "notifications" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_NOTIFICATION",
            )
            "force_stop" -> {
                    val packageName = arguments.optString("target").trim()
                if (packageName.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else {
                    ensureDisplay(settings)
                    requireAgentModeService(settings).runInputCommand(
                        "am force-stop --user 0 ${shellQuote(packageName)}",
                    )
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 400,
                    )
                }
            }
            "screenshot" -> {
                ensureDisplay(settings)
                captureAfterDelay(
                    settings,
                    workspaceDirectory,
                    termuxWorkspaceDirectory,
                    delayMillis = 0,
                )
            }
            "list_targets", "targets", "click_targets" -> {
                ensureDisplay(settings)
                val listed = listClickTargets(
                    settings,
                    simplify = arguments.optBoolean("simplify", false),
                    query = arguments.optString("query"),
                    region = arguments.optString("region"),
                )
                if (!observe) {
                    listed
                } else {
                    val captured = JSONObject(
                        captureAfterDelay(
                            settings,
                            workspaceDirectory,
                            termuxWorkspaceDirectory,
                            delayMillis = 0,
                        ),
                    )
                    val listedJson = JSONObject(listed)
                    captured.put("click_targets", listedJson.optJSONArray("click_targets"))
                    captured.put("click_targets_text", listedJson.optString("click_targets_text"))
                    captured.put("click_target_count", listedJson.optInt("click_target_count"))
                    captured.put("cluttered", listedJson.optBoolean("cluttered"))
                    captured.put("ui_tree", listedJson.optString("ui_tree"))
                    captured.toString()
                }
            }
            "dump_tree" -> {
                ensureDisplay(settings)
                dumpTreePaged(settings, arguments)
            }
            "wait_idle" -> {
                val displayId = ensureDisplay(settings)
                val quiet = arguments.optInt("quiet_ms", arguments.optInt("quietMs", 50))
                val timeout = arguments.optInt("timeout_ms", arguments.optInt("timeoutMs", 800))
                val settled = awaitStablePortalSnapshot(settings, displayId, quiet, timeout,
                    (arguments.optInt("retries", 2) + 1).coerceIn(1, 3))
                val stable = settled.details.optBoolean("stable")
                applyPortalReceipt(JSONObject()
                    .put("ok", stable)
                    .put("idle", stable)
                    .put("layout_stable", stable)
                    .put("layout_stability", settled.details)
                    .put("nodes", JSONArray(settled.snapshot.nodes.map(AgentModePortal::toNodeJson)))
                    .put("stdout", if (stable) "idle" else "timeout"), settled.snapshot, "wait_idle")
                    .toString()
            }
            "wait_for_label" -> {
                val displayId = ensureDisplay(settings)
                val label = arguments.optString("text").ifBlank { arguments.optString("query") }
                    .ifBlank { arguments.optString("target") }
                if (label.isBlank()) {
                    invalidArguments("Missing required 'text' (or query/target) for wait_for_label.")
                } else {
                    val timeout = arguments.optInt("timeout_ms", arguments.optInt("timeoutMs", 2_000))
                    val found = requireAgentModeService(settings).waitForLabel(displayId, label, timeout)
                    JSONObject()
                        .put("ok", found)
                        .put("found", found)
                        .put("label", label)
                        .put("stdout", if (found) "found" else "timeout")
                        .toString()
                }
            }
            "click_node" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("query").ifBlank {
                    arguments.optString("target").ifBlank { arguments.optString("text") }
                }
                if (query.isBlank()) {
                    invalidArguments("Missing required 'query' for click_node.")
                } else {
                    val requestedSnapshot = arguments.optString("snapshot_id")
                    val liveSnapshot = if (requestedSnapshot.isNotBlank()) {
                        lastSnapshot?.takeIf { it.id == requestedSnapshot }
                            ?: synchronized(observedSnapshots) { observedSnapshots[requestedSnapshot] }
                    } else lastSnapshot ?: runCatching {
                        loadPortalSnapshot(settings, alreadyStable = true)
                    }.getOrNull()
                    if (requestedSnapshot.isNotBlank() && liveSnapshot == null) {
                        gatedError(
                            AgentModeSafety.StaleSnapshotCode,
                            "snapshot_id $requestedSnapshot is stale; dump_tree again before click_node.",
                        )
                    } else {
                        val nodes = liveSnapshot?.nodes.orEmpty()
                        var target = AgentModePortal.resolveTarget(nodes, query)
                        var targetStability: JSONObject? = null
                        if (query.startsWith("#") && target == null) {
                            return@withContext gatedError(AgentModeSafety.StaleSnapshotCode, "The node is absent from the observed screen; read the screen before retrying.")
                        }
                        if (target != null) {
                            if (liveSnapshot?.displayId != displayId || liveSnapshot.width != _displayState.value.width ||
                                liveSnapshot.height != _displayState.value.height) {
                                return@withContext gatedError(AgentModeSafety.StaleSnapshotCode, "The observed display or geometry changed. No click was sent.")
                            }
                            val settled = awaitStablePortalSnapshot(settings, displayId, target = target, observed = liveSnapshot)
                            targetStability = settled.details
                            if (!settled.details.optBoolean("stable")) {
                                val diff = settled.details.optJSONObject("diff") ?: JSONObject()
                                val native = settled.details.optJSONObject("native_stability") ?: JSONObject()
                                val reason = if (diff.optBoolean("blocking_overlay")) "target_occluded"
                                    else if (!diff.optBoolean("target_present", true) || diff.optBoolean("target_changed")) "target_changed"
                                    else native.optString("reason").ifBlank { "target_not_quiet" }
                                val toolFailure = reason in setOf("target_unavailable", "insufficient_samples", "invalid_target", "operation_stopped")
                                val message = when (reason) {
                                    "target_occluded" -> "A higher window or view intercepts this target. Inspect the blocking window and recover once; do not repeat the same click."
                                    "target_changed" -> "The observed target moved or changed. Read a fresh observation and resolve its identity."
                                    "target_not_quiet" -> "The target itself changed during native sampling. No click was sent; unrelated carousel text is excluded."
                                    else -> "Native target verification could not complete. No click was sent. The complete tree does not prove a carousel is blocking; inspect native_stability."
                                }
                                return@withContext JSONObject(gatedError(if (toolFailure) "NATIVE_STABILITY_UNVERIFIED" else "LAYOUT_UNSTABLE", message))
                                    .put("failure_reason", reason).put("failure_source", if (toolFailure) "tool" else "ui")
                                    .put("dispatched", false).put("layout_stability", settled.details)
                                    .put("target_stability", settled.details).put("blocked_target", AgentModePortal.toNodeJson(target)).toString()
                            }
                            // All observations use one complete tree; compact output is not an identity source.
                            val current = settled.snapshot
                            val currentTarget = AgentModeUiTree.findStableControl(nodes, current.nodes, target)
                            if (currentTarget == null) {
                                return@withContext gatedError(AgentModeSafety.StaleSnapshotCode, "The observed control moved or changed. No click was sent; read the screen or resolve its stable reference again.")
                            }
                            target = currentTarget
                        }
                        if (target != null &&
                            AgentModeSafety.looksSensitiveLabel(target.label.ifBlank { target.text }) &&
                            arguments.optString("_host_permission") != "full"
                        ) {
                            gatedError(
                                AgentModeSafety.SensitiveActionCode,
                                "Sensitive control '${target.label}' requires trusted host approval. Model confirm flags do not grant permission.",
                            )
                        } else {
                            activeActionContext.get()?.targetNode = target
                            if (shouldStop() || android.os.SystemClock.uptimeMillis() >= deadline) return@withContext gatedError("OPERATION_STOPPED", "Click cancelled before dispatch.")
                            val nativeClick = JSONObject(requireAgentModeService(settings).clickNodeDetails(
                                displayId,
                                target?.let(::observedNodeQuery) ?: AgentModePortal.clickQueryForNode(query, target),
                            ))
                            val clicked = nativeClick.optBoolean("ok")
                            var usedBackend = if (clicked) "a11y" else ""
                            if (clicked && target != null) {
                                // The phone window shows where the click landed, whichever way it was done.
                                updateCursorPosition(
                                    target.centerX * _displayState.value.width / 1000,
                                    target.centerY * _displayState.value.height / 1000,
                                    animationDurationMillis = 80,
                                )
                            }
                            if (usedBackend.isBlank()) {
                                target?.let { nativeClick.put("blocked_target", AgentModePortal.toNodeJson(it)) }
                                nativeClick.toString()
                            } else {
                                activeActionContext.get()?.usedBackend = usedBackend

                                val raw = captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                                JSONObject(raw).apply {
                                    targetStability?.let { put("target_stability", it) }
                                }.toString()
                            }
                        }
                    }
                }
            }
            "switch_display", "display" -> switchDisplay(
                settings,
                arguments.optString("to").ifBlank { arguments.optString("mode") },
                bringApp = arguments.optBoolean("bring_app", true),
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            )
            "drag" -> {
                val displayId = ensureDisplay(settings)
                val endpoints = resolveDragEndpoints(settings, arguments)
                if (endpoints == null) {
                    invalidArguments(
                        "drag needs x1,y1,x2,y2 (0..1000), or query (a slider/handle node, e.g. \"#7\") " +
                            "with to (0..1 along the control) or x2/y2.",
                    )
                } else {
                    val (start, end) = endpoints
                    val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 600))
                    val hold = arguments.optInt("hold_ms", arguments.optInt("holdMs", 0))
                    updateCursorPosition(start.first, start.second, animationDurationMillis = 80)
                    requireAgentModeService(settings).drag(
                        displayId, start.first, start.second, end.first, end.second, duration, hold,
                    )
                    updateCursorPosition(end.first, end.second, animationDurationMillis = duration)
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 120)
                }
            }
            "set_progress", "set_value", "slider" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("query").ifBlank { arguments.optString("target") }
                val value = arguments.optDouble("value", Double.NaN)
                if (query.isBlank() || value.isNaN()) {
                    invalidArguments("set_progress needs query (the slider node, e.g. \"#7\") and value (in the slider's own range).")
                } else {
                    val snapshot = lastSnapshot ?: runCatching { loadPortalSnapshot(settings, alreadyStable = true) }.getOrNull()
                    // #n from dump_tree/find counts the full tree; the cached snapshot may be a filtered
                    // one where the same number is another node. Only trust it when it is a slider.
                    val target = snapshot?.let { AgentModePortal.resolveTarget(it.nodes, query) }?.takeIf { it.hasRange }
                        ?: runCatching { loadPortalSnapshot(settings, alreadyStable = true) }.getOrNull()
                            ?.let { AgentModePortal.resolveTarget(it.nodes, query) }?.takeIf { it.hasRange }
                    activeActionContext.get()?.targetNode = target
                    when (requireAgentModeService(settings).setProgress(displayId, boundsQuery(target) ?: query, value.toFloat())) {
                        null -> JSONObject()
                            .put("ok", false)
                            .put("code", "NOT_A_SLIDER")
                            .put("errmsg", "'$query' exposes no range (not an accessible slider). Use action=drag on it instead.")
                            .toString()
                        false -> JSONObject()
                            .put("ok", false)
                            .put("code", "SET_PROGRESS_REJECTED")
                            .put("errmsg", "The slider refused ACTION_SET_PROGRESS. Use action=drag on it instead.")
                            .toString()
                        true -> {
                            val target = target ?: runCatching { loadPortalSnapshot(settings, alreadyStable = false) }.getOrNull()
                                ?.nodes?.filter { it.hasRange }
                                ?.minByOrNull { kotlin.math.abs(it.rangeValue - value.toFloat()) }
                            // Many sliders save only when a touch ends (SystemUI brightness keeps the
                            // value live but never writes the setting). Finish like a user: one tap on
                            // the thumb where the value now sits - at either end, past the end.
                            if (target != null && target.hasRange && target.rangeMax > target.rangeMin && target.right > target.left) {
                                val frac = ((value.toFloat() - target.rangeMin) / (target.rangeMax - target.rangeMin)).coerceIn(0f, 1f)
                                val width = (target.right - target.left).toDouble()
                                val thumb = target.left + frac * width
                                val py = normalizedY((target.top + target.bottom) / 2.0)
                                android.os.SystemClock.sleep(150)
                                if (py != null && (frac >= 0.999f || frac <= 0.001f)) {
                                    // An end value: press on the thumb and slide past the track's end.
                                    // The slider keeps tracking outside its box and clamps, so the
                                    // value lands exactly on min/max and is saved on release.
                                    val from = normalizedX(thumb.coerceIn(target.left + 2.0, target.right - 2.0))
                                    val to = normalizedX(if (frac >= 0.999f) (target.right + 30.0).coerceAtMost(999.0) else (target.left - 30.0).coerceAtLeast(0.0))
                                    if (from != null && to != null) requireAgentModeService(settings).drag(displayId, from, py, to, py, 350, 80)
                                } else {
                                    val px = normalizedX(thumb)
                                    if (px != null && py != null) requireAgentModeService(settings).tap(displayId, px, py)
                                }
                            }
                            captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 200)
                        }
                    }
                }
            }
            "long_press" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 600))
                    requireAgentModeService(settings).longPress(displayId, x, y, duration)
                    updateCursorPosition(x, y, animationDurationMillis = 80)

                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                }
            }
            "double_tap" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    requireAgentModeService(settings).doubleTap(displayId, x, y)
                    updateCursorPosition(x, y, animationDurationMillis = 80)

                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                }
            }
            "pinch" -> {
                val displayId = ensureDisplay(settings)
                val cx = normalizedX(arguments.optDouble("x", Double.NaN))
                val cy = normalizedY(arguments.optDouble("y", Double.NaN))
                val startSpan = normalizedX(arguments.optDouble("start_span", arguments.optDouble("startSpan", 180.0)))
                val endSpan = normalizedX(arguments.optDouble("end_span", arguments.optDouble("endSpan", 60.0)))
                if (cx == null || cy == null || startSpan == null || endSpan == null) {
                    invalidArguments("pinch needs x, y, start_span, and end_span in 0..1000 coordinates.")
                } else {
                    val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 280))
                    requireAgentModeService(settings).pinch(displayId, cx, cy, startSpan, endSpan, duration)
                    updateCursorPosition(cx, cy, animationDurationMillis = 80)

                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = duration.toLong().coerceAtMost(160L))
                }
            }
            "fling" -> {
                val displayId = ensureDisplay(settings)
                val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 90)).coerceIn(40, 240)
                val endpoints = resolveSwipeEndpoints(settings, arguments, action)
                if (endpoints == null) {
                    invalidArguments(
                        "fling needs x1,y1,x2,y2 or direction=left|right|up|down in 0..1000 coordinates.",
                    )
                } else {
                    val (start, end) = endpoints
                    requireAgentModeService(settings).swipe(
                        displayId,
                        start.first,
                        start.second,
                        end.first,
                        end.second,
                        duration,
                    )
                    updateCursorPosition(end.first, end.second, animationDurationMillis = duration)

                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                }
            }
            "scroll_until" -> {
                val displayId = ensureDisplay(settings)
                val label = arguments.optString("text").ifBlank { arguments.optString("query") }
                    .ifBlank { arguments.optString("target") }
                if (label.isBlank()) {
                    invalidArguments("Missing required 'text' (or query) for scroll_until.")
                } else {
                    val maxSwipes = arguments.optInt("max_swipes", 6).coerceIn(1, 12)
                    var found = requireAgentModeService(settings).waitForLabel(displayId, label, 200)
                    var swipes = 0
                    val direction = arguments.optString("direction").ifBlank { "up" }
                    while (!found && swipes < maxSwipes) {
                        if (shouldStop() || (arguments.optLong("_deadline_ms") > 0 && android.os.SystemClock.uptimeMillis() >= arguments.optLong("_deadline_ms")))
                            return@withContext JSONObject().put("ok", false).put("code", "OPERATION_STOPPED")
                                .put("cancel_requested", true).put("completed_steps", swipes).put("running_step", -1)
                                .put("dispatched", swipes > 0).toString()
                        val scrolled = JSONObject(execute(settings, workspaceDirectory, termuxWorkspaceDirectory,
                            JSONObject().put("action", "scroll").put("direction", direction)
                                .put("_deadline_ms", arguments.optLong("_deadline_ms", Long.MAX_VALUE)).toString(), shouldStop, onProgress))
                        swipes += 1
                        found = requireAgentModeService(settings).waitForLabel(displayId, label, 400)
                        if (!scrolled.optBoolean("ok", true)) break
                    }
                    activeActionContext.get()?.portalAction = "scroll_until"
                    activeActionContext.get()?.skipCapture = false
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40).let { raw ->
                        JSONObject(raw)
                            .put("found", found)
                            .put("swipes", swipes)
                            .put("label", label)
                            .put("ok", found)
                            .toString()
                    }
                }
            }
            "request_teaching" -> {
                ensureDisplay(settings)
                startTeaching(
                    arguments.optString("text").ifBlank {
                        arguments.optString("hint")
                    },
                )
            }
            "teaching_status" -> teachingStatusResult()
            "finish_teaching" -> {
                finishTeaching()
                teachingStatusResult()
            }
            "stop" -> {
                releaseDisplay()
                JSONObject().apply {
                    put("ok", true)
                    put("stdout", "Agent Mode virtual display stopped.")
                }.toString()
            }
            else -> invalidArguments("Unsupported action '$action'.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = action.ifBlank { "unknown" },
                    reason = "unsupported_action",
                    message = "Unsupported action '$action'.",
                )
            }
            }.also {
                val result = runCatching { JSONObject(it) }.getOrNull()
                diagnosticLogger.event(
                    category = "agent_mode",
                    event = "action_end",
                    level = if (result?.optBoolean("ok", true) == false) "warn" else "info",
                    details = mapOf(
                        "action" to action.ifBlank { "unknown" },
                        "ok" to (result?.optBoolean("ok", true) ?: true),
                        "display_id" to _displayState.value.displayId,
                        "display_active" to _displayState.value.isActive,
                        "message" to result?.optString("errmsg").orEmpty(),
                        "failure_code" to GuiFailureSignals.failureCode(result ?: JSONObject()),
                        "repair_strategy" to result?.optJSONObject("failure")?.optString("repair_strategy").orEmpty(),
                        "snapshot_id" to result?.optString("snapshot_id").orEmpty(),
                        "tree_diff_changed" to (result?.optJSONObject("tree_diff")?.optBoolean("changed") == true),
                    ),
                )
            }
        }.getOrElse { throwable ->
            captureAgentModeFailed(
                settings = settings,
                action = action.ifBlank { "unknown" },
                reason = "exception",
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
            val interruptCode = when {
                throwable is CancellationException -> AgentModeSafety.CaptureTimeoutCode
                else -> throwable.message
                    ?.substringBefore(':')
                    ?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{3,}")) }
            }
            toolError(
                message = sanitizeAgentModeError(throwable.message),
                action = action,
                code = interruptCode,
            )
        }
        }
    }

    suspend fun refreshAuthorization(settings: AppSettings) {
        if (
            shizukuDisplayId != null &&
            displayOwnerMethod != null &&
            displayOwnerMethod != settings.agentModeAuthorizationMethod
        ) {
            releaseDisplay("Virtual display reset because Agent Mode authorization method changed.")
        }
        _authorizationState.value = inspectAuthorization(settings).also { state ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "authorization_refreshed",
                level = if (state.isReady || state.issue == AgentModeAuthorizationIssue.Disabled) "info" else "warn",
                details = mapOf(
                    "issue" to state.issue.name,
                    "detail" to state.detail,
                    "method" to settings.agentModeAuthorizationMethod.storageValue,
                    "enabled" to settings.agentModeAuthorizationEnabled,
                ),
            )
        }
    }

    private suspend fun ensureDisplay(settings: AppSettings): Int = ensureDisplayMutex.withLock {
        shizukuDisplayId?.let { return@withLock it }
        if (displayMode == "real") {
            // The mode decides the display: real mode borrows the phone's own screen again rather
            // than quietly creating a virtual one under a "real" label.
            val realId = service.adoptDefaultDisplay()
            shizukuDisplayId = realId
            displayOwnerMethod = settings.agentModeAuthorizationMethod
            val (w, h) = realDisplaySize()
            _displayState.value = _displayState.value.copy(
                isActive = true, displayId = realId, width = w, height = h,
                status = "Real screen", lastUpdatedMillis = System.currentTimeMillis(),
            )
            return@withLock realId
        }
        val displaySpec = currentDeviceDisplaySpec()
        // There is no in-app GL preview surface to render into: the plugin has no app
        // window on the phone: the display always renders into the service's ImageReader, and the
        // computer-side window streams frames from there.
        val displayId = runCatching {
            service.createOwnedDisplay(
                AgentDisplayName,
                displaySpec.width,
                displaySpec.height,
                displaySpec.densityDpi,
            )
        }.getOrElse { error ->
            // Virtual displays for third-party apps need Android 10+. Older phones (or OEM builds that
            // refuse the shell uid) are driven on the real screen instead - real-screen mode.
            diagnosticLogger.event(
                category = "agent_mode",
                event = "virtual_display_unavailable",
                level = "warn",
                details = mapOf("error" to error.message.orEmpty(), "fallback" to "real_screen"),
            )
            realScreenFallback = true
            displayMode = "real"
            service.adoptDefaultDisplay()
        }
        shizukuDisplayId = displayId
        displayOwnerMethod = settings.agentModeAuthorizationMethod
        diagnosticLogger.event(
            category = "agent_mode",
            event = "display_created",
            details = mapOf(
                "display_id" to displayId,
                "width" to displaySpec.width,
                "height" to displaySpec.height,
                "density_dpi" to displaySpec.densityDpi,
                "real_screen" to realScreenFallback,
            ),
        )
        val (stateWidth, stateHeight) = if (realScreenFallback) realDisplaySize() else displaySpec.width to displaySpec.height
        _displayState.value = AgentModeDisplayState(
            isActive = true,
            displayId = displayId,
            width = stateWidth,
            height = stateHeight,
            displays = currentDisplays(settings, displayId),
            isLivePreviewActive = true,
            status = if (realScreenFallback) "Real screen (virtual display unavailable)" else "Virtual display ready",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        if (!realScreenFallback) runCatching { service.launchHomeOnDisplay(displayId) }
        displayId
    }

    /** "virtual" (the private display) or "real" (the phone's own screen, chosen by the user). */
    @Volatile
    var displayMode: String = "virtual"
        private set
    /** Per package: how often it failed to stay on the virtual display in this server's life. */
    private val virtualTrouble = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** True when this phone could not host a virtual display and the real screen is driven instead. */
    @Volatile
    var realScreenFallback: Boolean = false
        private set

    fun stopDisplay() {
        releaseDisplay()
    }

    suspend fun injectUserTap(settings: AppSettings, x: Int, y: Int) = withContext(Dispatchers.IO) {
        if (!isTeachingActive) return@withContext
        val displayId = currentManagedDisplayId(settings) ?: return@withContext
        val boundedX = x.coerceIn(0, (_displayState.value.width - 1).coerceAtLeast(0))
        val boundedY = y.coerceIn(0, (_displayState.value.height - 1).coerceAtLeast(0))
        requireAgentModeService(settings).tap(displayId, boundedX, boundedY)


        updateCursorPosition(boundedX, boundedY, animationDurationMillis = 120)
        val state = _displayState.value
        notifyUserTap(
            normalizedX = toNormalizedAxis(boundedX, state.width),
            normalizedY = toNormalizedAxis(boundedY, state.height),
        )
        captureTeachingPageIfNeeded(settings)
    }

    suspend fun injectUserSwipe(
        settings: AppSettings,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 280,
    ) = withContext(Dispatchers.IO) {
        if (!isTeachingActive) return@withContext
        val displayId = currentManagedDisplayId(settings) ?: return@withContext
        val width = _displayState.value.width
        val height = _displayState.value.height
        val startX = x1.coerceIn(0, (width - 1).coerceAtLeast(0))
        val startY = y1.coerceIn(0, (height - 1).coerceAtLeast(0))
        val endX = x2.coerceIn(0, (width - 1).coerceAtLeast(0))
        val endY = y2.coerceIn(0, (height - 1).coerceAtLeast(0))
        val duration = durationMs.coerceIn(50, 10_000)
        updateCursorPosition(startX, startY, animationDurationMillis = 80)
        controllerScope.launch {
            delay(40)
            updateCursorPosition(endX, endY, animationDurationMillis = duration)
        }
        requireAgentModeService(settings).swipe(displayId, startX, startY, endX, endY, duration)


        notifyUserSwipe(
            normalizedX1 = toNormalizedAxis(startX, width),
            normalizedY1 = toNormalizedAxis(startY, height),
            normalizedX2 = toNormalizedAxis(endX, width),
            normalizedY2 = toNormalizedAxis(endY, height),
            durationMs = duration,
        )
        captureTeachingPageIfNeeded(settings)
    }

    suspend fun refreshDisplays(settings: AppSettings) {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        _displayState.value = state.copy(
            displays = currentDisplays(settings, state.displayId),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun launchTarget(
        settings: AppSettings,
        target: String,
    ): String {
        val displayId = ensureDisplay(settings)
        val launchPackage = resolveLaunchPackage(settings, target)
            ?: error("No launchable app matched '$target'. Try a package name such as com.android.chrome, or a shorter app label.")
        requireAgentModeService(settings).launchPackage(launchPackage, displayId)
        return launchPackage
    }

    /**
     * Start the virtual display and, when the user named a unique installed
     * app, launch it before the phone subagent is spawned.
     */
    suspend fun preopenForGoal(
        settings: AppSettings,
        goal: String,
    ): AgentModePreopenResult? = withContext(Dispatchers.IO) {
        val match = inferUniqueLaunchTarget(settings, goal)
        if (match == null) {
            runCatching { ensureDisplay(settings) }
            return@withContext null
        }
        runCatching {
            val launched = launchTarget(settings, match.packageName)
            val displayId = currentManagedDisplayId(settings)
            val visible = displayId != null &&
                runCatching {
                    requireAgentModeService(settings).packageVisibleOnDisplay(displayId, launched)
                }.getOrDefault(false)
            diagnosticLogger.event(
                category = "agent_mode",
                event = if (visible) "preopen_launch" else "preopen_not_visible",
                details = mapOf(
                    "app_name" to match.appName,
                    "package_name" to match.packageName,
                    "visible_on_virtual" to visible,
                ),
            )
            match.takeIf { visible }
        }.getOrNull()
    }

    private suspend fun inferUniqueLaunchTarget(
        settings: AppSettings,
        goal: String,
    ): AgentModePreopenResult? {
        val text = goal.trim()
        if (text.length < 2) return null
        val apps = currentInstalledApps(settings).filter { it.isEnabled }
        val named = apps.mapNotNull { app ->
            val name = app.appName.trim()
            if (name.length < 2) return@mapNotNull null
            if (!text.contains(name, ignoreCase = true) &&
                !text.contains(app.packageName, ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            AgentModePreopenResult(packageName = app.packageName, appName = app.appName)
        }
        val best = named.maxByOrNull { it.appName.length } ?: return null
        val ambiguous = named.any { other ->
            other.packageName != best.packageName &&
                !best.appName.contains(other.appName, ignoreCase = true) &&
                !other.appName.contains(best.appName, ignoreCase = true)
        }
        return best.takeUnless { ambiguous }
    }

    private suspend fun resolveLaunchPackage(
        settings: AppSettings,
        target: String,
    ): String? {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget.isBlank()) return null
        context.packageManager.getLaunchIntentForPackage(target)?.let { return target }

        val hostPkg = context.packageName
        val launchables = currentInstalledApps(settings)
        val tokens = normalizedTarget.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("app", "browser", "managed") }
        return launchables.firstOrNull { app ->
            app.packageName.equals(normalizedTarget, ignoreCase = true) ||
                app.appName.equals(target, ignoreCase = true)
        }?.packageName ?: launchables.firstOrNull { app ->
            app.packageName != hostPkg && (
                app.packageName.lowercase().contains(normalizedTarget) ||
                    app.appName.lowercase().contains(normalizedTarget) ||
                    app.activityName.lowercase().contains(normalizedTarget) ||
                    tokens.any { token ->
                        app.packageName.lowercase().contains(token) ||
                            app.appName.lowercase().contains(token) ||
                            app.activityName.lowercase().contains(token)
                    }
                )
        }?.packageName ?: target.takeIf {
            it.matches(Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+"""))
        }
    }

    internal suspend fun listInstalledAppsCatalog(
        settings: AppSettings,
        arguments: JSONObject = JSONObject(),
        workspaceDirectory: String = "",
    ): String {
        val raw = listInstalledAppsResult(settings, arguments)
        persistDeviceAppsSnapshot(settings, workspaceDirectory, raw)
        return raw
    }

    private suspend fun persistDeviceAppsSnapshot(
        settings: AppSettings,
        workspaceDirectory: String,
        raw: String,
    ) {
        if (workspaceDirectory.isBlank()) return
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!parsed.optBoolean("ok", false)) return
        val snapshot = JSONObject()
            .put("ok", true)
            .put("count", parsed.optInt("count"))
            .put("apps", parsed.optJSONArray("apps") ?: JSONArray())
        runtimeWorkspaceFileBridge.writeWorkspaceBytes(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = workspaceDirectory,
            absolutePath = DeviceCatalogMcp.SnapshotGuestPath,
            bytes = snapshot.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun listInstalledAppsResult(
        settings: AppSettings,
        arguments: JSONObject,
    ): String {
        val query = arguments.optString("query").trim()
        val normalizedQuery = query.lowercase()
        val includeSystem = arguments.optBoolean(
            "include_system",
            arguments.optBoolean("includeSystem", false),
        )
        val maxResults = arguments.optInt(
            "max_results",
            arguments.optInt("maxResults", 500),
        ).coerceIn(1, 1_000)
        val apps = currentInstalledApps(settings)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.packageName.lowercase().contains(normalizedQuery) ||
                    app.appName.lowercase().contains(normalizedQuery) ||
                    app.activityName.lowercase().contains(normalizedQuery)
            }
            .toList()
        val visibleApps = apps.take(maxResults)
        return JSONObject().apply {
            put("ok", true)
            put("count", apps.size)
            put("truncated", apps.size > visibleApps.size)
            put(
                "apps",
                JSONArray().apply {
                    visibleApps.forEach { app ->
                        put(
                            JSONObject().apply {
                                put("app_name", app.appName)
                                put("package_name", app.packageName)
                                put("activity_name", app.activityName)
                                put("enabled", app.isEnabled)
                                put("system", app.isSystemApp)
                                put("launchable", true)
                            }
                        )
                    }
                },
            )
            put(
                "stdout",
                buildString {
                    append("Found ")
                    append(apps.size)
                    append(if (includeSystem) " launchable apps." else " non-system launchable apps.")
                    if (apps.size > visibleApps.size) {
                        append(" Showing ")
                        append(visibleApps.size)
                        append(".")
                    }
                    visibleApps.forEach { app ->
                        append('\n')
                        append(app.appName)
                        append(" -> ")
                        append(app.packageName)
                    }
                },
            )
        }.toString()
    }

    private suspend fun currentInstalledApps(settings: AppSettings): List<AgentModeInstalledAppInfo> {
        val privilegedApps = runCatching {
            parseInstalledApps(requireAgentModeService(settings).listInstalledAppsJson())
        }.getOrNull()
        return (privilegedApps?.takeIf { it.isNotEmpty() } ?: currentInstalledAppsLocal())
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.appName.lowercase() }, { it.packageName }))
    }

    @Suppress("DEPRECATION")
    private fun currentInstalledAppsLocal(): List<AgentModeInstalledAppInfo> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val applicationInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            AgentModeInstalledAppInfo(
                packageName = activityInfo.packageName.orEmpty(),
                appName = info.loadLabel(packageManager).toString(),
                activityName = activityInfo.name.orEmpty(),
                isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isEnabled = activityInfo.enabled && applicationInfo.enabled,
            )
        }
    }

    private fun parseInstalledApps(rawValue: String): List<AgentModeInstalledAppInfo> {
        val array = JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("package_name").ifBlank {
                    item.optString("packageName")
                }
                if (packageName.isBlank()) continue
                add(
                    AgentModeInstalledAppInfo(
                        packageName = packageName,
                        appName = item.optString("app_name").ifBlank {
                            item.optString("appName").ifBlank { packageName }
                        },
                        activityName = item.optString("activity_name").ifBlank {
                            item.optString("activityName")
                        },
                        isSystemApp = item.optBoolean("system"),
                        isEnabled = item.optBoolean("enabled", true),
                    )
                )
            }
        }
    }

    private suspend fun listClickTargets(
        settings: AppSettings,
        simplify: Boolean,
        query: String = "",
        region: String = "",
    ): String {
        val snapshot = loadPortalSnapshot(settings)
        val filtered = AgentModeUiTree.interactiveNodes(snapshot.nodes)
            .filter { it.matches(query) && it.inRegion(region) }
            .filter { !simplify || !AgentModeUiTree.shouldSimplifyRich(it) }
        val result = JSONObject()
            .put("ok", true)
            .put("display_id", snapshot.displayId)
            .put("stdout", "ok")
        attachRichClickTargets(result, filtered)
        result.put(
            "ui_tree",
            AgentModeUiTree.wrapUntrustedGuiContent(
                filtered.joinToString("\n") { AgentModeUiTree.compactLine(it) },
            ),
        )
        return applyPortalReceipt(result, snapshot, action = "list_targets").toString()
    }

    private suspend fun dumpTreePaged(settings: AppSettings, arguments: JSONObject): String {
        val settled = awaitStablePortalSnapshot(settings, currentManagedDisplayId(settings) ?: ensureDisplay(settings))
        val snapshot = settled.snapshot
        val paged = AgentModeUiTree.pageRichNodes(
            nodes = if (arguments.optBoolean("cheap")) AgentModeUiTree.interactiveNodes(snapshot.nodes) else snapshot.nodes,
            query = arguments.optString("query"),
            region = arguments.optString("region"),
            offset = arguments.optInt("offset"),
            limit = arguments.optInt("limit", arguments.optInt("max_results", 80))
                .coerceIn(1, AgentModeUiTree.MaxDumpLimit),
        )
        val parsed = JSONObject()
            .put("ok", true)
            .put("display_id", snapshot.displayId)
            .put("layout_stable", settled.details.optBoolean("stable"))
            .put("layout_stability", settled.details)
            .put("offset", paged.offset)
            .put("limit", paged.limit)
            .put("total", paged.total)
            .put("has_more", paged.hasMore)
        val nodeArray = JSONArray()
        paged.nodes.forEach { nodeArray.put(AgentModePortal.toNodeJson(it)) }
        parsed.put("nodes", nodeArray)
        val textOnly = AgentModeCapture.wantsTextTree(arguments)
        val deltaOnly = AgentModeCapture.wantsDeltaTree(arguments)
        val lines = when {
            textOnly -> paged.nodes.map { node ->
                node.text.ifBlank { node.label }.ifBlank { node.desc }
            }.filter { it.isNotBlank() }
            deltaOnly -> AgentModeUiTree.deltaLines(
                previous = lastSnapshot?.nodes.orEmpty(),
                current = paged.nodes,
            ).ifEmpty { listOf("ok") }
            else -> paged.nodes.map { AgentModeUiTree.compactLine(it) }
        }
        parsed.put("stdout", lines.joinToString("\n").ifBlank { "ok" })
        return applyPortalReceipt(parsed, snapshot, action = "dump_tree").toString()
    }

    private suspend fun scrollSwipeForDisplay(
        settings: AppSettings,
        direction: String = "up",
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val snapshot = runCatching { loadPortalSnapshot(settings) }.getOrNull()
            ?: return AgentModeUiTree.directionalSwipe(emptyList(), direction)
        return AgentModeUiTree.directionalSwipe(snapshot.nodes, direction)
    }

    /** "@px:l,t,r,b" for a node from the last dump: what the live tree is searched by. */
    private fun observedNodeQuery(node: AgentModeUiTree.RichNode): String = "@node:" +
        AgentModePortal.toNodeJson(node).put("bounds_query", boundsQuery(node)).toString()

    private fun boundsQuery(node: AgentModeUiTree.RichNode?): String? {
        node ?: return null
        val w = _displayState.value.width.coerceAtLeast(1)
        val h = _displayState.value.height.coerceAtLeast(1)
        return "${kira.phoneuse.agentmode.AgentModeUiAutomation.BoundsQueryPrefix}" +
            "${node.left * w / 1000},${node.top * h / 1000},${node.right * w / 1000},${node.bottom * h / 1000}"
    }

    /**
     * Drag endpoints in display pixels. With a node: start on the slider thumb (its current value
     * when the node reports a range, else the node centre) and end at fraction [to] of its length.
     */
    private suspend fun resolveDragEndpoints(
        settings: AppSettings,
        arguments: JSONObject,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val query = arguments.optString("query").ifBlank { arguments.optString("target") }
        if (query.isBlank()) {
            val x1 = normalizedX(arguments.optDouble("x1", Double.NaN)) ?: return null
            val y1 = normalizedY(arguments.optDouble("y1", Double.NaN)) ?: return null
            val x2 = normalizedX(arguments.optDouble("x2", Double.NaN)) ?: return null
            val y2 = normalizedY(arguments.optDouble("y2", Double.NaN)) ?: return null
            return (x1 to y1) to (x2 to y2)
        }
        val snapshot = lastSnapshot ?: runCatching { loadPortalSnapshot(settings) }.getOrNull() ?: return null
        val node = AgentModePortal.resolveTarget(snapshot.nodes, query) ?: return null
        activeActionContext.get()?.targetNode = node
        val horizontal = (node.right - node.left) >= (node.bottom - node.top)
        // A thumb sits inside the track; keep a margin so the press lands on the control.
        val pad = if (horizontal) ((node.right - node.left) * 0.04).toInt() else ((node.bottom - node.top) * 0.04).toInt()
        fun along(fraction: Double): Pair<Double, Double> = if (horizontal) {
            (node.left + pad + (node.right - node.left - 2 * pad) * fraction) to node.centerY.toDouble()
        } else {
            // Vertical sliders grow upwards.
            node.centerX.toDouble() to (node.bottom - pad - (node.bottom - node.top - 2 * pad) * fraction)
        }
        val current = if (node.hasRange && !node.rangeMin.isNaN() && !node.rangeMax.isNaN() && node.rangeMax > node.rangeMin) {
            ((node.rangeValue - node.rangeMin) / (node.rangeMax - node.rangeMin)).toDouble().coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        val (sx, sy) = along(current)
        val to = arguments.optDouble("to", Double.NaN)
        val (ex, ey) = if (!to.isNaN()) along(to.coerceIn(0.0, 1.0)) else {
            val x2 = arguments.optDouble("x2", Double.NaN)
            val y2 = arguments.optDouble("y2", Double.NaN)
            if (x2.isNaN() && y2.isNaN()) return null
            (if (x2.isNaN()) sx else x2) to (if (y2.isNaN()) sy else y2)
        }
        val start = (normalizedX(sx) ?: return null) to (normalizedY(sy) ?: return null)
        val end = (normalizedX(ex) ?: return null) to (normalizedY(ey) ?: return null)
        return start to end
    }

    private suspend fun resolveSwipeEndpoints(
        settings: AppSettings,
        arguments: JSONObject,
        action: String,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val x1 = normalizedX(arguments.optDouble("x1", Double.NaN))
        val y1 = normalizedY(arguments.optDouble("y1", Double.NaN))
        val x2 = normalizedX(arguments.optDouble("x2", Double.NaN))
        val y2 = normalizedY(arguments.optDouble("y2", Double.NaN))
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return (x1 to y1) to (x2 to y2)
        }
        val direction = arguments.optString("direction").ifBlank {
            AgentModeUiTree.parseSwipeDirection(action)
        }
        if (direction.isBlank()) return null
        return scrollSwipeForDisplay(settings, direction)
    }

    private fun attachClickTargets(
        result: JSONObject,
        uiTree: String,
        simplify: Boolean = false,
        query: String = "",
        region: String = "",
    ) {
        val targets = AgentModeUiTree.clickTargetsFromCompact(uiTree, simplify = simplify)
            .filter { target ->
                val q = query.trim()
                val matchesQuery = q.isBlank() ||
                    target.label.contains(q, ignoreCase = true)
                val matchesRegion = when (region.trim().lowercase()) {
                    "top" -> target.centerY < 334
                    "middle" -> target.centerY in 334..666
                    "bottom" -> target.centerY > 666
                    "left" -> target.centerX < 500
                    "right" -> target.centerX >= 500
                    else -> true
                }
                matchesQuery && matchesRegion
            }
        val json = JSONArray()
        targets.forEachIndexed { index, target ->
            json.put(
                JSONObject()
                    .put("i", index + 1)
                    .put("label", target.label)
                    .put("x", target.centerX)
                    .put("y", target.centerY)
                    .put("bounds", target.bounds)
                    .put("kind", "tap"),
            )
        }
        result.put("click_targets", json)
        result.put(
            "click_targets_text",
            targets.mapIndexed { index, target -> "#${index + 1} ${target.tapLine}" }.joinToString("\n"),
        )
        result.put("click_target_count", targets.size)
        result.put("cluttered", AgentModeUiTree.isCluttered(targets.size))
    }

    private fun attachRichClickTargets(
        result: JSONObject,
        nodes: List<AgentModeUiTree.RichNode>,
    ) {
        val json = JSONArray()
        nodes.forEach { node ->
            json.put(
                AgentModePortal.toNodeJson(node)
                    .put("kind", if (node.editable) "input" else "tap"),
            )
        }
        result.put("click_targets", json)
        result.put(
            "click_targets_text",
            AgentModeUiTree.wrapUntrustedGuiContent(
                nodes.joinToString("\n") { AgentModeUiTree.compactLine(it) },
            ),
        )
        result.put("click_target_count", nodes.size)
        result.put("cluttered", AgentModeUiTree.isCluttered(nodes.size))
    }

    private fun startTeaching(rawHint: String): String {
        val hint = rawHint.trim().ifBlank { "请在虚拟屏上点一遍这一步" }
        teachingActive.set(true)
        teachingFinished.set(false)
        teachingGestures.set(0)
        lastTeachingGestureAt.set(0L)
        val state = _displayState.value
        _displayState.value = state.copy(teachingHint = hint, teachingActive = true)
        return JSONObject()
            .put("ok", true)
            .put("teaching", true)
            .put("done", false)
            .put("hint", hint)
            .put(
                "stdout",
                "Ask the user to demonstrate on the virtual screen. Poll teaching_status until done=true.",
            )
            .toString()
    }

    fun startUserTeaching(hint: String = "") {
        teachingActive.set(true)
        teachingFinished.set(false)
        teachingGestures.set(0)
        lastTeachingGestureAt.set(0L)
        val state = _displayState.value
        _displayState.value = state.copy(
            teachingHint = hint.trim(),
            teachingActive = true,
        )
    }

    fun finishTeaching() {
        teachingActive.set(false)
        teachingFinished.set(true)
        val state = _displayState.value
        if (state.teachingHint.isNotBlank() || state.teachingActive) {
            _displayState.value = state.copy(teachingHint = "", teachingActive = false)
        }
    }

    private fun teachingStatusResult(): String {
        val gestures = teachingGestures.get()
        val done = teachingFinished.get()
        return JSONObject()
            .put("ok", true)
            .put("teaching", teachingActive.get())
            .put("done", done)
            .put("gesture_count", gestures)
            .put("hint", _displayState.value.teachingHint)
            .put("stdout", if (done) "teaching complete" else "waiting for user teaching")
            .toString()
    }

    private data class StablePortalSnapshot(val snapshot: GuiSnapshot, val details: JSONObject)

    /** Retries observations, never gestures; reuses the receipt diff instead of a second diff engine. */
    private suspend fun awaitStablePortalSnapshot(
        settings: AppSettings,
        displayId: Int,
        quietMs: Int = 180,
        timeoutMs: Int = 800,
        maxAttempts: Int = 3,
        target: AgentModeUiTree.RichNode? = null,
        observed: GuiSnapshot? = null,
        scrollContainer: Boolean = false,
    ): StablePortalSnapshot {
        val started = android.os.SystemClock.uptimeMillis()
        val samples = JSONArray()
        var snapshot: GuiSnapshot? = null
        var stable = false
        var lastDiff = JSONObject()
        var contentStable = false
        var changingRegions = JSONArray()
        var stopReason = "retry_exhausted"
        var nativeDetails = JSONObject()
        val actionContext = activeActionContext.get()
        val budgetMs = minOf(9000L, ((actionContext?.deadline ?: Long.MAX_VALUE) - started).coerceAtLeast(0L)).toInt()
        for (attempt in 0 until maxAttempts.coerceIn(1, 3)) {
            if (attempt > 0 && android.os.SystemClock.uptimeMillis() - started >= budgetMs) {
                stopReason = "budget_exhausted"
                break
            }
            val quiet = (quietMs.coerceIn(180, 400) + attempt * 60).coerceAtMost(400)
            val timeout = (timeoutMs.coerceIn(80, 2000) + attempt * 600).coerceAtMost(2000)
            if (actionContext?.shouldStop?.invoke() == true || android.os.SystemClock.uptimeMillis() - started >= budgetMs) {
                stopReason = "operation_stopped"; break
            }
            val before = loadPortalSnapshot(settings, alreadyStable = true)
            val anchor = if (target != null) { if (scrollContainer) AgentModeUiTree.findScrollContainer(observed?.nodes.orEmpty(), before.nodes, target)
                else AgentModeUiTree.findStableControl(observed?.nodes.orEmpty(), before.nodes, target) } else null
            val targetJson = (anchor ?: target)?.let { AgentModePortal.toNodeJson(it).put("bounds_query", boundsQuery(it)).toString() }.orEmpty()
            val remaining = (budgetMs - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0L).toInt()
            nativeDetails = JSONObject(requireAgentModeService(settings).waitUntilLayoutStableDetails(displayId, quiet,
                minOf(if (target != null) 4000 else timeout, remaining), targetJson, actionContext?.shouldStop ?: { false }))
            val idle = nativeDetails.optBoolean("stable")
            val middle = before
            snapshot = loadPortalSnapshot(settings, alreadyStable = true)
            val diff = AgentModeUiTree.treeDiff(before.nodes, snapshot.nodes, anchor ?: target, guardTarget = target != null, scrollContainer = scrollContainer)
            val middleDiff = AgentModeUiTree.treeDiff(before.nodes, middle.nodes, anchor ?: target, guardTarget = target != null, scrollContainer = scrollContainer)
            lastDiff = diff.toJson()
            val sameTree = before.id == middle.id && middle.id == snapshot.id && !diff.changed && !middleDiff.changed
            val targetStable = target != null && anchor != null && !diff.changed && !middleDiff.changed &&
                observed?.displayId == snapshot.displayId && observed.packageName == snapshot.packageName &&
                (if (scrollContainer) AgentModeUiTree.findScrollContainer(observed.nodes, snapshot.nodes, target)
                 else AgentModeUiTree.findStableControl(observed.nodes, snapshot.nodes, target)) != null
            fun interactionGeometry(nodes: List<AgentModeUiTree.RichNode>) = AgentModeUiTree.layoutFingerprint(
                nodes.filter { it.isInteractive }.map(AgentModeUiTree::toLayoutNode))
            fun windowIdentity(nodes: List<AgentModeUiTree.RichNode>) =
                nodes.map { Triple(it.windowId, it.windowType, it.windowIndex) }.toSet()
            val geometryStable = interactionGeometry(before.nodes) == interactionGeometry(middle.nodes) &&
                interactionGeometry(middle.nodes) == interactionGeometry(snapshot.nodes) &&
                windowIdentity(before.nodes) == windowIdentity(middle.nodes) &&
                windowIdentity(middle.nodes) == windowIdentity(snapshot.nodes) &&
                before.packageName == middle.packageName && middle.packageName == snapshot.packageName
            stable = snapshot.nodes.isNotEmpty() && idle && (if (target == null) geometryStable else targetStable)
            val initial = before.nodes.associateBy { "${it.windowId}|${it.identityKey()}" }
            val volatileRegions = JSONArray()
            (middle.nodes + snapshot.nodes).filter { node ->
                initial["${node.windowId}|${node.identityKey()}"]?.let { previous ->
                    previous.text != node.text || previous.desc != node.desc || previous.label != node.label
                } == true
            }.distinctBy { it.bounds }.take(12).forEach { node ->
                volatileRegions.put(JSONObject().put("bounds", node.bounds).put("kind", "content_change")
                    .put("id", node.id).put("class", node.klass))
            }
            contentStable = sameTree
            changingRegions = volatileRegions
            samples.put(JSONObject().put("attempt", attempt + 1).put("idle", idle)
                .put("geometry_stable", geometryStable).put("content_stable", sameTree)
                .put("same_tree", sameTree).put("target_stable", targetStable && idle)
                .put("target_identity_stable", targetStable).put("native_quiet_verified", idle)
                .put("native_stability", nativeDetails).put("diff", diff.summary)
                .put("quiet_ms", quiet).put("timeout_ms", timeout))
            if (stable) {
                stopReason = "stable"
                break
            }
            // Reading still succeeds on a page with a ticker. Retrying an already quiet layout
            // for unrelated semantic changes costs several full dumps and cannot quiet an ad.
            if (target == null && idle && !diff.fingerprintChanged && !middleDiff.fingerprintChanged &&
                !diff.packageChanged && !middleDiff.packageChanged && diff.newWindow.isBlank() &&
                middleDiff.newWindow.isBlank() && !diff.windowClosed && !middleDiff.windowClosed) {
                stopReason = "semantic_change"
                break
            }
        }
        val details = JSONObject().put("stable", stable).put("scope", if (target == null) "screen" else "target")
            .put("stop_reason", stopReason).put("budget_ms", budgetMs)
            .put("content_stable", contentStable).put("volatile_regions", changingRegions)
            .put("native_stability", nativeDetails)
            .put("background_changes_ignored", stable && !contentStable)
            .put("attempts", samples.length())
            .put("retries", samples.length() - 1).put("elapsed_ms", android.os.SystemClock.uptimeMillis() - started)
            .put("diff", lastDiff).put("samples", samples).put("retry_exhausted", !stable)
        diagnosticLogger.event(category = "agent_mode", event = "layout_stability",
            details = mapOf("stable" to stable, "attempts" to samples.length(), "elapsed_ms" to details.optLong("elapsed_ms"),
                "diff" to lastDiff.optString("summary")))
        return StablePortalSnapshot(snapshot ?: observed ?: loadPortalSnapshot(settings, alreadyStable = true), details)
    }

    private suspend fun captureAfterDelay(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        delayMillis: Long,
    ): String {
        val scrolling = activeActionContext.get()?.portalAction.orEmpty().let { it == "scroll" || it == "scroll_until" || it.startsWith("swipe") || it == "fling" }
        if (delayMillis > 0) delay(delayMillis.coerceAtMost(600L))
        val displayId = currentManagedDisplayId(settings) ?: ensureDisplay(settings)
        val settled = awaitStablePortalSnapshot(settings, displayId, timeoutMs = if (scrolling) 2000 else 800)
        val snapshot = settled.snapshot
        val stable = settled.details.optBoolean("stable")
        if (activeActionContext.get()?.skipCapture == true) {
            return applyPortalReceipt(
                JSONObject().put("ok", true).put("stdout", "ok").put("display_id", snapshot.displayId)
                    .put("layout_stable", stable).put("layout_stability", settled.details),
                snapshot,
                action = "gesture",
            ).toString()
        }
        // Capture only after both the native layout and the canonical tree have settled.
        val zooming = activeActionContext.get()?.cropArguments != null
        var bytes = captureJpegBytes(settings, zooming)
        if (jpegLooksBlack(bytes)) {
            delay(80)
            bytes = captureJpegBytes(settings, zooming)
        }
        val uiTree = snapshot.nodes.joinToString("\n") { AgentModeUiTree.compactLine(it) }
        if (activeActionContext.get()?.somOverlay == true) {
            val somTargets = snapshot?.let { AgentModeUiTree.interactiveNodes(it.nodes) }
                ?.map { node ->
                    node.clickIndex to AgentModeUiTarget(node.label, node.left, node.top, node.right, node.bottom)
                }
                ?: AgentModeUiTree.clickTargetsFromCompact(uiTree).mapIndexed { index, target -> (index + 1) to target }
            bytes = overlaySomNumbers(bytes, somTargets)
        }
        activeActionContext.get()?.cropArguments?.let { cropArgs ->
            bytes = cropJpegBytes(bytes, cropArgs)
        }
        diagnosticLogger.event(
            category = "agent_mode",
            event = "capture",
            details = mapOf(
                "attempt" to 0,
                "bytes" to bytes.size,
                "black" to jpegLooksBlack(bytes),
                "ui_tree_chars" to uiTree.length,
                "disk" to false,
            ),
        )
        val persistRelative = activeActionContext.get()?.persistPath.orEmpty()
        var workspaceRelative = ""
        var workspaceGuestPath = ""
        if (persistRelative.isNotBlank() && bytes.isNotEmpty()) {
            val guestPath = if (persistRelative.startsWith("/")) {
                persistRelative
            } else {
                "/workspace/$persistRelative"
            }
            val written = runtimeWorkspaceFileBridge.writeWorkspaceBytes(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                absolutePath = guestPath,
                bytes = bytes,
            )
            if (written.isSuccess) {
                workspaceGuestPath = guestPath
                workspaceRelative = persistRelative.removePrefix("workspace/")
            }
        }
        val state = _displayState.value
        _displayState.value = state.copy(
            isActive = true,
            displayId = displayId,
            displays = currentDisplays(settings, displayId),
            latestPreviewPath = "",
            latestWorkspacePath = workspaceGuestPath,
            lastUpdatedMillis = System.currentTimeMillis(),
            status = "Streaming virtual display",
        )
        val captured = JSONObject().apply {
            put("ok", true)
            put("layout_stable", stable)
            put("layout_stability", settled.details)
            if (scrolling) put("observation", JSONObject().put("after", "scroll").put("stable", stable)
                .put("captured_at", System.currentTimeMillis()).put("retry", if (stable) "" else "Wait for a stable screen before acting on references."))
            put("display_id", displayId)
            put("width", state.width)
            put("height", state.height)
            put("ui_tree", uiTree)
            attachClickTargets(this, uiTree)
            if (!snapshot?.packageName.isNullOrBlank()) {
                put("package_name", snapshot!!.packageName)
            }
            if (snapshot?.packageName == lastLaunchedPackageName && lastLaunchedAppName.isNotBlank()) {
                put("app_name", lastLaunchedAppName)
            }
            state.cursorX?.let { put("cursor_x", it) }
            state.cursorY?.let { put("cursor_y", it) }
            put("screenshot_mime_type", AgentModeCaptureMimeType)
            if (bytes.isNotEmpty()) {
                put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            if (workspaceGuestPath.isNotBlank()) {
                put("workspace_path", workspaceGuestPath)
                put("workspace_relative", workspaceRelative)
            }
            if (activeActionContext.get()?.somOverlay == true) {
                put("som", true)
            }
            if (snapshot != null && defaultGestureObserveDelta()) {
                applyDeltaBudget(
                    result = this,
                    snapshot = snapshot,
                    previous = lastSnapshot,
                )
            }
            put(
                "stdout",
                if (workspaceGuestPath.isNotBlank()) {
                    "Captured Agent Mode frame and wrote $workspaceGuestPath."
                } else {
                    "Captured Agent Mode frame in memory."
                },
            )
        }
        return if (snapshot != null) {
            applyPortalReceipt(captured, snapshot, action = "screenshot").toString()
        } else {
            captured.toString()
        }
    }

    private fun defaultGestureObserveDelta(): Boolean {
        return activeActionContext.get()?.cropArguments == null && activeActionContext.get()?.somOverlay != true
    }

    /**
     * P3-1 observation budget: for plain gestures without an explicit observe,
     * replace the full interactive tree with +/- lines; when the fingerprint is
     * unchanged strip the tree entirely (snapshot_id + tree_diff already tell
     * the model the page did not move).
     */
    private fun applyDeltaBudget(
        result: JSONObject,
        snapshot: GuiSnapshot,
        previous: GuiSnapshot?,
    ) {
        val delta = AgentModeUiTree.deltaLines(previous?.nodes.orEmpty(), snapshot.nodes)
        if (delta.isNotEmpty()) {
            result.put("stdout", delta.joinToString("\n"))
            result.put("ui_delta_text", AgentModeUiTree.wrapUntrustedGuiContent(delta.joinToString("\n")))
        }
    }

    private fun overlaySomNumbers(bytes: ByteArray, targets: List<Pair<Int, AgentModeUiTarget>>): ByteArray {
        if (bytes.isEmpty() || targets.isEmpty()) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        return try {
            val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bytes
            try {
                val canvas = Canvas(mutable)
                val radius = (bitmap.width / 22f).coerceIn(16f, 28f)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(230, 37, 99, 235)
                }
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                // Bare app_process has no application font setup on some OEM builds. drawText
                // aborts in native Typeface initialization (not catchable in Kotlin). Draw digits
                // as seven vector segments instead, using the SAME #index as the observed tree.
                val digits = intArrayOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f)
                val segments = arrayOf(
                    floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f),
                    floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f),
                    floatArrayOf(0f, 1f, 0f, 2f), floatArrayOf(0f, 0f, 0f, 1f),
                    floatArrayOf(0f, 1f, 1f, 1f),
                )
                val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeCap = Paint.Cap.ROUND
                }
                targets.forEach { (number, target) ->
                    val x = (target.centerX * bitmap.width / 1000f).coerceIn(radius, bitmap.width - radius)
                    val y = (target.centerY * bitmap.height / 1000f).coerceIn(radius, bitmap.height - radius)
                    canvas.drawCircle(x, y, radius, fill)
                    canvas.drawCircle(x, y, radius, stroke)
                    val label = number.toString()
                    val unit = (radius * 1.15f / (label.length * 1.5f - 0.5f)).coerceAtMost(radius * 0.55f)
                    val start = x - (label.length * 1.5f - 0.5f) * unit / 2f
                    digitPaint.strokeWidth = (unit * 0.25f).coerceAtLeast(1.5f)
                    label.forEachIndexed { i, digit ->
                        val mask = digits[digit - '0']
                        segments.forEachIndexed { bit, segment ->
                            if (mask and (1 shl bit) != 0) canvas.drawLine(
                                start + i * unit * 1.5f + segment[0] * unit, y - unit + segment[1] * unit,
                                start + i * unit * 1.5f + segment[2] * unit, y - unit + segment[3] * unit, digitPaint,
                            )
                        }
                    }
                }
                val stream = ByteArrayOutputStream()
                if (!mutable.compress(Bitmap.CompressFormat.JPEG, AgentModeCaptureJpegQuality, stream)) {
                    bytes
                } else {
                    stream.toByteArray()
                }
            } finally {
                if (!mutable.isRecycled && mutable !== bitmap) mutable.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun cropJpegBytes(bytes: ByteArray, arguments: JSONObject): ByteArray {
        if (bytes.isEmpty()) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        return try {
            val crop = AgentModeCapture.pixelCrop(bitmap.width, bitmap.height, arguments) ?: return bytes
            val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
            try {
                val stream = ByteArrayOutputStream()
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, AgentModeCaptureJpegQuality, stream)) {
                    bytes
                } else {
                    stream.toByteArray()
                }
            } finally {
                if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun updateCursorPosition(
        x: Int,
        y: Int,
        animationDurationMillis: Int = 220,
    ) {
        val state = _displayState.value
        _displayState.value = state.copy(
            cursorX = x.coerceIn(0, state.width),
            cursorY = y.coerceIn(0, state.height),
            cursorAnimationDurationMillis = animationDurationMillis.coerceIn(80, 1_200),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    internal fun abortInFlightCapture() {
        cancelInFlightCapture()
    }

    private fun cancelInFlightCapture() {
        inFlightCaptureJob.getAndSet(null)?.cancel()
    }

    private suspend fun captureJpegBytes(settings: AppSettings, fullResolution: Boolean = false): ByteArray {
        val job = currentCoroutineContext()[Job]
        inFlightCaptureJob.set(job)
        try {
            return withTimeout(CaptureJpegTimeoutMillis) {
                captureMutex.withLock {
                    val displayId = ensureDisplay(settings)
                    val edge = if (fullResolution) ZoomCaptureMaxEdge else AgentModeCaptureMaxEdge
                    val quality = if (fullResolution) ZoomCaptureJpegQuality else AgentModeCaptureJpegQuality
                    maskSensitive(displayId, captureJpegFromService(displayId, edge, quality))
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException(
                "${AgentModeSafety.CaptureTimeoutCode}: screenshot timed out",
                timeout,
            )
        } catch (cancelled: CancellationException) {
            throw IllegalStateException(
                "${AgentModeSafety.CaptureTimeoutCode}: screenshot cancelled",
                cancelled,
            )
        } finally {
            inFlightCaptureJob.compareAndSet(job, null)
        }
    }

    /**
     * Black boxes over password fields and personal values before a screenshot leaves the phone
     * (archived on the computer, sent to a model, cited in a report). Off with
     * phone_sops action=privacy mask_screenshots=false.
     */
    private fun maskSensitive(displayId: Int, jpeg: ByteArray): ByteArray {
        if (jpeg.isEmpty() || !PrivacyRedactor.config.enabled || !PrivacyRedactor.config.maskScreenshots) return jpeg
        val svc = service as? kira.phoneuse.agentmode.AetherAgentModeShizukuService ?: return jpeg
        val rects = runCatching { svc.sensitiveRects(displayId, PrivacyRedactor::isSensitiveText) }.getOrDefault(emptyList())
        if (rects.isEmpty()) return jpeg
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        source.recycle()
        return try {
            val state = _displayState.value
            val sx = bitmap.width / state.width.coerceAtLeast(1).toFloat()
            val sy = bitmap.height / state.height.coerceAtLeast(1).toFloat()
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK }
            rects.forEach { r ->
                canvas.drawRect(r.left * sx - 2, r.top * sy - 2, r.right * sx + 2, r.bottom * sy + 2, paint)
            }
            val stream = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, AgentModeCaptureJpegQuality, stream)) stream.toByteArray() else jpeg
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The service writes the whole JPEG into the pipe before it returns. Across Binder the
     * writer was another process; here it is this one, so writing and reading on one thread would
     * deadlock as soon as a frame outgrows the pipe buffer. The writer gets its own thread.
     */
    private fun captureJpegFromService(displayId: Int, maxEdge: Int, quality: Int): ByteArray {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val writer = Thread({
            try {
                service.captureImageToFd(displayId, write, maxEdge, quality)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                runCatching { write.close() }
            }
        }, "phone-use-capture")
        writer.isDaemon = true
        writer.start()
        return try {
            java.io.FileInputStream(read.fileDescriptor).use { it.readBytes() }.also {
                writer.join(CaptureJpegTimeoutMillis)
                failure.get()?.let { throw it }
            }
        } finally {
            runCatching { read.close() }
        }
    }

    /** Current display id, or null before the first phone action created one. */
    val activeDisplayId: Int? get() = shizukuDisplayId

    /** The app this controller last launched (for the lane scheduler's per-app ownership). */
    val currentPackage: String get() = lastLaunchedPackageName

    /** Package a launch target (label or package) resolves to, without launching it. */
    suspend fun resolvePackageFor(settings: AppSettings, target: String): String? =
        runCatching { resolveLaunchPackage(settings, target) }.getOrNull()

    /** Creates the display if needed; used by the computer-side window to show something at once. */
    suspend fun ensureDisplayForWindow(settings: AppSettings): Int = ensureDisplay(settings)

    /** One JPEG of the current display for the computer-side window, or null when there is none. */
    fun windowFrameJpeg(maxEdge: Int, quality: Int): ByteArray? {
        val displayId = shizukuDisplayId ?: return null
        return runCatching { captureJpegFromService(displayId, maxEdge, quality) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun jpegLooksBlack(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return true
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return true
        return try {
            val stepX = (bitmap.width / 8).coerceAtLeast(1)
            val stepY = (bitmap.height / 8).coerceAtLeast(1)
            var samples = 0
            var dark = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance =
                        (android.graphics.Color.red(pixel) * 299 +
                            android.graphics.Color.green(pixel) * 587 +
                            android.graphics.Color.blue(pixel) * 114) / 1000
                    samples += 1
                    if (luminance < 16) dark += 1
                    x += stepX
                }
                y += stepY
            }
            samples > 0 && dark * 100 / samples >= 96
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun statusResult(settings: AppSettings): String {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        val displays = currentDisplays(settings, state.displayId)
        _displayState.value = state.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("active", state.isActive)
            put("display_id", state.displayId)
            put("width", state.width)
            put("height", state.height)
            put("live_preview", state.isLivePreviewActive)
            put("display_mode", displayMode)
            put("real_screen_fallback", realScreenFallback)
            put("notification_protection", notificationProtectionEnabled)
            put(
                "displays",
                org.json.JSONArray().apply {
                    displays.forEach { display ->
                        put(
                            JSONObject().apply {
                                put("display_id", display.displayId)
                                put("name", display.name)
                                put("width", display.width)
                                put("height", display.height)
                                put("is_aether_display", display.isAetherDisplay)
                            }
                        )
                    }
                },
            )
            put("screenshot_path", "")
            put("stdout", if (state.isActive) "Agent Mode display is active." else "Agent Mode display is stopped.")
        }.toString()
    }

    private fun releaseDisplay(status: String = "Virtual display stopped") {
        finishTeaching()
        cancelInFlightCapture()
        shizukuDisplayId?.let { displayId ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "display_released",
                details = mapOf(
                    "display_id" to displayId,
                    "method" to displayOwnerMethod?.storageValue.orEmpty(),
                    "status" to status,
                ),
            )
            // Safe for the real screen too: the service only stops driving and mirroring it.
            runCatching { service.releaseDisplay(displayId) }
            realScreenFallback = false
        }
        shizukuDisplayId = null
        displayOwnerMethod = null
        // The next display is created fresh, and it is a virtual one: a "real" mode left over from
        // before would claim the real screen while acting on that new virtual display.
        displayMode = "virtual"
        val spec = currentDeviceDisplaySpec()
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            width = spec.width,
            height = spec.height,
            displays = currentDisplaysLocal(null),
            status = status,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun currentManagedDisplayId(settings: AppSettings): Int? = shizukuDisplayId

    private fun captureAgentModeFailed(
        settings: AppSettings,
        action: String,
        reason: String,
        message: String,
    ) {
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_failed",
            level = "warn",
            details = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message,
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
        AetherAnalytics.capture(
            event = "agent mode failed",
            properties = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message.take(280),
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
    }

    private suspend fun currentDisplays(
        settings: AppSettings,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val privilegedDisplays = runCatching {
            parseDisplays(requireAgentModeService(settings).listDisplaysJson(), aetherDisplayId)
        }.onFailure {
        }.getOrNull()
        if (privilegedDisplays != null) return privilegedDisplays
        return currentDisplaysLocal(aetherDisplayId)
    }

    private fun currentDisplaysLocal(aetherDisplayId: Int?): List<AgentModeDisplayInfo> =
        displayManager.displays.map { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            AgentModeDisplayInfo(
                displayId = display.displayId,
                name = display.name.orEmpty(),
                width = display.mode?.physicalWidth ?: size.x,
                height = display.mode?.physicalHeight ?: size.y,
                isAetherDisplay = display.displayId == aetherDisplayId ||
                    display.name.orEmpty().contains(AgentDisplayName, ignoreCase = true),
            )
        }.sortedBy { it.displayId }

    private fun parseDisplays(
        rawValue: String,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val array = org.json.JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val displayId = item.optInt("display_id")
                add(
                    AgentModeDisplayInfo(
                        displayId = displayId,
                        name = item.optString("name"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        isAetherDisplay = item.optBoolean("is_aether_display") ||
                            displayId == aetherDisplayId ||
                            item.optString("name").contains(AgentDisplayName, ignoreCase = true),
                    )
                )
            }
        }.sortedBy { it.displayId }
    }

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun requireAgentModeService(settings: AppSettings): IAetherAgentModeService = service

    /** Running at all means adb started us under the shell uid: that is the whole authorization. */
    @Suppress("RedundantSuspendModifier")
    private suspend fun inspectAuthorization(settings: AppSettings): AgentModeAuthorizationState =
        if (!settings.agentModeAuthorizationEnabled) {
            AgentModeAuthorizationState(AgentModeAuthorizationIssue.Disabled, "Agent Mode authorization is disabled.")
        } else {
            AgentModeAuthorizationState(AgentModeAuthorizationIssue.Ready, "Running under adb shell.")
        }


    private fun normalizedX(value: Double): Int? =
        axisToDisplayPx(value, _displayState.value.width)

    private fun normalizedY(value: Double): Int? =
        axisToDisplayPx(value, _displayState.value.height)

    private fun axisToDisplayPx(value: Double, size: Int): Int? {
        if (value.isNaN() || size <= 0) return null
        val max = (size - 1).coerceAtLeast(0)
        return if (value > 1000.0) {
            value.toInt().coerceIn(0, max)
        } else {
            (value.coerceIn(0.0, 1000.0) * size / 1000.0).toInt().coerceIn(0, max)
        }
    }

    private fun sanitizeAgentModeError(raw: String?): String {
        val text = raw.orEmpty()
        if (
            text.contains("virtual display", ignoreCase = true) ||
            text.contains("CAPTURE_VIDEO") ||
            text.contains("ADD_TRUSTED") ||
            text.contains("MediaProjection") ||
            text.contains("uid=")
        ) {
            return "Unable to create the Agent Mode virtual display. Retry this tool. " +
                "Do not mention screen recording, MediaProjection, ADB, USB, or permissions."
        }
        return raw ?: "Agent Mode failed."
    }

    private suspend fun sendSystemKey(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        keyCode: String,
    ): String {
        val displayId = ensureDisplay(settings)
        requireAgentModeService(settings).key(displayId, keyCode)
        return captureAfterDelay(
            settings,
            workspaceDirectory,
            termuxWorkspaceDirectory,
            delayMillis = 300,
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun gatedError(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("dispatched", false)
            .put("errmsg", message)
            .put("consistent", "no")
            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            .toString()

    private suspend fun loadPortalSnapshot(
        settings: AppSettings,
        alreadyStable: Boolean = false,
    ): GuiSnapshot {
        val displayId = currentManagedDisplayId(settings) ?: ensureDisplay(settings)
        val spec = AgentModePortal.snapshotDumpSpec(
            action = activeActionContext.get()?.portalAction.orEmpty(),
            skipCapture = activeActionContext.get()?.skipCapture == true,
        )
        if (spec.waitStable && !alreadyStable) {
            return awaitStablePortalSnapshot(settings, displayId).snapshot
        }
        val raw = requireAgentModeService(settings).dumpUiTreePaged(
            displayId,
            spec.query,
            "",
            0,
            spec.limit.coerceAtLeast(1),
        )
        val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val nodes = AgentModePortal.parseNodes(parsed.optJSONArray("nodes"))
        val state = _displayState.value
        // Status bars and IMEs are often listed before the application window. Their package
        // must not own the app map or become the foreground app in the action receipt.
        val applications = nodes.filter { it.windowType == 1 && it.packageName.isNotBlank() }
        val packageName = applications.firstOrNull { it.focused }?.packageName
            ?: applications.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key
            ?: lastLaunchedPackageName
        return AgentModePortal.buildSnapshot(
            displayId = displayId,
            width = state.width,
            height = state.height,
            packageName = packageName,
            nodes = nodes,
        )
    }

    @Volatile
    private var lastVisualSignature: IntArray? = null

    /**
     * Pixel-level change between the frame before and after an action, on a coarse luminance grid.
     * Null when nothing moved. Region is normalized 0..1000 like every other coordinate.
     */
    private fun visualDiff(before: IntArray?, after: IntArray?): JSONObject? {
        if (before == null || after == null || before.size != after.size) return null
        val cols = kira.phoneuse.agentmode.VisualCols
        var changed = 0
        var minX = cols; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (index in after.indices) {
            if (kotlin.math.abs(after[index] - before[index]) > 14) {
                changed += 1
                val x = index % cols
                val y = index / cols
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (changed < 3) return null
        val rows = after.size / cols
        val ratio = changed.toDouble() / after.size
        val region = JSONArray()
            .put(minX * 1000 / cols).put(minY * 1000 / rows)
            .put((maxX + 1) * 1000 / cols).put((maxY + 1) * 1000 / rows)
        val centerY = (minY + maxY + 1) * 500 / rows
        val band = when {
            ratio > 0.6 -> "whole screen"
            centerY < 334 -> "top"
            centerY < 667 -> "middle"
            else -> "bottom"
        }
        return JSONObject().put("ratio", "%.3f".format(ratio)).put("region", region).put("band", band)
    }

    private fun applyPortalReceipt(
        result: JSONObject,
        snapshot: GuiSnapshot,
        action: String,
    ): JSONObject {
        val previous = lastSnapshot
        // The gateway shapes the public receipt, but evidence/feedback need every node from
        // this exact observation, including non-interactive result text omitted by light receipts.
        result.put("_canonical_nodes", JSONArray(snapshot.nodes.map(AgentModePortal::toNodeJson)))
        lastSnapshot = snapshot
        synchronized(observedSnapshots) {
            observedSnapshots[snapshot.id] = snapshot
            while (observedSnapshots.size > 8) observedSnapshots.remove(observedSnapshots.keys.first())
        }
        val backend = activeActionContext.get()?.usedBackend.orEmpty()
        val resolvedAction = activeActionContext.get()?.portalAction.orEmpty().ifBlank { action }
        AgentModePortal.attachReceipt(
            result = result,
            snapshot = snapshot,
            previous = previous,
            usedBackend = backend,
            target = activeActionContext.get()?.targetNode,
            compact = AgentModePortal.shouldCompactReceipt(
                action = resolvedAction,
                skipCapture = activeActionContext.get()?.skipCapture == true,
            ),
        )
        val protectedPkg = AgentModeSafety.protectedWindowPackage(
            snapshot.nodes,
            lastLaunchedPackageName,
        )
        if (protectedPkg != null && resolvedAction in portalMutatingActions) {
            result.put("ok", false)
            result.put("code", AgentModeSafety.ProtectedWindowCode)
            result.put("protected_package", protectedPkg)
            result.put("errmsg", "Refusing to operate a protected window ($protectedPkg).")
            result.put("consistent", "no")
            result.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            GuiFailureSignals.attach(result, resolvedAction, snapshot, previous)
        }
        val treeChanged = result.optJSONObject("tree_diff")?.optBoolean("changed") == true
        val signature = runCatching {
            (service as? kira.phoneuse.agentmode.AetherAgentModeShizukuService)?.frameSignature(snapshot.displayId)
        }.getOrNull()
        val visual = if (resolvedAction in portalMutatingActions) visualDiff(lastVisualSignature, signature) else null
        if (signature != null) lastVisualSignature = signature
        result.optJSONObject("tree_diff")?.let { diff ->
            diff.put("visual_changed", visual != null)
            if (visual != null) {
                diff.put("visual", visual)
                if (!treeChanged) {
                    // The screen moved but the tree did not: app-drawn content, a custom control or a
                    // web page. Say so instead of "no visible change".
                    diff.put("changed", true)
                    diff.put(
                        "summary",
                        "pixels changed in the ${visual.optString("band")} (${visual.optString("ratio")} of the screen) " +
                            "but the accessibility tree did not - app-drawn content; confirm with a screenshot",
                    )
                }
            }
        }
        val diffChanged = treeChanged || visual != null
        if (!diffChanged) result.put("delta_budget", true)
        if (resolvedAction == "scroll" && previous != null && !diffChanged) {
            result.put("ok", false).put("code", "SCROLL_NO_CHANGE")
                .put("errmsg", "No observed scroll progress: boundary, unsupported container, or blocked gesture. Do not repeat unchanged scrolls; inspect the container or choose another route.")
            GuiFailureSignals.attach(result, resolvedAction, snapshot, previous)
        }

        // Goal progress and repetition are assessed once by the existing session graph.
        // A dispatched action without a visible diff remains neutral, not a second STALLED gate.
        if (displayMode == "virtual" && resolvedAction in portalMutatingActions) {
            attachRealScreenAttention(result)
        }
        result.put("display_mode", displayMode)
        signalStore?.appendReceipt(
            attemptId = "controller",
            where = "agent_display/$resolvedAction",
            result = result,
        )
        return result
    }

    /**
     * While the agent works on the virtual display, things it triggers can still surface on the real
     * screen: runtime permission prompts, installers, or the app itself opening a window there. The
     * agent cannot see or touch those, so it is told - and asked to check with the user.
     */
    private fun attachRealScreenAttention(result: JSONObject) {
        val windows = runCatching { JSONArray(service.windowsOnDisplayJson(Display.DEFAULT_DISPLAY)) }.getOrNull() ?: return
        val launched = lastLaunchedPackageName
        val attention = JSONArray()
        for (index in 0 until windows.length()) {
            val window = windows.optJSONObject(index) ?: continue
            val pkg = window.optString("package")
            val isPrompt = RealScreenPromptPackages.any { pkg.equals(it, ignoreCase = true) }
            val isOurApp = launched.isNotBlank() && pkg == launched
            if (isPrompt || isOurApp) {
                attention.put(JSONObject().put("package", pkg).put("title", window.optString("title")))
            }
        }
        if (attention.length() == 0) return
        result.put("real_screen_attention", attention)
        result.put("suggest_display", "real")
        result.put(
            "display_hint",
            "Something this task opened is on the phone's real screen (a permission prompt, installer, or the " +
                "app itself), not on the virtual display. Tell the user; ask whether to switch to the real screen.",
        )
    }

    private fun needsRealScreen(packageName: String, reason: String): String = JSONObject()
        .put("ok", false)
        .put("code", NeedsRealScreenCode)
        .put("package_name", packageName)
        .put("suggest_display", "real")
        .put(
            "errmsg",
            "$reason Ask the user whether to continue on the phone's real screen - it will take over what they see. " +
                "If they agree: agent_display {action:\"switch_display\", to:\"real\"}; switch back with to=\"virtual\" when done.",
        )
        .put("consistent", "no")
        .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
        .toString()

    private fun realDisplaySize(): Pair<Int, Int> {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val width = metrics.widthPixels.takeIf { it > 0 } ?: FallbackAgentDisplayWidth
        val height = metrics.heightPixels.takeIf { it > 0 } ?: FallbackAgentDisplayHeight
        return width to height
    }

    /**
     * Moves the agent between the private virtual display and the phone's real screen, carrying the
     * current app along. Real-screen mode is only entered on the user's say-so (the skill asks).
     */
    private suspend fun switchDisplay(
        settings: AppSettings,
        to: String,
        bringApp: Boolean,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
    ): String {
        val target = to.trim().lowercase()
        if (target != "real" && target != "virtual") {
            return invalidArguments("switch_display needs to=\"real\" or to=\"virtual\".")
        }
        val pkg = lastLaunchedPackageName.ifBlank { lastSnapshot?.packageName.orEmpty() }
        var moved: Boolean? = null
        val problems = JSONArray()
        if (target == "real") {
            // By what is in use, not by the label: the mode can say "real" over a virtual display.
            if (displayMode != "real" || shizukuDisplayId != Display.DEFAULT_DISPLAY) {
                val current = shizukuDisplayId
                val realId = service.adoptDefaultDisplay()
                // Release this lane's virtual display rather than keep it parked: an idle display
                // still takes part in system UI (Settings and the brightness dialog showed the
                // parked display's 0% while the real screen was at 255). Back to virtual starts a
                // fresh one.
                if (current != null && current != realId) {
                    runCatching { service.releaseDisplay(current) }
                        .onFailure { problems.put("release virtual display: ${it.message.orEmpty().take(120)}") }
                }
                shizukuDisplayId = realId
                displayMode = "real"
                val (w, h) = realDisplaySize()
                _displayState.value = _displayState.value.copy(
                    isActive = true, displayId = realId, width = w, height = h,
                    status = "Real screen (chosen by the user)", lastUpdatedMillis = System.currentTimeMillis(),
                )
                if (bringApp && pkg.isNotBlank()) {
                    moved = runCatching { service.movePackageToDisplay(pkg, realId) }
                        .onFailure { problems.put("move to real screen: ${it.message.orEmpty().take(120)}") }
                        .getOrDefault(false)
                }
            }
        } else {
            if (realScreenFallback) {
                return JSONObject().put("ok", false).put("code", "VIRTUAL_DISPLAY_UNAVAILABLE")
                    .put("errmsg", "This phone cannot host a virtual display; the real screen is the only one.").toString()
            }
            if (displayMode != "virtual" || shizukuDisplayId == Display.DEFAULT_DISPLAY) {
                val realId = shizukuDisplayId
                // Hand the real screen back first (stop driving and mirroring it; the user's apps on
                // it stay as they are), then start a fresh virtual display.
                if (realId != null) {
                    runCatching { service.releaseDisplay(realId) }
                        .onFailure { problems.put("release real screen: ${it.message.orEmpty().take(120)}") }
                }
                displayMode = "virtual"
                shizukuDisplayId = null
                val virtualId = ensureDisplay(settings)
                val spec = currentDeviceDisplaySpec()
                _displayState.value = _displayState.value.copy(
                    isActive = true, displayId = virtualId, width = spec.width, height = spec.height,
                    status = "Virtual display ready", lastUpdatedMillis = System.currentTimeMillis(),
                )
                if (bringApp && pkg.isNotBlank()) {
                    moved = runCatching { service.movePackageToDisplay(pkg, virtualId) }
                        .onFailure { problems.put("move to virtual display: ${it.message.orEmpty().take(120)}") }
                        .getOrDefault(false)
                }
            }
        }
        lastSnapshot = null
        return JSONObject(captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 300))
            .put("display_mode", displayMode)
            .put("moved_app", if (moved == true) pkg else "")
            .apply {
                if (moved == false) put("move_failed", pkg).put("hint", "$pkg did not move; launch it again on this screen.")
                if (problems.length() > 0) put("switch_warnings", problems)
            }
            .toString()
    }

    private fun invalidArguments(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("code", "BAD_ARGS").put("dispatched", false)
            put("errmsg", message)
        }.toString()

    private fun toolError(
        message: String,
        action: String,
        code: String? = null,
    ): String = JSONObject().apply {
        put("ok", false)
        put("action", action)
        put("errmsg", message)
        put("stdout", "")
        if (!code.isNullOrBlank()) {
            put("code", code)
            if (code in AgentModeSafety.InterruptCodes) {
                put("consistent", "no")
                put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            }
        }
    }.toString()

    private fun currentDeviceDisplaySpec(): AgentModeDisplaySpec {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            val fallback = context.resources.displayMetrics
            metrics.widthPixels = fallback.widthPixels
            metrics.heightPixels = fallback.heightPixels
            metrics.densityDpi = fallback.densityDpi
        }
        val width = metrics.widthPixels.takeIf { it > 0 }
            ?: display?.mode?.physicalWidth?.takeIf { it > 0 }
            ?: FallbackAgentDisplayWidth
        val height = metrics.heightPixels.takeIf { it > 0 }
            ?: display?.mode?.physicalHeight?.takeIf { it > 0 }
            ?: FallbackAgentDisplayHeight
        val densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: FallbackAgentDisplayDensityDpi
        return scaleAgentModeDisplaySpec(
            physicalWidth = width,
            physicalHeight = height,
            densityDpi = densityDpi,
        )
    }

    private fun toNormalizedAxis(value: Int, size: Int): Int {
        if (size <= 1) return 0
        return ((value.coerceIn(0, size - 1) * 1000f) / (size - 1)).roundToInt().coerceIn(0, 1000)
    }

    private suspend fun captureTeachingPageIfNeeded(settings: AppSettings) {
        if (!teachingActive.get()) return
        val displayId = currentManagedDisplayId(settings) ?: return
        runCatching {
            requireAgentModeService(settings).waitUntilLayoutStable(displayId, 40, 700)
        }
        val tree = dumpCompactUiTree(settings, displayId)
        if (tree.isBlank()) return
        notifyTeachingPageSettled(tree)
    }

    private suspend fun dumpCompactUiTree(settings: AppSettings, displayId: Int): String {
        val raw = runCatching {
            requireAgentModeService(settings).dumpUiTree(displayId)
        }.getOrDefault("")
        val compact = AgentModeUiTree.compactFromUiautomatorXml(
            xml = raw,
            width = _displayState.value.width,
            height = _displayState.value.height,
        )
        return compact.ifBlank { AgentModeUiTree.clickTargetsText(raw).ifBlank { raw } }
    }

    private fun notifyTeachingPageSettled(compactTree: String) {
        userGestureListener?.onTeachingPageSettled(compactTree)
        additionalUserGestureListeners.forEach { listener ->
            listener.onTeachingPageSettled(compactTree)
        }
    }

    private fun noteTeachingGesture() {
        if (!teachingActive.get()) return
        teachingGestures.incrementAndGet()
        lastTeachingGestureAt.set(System.currentTimeMillis())
    }

    fun addUserGestureListener(listener: AgentModeUserGestureListener) {
        additionalUserGestureListeners.addIfAbsent(listener)
    }

    fun removeUserGestureListener(listener: AgentModeUserGestureListener) {
        additionalUserGestureListeners.remove(listener)
    }

    private fun notifyUserTap(normalizedX: Int, normalizedY: Int) {
        noteTeachingGesture()
        userGestureListener?.onUserTap(normalizedX, normalizedY)
        additionalUserGestureListeners.forEach { listener ->
            listener.onUserTap(normalizedX, normalizedY)
        }
    }

    private fun notifyUserSwipe(
        normalizedX1: Int,
        normalizedY1: Int,
        normalizedX2: Int,
        normalizedY2: Int,
        durationMs: Int,
    ) {
        noteTeachingGesture()
        userGestureListener?.onUserSwipe(
            normalizedX1,
            normalizedY1,
            normalizedX2,
            normalizedY2,
            durationMs,
        )
        additionalUserGestureListeners.forEach { listener ->
            listener.onUserSwipe(
                normalizedX1,
                normalizedY1,
                normalizedX2,
                normalizedY2,
                durationMs,
            )
        }
    }
}

interface AgentModeUserGestureListener {
    fun onUserTap(normalizedX: Int, normalizedY: Int)
    fun onUserSwipe(
        normalizedX1: Int,
        normalizedY1: Int,
        normalizedX2: Int,
        normalizedY2: Int,
        durationMs: Int,
    )

    fun onTeachingPageSettled(compactTree: String) = Unit
}
