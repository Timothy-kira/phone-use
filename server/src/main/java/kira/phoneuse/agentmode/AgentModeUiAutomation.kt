package kira.phoneuse.agentmode

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicReference
import kira.phoneuse.data.AgentModeUiTree
import kira.phoneuse.data.AgentModePortal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resident UiAutomation for the Shizuku Agent Mode process. Used for SET_TEXT,
 * compact tree dumps, and hiding the IME on the virtual display without spawning
 * `uiautomator dump` for every gesture.
 */
internal class AgentModeUiAutomation {
    private val lock = Any()
    private val automation = AtomicReference<UiAutomation?>(null)
    @Volatile
    private var looper: Looper? = null

    fun attachLooper(value: Looper) {
        looper = value
    }

    fun ensureConnected(): UiAutomation? {
        automation.get()?.let { return it }
        synchronized(lock) {
            automation.get()?.let { return it }
            val created = connectLocked(looper) ?: return null
            automation.set(created)
            return created
        }
    }

    fun disconnect() {
        synchronized(lock) {
            val current = automation.getAndSet(null) ?: return
            runCatching {
                val disconnect = current.javaClass.methods.firstOrNull {
                    it.name == "disconnect" && it.parameterTypes.isEmpty()
                }
                disconnect?.invoke(current)
            }
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()

    /** null means there is no accessible editor: the caller may use its owned InputConnection. */
    fun editFocused(displayId: Int, args: JSONObject): JSONObject? {
        val node = focusedEditable(displayId) ?: return null
        var fieldIdentity = ""
        try {
            if (!node.isEditable && !node.isPassword) return null
            val sensitive = node.isPassword || kira.phoneuse.data.InteractionPolicy.sensitive.containsMatchIn("${node.viewIdResourceName} ${node.hintText} ${node.contentDescription}")
            if (sensitive && args.optString("_host_permission") != "full") throw TextEditFailure("USER_TAKEOVER_REQUIRED", "Let the user enter credentials on the phone.")
            if (!node.refresh() || !node.isFocused || !node.isEnabled) throw TextEditFailure("INPUT_FIELD_UNAVAILABLE", "The editor lost focus before writing.")
            if (args.optString("package").isNotBlank() && node.packageName?.toString() != args.optString("package")) throw TextEditFailure("INPUT_CONTEXT_CHANGED", "The editor belongs to another app.")
            if (sensitive) {
                val action = args.optString("edit_action")
                if (action == "read") return JSONObject().put("ok", true).put("dispatched", false)
                    .put("value_redacted", true).put("text_verified", false).put("used_backend", "a11y")
                if (action !in setOf("set", "clear")) throw TextEditFailure("SENSITIVE_EDIT_UNSUPPORTED", "Use one replacement for a credential; its contents are never read or retained for undo.")
                if (!node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return null
                val value = if (action == "clear") "" else args.optString("text")
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
                return JSONObject().put("ok", accepted).put("code", if (accepted) "SENSITIVE_INPUT_DISPATCHED" else "TEXT_ACTION_REJECTED")
                    .put("dispatched", true).put("value_redacted", true).put("text_verified", false)
                    .put("effect", "unknown").put("used_backend", "a11y")
            }
            val before = nodeText(node)
            val box = Rect(); node.getBoundsInScreen(box)
            val key = "a11y|$displayId|${node.windowId}|${node.packageName}|${node.viewIdResourceName}|${node.className}|$box"
            fieldIdentity = key
            val action = args.optString("edit_action")
            if (action == "read") return TextEdits.receipt(before, "a11y", args).put("field_identity", key)
            if (action == "submit") {
                val imeAction = if (Build.VERSION.SDK_INT >= 30) AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id else -1
                val supported = node.actionList.any { it.id == imeAction }
                if (!supported) return null // No dispatch: try the same protocol through owned InputConnection.
                val accepted = node.performAction(imeAction)
                return JSONObject().put("ok", accepted).put("code", if (accepted) "SUBMIT_DISPATCHED" else "SUBMIT_REJECTED")
                    .put("dispatched", true).put("submission_verified", false).put("used_backend", "a11y")
            }
            val desired = TextEdits.desired(args, key, before, node.textSelectionStart, node.textSelectionEnd)
            if (!node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return null
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, desired) })
            if (!accepted) throw TextEditFailure("TEXT_ACTION_REJECTED", "ACTION_SET_TEXT was rejected. No second write was attempted.", true)
            var now: String? = null
            repeat(4) { if (now != desired) { android.os.SystemClock.sleep(40); if (node.refresh() && node.isFocused) now = nodeText(node) } }
            if (now != desired) throw TextEditFailure("TEXT_NOT_APPLIED", "Exact read-back did not match the write. Inspect the current screen; do not replay blindly.", true)
            TextEdits.verified(action, key, before, desired)
            return TextEdits.receipt(desired, "a11y").put("dispatched", true).put("text_verified", true).put("field_identity", key)
        } catch (error: Throwable) { return TextEdits.failure(error).put("field_identity", fieldIdentity) }
        finally { node.recycle() }
    }

    /**
     * The complete text of one element - an editor, a document view, a long label - which the
     * tree dump shortens. [query]: "@px:l,t,r,b" (an element from dump_tree), text / id to match,
     * or blank for the focused field, else the element on this display with the most text.
     * Returns null when nothing on the display holds text.
     */
    /**
     * Whether a scrollable container still hides content: a list shows only the rows on screen,
     * and without this an agent can take the visible rows for the whole list.
     */
    private fun scrollState(node: AccessibilityNodeInfo): String {
        if (!node.isScrollable) return ""
        val ids = node.actionList.map { it.id }.toSet()
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids ||
            (Build.VERSION.SDK_INT >= 23 && (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in ids))
        val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids ||
            (Build.VERSION.SDK_INT >= 23 && (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in ids ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in ids))
        return when {
            forward && backward -> "more both ways"
            forward -> "more ahead"
            backward -> "more behind"
            else -> "all shown"
        }
    }

    fun readFullText(displayId: Int, query: String): Pair<String, String>? {
        val auto = ensureConnected() ?: return null
        val observed = parseObservedQuery(query)
        val target = observed?.let { AgentModePortal.parseNodes(JSONArray().put(it)).singleOrNull() }
        val roots = windowsOnDisplay(auto, displayId).filter { target == null || target.windowId < 0 || it.id == target.windowId }.mapNotNull { it.root }
        try {
            val bounds = parseBoundsQuery(observed?.optString("bounds_query") ?: query)
            var node: AccessibilityNodeInfo? = when {
                target != null -> roots.firstNotNullOfOrNull { findObservedNode(it, target, bounds) }
                observed != null -> null
                query == "@focused" -> focusedEditable(displayId)
                bounds != null -> roots.firstNotNullOfOrNull { findNodeByBounds(it, bounds) }
                query.isNotBlank() -> roots.firstNotNullOfOrNull { findNode(it, query) }
                else -> focusedEditable(displayId)
            }
            if (node == null && query.isBlank()) {
                var best: AccessibilityNodeInfo? = null
                var bestLength = 0
                fun walk(n: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 60) return
                    val length = n.text?.length ?: 0
                    if (length > bestLength) {
                        best?.recycle()
                        best = AccessibilityNodeInfo.obtain(n)
                        bestLength = length
                    }
                    for (i in 0 until n.childCount) {
                        val child = n.getChild(i) ?: continue
                        try { walk(child, depth + 1) } finally { child.recycle() }
                    }
                }
                roots.forEach { walk(it, 0) }
                node = best
            }
            var found = node ?: return null
            fun ownText(n: AccessibilityNodeInfo): String =
                if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) "" else
                    n.text?.toString() ?: n.contentDescription?.toString().orEmpty()
            if (!found.isPassword && ownText(found).isEmpty() && found.childCount > 0) {
                // Read every textual descendant of the selected row, including its price.
                val chunks = ArrayList<String>()
                fun walk(n: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 40 || n.isPassword) return
                    val own = ownText(n)
                    if (own.isNotBlank()) { if (chunks.lastOrNull() != own) chunks += own; return }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { child -> try { walk(child, depth + 1) } finally { child.recycle() } }
                }
                try {
                    walk(found, 0)
                    return found.viewIdResourceName.orEmpty().substringAfterLast('/').ifBlank { found.className?.toString().orEmpty() } to chunks.joinToString("\n")
                } finally { found.recycle() }
            }
            return try {
                if (found.isPassword) return null
                val text = ownText(found)
                val label = found.viewIdResourceName.orEmpty().substringAfterLast('/').ifBlank { found.className?.toString().orEmpty() }
                label to text
            } finally {
                found.recycle()
            }
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    /** IME search/send on the focused field. Returns false so callers can inject ENTER. */
    fun submitFocusedIme(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = focusedEditable(displayId) ?: return false
        return try {
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            // SET_TEXT is 0x00200000; invoking it without a Bundle would erase the field.
            if (node.actionList.none { it.id == action }) return false
            node.performAction(action)
        } finally {
            node.recycle()
        }
    }

    fun packageVisibleOnDisplay(packageName: String, displayId: Int): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        val auto = ensureConnected() ?: return false
        return windowsOnDisplayStrict(auto, displayId).any { window ->
            windowRootPackage(window) == pkg
        }
    }

    fun waitUntilPackageVisibleOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (packageVisibleOnDisplay(packageName, displayId)) return true
            android.os.SystemClock.sleep(50L)
        }
        return packageVisibleOnDisplay(packageName, displayId)
    }

    /** [{"package","title","type"}] for every window on [displayId]. */
    fun windowsOnDisplayJson(displayId: Int): String {
        val auto = ensureConnected() ?: return "[]"
        val out = org.json.JSONArray()
        windowsOnDisplayStrict(auto, displayId).forEach { window ->
            out.put(
                org.json.JSONObject()
                    .put("package", windowRootPackage(window))
                    .put("title", if (Build.VERSION.SDK_INT >= 24) window.title?.toString().orEmpty() else "")
                    .put("type", window.type),
            )
        }
        return out.toString()
    }

    /**
     * Screen rectangles (display pixels) that must not appear in a screenshot: password fields and
     * nodes whose text [isSensitive] flags. Bounded walk; returns what it found when it runs out.
     */
    fun sensitiveRects(displayId: Int, isSensitive: (String) -> Boolean): List<Rect> {
        val auto = ensureConnected() ?: return emptyList()
        val out = ArrayList<Rect>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > 3_000 || depth > 60 || out.size > 60) return
            val text = nodeText(node)
            if ((node.isPassword && text.isNotEmpty()) || isSensitive(text) ||
                isSensitive(node.contentDescription?.toString().orEmpty())
            ) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) out += r
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        runCatching {
            windowsOnDisplayStrict(auto, displayId).forEach { window ->
                val root = window.root ?: return@forEach
                try {
                    walk(root, 0)
                } finally {
                    root.recycle()
                }
            }
        }
        return out
    }

    /** What this UiAutomation sees, per display - for the bridge's diagnostics. */
    fun diagnosticsJson(): String {
        val out = org.json.JSONObject()
        val auto = ensureConnected() ?: return out.put("connected", false).toString()
        out.put("connected", true)
        runCatching { out.put("service_flags", auto.serviceInfo?.flags ?: -1) }
        val all = windowsOnAllDisplays(auto)
        val byDisplay = org.json.JSONObject()
        all.groupBy { it.displayId }.forEach { (id, refs) ->
            byDisplay.put(id.toString(), org.json.JSONArray(refs.map { ref ->
                (if (Build.VERSION.SDK_INT >= 24) ref.window.title?.toString().orEmpty() else "") + " / " + windowRootPackage(ref.window)
            }))
        }
        out.put("windows", byDisplay)
        runCatching {
            val clientClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            out.put("client_cache_methods", org.json.JSONArray(clientClass.methods.filter { it.name.contains("ache") }
                .map { m -> (if (java.lang.reflect.Modifier.isStatic(m.modifiers)) "static " else "") + m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }))
            out.put("uiautomation_cache_methods", org.json.JSONArray(UiAutomation::class.java.declaredMethods.filter { it.name.contains("ache") }
                .map { it.name + it.parameterTypes.joinToString(",", "(", ")") { p -> p.simpleName } }))
        }.onFailure { out.put("cache_probe_error", it.toString()) }
        runCatching { out.put("active_root_package", auto.rootInActiveWindow?.packageName?.toString().orEmpty()) }
        return out.toString()
    }

    fun imeWindowVisible(displayId: Int): Boolean {
        val ids = imeDisplayIds()
        return displayId in ids
    }

    fun imeDisplayIds(): List<Int> {
        val auto = ensureConnected() ?: return emptyList()
        return windowsOnAllDisplays(auto).mapNotNull { ref ->
            if (!isImeWindow(ref.window)) return@mapNotNull null
            ref.displayId
        }.distinct()
    }

    private fun isImeWindow(window: AccessibilityWindowInfo): Boolean {
        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        val title = if (Build.VERSION.SDK_INT >= 24) {
            window.title?.toString().orEmpty()
        } else {
            ""
        }
        if (
            title.contains("InputMethod", ignoreCase = true) ||
            title.contains("Input Method", ignoreCase = true)
        ) {
            return true
        }
        val pkg = runCatching {
            val root = window.root ?: return@runCatching ""
            try {
                root.packageName?.toString().orEmpty()
            } finally {
                root.recycle()
            }
        }.getOrNull().orEmpty()
        val defaultIme = runCatching {
            val thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
            val system = thread.javaClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
            android.provider.Settings.Secure.getString(system.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        return pkg == defaultIme || pkg == "kira.phoneuse.server" || pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("ime.", ignoreCase = true) ||
            pkg.endsWith(".ime") ||
            pkg.contains("baidu.input", ignoreCase = true) ||
            pkg.contains("sogou", ignoreCase = true) ||
            pkg.contains("iflytek", ignoreCase = true) ||
            pkg.contains("com.huawei.ohos.inputmethod")
    }

    fun dumpCompactTree(displayId: Int, width: Int, height: Int): String {
        val auto = ensureConnected() ?: return ""
        val roots = windowsOnDisplay(auto, displayId).mapNotNull { it.root }
            .ifEmpty { listOfNotNull(runCatching { auto.rootInActiveWindow }.getOrNull()) }
        val lines = ArrayList<String>(AgentModeUiTree.DefaultDumpLimit)
        try {
            roots.forEach { root ->
                collectCompact(root, width.coerceAtLeast(1), height.coerceAtLeast(1), lines, AgentModeUiTree.DefaultDumpLimit)
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        return lines.joinToString("\n")
    }

    fun layoutFingerprint(displayId: Int): Long {
        val auto = ensureConnected() ?: return 0L
        val windows = windowsOnDisplayStrict(auto, displayId)
        val nodes = ArrayList<AgentModeUiTree.LayoutNode>(128)
        var windowKey = windows.size
        var contentReady = false
        windows.forEach { window ->
            val root = window.root ?: return@forEach
            val visible = ArrayList<AgentModeUiTree.LayoutNode>()
            try {
                collectLayoutNodes(root, visible, 400)
                if (visible.isNotEmpty() && !isImeWindow(window) &&
                    (window.type == AccessibilityWindowInfo.TYPE_APPLICATION || window.isFocused || window.isActive)) {
                    contentReady = true
                }
                windowKey = 31 * windowKey + window.id
                windowKey = 31 * windowKey + window.type
                nodes.addAll(visible)
            } finally {
                root.recycle()
            }
        }
        // A status bar alone, or an invisible activity during its transition, is not a ready screen.
        if (!contentReady || nodes.isEmpty()) return 0L
        return AgentModeUiTree.layoutFingerprint(nodes, windowKey)
    }

    /** Reused when the layout fingerprint is unchanged between two dumps (P3-6). */
    private var lastFullDumpFingerprint: Long = 0L
    private var lastFullDumpNodes: List<AgentModeUiTree.RichNode> = emptyList()

    fun dumpRichNodesIncremental(
        displayId: Int,
        width: Int,
        height: Int,
        maxNodes: Int = AgentModeUiTree.MaxDumpLimit,
        interactiveOnly: Boolean = false,
    ): List<AgentModeUiTree.RichNode> {
        // Reusing the previous dump when a shallow layout fingerprint has not changed is tempting. That
        // fingerprint misses content changes (values, text, web pages) and did not distinguish an
        // interactive-only dump from a full one, so a later full dump could come back partial and
        // old. Every dump is read fresh now; correctness over the few milliseconds saved.
        return dumpRichNodes(displayId, width, height, maxNodes, interactiveOnly)
    }

    /** Retain one native handle during quiet sampling; never dump the entire tree per poll. */
    private fun acquireControl(displayId: Int, target: AgentModeUiTree.RichNode, pixelQuery: String): AccessibilityNodeInfo? {
        val auto = ensureConnected() ?: return null
        for (window in windowsForMutation(auto, displayId).filter { target.windowId < 0 || it.id == target.windowId }) {
            val root = window.root ?: continue
            try { findObservedNode(root, target, parseBoundsQuery(pixelQuery))?.let { return it } }
            finally { root.recycle() }
        }
        return null
    }
    private fun controlFingerprint(node: AccessibilityNodeInfo, target: AgentModeUiTree.RichNode): Long {
        if (!node.refresh() || !onScreen(node) || (target.windowId >= 0 && node.windowId != target.windowId) ||
            node.packageName?.toString() != target.packageName || node.className?.toString()?.substringAfterLast('.') != target.klass) return 0L
        val box = Rect(); node.getBoundsInScreen(box)
        return listOf(node.windowId, node.className, node.viewIdResourceName, box,
            if (target.scrollable) "" else nodeText(node), if (target.scrollable) "" else node.contentDescription,
            node.isEnabled, node.isVisibleToUser, node.isClickable, node.isEditable, node.isScrollable,
            node.isChecked, node.isSelected, if (node.isEditable) node.isFocused else false)
            .joinToString("|").hashCode().toLong().let { if (it == 0L) 1L else it }
    }
    fun waitUntilLayoutStable(displayId: Int, quietMs: Int, timeoutMs: Int, targetJson: String = ""): Boolean =
        JSONObject(waitUntilLayoutStableDetails(displayId, quietMs, timeoutMs, targetJson)).optBoolean("stable")

    fun waitUntilLayoutStableDetails(displayId: Int, quietMs: Int, timeoutMs: Int, targetJson: String = "", shouldStop: () -> Boolean = { false }): String {
        val quiet = quietMs.coerceIn(180, 400); val timeout = timeoutMs.coerceIn(0, 9000)
        val data = targetJson.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val target = data?.let { AgentModePortal.parseNodes(JSONArray().put(it)).singleOrNull() }
        if (targetJson.isNotBlank() && target == null) return JSONObject().put("stable", false).put("reason", "invalid_target").toString()
        val started = android.os.SystemClock.uptimeMillis(); val end = started + timeout
        var handle: AccessibilityNodeInfo? = null
        var reacquired = false; var samples = 0; var changes = 0; var unavailable = 0
        var previous = 0L; var unchangedSince = started; var unchangedSamples = 0; var stable = false
        var lookupMs = 0L; var refreshMs = 0L
        val intervals = intArrayOf(37, 53, 71, 43)
        try {
            while (!shouldStop() && android.os.SystemClock.uptimeMillis() < end) {
                if (target != null && handle == null && (samples == 0 || !reacquired)) {
                    val at = android.os.SystemClock.uptimeMillis()
                    handle = acquireControl(displayId, target, data?.optString("bounds_query").orEmpty())
                    lookupMs += android.os.SystemClock.uptimeMillis() - at
                    if (samples > 0) reacquired = true
                }
                if (shouldStop() || android.os.SystemClock.uptimeMillis() >= end) break
                val at = android.os.SystemClock.uptimeMillis()
                val current = runCatching { if (target == null) layoutFingerprint(displayId)
                    else handle?.let { controlFingerprint(it, target) } ?: 0L }.getOrDefault(0L)
                val now = android.os.SystemClock.uptimeMillis(); refreshMs += now - at; samples++
                if (current == 0L) { unavailable++; handle?.recycle(); handle = null }
                else if (previous != 0L && current != previous) changes++
                if (current == 0L || current != previous) { unchangedSince = now; unchangedSamples = if (current == 0L) 0 else 1 }
                else unchangedSamples++
                if (now <= end && current != 0L && unchangedSamples >= 4 && now - unchangedSince >= quiet) { stable = true; break }
                previous = current
                val remaining = end - android.os.SystemClock.uptimeMillis()
                if (remaining > 0) android.os.SystemClock.sleep(minOf(intervals[(samples - 1) % intervals.size].toLong(), remaining))
            }
        } finally { handle?.recycle() }
        val reason = when { shouldStop() -> "operation_stopped"; stable -> "stable"; changes > 0 -> "target_not_quiet"
            unavailable > 0 -> "target_unavailable"; else -> "insufficient_samples" }
        return JSONObject().put("stable", stable).put("reason", reason).put("samples", samples)
            .put("changed_samples", changes).put("unavailable_samples", unavailable).put("quiet_ms", quiet)
            .put("timeout_ms", timeout).put("elapsed_ms", android.os.SystemClock.uptimeMillis() - started)
            .put("lookup_ms", lookupMs).put("refresh_ms", refreshMs).put("reacquired", reacquired).toString()
    }

    fun dumpRichNodes(
        displayId: Int,
        width: Int,
        height: Int,
        maxNodes: Int = AgentModeUiTree.MaxDumpLimit,
        interactiveOnly: Boolean = false,
    ): List<AgentModeUiTree.RichNode> {
        val auto = ensureConnected() ?: return emptyList()
        val windows = windowsOnDisplay(auto, displayId)
        val out = ArrayList<AgentModeUiTree.RichNode>(256)
        val limit = maxNodes.coerceIn(1, AgentModeUiTree.MaxDumpLimit)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (windows.isNotEmpty()) {
            windows.forEachIndexed { windowIndex, window ->
                val root = window.root ?: return@forEachIndexed
                try {
                    collectRich(
                        node = root,
                        width = w,
                        height = h,
                        out = out,
                        parent = -1,
                        depth = 0,
                        maxNodes = limit,
                        interactiveOnly = interactiveOnly,
                        windowType = if (isImeWindow(window)) AccessibilityWindowInfo.TYPE_INPUT_METHOD else window.type,
                        windowIndex = window.layer,
                        windowId = window.id,
                        windowContext = windowContext(window, w, h),
                    )
                } finally {
                    runCatching { root.recycle() }
                }
            }
        } else {
            val root = runCatching { auto.rootInActiveWindow }.getOrNull() ?: return emptyList()
            try {
                collectRich(
                    node = root,
                    width = w,
                    height = h,
                    out = out,
                    parent = -1,
                    depth = 0,
                    maxNodes = limit,
                    interactiveOnly = interactiveOnly,
                    windowType = 0,
                    windowIndex = 0,
                )
            } finally {
                runCatching { root.recycle() }
            }
        }
        return AgentModeUiTree.pruneRich(out)
    }

    fun hasWindowOnDisplay(displayId: Int): Boolean {
        val auto = ensureConnected() ?: return false
        return windowsOnDisplayStrict(auto, displayId).isNotEmpty()
    }

    /**
     * Clicks the node [query] names. A label usually lives on a non-clickable TextView inside the
     * clickable row; then the nearest clickable ancestor receives the click, as a finger would.
     */
    fun clickByQuery(displayId: Int, query: String): Boolean = clickByQueryDetails(displayId, query).optBoolean("ok")
    fun clickByQueryDetails(displayId: Int, query: String): JSONObject {
        var found = false; var dispatched = false; var supported = false
        var accepted = false
        performOnQuery(displayId, query) { node ->
            found = true
            val action = if (node.isEditable) AccessibilityNodeInfo.ACTION_FOCUS else AccessibilityNodeInfo.ACTION_CLICK
            if (node.isEditable && node.isFocused) { supported = true; accepted = true; return@performOnQuery true }
            var destination: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            var hops = 0
            while (destination != null && !destination.isEditable && !destination.isClickable && hops++ < 8) {
                val parent = destination.parent; destination.recycle(); destination = parent
            }
            val target = destination ?: return@performOnQuery false
            try {
                supported = target.actionList.any { it.id == action }
                if (!supported) return@performOnQuery false
                dispatched = true
                accepted = target.performAction(action)
                accepted
            } finally { target.recycle() }
        }
        val code = when { accepted -> "ACTION_DISPATCHED"; !found -> "NATIVE_NODE_NOT_FOUND"; !supported -> "NATIVE_ACTION_UNSUPPORTED"; else -> "NATIVE_ACTION_FALSE" }
        return JSONObject().put("ok", accepted).put("code", code).put("dispatched", dispatched)
            .put("failure_stage", if (!found) "lookup" else if (!supported) "action_support" else "perform_action")
            .put("used_backend", "a11y").put("effect_verified", false)
            .apply { if (!accepted) put("errmsg", "Native node action failed at $code. No coordinate fallback or repeated action was sent.") }
    }

    /** Scroll the observed container or its nearest scrollable parent, without touching a child. */
    fun scrollByQuery(displayId: Int, query: String, direction: String): Boolean =
        performOnQuery(displayId, query) { node ->
            val directional = when (direction) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            }
            val generic = if (direction == "up" || direction == "left")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            fun scroll(n: AccessibilityNodeInfo): Boolean = n.isScrollable &&
                (n.performAction(directional) || n.performAction(generic))
            if (scroll(node)) return@performOnQuery true
            if (node.isScrollable) return@performOnQuery false
            var ancestor = node.parent
            var hops = 0
            while (ancestor != null && hops++ < 8) {
                val moved = scroll(ancestor)
                val next = if (moved) null else ancestor.parent
                ancestor.recycle()
                if (moved) return@performOnQuery true
                ancestor = next
            }
            ancestor?.recycle()
            false
        }

    /**
     * Sets a slider/SeekBar/progress control to [value] through ACTION_SET_PROGRESS - exact, unlike a
     * drag. [query] is "#n", a label or an id; when it names a row, the first ranged descendant is used.
     * Returns null when no ranged node matched, so the caller can fall back to a drag.
     */
    fun setProgress(displayId: Int, query: String, value: Float): Boolean? {
        var matchedRange = false
        // A box can be shared: SystemUI's brightness mirror sits in an earlier window with the
        // slider's exact bounds and no range. For a bounds query, take the ranged node in that box.
        parseBoundsQuery(query.trim())?.let { box ->
            val auto = ensureConnected() ?: return null
            val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
            try {
                val ranged = roots.firstNotNullOfOrNull { rangedInBox(it, box, 0) }
                if (ranged != null) {
                    return try {
                        val info = ranged.rangeInfo
                        val bounded = if (info != null) value.coerceIn(info.min, info.max) else value
                        ranged.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, bounded) },
                        )
                    } finally {
                        ranged.recycle()
                    }
                }
            } finally {
                roots.forEach { runCatching { it.recycle() } }
            }
        }
        val ok = performOnQuery(displayId, query) { node ->
            val ranged = if (node.rangeInfo != null) AccessibilityNodeInfo.obtain(node) else firstRanged(node)
            if (ranged == null) {
                false
            } else {
                matchedRange = true
                try {
                    val info = ranged.rangeInfo
                    val bounded = if (info != null) value.coerceIn(info.min, info.max) else value
                    val args = Bundle().apply {
                        putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, bounded)
                    }
                    ranged.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        args,
                    )
                } finally {
                    ranged.recycle()
                }
            }
        }
        return if (matchedRange) ok else null
    }

    /** The ranged node overlapping [target] most (IoU > 0.5): dump boxes are rounded to 0..1000. */
    private fun rangedInBox(node: AccessibilityNodeInfo, target: Rect, depth: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.5
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 60) return
            if (n.rangeInfo != null) {
                val box = Rect()
                n.getBoundsInScreen(box)
                val inter = Rect()
                if (inter.setIntersect(box, target)) {
                    val i = inter.width().toDouble() * inter.height()
                    val u = box.width().toDouble() * box.height() + target.width().toDouble() * target.height() - i
                    val score = if (u > 0) i / u else 0.0
                    if (score > bestScore) { best?.recycle(); best = AccessibilityNodeInfo.obtain(n); bestScore = score }
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                try { walk(c, d + 1) } finally { c.recycle() }
            }
        }
        walk(node, depth)
        return best
    }

    private fun firstRanged(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (child.rangeInfo != null) return AccessibilityNodeInfo.obtain(child)
                firstRanged(child)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    /** Finds the node [query] names ("#n" from dump_tree, or text/desc/id) and runs [action] on it. */
    private fun performOnQuery(
        displayId: Int,
        query: String,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return false
        val auto = ensureConnected() ?: return false
        val numbered = AgentModeUiTree.parseIndexQuery(trimmed)
        val observed = parseObservedQuery(trimmed)
        val target = observed?.let { AgentModePortal.parseNodes(JSONArray().put(it)).singleOrNull() }
        val bounds = parseBoundsQuery(observed?.optString("bounds_query") ?: trimmed)
        val roots = windowsForMutation(auto, displayId).filter { target == null || target.windowId < 0 || it.id == target.windowId }.mapNotNull { it.root }
        try {
            val node = when {
                target != null -> roots.firstNotNullOfOrNull { root -> findObservedNode(root, target, bounds) }
                observed != null -> null
                bounds != null -> roots.firstNotNullOfOrNull { root -> findNodeByBounds(root, bounds) }
                numbered != null -> nodeAtWalkedIndex(roots, numbered)
                else -> roots.firstNotNullOfOrNull { root -> findNode(root, trimmed) }
            } ?: return false
            return try {
                action(node)
            } finally {
                node.recycle()
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    private fun parseObservedQuery(query: String): JSONObject? = query.takeIf { it.startsWith("@node:") }
        ?.removePrefix("@node:")?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun findObservedNode(root: AccessibilityNodeInfo, target: AgentModeUiTree.RichNode, bounds: Rect?): AccessibilityNodeInfo? {
        if (bounds == null) return null
        val matches = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || matches.size > 1) return
            val box = Rect(); n.getBoundsInScreen(box)
            val same = n.packageName?.toString() == target.packageName && n.className?.toString()?.substringAfterLast('.') == target.klass &&
                (target.id.isBlank() || n.viewIdResourceName.orEmpty().substringAfterLast('/') == target.id) &&
                (!target.scrollable || n.isScrollable) && (!target.editable || n.isEditable) && onScreen(n) &&
                kotlin.math.abs(box.left - bounds.left) <= 4 && kotlin.math.abs(box.top - bounds.top) <= 4 &&
                kotlin.math.abs(box.right - bounds.right) <= 4 && kotlin.math.abs(box.bottom - bounds.bottom) <= 4 &&
                (target.scrollable || (target.text.isBlank() || n.text?.toString() == target.text) &&
                    (target.desc.isBlank() || n.contentDescription?.toString() == target.desc))
            if (same) matches += AccessibilityNodeInfo.obtain(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> try { walk(c, depth + 1) } finally { c.recycle() } }
        }
        walk(root, 0)
        if (matches.size == 1) return matches.single()
        matches.forEach { it.recycle() }; return null
    }

    /** "@px:left,top,right,bottom" - the exact on-screen box of a node from a dump. */
    private fun parseBoundsQuery(query: String): Rect? {
        if (!query.startsWith(BoundsQueryPrefix)) return null
        val parts = query.removePrefix(BoundsQueryPrefix).split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 4) Rect(parts[0], parts[1], parts[2], parts[3]) else null
    }

    /** The deepest node whose box matches [target] within a few pixels (dump coordinates are rounded). */
    private fun findNodeByBounds(node: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = try {
                findNodeByBounds(child, target)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        val box = Rect()
        node.getBoundsInScreen(box)
        val tolerance = 4
        val matches = kotlin.math.abs(box.left - target.left) <= tolerance &&
            kotlin.math.abs(box.top - target.top) <= tolerance &&
            kotlin.math.abs(box.right - target.right) <= tolerance &&
            kotlin.math.abs(box.bottom - target.bottom) <= tolerance
        return if (matches) AccessibilityNodeInfo.obtain(node) else null
    }

    private fun nodeAtWalkedIndex(roots: List<AccessibilityNodeInfo>, clickIndex: Int): AccessibilityNodeInfo? {
        val wanted = if (clickIndex >= 1) clickIndex - 1 else clickIndex
        if (wanted < 0) return null
        var current = 0
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current == wanted) return AccessibilityNodeInfo.obtain(node)
            current += 1
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = try {
                    walk(child)
                } finally {
                    child.recycle()
                }
                if (found != null) return found
            }
            return null
        }
        return roots.firstNotNullOfOrNull { walk(it) }
    }

    fun clickRichNode(displayId: Int, target: AgentModeUiTree.RichNode): Boolean {
        val auto = ensureConnected() ?: return false
        val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
        try {
            for (root in roots) {
                findNodeByRich(root, target)?.let { node ->
                    return try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        node.recycle()
                    }
                }
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    fun waitForLabel(displayId: Int, label: String, timeoutMs: Int): Boolean {
        val wanted = label.trim()
        if (wanted.isBlank()) return false
        val timeout = timeoutMs.coerceIn(80, 8_000)
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val auto = ensureConnected() ?: return false
            val roots = windowsOnDisplay(auto, displayId).mapNotNull { it.root }
                .ifEmpty { listOfNotNull(runCatching { auto.rootInActiveWindow }.getOrNull()) }
            try {
                if (roots.any { containsLabel(it, wanted) }) return true
            } finally {
                roots.forEach { runCatching { it.recycle() } }
            }
            android.os.SystemClock.sleep(50L)
        }
        return false
    }

    private fun collectLayoutNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AgentModeUiTree.LayoutNode>,
        maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (node.isVisibleToUser && !bounds.isEmpty) {
            val klass = node.className?.toString().orEmpty().substringAfterLast('.')
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
            val label = node.text?.toString().orEmpty().ifBlank {
                node.contentDescription?.toString().orEmpty()
            }
            if (
                !AgentModeUiTree.isIgnoredMedia(
                    klass = klass,
                    id = id,
                    interactive = node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable,
                    label = label,
                )
            ) {
                out += AgentModeUiTree.LayoutNode(
                    klass = klass,
                    id = id,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    clickable = node.isClickable,
                    childCount = node.childCount,
                    label = label,
                )
            }
        }
        for (index in 0 until node.childCount) {
            if (out.size >= maxNodes) return
            val child = node.getChild(index) ?: continue
            try {
                collectLayoutNodes(child, out, maxNodes)
            } finally {
                child.recycle()
            }
        }
    }

    private fun focusedEditable(displayId: Int): AccessibilityNodeInfo? {
        val auto = ensureConnected() ?: return null
        val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
        try {
            for (root in roots) {
                findFocusedEditable(root)?.let { return it }
            }
        } finally {
            roots.forEach { node -> runCatching { node.recycle() } }
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && (node.isEditable || node.isPassword || node.className?.contains("EditText") == true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFocusedEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun collectCompact(
        node: AccessibilityNodeInfo,
        width: Int,
        height: Int,
        lines: MutableList<String>,
        maxLines: Int,
    ) {
        if (lines.size >= maxLines) return
        val text = nodeText(node)
        val desc = node.contentDescription?.toString().orEmpty()
        val klass = node.className?.toString().orEmpty().substringAfterLast('.')
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val clickable = node.isClickable
        val focused = node.isFocused
        if (
            AgentModeUiTree.shouldEmitCompact(
                klass = klass,
                text = text,
                desc = desc,
                id = id,
                clickable = clickable || node.isCheckable,
                focused = focused,
            )
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val nx1 = bounds.left * 1000 / width
            val ny1 = bounds.top * 1000 / height
            val nx2 = bounds.right * 1000 / width
            val ny2 = bounds.bottom * 1000 / height
            val label = text.ifBlank { desc }.ifBlank { id }.ifBlank { klass.ifBlank { "view" } }
            val flags = buildString {
                if (clickable || node.isCheckable) append(" tap")
                if (focused) append(" focus")
            }
            val idSuffix = if (id.isNotBlank()) " #$id" else ""
            lines += "$label$flags ($nx1,$ny1)-($nx2,$ny2)$idSuffix"
        }
        for (index in 0 until node.childCount) {
            if (lines.size >= maxLines) return
            val child = node.getChild(index) ?: continue
            try {
                collectCompact(child, width, height, lines, maxLines)
            } finally {
                child.recycle()
            }
        }
    }

    private data class WindowContext(val parentId: Int = -1, val anchorId: String = "",
        val focused: Boolean = false, val active: Boolean = false, val pip: Boolean = false,
        val touchRegion: String = "", val title: String = "")

    private fun windowContext(window: android.view.accessibility.AccessibilityWindowInfo, width: Int, height: Int): WindowContext {
        val parentId = runCatching { window.parent?.let { parent -> try { parent.id } finally { parent.recycle() } } ?: -1 }.getOrDefault(-1)
        val anchorId = runCatching { window.anchor?.let { anchor -> try { anchor.viewIdResourceName.orEmpty() } finally { anchor.recycle() } }.orEmpty() }.getOrDefault("")
        val rectangles = JSONArray()
        val region = android.graphics.Region()
        val known = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) window.getRegionInScreen(region)
            else { val bounds = Rect(); window.getBoundsInScreen(bounds); region.set(bounds) }
            val iterator = android.graphics.RegionIterator(region); val bounds = Rect()
            while (iterator.next(bounds)) rectangles.put(JSONArray(listOf(bounds.left * 1000 / width,
                bounds.top * 1000 / height, bounds.right * 1000 / width, bounds.bottom * 1000 / height)))
            true
        }.getOrDefault(false)
        return WindowContext(parentId, anchorId, window.isFocused, window.isActive,
            if (android.os.Build.VERSION.SDK_INT >= 26) window.isInPictureInPictureMode else false,
            if (known) rectangles.toString() else "", window.title?.toString().orEmpty())
    }

    private fun collectRich(
        node: AccessibilityNodeInfo,
        width: Int,
        height: Int,
        out: MutableList<AgentModeUiTree.RichNode>,
        parent: Int,
        depth: Int,
        maxNodes: Int,
        interactiveOnly: Boolean,
        windowType: Int,
        windowIndex: Int,
        windowId: Int = -1,
        windowContext: WindowContext = WindowContext(),
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val text = nodeText(node)
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val klass = node.className?.toString().orEmpty().substringAfterLast('.')
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        // A clickable row usually carries no text itself; its label lives on child TextViews. Name the
        // row by them, so "#5 [btn] WLAN" instead of "#5 [btn] LinearLayout" (and so rows stay
        // distinguishable when the tree is compared before and after a scroll).
        val ownLabel = text.ifBlank { desc }.ifBlank { hint }
        val label = ownLabel.ifBlank {
            if (node.isClickable || node.isCheckable || node.isLongClickable) descendantText(node) else ""
        }.ifBlank { id }.ifBlank { klass.ifBlank { "view" } }
        val candidate = AgentModeUiTree.RichNode(
            index = out.size,
            label = label,
            text = text,
            desc = desc,
            hint = hint,
            klass = klass,
            id = id,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            enabled = node.isEnabled,
            scrollable = node.isScrollable,
            focused = node.isFocused,
            editable = node.isEditable,
            left = bounds.left * 1000 / width.coerceAtLeast(1),
            top = bounds.top * 1000 / height.coerceAtLeast(1),
            right = bounds.right * 1000 / width.coerceAtLeast(1),
            bottom = bounds.bottom * 1000 / height.coerceAtLeast(1),
            parent = parent,
            depth = depth,
            drawingOrder = if (android.os.Build.VERSION.SDK_INT >= 24) node.drawingOrder else 0,
            password = node.isPassword,
            packageName = node.packageName?.toString().orEmpty(),
            windowType = windowType,
            windowIndex = windowIndex,
            windowId = windowId,
            windowParentId = windowContext.parentId,
            windowAnchorId = windowContext.anchorId,
            windowFocused = windowContext.focused,
            windowActive = windowContext.active,
            windowPictureInPicture = windowContext.pip,
            windowTouchRegion = windowContext.touchRegion,
            windowTitle = windowContext.title,
            rangeValue = node.rangeInfo?.current ?: Float.NaN,
            rangeMin = node.rangeInfo?.min ?: Float.NaN,
            rangeMax = node.rangeInfo?.max ?: Float.NaN,
            state = (if (android.os.Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty() else "")
                .ifBlank { scrollState(node) },
        )
        val overlayWindow = AgentModeUiTree.isOverlayWindowType(windowType)
        // Hidden/collapsed notification children and off-screen app rows must not become
        // actionable references. Still traverse children: OEMs can mark a parent invisible.
        val visible = node.isVisibleToUser && !bounds.isEmpty && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < width && bounds.top < height
        val keep = visible && if (!interactiveOnly) {
            out.size < maxNodes
        } else {
            overlayWindow || AgentModeUiTree.shouldKeepForCheapDump(candidate)
        }
        val selfIndex = if (keep && (out.size < maxNodes || overlayWindow && interactiveOnly)) {
            val index = out.size
            out += candidate.copy(index = index)
            index
        } else {
            parent
        }
        if (!interactiveOnly && out.size >= maxNodes) return
        for (index in 0 until node.childCount) {
            if (!interactiveOnly && out.size >= maxNodes) return
            val child = node.getChild(index) ?: continue
            try {
                collectRich(
                    node = child,
                    width = width,
                    height = height,
                    out = out,
                    parent = selfIndex,
                    depth = depth + 1,
                    maxNodes = maxNodes,
                    interactiveOnly = interactiveOnly,
                    windowType = windowType,
                    windowIndex = windowIndex,
                    windowId = windowId,
                    windowContext = windowContext,
                )
            } finally {
                child.recycle()
            }
        }
    }

    /** Up to two text/description strings below [node], depth-limited, joined with " 路 ". */
    private fun descendantText(node: AccessibilityNodeInfo): String {
        val found = ArrayList<String>(2)
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (found.size >= 2 || depth > 4) return
            for (index in 0 until current.childCount) {
                if (found.size >= 2) return
                val child = current.getChild(index) ?: continue
                try {
                    val value = child.text?.toString()?.trim().orEmpty()
                        .ifBlank { child.contentDescription?.toString()?.trim().orEmpty() }
                    if (value.isNotBlank() && value !in found) found += value.take(40)
                    walk(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        walk(node, 1)
        return found.joinToString(" 路 ")
    }

    private fun findNodeByRich(
        node: AccessibilityNodeInfo,
        target: AgentModeUiTree.RichNode,
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val text = nodeText(node)
        val desc = node.contentDescription?.toString().orEmpty()
        val label = text.ifBlank { desc }.ifBlank { id }
        val idMatch = target.id.isNotBlank() && id.equals(target.id, ignoreCase = true)
        val labelMatch = target.label.isNotBlank() && label.equals(target.label, ignoreCase = true)
        val sameKind = node.packageName?.toString() == target.packageName &&
            node.className?.toString()?.substringAfterLast('.') == target.klass
        if (sameKind && (idMatch || (labelMatch && target.id.isBlank()))) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNodeByRich(child, target)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        if (query.isBlank()) return null
        val text = nodeText(node)
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val needle = query.removePrefix("#")
        if (
            text.equals(query, ignoreCase = true) ||
            desc.equals(query, ignoreCase = true) ||
            id.equals(query, ignoreCase = true) ||
            id.equals(needle, ignoreCase = true) ||
            text.contains(query, ignoreCase = true) ||
            desc.contains(query, ignoreCase = true) ||
            (needle.isNotBlank() && id.contains(needle, ignoreCase = true))
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNode(child, query)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /**
     * Whether [label] is on screen under [node]. Lists keep rows laid out past their edge in the
     * tree; those are not on screen, so scroll_until keeps scrolling and wait_for_label keeps waiting.
     */
    private fun containsLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = nodeText(node)
        val desc = node.contentDescription?.toString().orEmpty()
        if ((text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)) && onScreen(node)) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = containsLabel(child, label)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun onScreen(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        return !r.isEmpty
    }

    private fun windowsOnDisplay(auto: UiAutomation, displayId: Int): List<AccessibilityWindowInfo> {
        val matching = windowsOnDisplayStrict(auto, displayId)
        if (matching.isNotEmpty()) return matching
        if (displayId == Display.DEFAULT_DISPLAY) {
            return windowsOnAllDisplays(auto).map { it.window }
        }
        return emptyList()
    }
    private fun windowsForMutation(
        auto: UiAutomation,
        displayId: Int,
    ): List<AccessibilityWindowInfo> = windowsOnDisplayStrict(auto, displayId)

    /**
     * Windows whose Accessibility display id equals [displayId]. Never falls
     * back to the real screen: a Huawei virtual display can report WeChat as a
     * task while the only painted window is still the boot earth wallpaper.
     */
    private fun windowsOnDisplayStrict(
        auto: UiAutomation,
        displayId: Int,
    ): List<AccessibilityWindowInfo> {
        return windowsOnAllDisplays(auto)
            .filter { ref -> ref.displayId == displayId }
            .map { it.window }
    }

    private data class WindowOnDisplay(
        val displayId: Int,
        val window: AccessibilityWindowInfo,
    )

    companion object {
        private const val LogTag = "AetherUiAuto"
        const val BoundsQueryPrefix = "@px:"
        private const val RetrieveInteractiveWindowsFlag =
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

        /**
         * Pure P0-2 policy: given (displayId, matchedOnTargetDisplay, allDisplays),
         * mutation paths only ever see the strict target-display set. Read-only
         * dumps may fall back to the default display only when the requested
         * display *is* the default display.
         */
        internal fun selectWindowsForMutation(
            displayId: Int,
            strict: List<AccessibilityWindowInfo>,
        ): List<AccessibilityWindowInfo> = strict

        internal fun selectWindowsForRead(
            displayId: Int,
            strict: List<AccessibilityWindowInfo>,
            allDisplays: List<AccessibilityWindowInfo>,
        ): List<AccessibilityWindowInfo> = when {
            strict.isNotEmpty() -> strict
            displayId == Display.DEFAULT_DISPLAY -> allDisplays
            else -> emptyList()
        }
    }

    private fun windowRootPackage(window: AccessibilityWindowInfo): String {
        return runCatching {
            val root = window.root ?: return@runCatching ""
            try {
                root.packageName?.toString().orEmpty()
            } finally {
                root.recycle()
            }
        }.getOrNull().orEmpty()
    }

    /**
     * Drops the accessibility node cache before every read. The cache is only invalidated by the
     * app's own accessibility events; many apps send none for changes on a virtual display (a SeekBar
     * moving, a list re-binding), so without this a dump returns the page as it was minutes ago -
     * stale values, "no visible change" diffs, and STALLED on gestures that did work.
     */
    private fun clearNodeCache(auto: UiAutomation) {
        runCatching {
            val clientClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val noArg = clientClass.methods.firstOrNull { it.name == "clearCache" && it.parameterTypes.isEmpty() }
            if (noArg != null) {
                val instance = if (java.lang.reflect.Modifier.isStatic(noArg.modifiers)) null
                else clientClass.getMethod("getInstance").invoke(null)
                noArg.invoke(instance)
                return
            }
            // Android 13+: clearCache(int connectionId), static.
            val byConnection = clientClass.methods.firstOrNull {
                it.name == "clearCache" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return
            val connectionId = UiAutomation::class.java.getDeclaredField("mConnectionId")
                .apply { isAccessible = true }
                .getInt(auto)
            val instance = if (java.lang.reflect.Modifier.isStatic(byConnection.modifiers)) null
            else clientClass.getMethod("getInstance").invoke(null)
            byConnection.invoke(instance, connectionId)
        }
    }

    private fun windowsOnAllDisplays(auto: UiAutomation): List<WindowOnDisplay> {
        clearNodeCache(auto)
        val viaAll = runCatching {
            val method = auto.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "getWindowsOnAllDisplays" && candidate.parameterTypes.isEmpty()
            } ?: return@runCatching emptyList()
            val sparse = method.invoke(auto)
            if (sparse !is android.util.SparseArray<*>) return@runCatching emptyList()
            val out = ArrayList<WindowOnDisplay>()
            for (index in 0 until sparse.size()) {
                val list = sparse.valueAt(index) as? List<*> ?: continue
                list.forEach { item ->
                    if (item is AccessibilityWindowInfo) {
                        val reported = windowDisplayId(item)
                        out += WindowOnDisplay(
                            displayId = reported,
                            window = item,
                        )
                    }
                }
            }
            out
        }.getOrNull().orEmpty()
        if (viaAll.isNotEmpty()) return viaAll
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            auto.javaClass.methods.firstOrNull { method ->
                method.name == "getWindows" && method.parameterTypes.isEmpty()
            }?.invoke(auto) as? List<AccessibilityWindowInfo>
        }.getOrNull().orEmpty().map { window ->
            WindowOnDisplay(displayId = windowDisplayId(window), window = window)
        }
    }

    private fun windowDisplayId(window: AccessibilityWindowInfo): Int {
        return runCatching {
            val method = window.javaClass.methods.firstOrNull {
                it.name == "getDisplayId" && it.parameterTypes.isEmpty()
            }
            method?.invoke(window) as? Int
        }.getOrNull() ?: Display.DEFAULT_DISPLAY
    }

    private fun connectLocked(preferredLooper: Looper?): UiAutomation? {
        return runCatching {
            val connectionClass = Class.forName("android.app.UiAutomationConnection")
            val connection = connectionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val interfaceClass = Class.forName("android.app.IUiAutomationConnection")
            val constructor = UiAutomation::class.java.getDeclaredConstructor(
                Looper::class.java,
                interfaceClass,
            ).apply { isAccessible = true }
            val looper = preferredLooper ?: Looper.getMainLooper() ?: Looper.myLooper()
                ?: error("UiAutomation requires a Looper")
            val created = constructor.newInstance(looper, connection) as UiAutomation
            val dontSuppress = runCatching {
                UiAutomation::class.java.getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null)
            }.getOrDefault(0)
            val connectWithFlags = created.javaClass.methods.firstOrNull { method ->
                method.name == "connect" && method.parameterTypes.size == 1
            }
            if (connectWithFlags != null) {
                connectWithFlags.invoke(created, dontSuppress)
            } else {
                created.javaClass.getMethod("connect").invoke(created)
            }
            // Without FLAG_RETRIEVE_INTERACTIVE_WINDOWS getWindows()/getWindowsOnAllDisplays() come back
            // empty for a bare app_process connection, and every dump silently falls back to the real
            // screen's active window. (A Shizuku-hosted process can get windows without it; app_process cannot.)
            runCatching {
                val info = created.serviceInfo
                if (info != null) {
                    info.flags = info.flags or RetrieveInteractiveWindowsFlag
                    // Chrome (and Chromium WebViews) build the full web-content tree only for a
                    // service that looks like a screen reader; for anything else they expose the
                    // browser chrome and form controls only. Spoken feedback is what they check.
                    // Touch exploration stays off, so gestures keep their normal meaning.
                    info.feedbackType = info.feedbackType or
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN or
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
                    created.serviceInfo = info
                }
            }.onFailure { error ->
                Log.w(LogTag, "UiAutomation window flag not applied: ${error.message?.take(180)}")
            }
            created
        }.onFailure { error ->
            Log.w(LogTag, "UiAutomation connect failed: ${error.message?.take(180)}")
        }.getOrNull()
    }
}
