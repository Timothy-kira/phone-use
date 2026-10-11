package kira.phoneuse.data

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * What agent_display sends to the model: an outline by default, details on request.
 *
 * Every action used to carry the whole screen several times over (interactive JSON, the same list as
 * click_targets, a text copy, the tree, three forms of the change) - 6-15k characters per tap. Now an
 * action answers with a short outline of the new screen; the full screen stays here, and the agent
 * reads exactly what it needs, the way one reads a codebase:
 *
 * - `find {grep, role, region, offset, limit}` - filter the current screen's lines (regex over text,
 *   description, id and hint; role; top/middle/bottom or the subtree of #n; paging);
 * - `read_text {query}` - one element's complete text;
 * - `zoom {query:#n | region}` - a sharp crop of the screen, cut from a full-resolution frame;
 * - `detail:"full"` on any action - the complete payload as before.
 *
 * Nothing is lost: the outline names how much it left out and how to get it.
 */
object AgentDisplayShaper {
    /** Actions whose purpose is reading - their tree is the answer, filtered rather than outlined. */
    private val ReadActions = setOf("dump_tree", "find", "list_targets", "targets", "click_targets", "read_field")

    /** Fields that repeat what the outline / tree already says, or are fixed text. */
    private val Repeats = listOf(
        "interactive", "click_targets", "click_targets_text", "ui_delta", "planning", "gui_learning", "receipt",
        "cluttered", "width", "height", "cursor_x", "cursor_y", "screenshot_mime_type", "display_id",
        "delta_budget",
    )
    private val Layout = Regex(
        "^(FrameLayout|LinearLayout|RelativeLayout|ConstraintLayout|CoordinatorLayout|ViewGroup|View|ScrollView|" +
            "NestedScrollView|HorizontalScrollView|ViewPager2?|RecyclerView|ListView|GridView|CardView|ComposeView|" +
            "AndroidComposeView|DrawerLayout|AppBarLayout|Toolbar|LinearLayoutCompat|ContentFrameLayout|" +
            "ActionBarOverlayLayout|FitWindowsLinearLayout|TableLayout|TableRow)$",
    )
    private val SystemUi = Regex(
        "(status_bar|statusBar|navigation_bar|navigationBar|notification_icon|system_icons|statusIcons|" +
            "notificationIcons|battery|wifi_signal|mobile_signal|clock_container|left_aligned_notification|" +
            "launch_animation_container|ongoing_privacy|heads_up)",
        RegexOption.IGNORE_CASE,
    )
    private val EmptyState = Regex(
        "(could not find|couldn't find|no results|nothing found|no matches|not found|没有找到|未找到|无结果|没有结果)",
        RegexOption.IGNORE_CASE,
    )
    // Negative while a page slides in or out.
    private val Bounds = Regex("\\((-?\\d+),(-?\\d+)\\)-\\((-?\\d+),(-?\\d+)\\)")
    private val Head = Regex("^#(\\d+) \\[([a-z_]+)] ?(.*)$")
    private val Actions = Regex("\\s(tap|long|scroll|focus)\\b")
    private val RoleAliases = mapOf("button" to "btn", "edit" to "input", "field" to "input", "checkbox" to "check",
        "image" to "img", "toggle" to "switch")

    private const val OutlineBudget = 2200
    private const val DefaultFindLimit = 120

    /** The last full screen per lane, for zoom #n and for find without a new capture. */
    private val lastTree = ConcurrentHashMap<String, String>()
    private val lastTreeSnapshotId = ConcurrentHashMap<String, String>()
    private val lastNodes = ConcurrentHashMap<String, List<AgentModeUiTree.RichNode>>()
    private val lastDimensions = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** The last screen fingerprint (snapshot_id) per lane - peek checks it is back where it began. */
    private val lastSnapshotId = ConcurrentHashMap<String, String>()

    fun snapshotId(lane: String): String? = lastTreeSnapshotId[lane]

    private data class Line(val text: String, val index: Int, val role: String, val label: String,
                            val interactive: Boolean, val bounds: IntArray?, val system: Boolean, val noise: Boolean)

    private fun parse(raw: String): Line? {
        val text = raw.trim()
        val m = Head.find(text) ?: return null
        val rest = m.groupValues[3]
        val cut = listOf(Actions.find(" $rest")?.range?.first?.minus(1), rest.indexOf(" hint="), Bounds.find(rest)?.range?.first)
            .filterNotNull().filter { it >= 0 }.minOrNull() ?: rest.length
        val label = rest.substring(0, cut).trim()
        val b = Bounds.find(rest)?.groupValues?.drop(1)?.map { it.toInt() }?.toIntArray()
        val interactive = Actions.containsMatchIn(" $rest") || m.groupValues[2] in setOf("input", "btn", "check", "switch")
        val system = SystemUi.containsMatchIn(rest) || Regex("#clock(?:\\s|$)").containsMatchIn(rest)
        val noise = !interactive && (label.isBlank() || Layout.matches(label) || label.startsWith("#") ||
            (!label.contains(' ') && rest.contains("#$label")))
        return Line(text, m.groupValues[1].toInt(), m.groupValues[2], label, interactive, b, system, noise)
    }

    private fun lines(tree: String): List<Line> =
        tree.replace(AgentModeUiTree.UntrustedGuiOpen, "").replace(AgentModeUiTree.UntrustedGuiClose, "")
            .lines().mapNotNull(::parse)

    /** One argument contract, including substeps, before execution, refs and feedback. */
    fun normalizeArguments(args: JSONObject) {
        if (args.optJSONArray("actions")?.length()?.let { it > 0 } == true) args.put("action", "actions")
        val action = PhoneUiMcp.normalizeAction(args.optString("action"))
        args.put("action", action)
        if (action in setOf("click_node", "read_text", "read", "scroll", "set_progress", "edit_text", "read_field") && args.optString("query").isBlank())
            args.optString("at").takeIf { it.isNotBlank() && it != "center" }?.let { args.put("query", if (it.toIntOrNull() != null) "#$it" else it) }
        if (action in setOf("tap", "click") && args.optString("query").isNotBlank() && !args.has("x")) args.put("at", args.get("query"))
        args.optJSONArray("actions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::normalizeArguments) }
    }

    fun textHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Before running: find / zoom become the plain actions that capture what they need. */
    fun prepare(arguments: JSONObject, lane: String): JSONObject {
        normalizeArguments(arguments)
        val action = arguments.optString("action").trim().lowercase()
        val out = JSONObject(arguments.toString())
        arguments.optJSONArray("actions")?.let { steps ->
            out.put("actions", JSONArray((0 until steps.length()).map { prepare(steps.getJSONObject(it), lane) }))
        }
        if (action in setOf("click_node", "read_text", "read", "edit_text", "read_field") && arguments.optString("query").startsWith("#") && !out.has("snapshot_id")) {
            lastTreeSnapshotId[lane]?.let { out.put("snapshot_id", it) }
        }
        val atNode = arguments.optString("at").trim()
        if (action in setOf("tap", "click") && atNode.matches(Regex("^#\\d+$"))) {
            // Preserve the node and its snapshot instead of turning it into an unguarded pixel tap.
            out.put("action", "click_node").put("query", atNode)
            out.remove("at")
            if (!out.has("snapshot_id")) lastTreeSnapshotId[lane]?.let { out.put("snapshot_id", it) }
            return out
        }
        if (action == "scroll" || (action.startsWith("swipe") && arguments.optString("query").startsWith("#"))) {
            out.put("action", "scroll")
            out.put("direction", arguments.optString("direction").ifBlank {
                action.removePrefix("swipe_").takeIf { it != action } ?: "up"
            })
            if (!out.has("snapshot_id")) lastTreeSnapshotId[lane]?.let { out.put("snapshot_id", it) }
        }
        // at:"center" (the screen's middle) or at:"#n" (that element's middle) instead of x/y - the
        // way to act on an exact spot, e.g. long_press at:center after open_url geo:lat,lon.
        arguments.optString("at").trim().takeIf { it.isNotBlank() && !arguments.has("x") && action !in setOf("edit_text", "read_field") }?.let { at ->
            val point = if (at.equals("center", true)) intArrayOf(500, 500) else
                Regex("^#(\\d+)$").find(at)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { n -> lines(lastTree[lane].orEmpty()).firstOrNull { it.index == n }?.bounds }
                    ?.let { b -> intArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) }
            if (point != null) { out.put("x", point[0]).put("y", point[1]); out.remove("at") }
        }
        when (action) {
            "find", "dump_tree" -> {
                // One full read of the screen; filtering and paging happen on the way out (the
                // capture's own limit would cut the screen before grep sees it).
                out.put("action", "dump_tree")
                if (!arguments.optString("detail").equals("full", true)) out.put("limit", 800)
                listOf("offset", "region", "grep", "role").forEach(out::remove)
                if (action == "find") out.remove("query")
            }
            "zoom" -> {
                out.put("action", "screenshot")
                val query = arguments.optString("query").trim()
                val node = Regex("^#(\\d+)$").find(query)?.groupValues?.get(1)?.toIntOrNull()
                val box = node?.let { n -> lines(lastTree[lane].orEmpty()).firstOrNull { it.index == n }?.bounds }
                if (box != null) {
                    // A little context around the element.
                    val padX = maxOf(20, (box[2] - box[0]) / 6)
                    val padY = maxOf(20, (box[3] - box[1]) / 3)
                    out.put("crop_left", (box[0] - padX).coerceIn(0, 1000))
                    out.put("crop_top", (box[1] - padY).coerceIn(0, 1000))
                    out.put("crop_right", (box[2] + padX).coerceIn(0, 1000))
                    out.put("crop_bottom", (box[3] + padY).coerceIn(0, 1000))
                } else if (!arguments.has("crop_region") && arguments.optString("region").isNotBlank()) {
                    out.put("crop_region", arguments.optString("region"))
                } else if (!arguments.has("crop_region") && !arguments.has("crop_left")) {
                    out.put("crop_region", "middle")
                }
            }
        }
        return out
    }

    /** Labels whose tap commits something; peek never presses them. */
    private val Irreversible = Regex(
        "\\b(delete|remove|send|pay|purchase|buy|order|confirm|submit|save|call|dial|install|uninstall|sign ?out|" +
            "log ?out|reset|erase|clear|post|publish|share)\\b|删除|移除|发送|支付|付款|购买|下单|确认|提交|保存|拨打|呼叫|安装|卸载|退出|重置|清除|发布|分享",
        RegexOption.IGNORE_CASE,
    )

    /** Why [query] must not be peeked (an irreversible-looking or text-entry element), or null. */
    fun peekRefusal(query: String, lane: String): String? {
        val q = query.trim()
        val line = Regex("^#(\\d+)$").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { n -> lines(lastTree[lane].orEmpty()).firstOrNull { it.index == n } }
        val label = line?.label ?: q
        return when {
            line?.role == "input" -> "a text field (peek only previews navigation)"
            Irreversible.containsMatchIn(label) -> "'$label' looks like it commits something"
            else -> null
        }
    }

    /** What the model sees of [result] (a parsed agent_display output) for the call [arguments]. */
    fun shape(result: JSONObject, arguments: JSONObject, lane: String): JSONObject {
        val action = arguments.optString("action").trim().lowercase()
        val canonicalNodes = result.remove("_canonical_nodes") as? JSONArray
        val fullReceipt = JSONObject(result.toString()).apply {
            canonicalNodes?.let { put("nodes", it) }
            keys().asSequence().toList().filter { it.contains("base64", true) || it == "screenshot" }.forEach(::remove)
        }
        if (result.optInt("width") > 0 && result.optInt("height") > 0) lastDimensions[lane] = result.optInt("width") to result.optInt("height")
        val beforeId = lastSnapshotId[lane]
        val beforeTree = lastTree[lane].orEmpty()
        result.optString("snapshot_id").takeIf { it.isNotBlank() }?.let { lastSnapshotId[lane] = it }
        // Keep structured identity before compacting the model's observation.
        val structured = canonicalNodes ?: result.optJSONArray("nodes") ?: result.optJSONArray("click_targets") ?: result.optJSONArray("interactive")
        val rich = runCatching { AgentModePortal.parseNodes(structured) }.getOrDefault(emptyList())
        if (rich.isNotEmpty()) lastNodes[lane] = rich
        val g = graph(lane)
        val navigates = action in NavActions || arguments.optJSONArray("actions")?.let { a ->
            (0 until a.length()).any { a.optJSONObject(it)?.optString("action") in NavActions }
        } == true
        val feedbackTarget = arguments.optJSONObject("_feedback_target")
        val focusOnly = action in setOf("click_node", "click", "tap") &&
            (feedbackTarget?.optBoolean("editable") == true || feedbackTarget?.optBoolean("checkable") == true)
        if (result.optBoolean("ok", true) && navigates && !focusOnly && beforeId != null) g.navigationEpoch++
        // The same list under two names - never useful twice.
        if (result.has("interactive") && result.has("click_targets")) result.remove("interactive")
        // dump_tree / find carry the full node list next to a short text tree: read the full list
        // (grep must see every node), and send only the filtered lines back.
        val fullNodes = if (action in ReadActions && !arguments.optString("detail").equals("full", true))
            result.optJSONArray("nodes")?.takeIf { it.length() > 0 }
                ?.let { runCatching { AgentModePortal.parseNodes(it) }.getOrDefault(emptyList()) }.orEmpty()
                .filter { arguments.optBoolean("include_system") || result.optString("package_name").isBlank() || it.packageName.isBlank() || it.packageName == result.optString("package_name") }
                .joinToString("\n") { AgentModeUiTree.compactLine(it) }
                .also { if (it.isNotBlank()) listOf("nodes", "offset", "limit", "total", "has_more").forEach(result::remove) }
        else ""
        // Some results carry the screen only as a node list (screenshot, list_targets); render it.
        val canonicalTree = rich.filter { arguments.optBoolean("include_system") || result.optString("package_name").isBlank() || it.packageName.isBlank() || it.packageName == result.optString("package_name") }
            .joinToString("\n") { AgentModeUiTree.compactLine(it) }
        val tree = canonicalTree.ifBlank { fullNodes }.ifBlank { result.optString("ui_tree") }.ifBlank { result.optString("click_targets_text") }.ifBlank {
            val nodes = (result.optJSONArray("click_targets") ?: result.optJSONArray("interactive"))
                ?.takeIf { it.length() > 0 && it.optJSONObject(0)?.has("bounds") == true }
                ?.let { runCatching { AgentModePortal.parseNodes(it) }.getOrDefault(emptyList()) }.orEmpty()
            nodes.filter { arguments.optBoolean("include_system") || result.optString("package_name").isBlank() || it.packageName.isBlank() || it.packageName == result.optString("package_name") }
                .filter { it.clickIndex > 0 || it.label.isNotBlank() }
                .joinToString("\n") { AgentModeUiTree.compactLine(it) }
        }.ifBlank {
            // dump_tree answers with the whole node list; render it as lines so grep/role/paging apply.
            if (arguments.optString("detail").equals("full", true)) "" else
                result.optJSONArray("nodes")?.takeIf { it.length() > 0 }
                    ?.let { runCatching { AgentModePortal.parseNodes(it) }.getOrDefault(emptyList()) }.orEmpty()
                    .joinToString("\n") { AgentModeUiTree.compactLine(it) }
                    .also { if (it.isNotBlank()) listOf("nodes", "offset", "limit", "total", "has_more").forEach(result::remove) }
        }
        if (tree.isNotBlank() && (action in ReadActions || tree.length > 200)) {
            lastTree[lane] = tree
            result.optString("snapshot_id").takeIf { it.isNotBlank() }?.let { lastTreeSnapshotId[lane] = it }
        }
        runCatching { recordStep(lane, action, arguments, result, beforeId, lines(tree), lines(beforeTree)) }
        recordEvidence(lane, action, arguments, result, rich)
        retainReceipt(lane, result, fullReceipt)
        if (action in ReadActions || action == "screenshot" || action == "scroll" || action == "scroll_until" || action.startsWith("swipe")) {
            val regions = JSONArray()
            val candidates = lastNodes[lane].orEmpty().filter { it.scrollable && it.enabled &&
                it.right > it.left && it.bottom > it.top && it.packageName == currentScreen(lane)?.pkg }
                .sortedWith(compareByDescending<AgentModeUiTree.RichNode> { (it.right - it.left) * (it.bottom - it.top) }
                    .thenByDescending { it.depth })
                .distinctBy { it.bounds }
                .filter { it.bottom - it.top >= 180 || it.right - it.left >= 180 }
                .sortedByDescending { if (it.bottom - it.top >= 180) 1 else 0 }.take(3)
            candidates.forEach { node ->
                val item = synchronized(g) { g.items.values.firstOrNull { it.container != null && locate(lane, it.id) == node.clickIndex } }
                    ?: pin(lane, "#${node.clickIndex}", currentScreen(lane)?.pkg.orEmpty(), "scroll container", "")
                if (item != null) regions.put(JSONObject().put("ref", item.id).put("node", "#${node.clickIndex}")
                    .put("axis", if (node.bottom - node.top < 180) "horizontal" else if (node.right - node.left < 180) "vertical" else "both")
                    .put("bounds", node.bounds).put("label", node.label.take(80)).put("parent", node.parent)
                    .put("path", AgentModeUiTree.scrollContainerPath(lastNodes[lane].orEmpty(), node)))
            }
            if (regions.length() > 0) result.put("scroll_regions", regions)
        }
        progressFeedback(lane, action, arguments, result, lines(tree))
        runCatching { saveDirty(lane)?.let { result.put("memory_saved", it) } }
        result.put("phone_time", phoneTime())
        if (arguments.optString("detail").equals("full", true)) { canonicalNodes?.let { result.put("nodes", it) }; return result }

        // Keep full evidence behind existing refs, while returning one compact observation.
        listOf("layout_stability", "target_stability").forEach { k -> result.optJSONObject(k)?.remove("samples") }
        result.optJSONObject("progress_feedback")?.optJSONObject("current_step")?.optJSONObject("obstacle")?.let { obstacle ->
            obstacle.optJSONObject("target_stability")?.remove("samples")
        }
        Repeats.forEach(result::remove)
        result.remove("ui_tree")
        if (result.optString("consistent") == "yes") result.remove("consistent")
        // The +/- lines (#n, bounds) are the only screen a light gesture receipt carries: drop them
        // only when an outline replaces them.
        if (result.has("tree_diff") && tree.isNotBlank()) result.remove("ui_delta_text")
        result.optJSONArray("windows")?.let { if (it.length() <= 1) result.remove("windows") }
        result.optString("stdout").let { if (it.isBlank() || it == "ok" || it.contains(AgentModeUiTree.UntrustedGuiOpen)) result.remove("stdout") }
        result.optJSONObject("tree_diff")?.let { diff ->
            val keep = JSONObject()
            listOf("summary", "changed", "value_changed", "state_changed", "visual", "new_window", "window_closed",
                "package_before", "package_after", "scrolled").forEach { k -> if (diff.has(k)) keep.put(k, diff.get(k)) }
            listOf("added", "removed").forEach { k ->
                diff.optJSONArray(k)?.let { arr ->
                    if (arr.length() > 0) keep.put(k, JSONArray((0 until minOf(arr.length(), 8)).map { arr.opt(it) }))
                    if (arr.length() > 8) keep.put("${k}_more", arr.length() - 8)
                }
            }
            result.put("tree_diff", keep)
        }
        if (tree.isBlank()) return result

        val all = lines(tree)
        val includeSystem = arguments.optBoolean("include_system", false)
        if (action in ReadActions) {
            val picked = filter(all, arguments, includeSystem)
            val offset = arguments.optInt("offset", 0).coerceAtLeast(0)
            val limit = arguments.optInt("limit", DefaultFindLimit).coerceIn(1, 800)
            val page = picked.drop(offset).take(limit)
            result.put("ui_tree", AgentModeUiTree.wrapUntrustedGuiContent(page.joinToString("\n") { it.text }))
            result.put("lines", JSONObject()
                .put("matched", picked.size).put("returned", page.size).put("offset", offset)
                .put("screen_total", all.size)
                .apply { if (offset + page.size < picked.size) put("next_offset", offset + page.size) })
            return result
        }

        // Any other action: an outline of the screen it left behind.
        val shown = all.filter { !it.noise && (includeSystem || !it.system) }
        val out = StringBuilder()
        var used = 0
        for (line in shown) {
            if (out.length + line.text.length + 1 > OutlineBudget) break
            out.append(line.text).append('\n')
            used++
        }
        result.put("screen", AgentModeUiTree.wrapUntrustedGuiContent(out.toString().trimEnd()))
        result.put("screen_info", JSONObject()
            .put("lines_shown", used)
            .put("lines_total", all.size)
            .put("interactive", all.count { it.interactive })
            .put("layout_or_system_hidden", all.size - shown.size)
            .apply {
                // A list shows only the rows on screen; name the ones that hold more.
                val more = all.filter { it.text.contains("state=more") }.map { "#${it.index}" }
                if (more.isNotEmpty()) put("lists_with_more", "${more.joinToString(" ")}: only part of the list is on screen - scroll to see the rest")
                // An empty result is usually a dead end only for the query, not the screen: name the
                // buttons it offers (e.g. "Increase search radius") so they get tried.
                if (shown.any { EmptyState.containsMatchIn(it.label) }) {
                    val offers = shown.filter { it.interactive && it.label.isNotBlank() && it.role in setOf("btn", "text") }
                        .map { "#${it.index} ${it.label.take(40)}" }.take(6)
                    if (offers.isNotEmpty()) put("next_steps", "No results here. This screen offers: ${offers.joinToString("; ")} - try them (peek {query:#n} previews one).")
                }
            }
            .apply {
                if (used < shown.size) {
                    put("more", "${shown.size - used} more lines: find {grep, role, region, offset} reads them; " +
                        "read_text {query:#n} gives one element's full text; zoom {query:#n} a sharp crop.")
                }
            })
        return result
    }

    private fun filter(all: List<Line>, args: JSONObject, includeSystem: Boolean): List<Line> {
        var pick = all.filter { includeSystem || !it.system }
        // Pure layout lines carry nothing to read; keep them only for a full dump.
        if (args.optString("action") == "find" || args.has("grep") || args.has("role")) pick = pick.filter { !it.noise }
        args.optString("grep").takeIf { it.isNotBlank() }?.let { pattern ->
            val options = if (args.optBoolean("ignoreCase", true)) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val re = runCatching { Regex(pattern, options) }.getOrElse { Regex(Regex.escape(pattern), options) }
            pick = pick.filter { re.containsMatchIn(it.text) }
        }
        args.optString("role").takeIf { it.isNotBlank() }?.let { roles ->
            val wanted = roles.lowercase().split(',', '|', ' ').map { it.trim() }.filter { it.isNotBlank() }
                .map { RoleAliases[it] ?: it }.toSet()
            pick = pick.filter { line -> line.role in wanted || ("interactive" in wanted && line.interactive) }
        }
        val region = args.optString("region").trim().lowercase()
        when {
            region.startsWith("#") -> {
                val anchor = region.drop(1).toIntOrNull()?.let { n -> all.firstOrNull { it.index == n } }?.bounds
                if (anchor != null) pick = pick.filter { l -> l.bounds?.let { inside(it, anchor) } == true }
            }
            region in setOf("top", "upper") -> pick = pick.filter { (it.bounds?.get(1) ?: 0) < 334 }
            region in setOf("middle", "mid", "center") -> pick = pick.filter { (it.bounds?.get(1) ?: 0) in 333..666 }
            region in setOf("bottom", "bot", "lower") -> pick = pick.filter { (it.bounds?.get(1) ?: 0) > 666 }
        }
        return pick
    }

    private fun inside(b: IntArray, anchor: IntArray): Boolean =
        b[0] >= anchor[0] && b[1] >= anchor[1] && b[2] <= anchor[2] && b[3] <= anchor[3]

    // --- references: a small graph the agent can point into ---------------------------------
    //
    // `#n` only names an element on the current screen; after a scroll or a new page it names
    // something else, and two rows with the same title cannot be told apart at all. The graph keeps
    // what was seen under stable names: screens (@sN), the actions between them (@aN), list rows by
    // position (@rN) and pinned elements (@pN). Any of them can carry a note, and any `query`/`at`
    // may name an item - the gateway brings it on screen and turns it back into `#n`.

    /** One row of a list page: its label and the `#n` it has on this screen. */
    data class Row(val label: String, val index: Int)

    /** The rows of one scrollable list on the current screen. [more]: the list holds rows below. */
    data class ListPage(val box: IntArray?, val rows: List<Row>, val more: Boolean, val behind: Boolean, val node: Int? = null)

    class Item(
        val id: String, val label: String, val screen: String, val listKey: String?, var ordinal: Int?,
        val before: String?, val after: String?, var note: String = "", var text: String = "",
        var containerId: String = "", var container: JSONObject? = null,
    )
    class ScreenNode(val id: String, val pkg: String, var title: String, val sig: String, var note: String = "")
    class ActionNode(val id: String, val what: String, val target: String, val from: String?, var to: String?,
                     val ok: Boolean, var note: String = "", var facts: JSONObject? = null, var evidenceSeq: Long = 0, var receipt: JSONObject? = null)

    class Graph {
        val screens = LinkedHashMap<String, ScreenNode>()          // screen signature -> node
        val alias = LinkedHashMap<String, String>()                 // snapshot_id -> signature
        val actions = ArrayList<ActionNode>()
        val items = LinkedHashMap<String, Item>()                   // @r/@p id -> item
        val lists = HashMap<String, MutableList<String>>()
        val collections = LinkedHashMap<String, JSONObject>() // bounded list reading cursors, same session graph          // list key -> labels by position
        var counter = 0
        var goal = ""
        var taskDate = ""
        var taskTime = ""
        var checkpoints = JSONArray()
        var evidenceSeq = 0L
        var checkpointSeq = 0L
        var lastMutationSeq = 0L
        var taskStartedSeq = 0L
        val unknownOperations = LinkedHashSet<String>()
        var feedback = JSONObject()
        var observedContent = ""
        var repeatCount = 0
        var lastIneffectiveAction = ""
        var unverifiedActions = 0
        val readQuestions = HashSet<String>()
        val verifiedWrites = LinkedHashSet<String>()
        var volatileScreen = ""
        val volatileControls = LinkedHashSet<String>()
        val seenStates = LinkedHashSet<String>()
        var navigationEpoch = 0
        var blockedAction = ""
        var recoveryUsed = false
        var externalObstacle = ""
        var dirty = false
        fun next(prefix: String) = "@$prefix${++counter}"
    }

    private val graphs = ConcurrentHashMap<String, Graph>()

    /** Per lane: the key of the graph that recorded a step last. */
    private val lastActive = ConcurrentHashMap<String, String>()

    /** The session the current call belongs to (set by the gateway); graphs persist per session and lane. */
    val session: ThreadLocal<String> = ThreadLocal.withInitial { "" }

    private fun graphKey(lane: String) = session.get().ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_") + "~" + lane

    fun graph(lane: String): Graph = graphs.getOrPut(graphKey(lane)) { loadGraph(graphKey(lane)) ?: Graph() }

    /** Task state lives with the same session/lane graph as screen refs, not in a second planner. */
    fun taskState(lane: String, args: JSONObject): JSONObject {
        val g = graph(lane)
        return synchronized(g) {
            when (args.optString("action")) {
                "begin" -> {
                    val steps = args.optJSONArray("steps") ?: JSONArray().put(args.optString("goal"))
                    if (steps.length() !in 1..12 || (0 until steps.length()).any { steps.optString(it).trim().isBlank() })
                        return@synchronized JSONObject().put("ok", false).put("errmsg", "steps must contain 1..12 nonblank small goals, or be omitted.")
                    g.goal = args.optString("goal").take(400)
                    g.taskStartedSeq = g.evidenceSeq; g.checkpointSeq = g.evidenceSeq; g.lastMutationSeq = g.evidenceSeq
                    g.taskTime = phoneTime()
                    g.taskDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    g.checkpoints = JSONArray((0 until steps.length()).map { i -> JSONObject()
                        .put("step", i + 1).put("goal", steps.optString(i).take(200)).put("status", "pending") })
                    g.observedContent = ""; g.repeatCount = 0; g.lastIneffectiveAction = ""; g.unverifiedActions = 0; g.readQuestions.clear(); g.verifiedWrites.clear(); g.volatileControls.clear(); g.volatileScreen = ""; g.seenStates.clear(); g.feedback = JSONObject()
                    g.blockedAction = ""; g.recoveryUsed = false; g.externalObstacle = ""
                    g.dirty = true
                }
                "checkpoint" -> {
                    val step = args.optInt("step")
                    val status = args.optString("status")
                    val evidence = args.optString("report").trim()
                    val next = (0 until g.checkpoints.length()).firstOrNull { g.checkpoints.optJSONObject(it).optString("status") != "done" }
                    if (next == null || step != next + 1 || status !in setOf("done", "blocked") || evidence.isBlank())
                        return@synchronized JSONObject().put("ok", false).put("code", "INVALID_CHECKPOINT")
                            .put("errmsg", "Update only the current step with status=done|blocked and observed evidence in report.")
                    val unresolved = g.checkpoints.getJSONObject(next).optJSONObject("obstacle")
                    if (status == "done" && unresolved?.optBoolean("unresolved") == true && unresolved.optJSONObject("recovery_observation") == null)
                        return@synchronized JSONObject().put("ok", false).put("code", "UNRESOLVED_OBSTACLE")
                            .put("errmsg", "The latest blocked action has no verified recovery. Inspect progress and verify an actual UI result before completing this checkpoint.")
                            .put("obstacle", JSONObject(unresolved.toString()))
                    val verified = if (status == "done") verifyChecks(g, args.optJSONArray("checks")) else null
                    if (verified != null && !verified.optBoolean("verified")) {
                        g.feedback = JSONObject().put("signal", "checkpoint_unverified").put("score", -1).put("verification", verified)
                        g.dirty = true; saveDirty(lane)
                        return@synchronized JSONObject().put("ok", false).put("code", verified.optString("code"))
                            .put("verification", verified).put("progress_feedback", JSONObject(g.feedback.toString()))
                            .put("errmsg", when (verified.optString("code")) {
                                "EVIDENCE_MISSING" -> "check.ref must be the tool's evidence_ref @aN, not #node or UI text. For node use ref=@aN, query=#n, expected={text:requiredValue}."
                                "BAD_CHECK" -> "Use kind=node with ref=@aN, query=#n and expected field object; text_equal requires complete raw text and an expected string or source receipt refs."
                                "EVIDENCE_NOT_FRESH" -> "This receipt predates the current step. Obtain new tool evidence after the previous checkpoint; do not backfill a plan from one observation."
                                "EVIDENCE_SUPERSEDED" -> "A later action changed the result after this receipt. Read the current result and check that new fact; historical source refs may still be used as sources."
                                "EVIDENCE_INCOMPLETE" -> "The receipt was truncated or lacks complete raw text. Read a complete value before comparing; numbered display text is not the original."
                                else -> "Observed evidence did not verify this step. Correct the result or obtain a complete fresh receipt; a report alone is not evidence."
                            })
                    }
                    g.checkpoints.getJSONObject(next).put("verification", verified ?: JSONObject().put("verified", false))
                        .put("status", status).put("evidence", evidence.take(1000))
                        .put("screen", currentScreen(lane)?.id.orEmpty())
                    if (status == "done") {
                        unresolved?.put("unresolved", false)?.put("resolved_by", "evidenced_checkpoint_after_recovery")
                        g.checkpointSeq = g.evidenceSeq
                        g.repeatCount = 0; g.blockedAction = ""; g.recoveryUsed = false
                        g.readQuestions.clear(); g.verifiedWrites.clear(); g.volatileControls.clear(); g.volatileScreen = ""; g.seenStates.clear(); g.externalObstacle = ""
                        g.feedback = JSONObject().put("signal", "checkpoint_completed").put("score", 1)
                            .put("meaning", "The current step passed structured checks against fresh tool observations.")
                    }
                    g.dirty = true
                }
            }
            val saved = saveDirty(lane)
            JSONObject().put("ok", true).apply { saved?.let { put("memory_saved", it) } }.put("goal", g.goal).put("task_date", g.taskDate).put("task_time", g.taskTime).put("steps", JSONArray(g.checkpoints.toString()))
                .put("current_step", (0 until g.checkpoints.length()).firstOrNull { g.checkpoints.optJSONObject(it).optString("status") != "done" }?.plus(1) ?: 0)
                .put("feedback", JSONObject(g.feedback.toString())).put("unknown_operations", JSONArray(g.unknownOperations.toList()))
                .put("result_changed_after_verification", g.lastMutationSeq > g.checkpointSeq)
        }
    }

    private fun recordEvidence(lane: String, action: String, args: JSONObject, result: JSONObject, nodes: List<AgentModeUiTree.RichNode>) {
        if (args.optBoolean("_internal_observation") || !result.optBoolean("ok", true) || action.isBlank() ||
            (nodes.isEmpty() && !result.has("raw_text") && !result.has("field_raw"))) return
        val g = graph(lane)
        synchronized(g) {
            val ref = result.optString("action_ref")
            val a = g.actions.firstOrNull { it.id == ref } ?: ActionNode(g.next("a"), action,
                args.optString("query"), currentScreen(lane)?.id, currentScreen(lane)?.id, true).also { g.actions += it; result.put("action_ref", it.id) }
            if (a.facts != null) return
            val facts = JSONObject().put("live", true).put("package_name", result.optString("package_name"))
                .put("snapshot_id", result.optString("snapshot_id")).put("coverage", "visible")
            listOf("raw_text", "complete", "text_sha256", "total_chars", "field_raw", "field_complete", "submission_verified").forEach { k ->
                if (result.has(k)) facts.put(k, result.get(k))
            }
            val compact = JSONArray(); var bytes = facts.toString().length
            for (node in nodes) {
                if (node.password) continue
                val value = AgentModePortal.toNodeJson(node)
                val keep = setOf("i", "index", "label", "text", "desc", "id", "class", "state", "value", "selected", "checked", "enabled", "editable", "clickable", "scrollable", "focused", "parent", "depth", "bounds", "left", "top", "right", "bottom", "range_value", "range_min", "range_max", "package_name", "window_id", "window_index")
                value.keys().asSequence().toList().filter { it !in keep }.forEach(value::remove)
                if (bytes + value.toString().length > 65536) break
                compact.put(value); bytes += value.toString().length
            }
            facts.put("nodes", compact).put("nodes_complete", compact.length() == nodes.count { !it.password })
            a.facts = facts; a.evidenceSeq = ++g.evidenceSeq
            if (action !in ReadActions && action !in setOf("read", "read_text", "screenshot", "zoom", "wait_idle", "status", "recap", "map", "refs", "pin", "note") && result.optBoolean("dispatched", true))
                g.lastMutationSeq = a.evidenceSeq
            result.put("evidence_ref", a.id)
            val kept = g.actions.filter { it.facts != null }
            kept.dropLast(64).forEach { it.facts = null }
            while (g.actions.size > 400) g.actions.removeAt(0)
            g.dirty = true
        }
    }

    private fun retainReceipt(lane: String, result: JSONObject, receipt: JSONObject) {
        val g = graph(lane)
        synchronized(g) {
            val node = g.actions.firstOrNull { it.id == result.optString("action_ref") } ?: return
            if (node.receipt == null) node.receipt = if (receipt.toString().length <= 256000) receipt
                else JSONObject().put("complete", false).put("code", "RECEIPT_DETAIL_TRUNCATED")
            g.actions.filter { it.receipt != null }.dropLast(64).forEach { it.receipt = null }
            g.dirty = true
        }
    }

    /** Keep each batch child's actual facts behind the same immutable @a receipt references. */
    fun retainBatchStep(lane: String, args: JSONObject, result: JSONObject) {
        val g = graph(lane)
        synchronized(g) {
            val a = ActionNode(g.next("a"), args.optString("action"), args.optString("query"),
                currentScreen(lane)?.id, currentScreen(lane)?.id, result.optBoolean("ok", true))
            g.actions += a; result.put("action_ref", a.id)
            val canonical = result.optJSONArray("_canonical_nodes") ?: result.optJSONArray("nodes")
            recordEvidence(lane, args.optString("action"), args, result, AgentModePortal.parseNodes(canonical))
            retainReceipt(lane, result, JSONObject(result.toString()).apply {
                remove("_canonical_nodes")
                canonical?.let { put("nodes", it) }
                keys().asSequence().toList().filter { it.contains("base64", true) || it == "screenshot" }.forEach(::remove)
            })
        }
    }

    /** Copy exact source facts without a model/host string round-trip that can trim whitespace. */
    fun resolveTextSourceRefs(lane: String, args: JSONObject): JSONObject? {
        if (!args.has("source_refs")) return null
        fun fail(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("dispatched", false).put("errmsg", message)
        val refs = args.optJSONArray("source_refs") ?: return fail("INVALID_ARGUMENTS", "source_refs must be an array of complete text receipt references.")
        if (args.has("text") || refs.length() !in 1..12 || args.optString("edit_action") !in setOf("set", "insert", "replace"))
            return fail("INVALID_ARGUMENTS", "Use text OR 1..12 source_refs for set/insert/replace, never both.")
        val g = graph(lane)
        synchronized(g) {
            val parts = ArrayList<String>()
            for (i in 0 until refs.length()) {
                val source = g.actions.firstOrNull { it.id == refs.optString(i) } ?: return fail("EVIDENCE_MISSING", "Missing source text receipt ${refs.optString(i)}.")
                val fact = source.facts ?: return fail("EVIDENCE_EXPIRED", "The source text receipt expired; read it again.")
                if (source.evidenceSeq <= g.taskStartedSeq || !fact.optBoolean("live")) return fail("EVIDENCE_EXPIRED", "Source text belongs to an earlier task.")
                val raw = when {
                    fact.has("raw_text") && fact.optBoolean("complete") -> fact.optString("raw_text")
                    fact.has("field_raw") && fact.optBoolean("field_complete") -> fact.optString("field_raw")
                    else -> return fail("EVIDENCE_INCOMPLETE", "Source text was truncated; never reconstruct original text from numbered display lines.")
                }
                parts += raw
            }
            args.put("text", parts.joinToString(args.optString("separator")))
        }
        return null
    }

    /** Checks are evaluated over immutable actual receipts, never caller-supplied actual values. */
    private fun verifyChecks(g: Graph, checks: JSONArray?): JSONObject {
        fun fail(code: String, index: Int = -1) = JSONObject().put("verified", false).put("code", code).put("failed_check", index)
        if (checks == null || checks.length() !in 1..12) return fail("CHECKS_REQUIRED")
        val checked = JSONArray()
        for (i in 0 until checks.length()) {
            val c = checks.optJSONObject(i) ?: return fail("BAD_CHECK", i)
            val actual = g.actions.firstOrNull { it.id == c.optString("ref") } ?: return fail("EVIDENCE_MISSING", i)
            val f = actual.facts ?: return fail("EVIDENCE_EXPIRED", i)
            if (!f.optBoolean("live") || actual.evidenceSeq <= g.checkpointSeq) return fail("EVIDENCE_NOT_FRESH", i)
            if (actual.evidenceSeq < g.lastMutationSeq) return fail("EVIDENCE_SUPERSEDED", i)
            fun text(facts: JSONObject): String? = when {
                facts.has("raw_text") && facts.optBoolean("complete") -> facts.optString("raw_text")
                facts.has("field_raw") && facts.optBoolean("field_complete") -> facts.optString("field_raw")
                else -> null
            }
            val ok = when (c.optString("kind")) {
                "package" -> c.optString("expected").isNotBlank() && f.optString("package_name") == c.optString("expected")
                "node" -> {
                    val nodes = AgentModePortal.parseNodes(f.optJSONArray("nodes"))
                    val node = AgentModePortal.resolveTarget(nodes, c.optString("query")) ?: return fail("EVIDENCE_NODE_MISSING", i)
                    val observed = AgentModePortal.toNodeJson(node).apply { if (node.hasRange) put("value", node.rangeValue) }
                    val expected = c.optJSONObject("expected") ?: return fail("BAD_CHECK", i)
                    val keys = expected.keys().asSequence().toList()
                    if (keys.isEmpty() || keys.any { it !in setOf("text", "label", "desc", "id", "class", "selected", "checked", "enabled", "state", "value") }) return fail("BAD_CHECK", i)
                    keys.all { observed.has(it) && observed.get(it).toString() == expected.get(it).toString() }
                }
                "text_equal", "text_concat" -> {
                    val value = text(f) ?: return fail("EVIDENCE_INCOMPLETE", i)
                    val sources = c.optJSONArray("sources")
                    if (c.has("sources") && sources == null) return fail("BAD_CHECK", i)
                    if (sources != null && c.has("expected")) return fail("BAD_CHECK", i)
                    if (c.optString("kind") == "text_concat" && sources == null) return fail("BAD_CHECK", i)
                    val expected = if (sources != null) {
                        if (sources.length() !in 1..12) return fail("BAD_CHECK", i)
                        val parts = (0 until sources.length()).map { n ->
                            val source = g.actions.firstOrNull { it.id == sources.optString(n) } ?: return fail("EVIDENCE_MISSING", i)
                            if (source.id == actual.id || source.evidenceSeq >= actual.evidenceSeq) return fail("EVIDENCE_NOT_INDEPENDENT", i)
                            if (source.evidenceSeq <= g.taskStartedSeq || source.facts?.optBoolean("live") != true) return fail("EVIDENCE_EXPIRED", i)
                            text(source.facts!!) ?: return fail("EVIDENCE_INCOMPLETE", i)
                        }
                        parts.joinToString(c.optString("separator"))
                    } else if (c.has("expected") && c.get("expected") is String) c.getString("expected") else return fail("BAD_CHECK", i)
                    value == expected
                }
                "count", "order" -> {
                    if (c.optString("scope") != "visible" || !f.optBoolean("nodes_complete")) return fail("EVIDENCE_INCOMPLETE", i)
                    val selector = c.optJSONObject("selector") ?: return fail("BAD_CHECK", i)
                    val keys = selector.keys().asSequence().toList()
                    if (keys.isEmpty() || keys.any { it !in setOf("id", "class", "text", "desc", "label") }) return fail("BAD_CHECK", i)
                    val nodes = f.optJSONArray("nodes") ?: JSONArray()
                    val picked = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.filter { n ->
                        keys.all { n.optString(it) == selector.optString(it) }
                    }
                    if (c.optString("kind") == "count") c.has("expected") && picked.size == c.optInt("expected", -1)
                    else {
                        val expected = c.optJSONArray("expected") ?: return fail("BAD_CHECK", i)
                        val field = c.optString("field").ifBlank { "text" }
                        if (field !in setOf("text", "label", "desc", "id", "state")) return fail("BAD_CHECK", i)
                        picked.map { it.optString(field) } == (0 until expected.length()).map { expected.optString(it) }
                    }
                }
                else -> return fail("BAD_CHECK", i)
            }
            if (!ok) return fail("CHECK_MISMATCH", i)
            checked.put(JSONObject(c.toString()))
        }
        return JSONObject().put("verified", true).put("checks", checked).put("evidence_seq", g.evidenceSeq)
    }

    /** Validate before a ref read or gesture; native scroll does not accept browser scroll parameters. */
    fun validateArguments(args: JSONObject): JSONObject? {
        val action = args.optString("action").lowercase()
        if (args.has("actions")) {
            val steps = args.optJSONArray("actions")
            if (steps == null || steps.length() !in 1..40) return JSONObject().put("ok", false).put("code", "BAD_ARGS").put("dispatched", false).put("errmsg", "actions must contain 1..40 action objects.")
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: return JSONObject().put("ok", false).put("code", "BAD_ARGS").put("dispatched", false).put("errmsg", "Each batch step must be an action object.")
                validateArguments(step)?.let { return it.put("failed_step", i) }
            }
            return null
        }
        if (args.optString("query").isNotBlank() && args.optString("at").isNotBlank() && args.optString("query") != args.optString("at"))
            return JSONObject().put("ok", false).put("code", "INVALID_ARGUMENTS").put("dispatched", false).put("errmsg", "query and at name different targets; choose one.")
        if (action == "click_node" && args.optString("query").ifBlank { args.optString("target").ifBlank { args.optString("text") } }.isBlank())
            return JSONObject().put("ok", false).put("code", "BAD_ARGS").put("dispatched", false).put("errmsg", "click_node requires a current query/at node or reference.")
        if (action in setOf("edit_text", "read_field")) {
            val edit = args.optString("edit_action")
            val problem = when {
                edit !in setOf("read", "set", "insert", "replace", "delete", "clear", "undo", "submit") -> "Unsupported text action."
                edit in setOf("set", "insert", "replace") && (!args.has("text") || args.opt("text") !is String) -> "This text action requires a string text."
                edit in setOf("replace", "delete") && (!args.has("start") || !args.has("end") || !args.optString("expected_sha256").matches(Regex("[a-fA-F0-9]{64}"))) -> "Range edits require UTF-16 start/end and complete expected_sha256."
                else -> ""
            }
            if (problem.isNotBlank()) return JSONObject().put("ok", false).put("code", "INVALID_ARGUMENTS").put("dispatched", false).put("errmsg", problem)
        }
        if (action == "scroll" || (action.startsWith("swipe") && args.optString("query").isNotBlank())) {
            if (args.has("dy") || args.has("to")) return JSONObject().put("ok", false).put("code", "BAD_ARGS").put("dispatched", false)
                .put("errmsg", "Native scroll accepts query and direction=up|down|left|right; dy and to are browser-only. up advances content; down returns toward the beginning. No gesture sent.")
            if (args.optString("direction").ifBlank { "up" } !in setOf("up", "down", "left", "right"))
                return JSONObject().put("ok", false).put("code", "BAD_ARGS").put("dispatched", false).put("errmsg", "direction must be up, down, left or right. No gesture sent.")
        }
        return null
    }

    fun feedbackTarget(lane: String, args: JSONObject): JSONObject? {
        val query = args.optString("query").ifBlank { args.optString("at") }
        val index = query.removePrefix("#").toIntOrNull() ?: locate(lane, query) ?: return null
        val node = lastNodes[lane].orEmpty().firstOrNull { it.clickIndex == index } ?: return null
        return AgentModePortal.toNodeJson(node).apply { if (node.password) { remove("text"); remove("label"); remove("desc") } }
    }

    fun feedbackKey(lane: String, args: JSONObject): String {
        val query = args.optString("query").ifBlank { args.optString("at") }
        val item = graph(lane).items[query]
        val node = query.removePrefix("#").toIntOrNull()?.let { n -> lastNodes[lane]?.firstOrNull { it.clickIndex == n } }
        val target = item?.container?.let { JSONObject(it.toString()).apply { remove("bounds"); remove("epoch"); remove("window") }.toString() } ?: node?.let {
            if (it.scrollable) containerIdentity(lane, it).apply { remove("bounds"); remove("epoch"); remove("window") }.toString()
            else if (it.id.isNotBlank() && lastNodes[lane].orEmpty().count { n -> n.windowId == it.windowId && n.id == it.id && n.klass == it.klass } == 1)
                "${it.windowId}|${it.packageName}|${it.id}|${it.klass}"
            else "${it.windowId}|${it.packageName}|${it.id}|${it.klass}|${it.label}|${it.bounds}"
        } ?: query
        return listOf(args.optString("action"), target, args.optString("direction").ifBlank { "up" },
            args.optString("grep"), args.optString("role"), args.optString("region"), args.optInt("offset").toString(),
            args.optString("edit_action"), args.optString("position"), args.optString("start"), args.optString("end"), args.optString("expected_sha256"),
            args.optString("text"), args.optString("key"), args.optString("target"), args.optString("to"),
            args.optString("x"), args.optString("y"), args.optInt("offset_lines").toString(),
            args.optInt("limit_lines").toString(), args.optInt("limit").toString()).joinToString("|")
    }

    fun repetitionGate(lane: String, args: JSONObject): JSONObject? {
        // Reading does not dispatch an operation or consume recovery attempts.
        if (args.optString("action") in ReadActions || args.optString("action") in setOf("screenshot", "wait_idle", "zoom", "read_text", "read_field")) return null
        if (args.optString("action") in setOf("status", "recap", "map", "refs", "note", "pin", "teaching_status", "request_teaching", "switch_display", "notification_protection", "back", "home")) return null
        if (args.optString("action") == "key" && args.optString("key").uppercase().removePrefix("KEYCODE_") in
            setOf("BACK", "HOME", "ESCAPE", "WAKEUP", "4", "3", "111", "224")) return null
        val g = graph(lane)
        synchronized(g) {
            val obstacle = (0 until g.checkpoints.length()).mapNotNull { g.checkpoints.optJSONObject(it)?.optJSONObject("obstacle") }
                .lastOrNull { it.optBoolean("unresolved") && it.has("target") && !it.optBoolean("dispatched") &&
                    (it.optString("action") == "click_node" || it.optString("reason") in setOf("target_occluded", "target_changed")) }
            val blocked = obstacle?.optJSONObject("target")
            if (blocked != null) {
                fun hits(args: JSONObject): Boolean {
                    val batch = args.optJSONArray("actions")
                    if (batch != null && (0 until batch.length()).any { batch.optJSONObject(it)?.let(::hits) == true }) return true
                    val dims = lastDimensions[lane]
                    if (args.has("x") && !args.optDouble("x").isFinite() || args.has("y") && !args.optDouble("y").isFinite()) return false
                    val x = args.optDouble("x").let { if (it > 1000 && dims != null) it * 1000 / dims.first else it }.toInt()
                    val y = args.optDouble("y").let { if (it > 1000 && dims != null) it * 1000 / dims.second else it }.toInt()
                    return args.optString("action") in setOf("tap", "click", "double_tap", "long_press") && args.has("x") && args.has("y") &&
                        x in blocked.optInt("left") until blocked.optInt("right") && y in blocked.optInt("top") until blocked.optInt("bottom")
                }
                val original = AgentModePortal.parseNodes(JSONArray().put(blocked)).singleOrNull()
                val live = original?.let { AgentModeUiTree.findSameControl(lastNodes[lane].orEmpty(), it) }
                if (hits(args) && (g.evidenceSeq <= (obstacle?.optLong("observed_evidence_seq", g.evidenceSeq) ?: g.evidenceSeq) ||
                        live == null || AgentModeUiTree.blockingOverlay(live, lastNodes[lane].orEmpty()) != null))
                    return JSONObject().put("ok", false).put("code", "TARGET_STILL_BLOCKED").put("dispatched", false)
                        .put("errmsg", "Coordinates overlap an unresolved blocked node. Observe recovery or choose an explicit recovery control; no click was sent.")
            }
            if (!g.feedback.optBoolean("replan_required")) return null
            if (g.feedback.optBoolean("recovery_ready") && feedbackKey(lane, args) == g.blockedAction) {
                g.feedback.put("recovery_ready", false)
                return null // One retry after a successful recovery and a verified quiet observation.
            }
            if (args.optString("action") !in ReadActions && args.optString("action") !in setOf("screenshot", "wait_idle", "zoom", "read_text") &&
                feedbackKey(lane, args) != g.blockedAction && !g.recoveryUsed) { g.recoveryUsed = true; return null }
            return JSONObject().put("ok", false).put("code", "REPLAN_REQUIRED")
                .put("errmsg", "Repeated actions produced no new semantic evidence. The single distinct recovery attempt is exhausted or this repeats the blocked action. Checkpoint blocked and report the obstacle; no gesture sent.")
                .put("progress_feedback", JSONObject(g.feedback.toString()))
        }
    }

    private fun progressFeedback(lane: String, action: String, args: JSONObject, result: JSONObject, all: List<Line>) {
        if (args.optBoolean("_internal_observation") || action.isBlank() || action in setOf("recap", "map", "refs", "note", "pin", "status", "list_apps", "notification_protection")) return
        val g = graph(lane)
        synchronized(g) {
            // Normalize only observed changing regions and derived labels of unique controls.
            // A rotating caption must not create a new goal state or erase a failed action.
            val nativeNodes = lastNodes[lane].orEmpty()
            val regions = result.optJSONObject("layout_stability")?.optJSONArray("volatile_regions")
            val volatileBounds = (0 until (regions?.length() ?: 0)).mapNotNull { regions?.optJSONObject(it)?.optString("bounds") }.toSet()
            val screenScope = currentScreen(lane)?.id.orEmpty()
            if (g.volatileScreen != screenScope) { g.volatileControls.clear(); g.volatileScreen = screenScope }
            fun volatileKey(node: AgentModeUiTree.RichNode) = "${node.windowId}|${node.id}|${node.klass}|${node.parent}|${node.bounds}"
            nativeNodes.filter { it.bounds in volatileBounds && !it.editable && !it.checkable && !it.hasRange }
                .forEach { g.volatileControls.add(volatileKey(it)) }
            while (g.volatileControls.size > 64) g.volatileControls.remove(g.volatileControls.first())
            val content = all.filter { !it.noise && !it.system }.joinToString("\n") { line ->
                val node = nativeNodes.firstOrNull { it.clickIndex == line.index }
                val uniqueContainer = node != null && node.id.isNotBlank() && !node.editable && node.text.isBlank() && node.desc.isBlank() &&
                    nativeNodes.count { it.windowId == node.windowId && it.id == node.id && it.klass == node.klass } == 1
                val label = if ((node != null && volatileKey(node) in g.volatileControls) || uniqueContainer) "<control:${node?.windowId}:${node?.id}:${node?.klass}>"
                    else rowLabel(line.label).replace(Regex("[0-9.万千]+\\s*(获赞|赞|粉丝|播放|观看|likes|views|followers)", RegexOption.IGNORE_CASE), "<counter>")
                "${line.role}:$label"
            }
            val observed = content.isNotBlank()
            val state = if (observed) sha1("${currentScreen(lane)?.pkg}|$content") else g.observedContent
            val changed = observed && g.observedContent.isNotBlank() && state != g.observedContent
            val novel = observed && g.seenStates.add(state)
            if (g.seenStates.size > 256) g.seenStates.remove(g.seenStates.first())
            val operationId = result.optString("operation_id")
            if (operationId.isNotBlank()) {
                if (result.optBoolean("operation_finished")) g.unknownOperations.remove(operationId)
                else if (result.optString("code") == "STILL_RUNNING") g.unknownOperations.add(operationId)
            }
            val failed = !result.optBoolean("ok", true)
            val cancelled = failed && result.optBoolean("cancel_requested") && result.optBoolean("operation_finished") &&
                result.optString("code") == "OPERATION_STOPPED"
            if (cancelled) {
                // Requested cancellation is a known partial result, not a new app obstruction.
                // Keep steps pending and all prior obstacles; only fresh checks can complete a goal.
                if (observed) g.observedContent = state
                g.feedback = JSONObject(g.feedback.toString()).put("signal", "operation_cancelled_needs_verification").put("score", 0)
                    .put("state_changed", changed).put("same_state_count", g.repeatCount)
                    .put("meaning", "Requested cancellation finished with retained partial progress. Read the actual result; no task step is automatically completed.")
                result.put("progress_feedback", JSONObject(g.feedback.toString()))
                g.dirty = true
                return
            }
            if (failed && result.optString("code") in setOf("BAD_ARGS", "INVALID_ARGUMENTS", "INVALID_TEXT_RANGE", "TEXT_HASH_MISMATCH", "UNDO_FIELD_MISMATCH", "NO_UNDO", "NOT_A_SLIDER", "UNSUPPORTED_ACTION", "SUBMIT_UNSUPPORTED")) {
                result.put("failure_source", "tool").put("dispatched", false)
                g.feedback.put("signal", "parameter_rejected").put("score", -1)
                    .put("meaning", "Correct the arguments; a rejected parameter is not a new app obstacle and does not erase prior recovery evidence.")
                result.put("progress_feedback", JSONObject(g.feedback.toString()))
                g.dirty = true
                return
            }
            val reading = action in ReadActions || action in setOf("screenshot", "zoom", "read_text", "wait_idle")
            val key = args.optString("_feedback_key").ifBlank { feedbackKey(lane, args) }
            val verifiedEdit = !failed && !reading && result.optBoolean("text_verified") && result.optBoolean("field_complete") &&
                g.verifiedWrites.add("$key|${result.optString("field_identity")}|${result.optString("text_sha256")}")
            if (g.verifiedWrites.size > 64) g.verifiedWrites.remove(g.verifiedWrites.first())
            if (verifiedEdit) g.readQuestions.clear() // Permit a fresh independent read of the verified write.
            val newRead = reading && g.readQuestions.add("$state|$key")
            val current = (0 until g.checkpoints.length()).firstOrNull { g.checkpoints.optJSONObject(it).optString("status") != "done" }
                ?: (g.checkpoints.length() - 1).takeIf { it >= 0 && failed && !reading }
            val checkpoint = current?.let { g.checkpoints.getJSONObject(it) }
            val prior = checkpoint?.optJSONObject("obstacle")
            val reason = result.optString("failure_reason").ifBlank { result.optString("code") }
            val obstacleKey = "$key|$reason"
            val firstExternalBlock = failed && obstacleKey != g.externalObstacle && result.optString("failure_source") != "tool"
            if (failed && !reading) {
                val attempts = if (prior?.optString("key") == obstacleKey) prior.optInt("attempts") + 1 else 1
                val obstruction = JSONObject().put("key", obstacleKey).put("code", result.optString("code"))
                    .put("reason", reason).put("source", result.optString("failure_source").ifBlank { "action" })
                    .put("attempts", attempts).put("unresolved", true).put("action", action)
                    .put("action_ref", result.optString("action_ref")).put("screen", currentScreen(lane)?.id.orEmpty())
                    .put("dispatched", result.optBoolean("dispatched", false))
                    .put("field_identity", result.optString("field_identity"))
                    .put("observed_evidence_seq", g.evidenceSeq)
                if (action == "launch") obstruction.put("expected_package", result.optString("expected_package").ifBlank { args.optString("_expected_package").ifBlank { args.optString("target").takeIf { it.contains('.') }.orEmpty() } })
                result.optJSONObject("target_stability")?.let { obstruction.put("target_stability", JSONObject(it.toString())) }
                result.optJSONObject("user_takeover")?.let { obstruction.put("user_takeover", JSONObject(it.toString())) }
                (result.optJSONObject("blocked_target") ?: args.optJSONObject("_feedback_target"))?.let { obstruction.put("target", JSONObject(it.toString())) }
                checkpoint?.put("obstacle", obstruction)?.put("status", "blocked")
                g.blockedAction = key
                g.externalObstacle = obstacleKey
            }
            val unresolved = checkpoint?.optJSONObject("obstacle")?.optBoolean("unresolved") == true
            if (unresolved && !failed && !reading && key != g.blockedAction) {
                checkpoint?.optJSONObject("obstacle")?.put("recovery_action_succeeded", true)
            }
            if (unresolved && !failed && reading && action in setOf("read_text", "read") && result.optInt("total_chars") > 0 && newRead)
                checkpoint?.optJSONObject("obstacle")?.put("recovery_observation", JSONObject().put("action_ref", result.optString("action_ref"))
                    .put("kind", "verified_text_read").put("characters", result.optInt("total_chars")))
            val blockedTarget = checkpoint?.optJSONObject("obstacle")?.optJSONObject("target")?.let {
                AgentModePortal.parseNodes(JSONArray().put(it)).singleOrNull()
            }
            val recoveryTarget = blockedTarget?.let { AgentModeUiTree.findSameControl(nativeNodes, it) }
            val recoveryReady = unresolved && !failed && reading && g.evidenceSeq > (prior?.optLong("observed_evidence_seq") ?: 0L) &&
                checkpoint?.optJSONObject("obstacle")?.optBoolean("recovery_action_succeeded") == true &&
                recoveryTarget != null && AgentModeUiTree.blockingOverlay(recoveryTarget, nativeNodes) == null

            val obstacle = checkpoint?.optJSONObject("obstacle")
            val fieldFailure = obstacle?.optString("action") in setOf("text", "search", "clear_text", "undo", "edit_text")
            val verifiedField = result.optBoolean("text_verified") && result.optBoolean("field_complete") &&
                (!result.has("submission_verified") || result.optBoolean("submission_verified")) &&
                (blockedTarget == null || recoveryTarget != null) &&
                (obstacle?.optString("field_identity").isNullOrBlank() || obstacle?.optString("field_identity") == result.optString("field_identity"))
            val sameTargetChanged = blockedTarget != null && recoveryTarget != null &&
                (blockedTarget.text != recoveryTarget.text || blockedTarget.checked != recoveryTarget.checked ||
                    blockedTarget.rangeText != recoveryTarget.rangeText || blockedTarget.state != recoveryTarget.state)
            val verifiedEffect = !failed && !reading && (if (fieldFailure) verifiedField else
                (key == g.blockedAction || sameTargetChanged) && observed && changed && (!result.has("submission_verified") || result.optBoolean("submission_verified")))
            val launchVerified = unresolved && !failed && observed && obstacle?.optString("action") == "launch" &&
                obstacle.optString("expected_package").let { it.isNotBlank() && it == result.optString("package_name") }
            val previousWindow = blockedTarget?.windowId ?: -1
            val transientAction = obstacle?.optString("action") in setOf("click_node", "tap") &&
                obstacle?.optBoolean("dispatched") == false && previousWindow >= 0
            val superseded = unresolved && !failed && observed && transientAction &&
                nativeNodes.none { it.windowId == previousWindow } &&
                result.optString("package_name").isNotBlank() && result.optString("package_name") == blockedTarget?.packageName &&
                result.optBoolean("layout_stable")
            if (superseded) obstacle?.put("resolution", "Observed app navigation closed the obsolete target window; no extra click required.")
            if (unresolved && (verifiedEffect || launchVerified || superseded)) {
                checkpoint?.optJSONObject("obstacle")?.put("unresolved", false)?.put("resolved_by", result.optString("action_ref"))
                    ?.put("resolution_screen", currentScreen(lane)?.id.orEmpty())
                checkpoint?.put("status", "pending")
                g.externalObstacle = ""; g.repeatCount = 0; g.lastIneffectiveAction = ""; g.unverifiedActions = 0; g.blockedAction = ""; g.recoveryUsed = false
            }
            val stillBlocked = checkpoint?.optJSONObject("obstacle")?.optBoolean("unresolved") == true
            val blockedAttempts = if (stillBlocked) checkpoint?.optJSONObject("obstacle")?.optInt("attempts") ?: 0 else 0
            val visualObservation = !failed && !reading && content.length < 80 && result.optJSONObject("tree_diff")?.optBoolean("visual_changed") == true
            val noProgress = !reading && ((failed && !firstExternalBlock) || (!failed && !verifiedEdit && !novel))
            if (noProgress) {
                g.repeatCount = if (g.lastIneffectiveAction == key) g.repeatCount + 1 else 1
                g.lastIneffectiveAction = key
                g.unverifiedActions++ // Distinct points on a canvas are unknown effects, not verified progress.
            } else if (!reading && !stillBlocked && (novel || verifiedEdit)) {
                g.lastIneffectiveAction = ""; g.unverifiedActions = 0
                g.repeatCount = 0; g.blockedAction = ""; g.recoveryUsed = false
            }
            if (observed) g.observedContent = state
            g.feedback = JSONObject().put("signal", if ((verifiedEffect || launchVerified || superseded) && unresolved) "verified_recovery"
                else if (firstExternalBlock) "external_obstacle" else if (failed) "action_failed" else if (noProgress) "no_new_evidence"
                else if (verifiedEdit) "text_readback_needs_checkpoint" else if (visualObservation) "visual_observation_needs_verification" else if (novel) "new_observation" else "new_read")
                .put("score", if (noProgress) -1 else 0).put("state_changed", changed)
                .put("same_state_count", g.repeatCount).put("blocked_attempts", blockedAttempts).put("recovery_ready", recoveryReady)
                .put("meaning", "Observation and dispatched clicks are not completion; positive feedback requires an evidenced checkpoint after verified recovery.")
            checkpoint?.let { g.feedback.put("current_step", JSONObject(it.toString())) }
            if (g.taskDate.isNotBlank() && Regex("今天|今日|\\btoday\\b", RegexOption.IGNORE_CASE).containsMatchIn(g.goal))
                g.feedback.put("date_scope", g.taskDate).put("scope_hint", "Only this phone date is today for this task. Yesterday's items are outside scope. A verified empty result is a valid answer; do not substitute another date.")
            if (g.repeatCount >= 2 || g.unverifiedActions >= 6 || blockedAttempts >= 3) {
                if (g.blockedAction.isBlank() && !reading) g.blockedAction = key
                g.feedback.put("replan_required", true).put("next", "Inspect the blocker or native failure, then try one distinct grounded recovery. Recurring masks do not justify refreshing or clicking through. For credentials, use the takeover questionnaire. Stay in the requested app.")
            }
            result.put("progress_feedback", JSONObject(g.feedback.toString()))
            g.dirty = true
        }
    }

    private fun containerIdentity(lane: String, node: AgentModeUiTree.RichNode): JSONObject {
        val nodes = lastNodes[lane].orEmpty()
        val uniqueId = node.id.takeIf { id -> id.isNotBlank() && nodes.count { it.id == id } == 1 }.orEmpty()
        val parents = nodes.filter { it.scrollable && it.depth < node.depth && it.windowIndex == node.windowIndex &&
            it.left <= node.left && it.top <= node.top && it.right >= node.right && it.bottom >= node.bottom }
            .sortedBy { it.depth }.joinToString("/") { "${it.klass}:${it.depth}" }
        return JSONObject().put("package", node.packageName).put("window", node.windowIndex).put("window_type", node.windowType)
            .put("window_id", node.windowId).put("class", node.klass).put("id", uniqueId)
            .put("parents", parents).put("path", AgentModeUiTree.scrollContainerPath(nodes, node)).put("depth", node.depth)
            .put("bounds", node.bounds).put("epoch", graph(lane).navigationEpoch)
    }

    private fun currentScreen(lane: String): ScreenNode? {
        val g = graph(lane)
        return g.alias[lastSnapshotId[lane].orEmpty()]?.let { g.screens[it] }
    }

    private val IdToken = Regex("(?<=\\s)#([A-Za-z0-9_][A-Za-z0-9_.]*)")

    /**
     * What a screen is, independent of its content: the app plus the resource ids of its controls
     * (a scrolled list or new data keep it the same screen). Screens without ids fall back to the
     * labels of their first controls.
     */
    private fun signature(pkg: String, all: List<Line>): Pair<String, List<String>> {
        val controls = all.filter { it.interactive && !it.system && !it.noise }
        val ids = controls.flatMap { l -> IdToken.findAll(l.text).map { it.groupValues[1] }.toList() }.distinct().sorted().take(30)
        val basis = if (ids.size >= 2) ids else controls.map { rowLabel(it.label) }.filter { it.isNotBlank() }.take(6).sorted()
        val features = controls.map { rowLabel(it.label) }.filter { it.isNotBlank() }.distinct().take(40)
        // Pages built from one layout (every Settings sub-page) share their ids: the toolbar title tells them apart.
        val sig = sha1("$pkg|${toolbarTitle(all).orEmpty()}|${basis.joinToString(",")}").take(14)
        return sig to features
    }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val TitleId = Regex("(?<=\\s)#[A-Za-z0-9_.]*(toolbar|title|action_bar|actionbar|header)[A-Za-z0-9_.]*", RegexOption.IGNORE_CASE)

    /** A page's own name: static text in a toolbar / title view near the top. */
    private fun toolbarTitle(all: List<Line>): String? =
        all.firstOrNull { !it.interactive && !it.system && it.label.isNotBlank() && !it.noise &&
            (it.bounds?.get(1) ?: 1000) < 300 && TitleId.containsMatchIn(it.text) }?.label?.let(::rowLabel)?.take(60)

    private fun titleOf(all: List<Line>): String =
        toolbarTitle(all) ?: (all.firstOrNull { !it.noise && !it.system && it.label.isNotBlank() && !it.interactive }?.label
            ?: all.firstOrNull { !it.noise && !it.system && it.label.isNotBlank() }?.label).orEmpty().let(::rowLabel).take(60)

    /** The screen node for this screen, created on first sight; also keeps the app's map current. */
    private fun screenNode(lane: String, snapshotId: String, pkg: String, all: List<Line>): ScreenNode? {
        if (snapshotId.isBlank() || all.isEmpty()) return null
        val (sig, features) = signature(pkg, all)
        val title = titleOf(all)
        val g = graph(lane)
        val node = synchronized(g) {
            g.alias[snapshotId] = sig
            if (g.alias.size > 2000) g.alias.remove(g.alias.keys.first())
            g.screens.getOrPut(sig) { g.dirty = true; ScreenNode(g.next("s"), pkg, title, sig) }
                .also { if (it.title.isBlank() && title.isNotBlank()) it.title = title }
        }
        if (pkg.isNotBlank()) runCatching { appMap(pkg)?.see(sig, title, features) }
        return node
    }

    private val NavActions = setOf("tap", "click_node", "click", "long_press", "double_tap", "key", "back", "launch", "open_url", "search", "home", "recents", "switch_display")

    /** The control an action aimed at, named so it can be found again: resource id and label. */
    private fun locatorOf(arguments: JSONObject, before: List<Line>): Pair<String, String>? {
        val query = arguments.optString("query").trim()
        val n = Regex("^#(\\d+)$").find(query)?.groupValues?.get(1)?.toIntOrNull()
        val line = when {
            n != null -> before.firstOrNull { it.index == n }
            arguments.has("x") && arguments.has("y") -> {
                val x = arguments.optInt("x"); val y = arguments.optInt("y")
                before.filter { l -> l.interactive && l.bounds?.let { x in it[0]..it[2] && y in it[1]..it[3] } == true }
                    .minByOrNull { l -> l.bounds!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
            }
            query.isNotBlank() -> before.firstOrNull { rowLabel(it.label).equals(query, true) }
                ?: before.firstOrNull { it.interactive && primaryLabel(it.label).equals(primaryLabel(query), true) }
                ?: return "" to query
            else -> null
        } ?: return null
        val id = IdToken.findAll(line.text).map { it.groupValues[1] }.firstOrNull().orEmpty()
        return id to rowLabel(line.label)
    }

    /** Called from [shape]: name the screen, record the action that led to it, and the map edge. */
    private fun recordStep(lane: String, action: String, arguments: JSONObject, result: JSONObject,
                           before: String?, all: List<Line>, beforeLines: List<Line>) {
        val after = result.optString("snapshot_id")
        val pkg = result.optString("package_name").ifBlank { result.optJSONObject("tree_diff")?.optString("package_after").orEmpty() }
        lastActive[lane] = graphKey(lane)
        if (action == "begin") graph(lane).goal = arguments.optString("goal").take(400)
        // Blank: answers the gateway composed itself (map, refs, goto ...), shaped with detail=full only.
        val passive = action.isBlank() || action in ReadActions || action in setOf("screenshot", "zoom", "read_text", "wait_idle", "refs", "begin", "recap", "map")
        val g = graph(lane)
        val screen = screenNode(lane, after, pkg, all)
        if (screen != null) {
            result.put("screen_ref", screen.id)
            // Gestures answer without a screen: the first read after them says where they led.
            val waiting = pending.remove(lane).orEmpty()
            waiting.forEach { p -> synchronized(g) { p.node.to = screen.id; g.dirty = true } }
            // Only a single gesture since the last known screen is a replayable step.
            waiting.singleOrNull()?.let { p ->
                if (p.pkg == pkg && edge(p.pkg, p.action, p.arguments, p.ok, p.fromSig, screen.sig, p.beforeLines)) result.put("map", "edge recorded")
            }
        }
        if (passive) { screen?.let { lastScreen[lane] = it.sig }; return }
        val target = arguments.optString("query").ifBlank { arguments.optString("target") }
            .ifBlank { arguments.optString("text").take(30) }
            .ifBlank { if (arguments.has("x")) "(${arguments.optInt("x")},${arguments.optInt("y")})" else "" }
        val ok = result.optBoolean("ok", true)
        // The screen the action started on: the last one named, unless gestures since then left it unknown.
        val fromSig = if (pending[lane].isNullOrEmpty()) lastScreen[lane] else null
        val fromNode = fromSig?.let { g.screens[it] }
        val node = synchronized(g) {
            g.dirty = true
            ActionNode(g.next("a"), action, target, fromNode?.id, screen?.id, ok).also {
                g.actions += it
                if (g.actions.size > 400) g.actions.removeAt(0)
            }
        }
        result.put("action_ref", node.id)
        if (screen == null && !ok && !result.optBoolean("dispatched", false)) {
            synchronized(g) { node.to = fromNode?.id; g.dirty = true }
            return // No dispatched gesture: keep the screen and avoid a second fictitious settling wait.
        }
        if (screen == null) {
            pending.getOrPut(lane) { mutableListOf() } += Pending(node, action, JSONObject(arguments.toString()), ok, fromSig,
                pkg.ifBlank { fromNode?.pkg.orEmpty() }, beforeLines)
            lastScreen.remove(lane)
            return
        }
        lastScreen[lane] = screen.sig
        if (edge(pkg, action, arguments, ok, fromSig, screen.sig, beforeLines)) result.put("map", "edge recorded")
    }

    /** A gesture waiting for the next screen read to learn where it led. */
    private class Pending(val node: ActionNode, val action: String, val arguments: JSONObject, val ok: Boolean,
                          val fromSig: String?, val pkg: String, val beforeLines: List<Line>)

    private val pending = ConcurrentHashMap<String, MutableList<Pending>>()

    /** Gestures since the last screen read: that read names where they led, so it must see a settled screen. */
    fun hasPending(lane: String): Boolean = !pending[lane].isNullOrEmpty()

    /** Per lane: the signature of the screen last seen, the start of the next action. */
    private val lastScreen = ConcurrentHashMap<String, String>()

    /**
     * A navigation edge for the app's map: the screen changed, the action can be replayed, and it
     * did not commit anything (send / delete / pay / save ... never become routes).
     */
    private fun edge(pkg: String, action: String, arguments: JSONObject, ok: Boolean, fromSig: String?, toSig: String,
                     beforeLines: List<Line>): Boolean {
        if (!ok || pkg.isBlank() || action !in NavActions) return false
        val kind = when {
            action == "launch" -> "launch"
            action in setOf("key", "back") && (action == "back" || arguments.optString("key").equals("back", true)) -> "back"
            action in setOf("key", "back") -> return false
            else -> action
        }
        if (kind != "launch" && (fromSig == null || fromSig == toSig)) return false
        val loc = if (kind == "back" || kind == "launch") ("" to "") else (locatorOf(arguments, beforeLines) ?: return false)
        if (Irreversible.containsMatchIn(loc.second)) return false
        return runCatching { appMap(pkg)?.edge(if (kind == "launch") null else fromSig, toSig, kind, loc.first, loc.second) }.isSuccess
    }

    private fun listKey(box: IntArray, pkg: String) = "$pkg:${box.joinToString(",")}"

    /**
     * The list on the current screen named by [query] (`#n` of the list), else the one at [box],
     * else the first one that holds more rows. Rows are the list's labelled entries top to bottom.
     */
    fun listPage(lane: String, query: String, box: IntArray?): ListPage {
        val all = lines(lastTree[lane].orEmpty())
        val byQuery = Regex("^#(\\d+)$").find(query.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?.let { n -> all.firstOrNull { it.index == n } }
        val byBox = box?.let { b -> all.filter { it.role == "list" || it.text.contains(" scroll") }
            .minByOrNull { l -> l.bounds?.let { (0..3).sumOf { i -> kotlin.math.abs(it[i] - b[i]) } } ?: Int.MAX_VALUE } }
        val scrollable = all.filter { it.text.contains(" scroll") && it.bounds != null }
        val anchor = byQuery ?: byBox
            ?: scrollable.firstOrNull { it.text.contains("state=more ahead") || it.text.contains("state=more both") }
            ?: scrollable.maxByOrNull { l -> l.bounds!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
        val area = anchor?.bounds ?: return ListPage(null, emptyList(), false, false)
        // Rows fully inside the list (a row cut at an edge reads differently from page to page), not
        // nested scroll containers, labelled by their words (resource-id tokens dropped: they come
        // and go with the row's partly drawn children).
        val inside = all.filter { l ->
            l !== anchor && !l.noise && !l.text.contains(" scroll") && l.bounds?.let { b ->
                inside(b, area) && b[1] > area[1] + 1 && b[3] < area[3] - 1
            } == true
        }.mapNotNull { l -> rowLabel(l.label).takeIf { it.isNotBlank() }?.let { l to it } }
        val rows = (inside.filter { it.first.interactive }.ifEmpty { inside }).map { (l, label) -> Row(label, l.index) }
        val state = anchor.text
        return ListPage(area, rows, state.contains("more ahead") || state.contains("more both"),
            state.contains("more behind") || state.contains("more both"), anchor.index)
    }

    /** A row's words: resource-id tokens (word_word) and widget class names dropped. */
    private fun rowLabel(label: String): String = label.split(' ')
        .filterNot { it.contains('_') || it.matches(Regex("^(ImageButton|ImageView|TextView|LinearLayout|FrameLayout|RelativeLayout|ViewGroup|View)$")) }
        .joinToString(" ").trim()

    /**
     * Appends a page to the list's sequence by its overlap with what was already seen (the longest
     * run at the end of the sequence equal to the start of the page), so rows with the same label
     * stay separate. Returns the position in the sequence of the page's first row.
     */
    fun mergePage(seq: MutableList<String>, page: List<String>): Int {
        for (k in minOf(seq.size, page.size) downTo 1) {
            if (seq.subList(seq.size - k, seq.size) == page.subList(0, k)) {
                val start = seq.size - k
                seq.addAll(page.subList(k, page.size))
                return start
            }
        }
        val start = seq.size
        seq.addAll(page)
        return start
    }

    /** Row items for positions of a collected list, reusing the ids already handed out for them. */
    fun rowItems(lane: String, box: IntArray, pkg: String, seq: List<String>): List<Item> {
        val g = graph(lane)
        val key = listKey(box, pkg)
        val screen = currentScreen(lane)?.id.orEmpty()
        return synchronized(g) {
            g.dirty = true
            g.lists[key] = seq.toMutableList()
            seq.mapIndexed { i, label ->
                g.items.values.firstOrNull { it.listKey == key && it.ordinal == i && it.label == label }
                    ?: Item(g.next("r"), label, screen, key, i, seq.getOrNull(i - 1), seq.getOrNull(i + 1)).also { g.items[it.id] = it }
            }
        }
    }

    /** Pins `#n` of the current screen as @pN, with its neighbours and (in a collected list) its position. */
    fun pin(lane: String, query: String, pkg: String, note: String, text: String): Item? {
        val all = lines(lastTree[lane].orEmpty())
        val n = Regex("^#(\\d+)$").find(query.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val line = all.firstOrNull { it.index == n } ?: return null
        val list = all.filter { l -> (l.role == "list" || l.text.contains(" scroll")) && l.bounds != null && line.bounds != null &&
            inside(line.bounds, l.bounds) }.minByOrNull { l -> l.bounds!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
        val siblings = list?.let { listPage(lane, "#${it.index}", null).rows.map { r -> r.label } }.orEmpty()
        val label = rowLabel(line.label).ifBlank { line.label }
        val at = siblings.indexOf(label)
        val g = graph(lane)
        val key = list?.bounds?.let { listKey(it, pkg) }
        val ordinal = key?.let { k -> g.lists[k] }?.let { seq ->
            // Position in the collected sequence: the occurrence whose neighbours match this screen.
            seq.indices.firstOrNull { i -> seq[i] == label && seq.getOrNull(i - 1) == siblings.getOrNull(at - 1) &&
                seq.getOrNull(i + 1) == siblings.getOrNull(at + 1) }
        }
        val screen = currentScreen(lane)?.id.orEmpty()
        return synchronized(g) {
            g.dirty = true
            Item(g.next("p"), label, screen, key, ordinal, siblings.getOrNull(at - 1), siblings.getOrNull(at + 1), note, text,
                if (line.role == "list") IdToken.findAll(line.text).firstOrNull()?.groupValues?.get(1).orEmpty() else "",
                if (line.role == "list") lastNodes[lane]?.firstOrNull { it.clickIndex == n }?.let { containerIdentity(lane, it) } else null)
                .also { g.items[it.id] = it }
        }
    }

    /**
     * `#n` of item [id] if it is on the current screen: same label, and when several rows share it,
     * the one whose neighbours match. Null when it is not (or not unambiguously) on screen.
     */
    fun locate(lane: String, id: String): Int? {
        val item = graph(lane).items[id] ?: return null
        val all = lines(lastTree[lane].orEmpty())
        val originPkg = graph(lane).screens.values.firstOrNull { it.id == item.screen }?.pkg
            ?: item.listKey?.substringBefore(':')
        if (!originPkg.isNullOrBlank() && originPkg != currentScreen(lane)?.pkg) return null
        item.container?.let { identity ->
            var matches = lastNodes[lane].orEmpty().filter { n ->
                if (!n.scrollable || !n.enabled) false else {
                    val live = containerIdentity(lane, n)
                    listOf("package", "window_type", "class").all { live.opt(it) == identity.opt(it) } &&
                        (!identity.has("epoch") || live.optInt("epoch") == identity.optInt("epoch")) &&
                        (if (identity.optInt("window_id", -1) >= 0) live.optInt("window_id") == identity.optInt("window_id")
                         else live.optInt("window") == identity.optInt("window")) &&
                        (if (identity.has("path")) live.optString("path") == identity.optString("path")
                         else live.optInt("depth") == identity.optInt("depth") && live.optString("parents") == identity.optString("parents")) &&
                        (identity.optString("id").isBlank() || live.optString("id") == identity.optString("id"))
                }
            }
            if (matches.isEmpty()) matches = lastNodes[lane].orEmpty().filter { n ->
                val live = containerIdentity(lane, n)
                n.scrollable && n.enabled && listOf("package", "window_type", "window_id", "class", "parents", "depth", "bounds", "epoch").all { live.opt(it) == identity.opt(it) } &&
                    (identity.optString("id").isBlank() || live.optString("id") == identity.optString("id"))
            }
            // A collapsing toolbar changes the viewport, not the container. Bounds only disambiguate siblings.
            if (matches.size == 1) return matches.single().clickIndex
            return matches.filter { it.bounds == identity.optString("bounds") }.singleOrNull()?.clickIndex
        }
        if (item.containerId.isNotBlank() && item.listKey?.substringBefore(':') == currentScreen(lane)?.pkg) {
            // A container's resource id survives scrolling and a changed screenshot outline.
            // Do not locate it by its child labels or transient screen signature.
            all.filter { it.role == "list" && IdToken.findAll(it.text).any { token -> token.groupValues[1] == item.containerId } }
                .singleOrNull()?.let { return it.index }
        }
        if (item.ordinal == null && item.listKey != null && item.screen == currentScreen(lane)?.id) {
            val box = item.listKey.substringAfterLast(':')
            all.filter { it.role == "list" && it.bounds?.joinToString(",") == box }.singleOrNull()?.let { return it.index }
        }
        val same = all.filter { rowLabel(it.label) == item.label && !it.noise }
        if (same.size <= 1) return same.firstOrNull()?.index
        if (item.before == null && item.after == null) return null
        val rows = all.filter { !it.noise && it.label.isNotBlank() && it.interactive }
        val labelOf = { l: Line? -> l?.let { rowLabel(it.label) } }
        val hit = rows.indices.filter { j -> labelOf(rows[j]) == item.label &&
            (labelOf(rows.getOrNull(j - 1)) == item.before || labelOf(rows.getOrNull(j + 1)) == item.after) }
        return hit.singleOrNull()?.let { rows[it].index }
    }

    /** Which collected position the rows of the current page start at, for a list [key]. */
    fun pageStart(lane: String, key: String, page: List<String>): Int? {
        val seq = graph(lane).lists[key] ?: return null
        if (page.isEmpty()) return null
        return (0..seq.size - page.size).firstOrNull { s -> seq.subList(s, s + page.size) == page }
            ?: (0 until seq.size).firstOrNull { s -> seq.subList(s, seq.size) == page.subList(0, minOf(page.size, seq.size - s)) }
    }

    fun note(lane: String, id: String, note: String): Boolean {
        val g = graph(lane)
        synchronized(g) {
            g.dirty = true
            g.items[id]?.let { it.note = note; return true }
            g.screens.values.firstOrNull { it.id == id }?.let { it.note = note; return true }
            g.actions.firstOrNull { it.id == id }?.let { it.note = note; return true }
        }
        return false
    }

    /** The graph (or one node of it) as JSON for `refs`. */
    fun refsJson(lane: String, id: String, full: Boolean = false): JSONObject {
        val g = graph(lane)
        fun itemJson(it: Item) = JSONObject().put("ref", it.id).put("label", it.label).put("screen", it.screen)
            .apply { it.ordinal?.let { o -> put("position", o + 1) }; if (it.note.isNotBlank()) put("note", it.note)
                if (it.text.isNotBlank()) put("text", it.text.take(400)) }
        synchronized(g) {
            if (id.isNotBlank()) {
                g.items[id]?.let { item ->
                    val via = g.actions.filter { it.to == item.screen }.takeLast(3).map { "${it.id} ${it.what} ${it.target} from ${it.from}" }
                    return itemJson(item).put("ok", true).put("before", item.before.orEmpty()).put("after", item.after.orEmpty())
                        .put("reached_by", JSONArray(via))
                }
                g.screens.values.firstOrNull { it.id == id }?.let { s ->
                    val into = g.actions.filter { it.to == s.id }.takeLast(5).map { "${it.id} ${it.what} ${it.target} from ${it.from}" }
                    val out = g.actions.filter { it.from == s.id }.takeLast(5).map { "${it.id} ${it.what} ${it.target} -> ${it.to}" }
                    return JSONObject().put("ok", true).put("ref", s.id).put("app", s.pkg).put("title", s.title)
                        .put("note", s.note).put("reached_by", JSONArray(into)).put("left_by", JSONArray(out))
                        .put("items", JSONArray(g.items.values.filter { it.screen == s.id }.map { "${it.id} ${it.label.take(50)}" }))
                }
                g.actions.firstOrNull { it.id == id }?.let { a ->
                    val facts = JSONObject(a.facts?.toString() ?: "{}")
                    val receipt = JSONObject(a.receipt?.toString() ?: "{}")
                    if (!full) {
                        facts.optJSONArray("nodes")?.let { facts.put("node_count", it.length()) }
                        listOf("nodes", "raw_text", "field_raw").forEach(facts::remove)
                        val keep = setOf("ok", "code", "errmsg", "dispatched", "used_backend", "snapshot_id", "package_name",
                            "operation_id", "operation_finished", "cancel_requested", "completed_steps", "running_step",
                            "tree_diff", "layout_stability", "target_stability", "failure_reason", "failure_source",
                            "text_verified", "text_sha256", "complete", "field_complete", "total_chars", "field_identity",
                            "input_service_restored", "input_service_released")
                        receipt.keys().asSequence().toList().filter { it !in keep }.forEach(receipt::remove)
                        listOf("layout_stability", "target_stability").forEach { receipt.optJSONObject(it)?.remove("samples") }
                    }
                    return JSONObject().put("ok", true).put("ref", a.id).put("action", a.what).put("target", a.target)
                        .put("from", a.from).put("to", a.to).put("succeeded", a.ok).put("note", a.note)
                        .put("evidence", facts).put("evidence_seq", a.evidenceSeq).put("receipt", receipt)
                        .put("detail", if (full) "full" else "compact")
                        .apply { if (!full) put("more", "refs query:$id detail:full returns the retained complete facts and receipt.") }
                }
                return JSONObject().put("ok", false).put("code", "NO_SUCH_REF").put("errmsg", "$id is not in this lane's references.")
            }
            return JSONObject().put("ok", true)
                .put("items", JSONArray(g.items.values.toList().takeLast(200).map(::itemJson)))
                .put("screens", JSONArray(g.screens.values.toList().takeLast(40).map { "${it.id} ${it.pkg} · ${it.title}" + if (it.note.isNotBlank()) " - ${it.note}" else "" }))
                .put("recent_actions", JSONArray(g.actions.takeLast(25).map { "${it.id} ${it.what} ${it.target} ${it.from}->${it.to}" + if (it.note.isNotBlank()) " - ${it.note}" else "" }))
        }
    }

    // --- app maps: the long-term memory of each app's screens --------------------------------
    //
    // Per app version and per phone: screens by signature with what each offers, and the navigation
    // edges between them (by resource id / label, never coordinates or #n). Used by map / goto /
    // explore, kept across sessions, and matched to the phone and the app version on load.

    /** Where maps and session graphs live on the phone (set by PhoneUseRuntime). */
    @Volatile var memoryRoot: java.io.File? = null

    /** The installed version of a package: versionCode to versionName (set by PhoneUseRuntime). */
    @Volatile var appVersion: (String) -> Pair<Long, String>? = { null }

    /** The phone environment a map belongs to (set by PhoneUseRuntime: model, build, display, locale). */
    @Volatile var deviceInfo: JSONObject = JSONObject()

    val deviceKey: String get() = sha1(deviceInfo.toString()).take(12)

    class MapScreen(val sig: String, var title: String, val features: LinkedHashSet<String>, var seen: Int = 0)
    class MapEdge(val from: String?, val to: String, val kind: String, val id: String, val label: String,
                  var ok: Int = 1, var last: Long = System.currentTimeMillis(), var verified: Boolean = true)

    class AppMap(val pkg: String, val versionCode: Long, val versionName: String, var status: String) {
        val screens = LinkedHashMap<String, MapScreen>()
        val edges = ArrayList<MapEdge>()
        @Volatile var dirty = false

        fun see(sig: String, title: String, features: List<String>) = synchronized(this) {
            val sc = screens.getOrPut(sig) { dirty = true; MapScreen(sig, title, LinkedHashSet()) }
            sc.seen++
            if (sc.title.isBlank() && title.isNotBlank()) { sc.title = title; dirty = true }
            val before = sc.features.size
            features.forEach { if (sc.features.size < 60) sc.features.add(it) }
            if (sc.features.size != before) dirty = true
        }

        fun edge(from: String?, to: String, kind: String, id: String, label: String) = synchronized(this) {
            val same = edges.firstOrNull { it.from == from && it.to == to && it.kind == kind && it.id == id && it.label == label }
            if (same != null) { same.ok++; same.last = System.currentTimeMillis(); same.verified = true }
            else { edges += MapEdge(from, to, kind, id, label); if (edges.size > 3000) edges.removeAt(0) }
            dirty = true
        }

        fun toJson(device: JSONObject): JSONObject = synchronized(this) {
            JSONObject().put("schema", 1).put("package", pkg).put("version_code", versionCode).put("version_name", versionName)
                .put("device", device).put("updated", System.currentTimeMillis())
                .put("screens", JSONArray(screens.values.map {
                    JSONObject().put("sig", it.sig).put("title", it.title).put("seen", it.seen).put("features", JSONArray(it.features.toList()))
                }))
                .put("edges", JSONArray(edges.map {
                    JSONObject().put("from", it.from ?: JSONObject.NULL).put("to", it.to).put("kind", it.kind).put("id", it.id)
                        .put("label", it.label).put("ok", it.ok).put("last", it.last).put("verified", it.verified)
                }))
        }

        companion object {
            fun fromJson(j: JSONObject, status: String): AppMap {
                val m = AppMap(j.optString("package"), j.optLong("version_code"), j.optString("version_name"), status)
                val sc = j.optJSONArray("screens") ?: JSONArray()
                for (i in 0 until sc.length()) sc.optJSONObject(i)?.let { o ->
                    val f = o.optJSONArray("features") ?: JSONArray()
                    m.screens[o.optString("sig")] = MapScreen(o.optString("sig"), o.optString("title"),
                        LinkedHashSet((0 until f.length()).map { f.optString(it) }), o.optInt("seen"))
                }
                val ed = j.optJSONArray("edges") ?: JSONArray()
                for (i in 0 until ed.length()) ed.optJSONObject(i)?.let { o ->
                    // A borrowed or out-of-date map is a guide: each edge is checked again when walked.
                    m.edges += MapEdge(if (o.isNull("from")) null else o.optString("from"), o.optString("to"), o.optString("kind"),
                        o.optString("id"), o.optString("label"), o.optInt("ok", 1), o.optLong("last"),
                        status == "native" && o.optBoolean("verified", true))
                }
                return m
            }
        }
    }

    private val maps = ConcurrentHashMap<String, AppMap>()

    private fun mapFile(device: String, pkg: String, versionCode: Long): java.io.File? =
        memoryRoot?.let { java.io.File(it, "app-maps/$device/$pkg@$versionCode.json") }

    /**
     * The map of [pkg] at its installed version for this phone, loaded on first use:
     * same phone and version - "native"; same version from another phone - "borrowed" (edges are
     * re-checked when walked); this phone, older version - "stale" (a guide only); else a new map.
     */
    fun appMap(pkg: String): AppMap? {
        if (pkg.isBlank() || memoryRoot == null) return null
        val (vc, vn) = appVersion(pkg) ?: (0L to "")
        val key = "$pkg@$vc"
        maps[key]?.let { return it }
        val root = java.io.File(memoryRoot, "app-maps")
        fun read(f: java.io.File, status: String) = runCatching { AppMap.fromJson(JSONObject(f.readText()), status) }.getOrNull()
        val loaded = mapFile(deviceKey, pkg, vc)?.takeIf { it.isFile }?.let { read(it, "native") }
            ?: root.listFiles()?.asSequence()?.filter { it.isDirectory && it.name != deviceKey }
                ?.mapNotNull { java.io.File(it, "$pkg@$vc.json").takeIf { f -> f.isFile } }?.firstOrNull()?.let { read(it, "borrowed")?.also { m -> m.dirty = true } }
            ?: java.io.File(root, deviceKey).listFiles { f -> f.name.startsWith("$pkg@") && f.name.endsWith(".json") }
                ?.maxByOrNull { it.lastModified() }?.let { read(it, "stale") }
                ?.let { old -> AppMap(pkg, vc, vn, "stale").also { m -> m.screens.putAll(old.screens); m.edges.addAll(old.edges.onEach { e -> e.verified = false }) } }
            ?: AppMap(pkg, vc, vn, "new")
        return maps.putIfAbsent(key, loaded) ?: loaded
    }

    /** Writes changed maps and the lane's graph; returns the paths written (for the bridge to copy). */
    private fun saveDirty(lane: String): JSONArray? {
        val written = JSONArray()
        maps.values.filter { it.dirty }.forEach { m ->
            val f = mapFile(deviceKey, m.pkg, m.versionCode) ?: return@forEach
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(PrivacyRedactor.json(m.toJson(deviceInfo)).toString())
                m.dirty = false
                if (m.status == "new" || m.status == "borrowed") m.status = if (m.status == "new") "native" else m.status
                written.put(f.relativeTo(memoryRoot!!).path.replace('\\', '/'))
            }
        }
        val g = graph(lane)
        if (g.dirty) {
            memoryRoot?.let { root ->
                val f = java.io.File(root, "sessions/${graphKey(lane)}.json")
                runCatching {
                    f.parentFile?.mkdirs()
                    f.writeText(PrivacyRedactor.json(graphJson(g)).toString())
                    g.dirty = false
                    written.put(f.relativeTo(root).path.replace('\\', '/'))
                }
            }
        }
        return written.takeIf { it.length() > 0 }
    }

    private fun graphJson(g: Graph): JSONObject = synchronized(g) {
        JSONObject().put("schema", 1).put("counter", g.counter).put("goal", g.goal).put("task_date", g.taskDate).put("task_time", g.taskTime)
            .put("checkpoints", g.checkpoints).put("feedback", g.feedback).put("navigation_epoch", g.navigationEpoch)
            .put("unknown_operations", JSONArray(g.unknownOperations.toList()))
            .put("evidence_seq", g.evidenceSeq).put("checkpoint_seq", g.checkpointSeq).put("task_started_seq", g.taskStartedSeq).put("last_mutation_seq", g.lastMutationSeq)
            .put("screens", JSONArray(g.screens.values.map {
                JSONObject().put("id", it.id).put("pkg", it.pkg).put("title", it.title).put("sig", it.sig).put("note", it.note)
            }))
            .put("actions", JSONArray(g.actions.takeLast(200).map {
                JSONObject().put("id", it.id).put("what", it.what).put("target", it.target).put("from", it.from ?: "")
                    .put("to", it.to ?: "").put("ok", it.ok).put("note", it.note).put("facts", it.facts).put("evidence_seq", it.evidenceSeq).put("receipt", it.receipt)
            }))
            .put("items", JSONArray(g.items.values.map {
                JSONObject().put("id", it.id).put("label", it.label).put("screen", it.screen).put("list", it.listKey ?: "")
                    .put("ordinal", it.ordinal ?: -1).put("before", it.before ?: "").put("after", it.after ?: "")
                    .put("note", it.note).put("text", it.text.take(2000)).put("container_id", it.containerId).put("container", it.container)
            }))
            .put("lists", JSONObject(g.lists.mapValues { JSONArray(it.value) }))
            .put("collections", JSONObject(g.collections))
    }

    /** A session graph from the phone's memory, so references survive a server restart. */
    private fun loadGraph(key: String): Graph? {
        val f = memoryRoot?.let { java.io.File(it, "sessions/$key.json") }?.takeIf { it.isFile } ?: return null
        return runCatching {
            val j = JSONObject(f.readText())
            Graph().apply {
                counter = j.optInt("counter"); goal = j.optString("goal"); taskDate = j.optString("task_date"); taskTime = j.optString("task_time")
                checkpoints = j.optJSONArray("checkpoints") ?: JSONArray()
                feedback = j.optJSONObject("feedback") ?: JSONObject()
                navigationEpoch = j.optInt("navigation_epoch")
                j.optJSONArray("unknown_operations")?.let { a -> for (i in 0 until a.length()) unknownOperations.add(a.optString(i)) }
                evidenceSeq = j.optLong("evidence_seq"); checkpointSeq = j.optLong("checkpoint_seq"); taskStartedSeq = j.optLong("task_started_seq"); lastMutationSeq = j.optLong("last_mutation_seq")
                j.optJSONObject("collections")?.let { o -> o.keys().forEach { key -> o.optJSONObject(key)?.let { collections[key] = it } } }
                j.optJSONArray("screens")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                    screens[o.optString("sig")] = ScreenNode(o.optString("id"), o.optString("pkg"), o.optString("title"), o.optString("sig"), o.optString("note"))
                } }
                j.optJSONArray("actions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                    actions += ActionNode(o.optString("id"), o.optString("what"), o.optString("target"),
                        o.optString("from").ifBlank { null }, o.optString("to").ifBlank { null }, o.optBoolean("ok", true), o.optString("note"), o.optJSONObject("facts")?.apply { put("live", false) }, o.optLong("evidence_seq"), o.optJSONObject("receipt"))
                } }
                j.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                    items[o.optString("id")] = Item(o.optString("id"), o.optString("label"), o.optString("screen"),
                        o.optString("list").ifBlank { null }, o.optInt("ordinal", -1).takeIf { it >= 0 },
                        o.optString("before").ifBlank { null }, o.optString("after").ifBlank { null }, o.optString("note"), o.optString("text"), o.optString("container_id"), o.optJSONObject("container"))
                } }
                j.optJSONObject("lists")?.let { l -> l.keys().forEach { k ->
                    val a = l.optJSONArray(k) ?: JSONArray()
                    lists[k] = (0 until a.length()).map { a.optString(it) }.toMutableList()
                } }
            }
        }.getOrNull()
    }

    /** The signature of the screen a lane is on now. */
    fun currentPackage(lane: String): String? = currentScreen(lane)?.pkg

    fun currentSig(lane: String): String? = currentScreen(lane)?.sig

    /** The package of the screen a lane is on now. */
    fun currentPkg(lane: String): String? = currentScreen(lane)?.pkg

    /** The lane's @sN for a map screen (registering it if this session has not been there yet). */
    fun screenRef(lane: String, pkg: String, sig: String): String {
        val g = graph(lane)
        return synchronized(g) {
            g.screens.getOrPut(sig) { g.dirty = true; ScreenNode(g.next("s"), pkg, appMap(pkg)?.screens?.get(sig)?.title.orEmpty(), sig) }.id
        }
    }

    /** Signature behind a screen reference @sN. */
    fun sigOf(lane: String, ref: String): Pair<String, String>? =
        graph(lane).screens.values.firstOrNull { it.id == ref }?.let { it.pkg to it.sig }

    /**
     * Shortest route in [pkg]'s map from [from] to [to]: navigation edges, plus "launch the app" as a
     * way in from anywhere. Edges walked successfully more often win ties.
     */
    fun route(pkg: String, from: String?, to: String): List<MapEdge>? {
        val m = appMap(pkg) ?: return null
        if (from == to) return emptyList()
        val edges = synchronized(m) { m.edges.toList() }
        val bySource = edges.filter { it.from != null }.groupBy { it.from!! }
            .mapValues { (_, v) -> v.sortedByDescending { it.ok }.distinctBy { it.to } }
        fun bfs(start: String): List<MapEdge>? {
            val prev = HashMap<String, MapEdge>()
            val seen = hashSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (cur == to) {
                    val path = ArrayList<MapEdge>()
                    var at = cur
                    while (at != start) { val e = prev[at]!!; path.add(0, e); at = e.from!! }
                    return path
                }
                bySource[cur].orEmpty().forEach { e -> if (seen.add(e.to)) { prev[e.to] = e; queue.addLast(e.to) } }
            }
            return null
        }
        from?.let { bfs(it) }?.let { return it }
        // Through the app's start screen: launch, then walk from where launching lands.
        val entry = edges.filter { it.kind == "launch" }.maxByOrNull { it.ok } ?: return null
        val rest = bfs(entry.to) ?: return null
        return listOf(entry) + rest
    }

    /** Screens of [pkg]'s map whose title or features mention [query], best first. */
    fun searchMap(pkg: String, query: String): List<MapScreen> {
        val m = appMap(pkg) ?: return emptyList()
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return synchronized(m) {
            m.screens.values.map { sc ->
                val text = (listOf(sc.title) + sc.features).joinToString(" ").lowercase()
                sc to words.count { text.contains(it) }
            }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        }
    }

    /** `map`: the app's map in brief, or the screens matching [query] with the route from here. */
    fun mapJson(lane: String, pkgArg: String, query: String): JSONObject {
        val pkg = pkgArg.ifBlank { currentPkg(lane).orEmpty() }
        val m = appMap(pkg) ?: return JSONObject().put("ok", false).put("code", "NO_APP").put("errmsg", "Open an app first or pass app/package.")
        val here = currentSig(lane)
        fun describe(sc: MapScreen): JSONObject {
            val route = route(pkg, here, sc.sig)
            return JSONObject().put("screen", screenRef(lane, pkg, sc.sig)).put("title", sc.title)
                .put("offers", JSONArray(sc.features.take(12)))
                .put("route", route?.let { r -> JSONArray(r.map(::stepText)) } ?: "unknown - explore or navigate there once")
        }
        val body = JSONObject().put("ok", true).put("app", pkg).put("version", m.versionName).put("map_status", m.status)
            .put("screens_known", m.screens.size).put("routes_known", m.edges.size)
        if (query.isNotBlank()) {
            val hits = searchMap(pkg, query)
            body.put("matches", JSONArray(hits.take(5).map(::describe)))
            if (hits.isEmpty()) body.put("hint", "Nothing in the map mentions \"$query\" yet. explore {depth:2} from a likely screen finds more.")
        } else {
            body.put("screens", JSONArray(synchronized(m) { m.screens.values.sortedByDescending { it.seen }.take(25) }.map {
                "${screenRef(lane, pkg, it.sig)} ${it.title} - ${it.features.take(6).joinToString(", ")}"
            }))
        }
        return body
    }

    fun stepText(e: MapEdge): String = when (e.kind) {
        "launch" -> "launch the app"
        "back" -> "back"
        else -> "${e.kind} ${e.label.ifBlank { "#" + e.id }}${if (e.id.isNotBlank()) " (#${e.id})" else ""}"
    }

    /**
     * The `#n` on the current screen for a recorded edge's control: by resource id (and label when
     * several share it), else by label.
     */
    fun findControl(lane: String, e: MapEdge): Int? {
        val all = lines(lastTree[lane].orEmpty()).filter { !it.noise && !it.system }
        val byId = if (e.id.isNotBlank()) all.filter { l -> IdToken.findAll(l.text).any { it.groupValues[1] == e.id } } else emptyList()
        byId.singleOrNull()?.let { return it.index }
        byId.firstOrNull { rowLabel(it.label) == e.label }?.let { return it.index }
        all.firstOrNull { rowLabel(it.label) == e.label && it.interactive }?.let { return it.index }
        all.firstOrNull { rowLabel(it.label) == e.label }?.let { return it.index }
        // A row's subtitle changes with the data ("System · ..." / "Storage · 48% used"): match its title.
        val title = primaryLabel(e.label)
        if (title.isBlank()) return null
        return all.firstOrNull { it.interactive && primaryLabel(it.label).equals(title, true) }?.index
    }

    /** What to scroll to when an edge's control is off screen: its label's title. */
    fun edgeTitle(e: MapEdge): String = primaryLabel(e.label)

    /** A row's title: its words before the " · subtitle". */
    private fun primaryLabel(label: String): String = rowLabel(label).substringBefore(" · ").trim()

    /** Marks an edge as walked (or failed) so routes prefer what works on this phone. */
    fun walked(pkg: String, e: MapEdge, ok: Boolean) {
        val m = appMap(pkg) ?: return
        synchronized(m) { if (ok) { e.ok++; e.verified = true; e.last = System.currentTimeMillis() } else e.ok = maxOf(0, e.ok - 2); m.dirty = true }
    }

    /** After the bridge restored files: drop cached maps / graphs so they are read again. */
    fun forget(rel: String) {
        if (rel.startsWith("app-maps")) maps.clear()
        if (rel.startsWith("sessions")) graphs.clear()
    }

    /**
     * Controls `explore` may try on the current screen: interactive, labelled, not text fields, not
     * anything that commits (send / delete / pay / save ...), not yet tried from this screen. Each
     * comes with the `#n` of the scrolling list it sits in (null outside lists): the caller stops a
     * list once its rows turn out to open one screen template (contacts, messages ...), while a
     * menu-like list (Settings) has every row explored.
     */
    fun exploreCandidates(lane: String, pkg: String, sig: String): List<Pair<String, Int?>> {
        val all = lines(lastTree[lane].orEmpty())
        val tried = appMap(pkg)?.let { m -> synchronized(m) { m.edges.filter { it.from == sig }.map { it.label }.toSet() } }.orEmpty()
        val lists = all.filter { it.text.contains(" scroll") && it.bounds != null }
        val perList = HashMap<Int, Int>()
        val out = LinkedHashMap<String, Int?>()
        for (l in all) {
            if (!l.interactive || l.system || l.noise || l.role == "input") continue
            val label = rowLabel(l.label)
            if (label.isBlank() || label in tried || label in out || Irreversible.containsMatchIn(label)) continue
            if (l.text.contains(" scroll")) continue
            // The innermost list holding the row.
            val inList = lists.filter { c -> c !== l && l.bounds != null && inside(l.bounds, c.bounds!!) }
                .minByOrNull { c -> c.bounds!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
            if (inList != null && perList.merge(inList.index, 1, Int::plus)!! > MaxRowsPerList) continue
            out[label] = inList?.index
        }
        return out.entries.take(30).map { it.key to it.value }
    }

    private const val MaxRowsPerList = 8

    /** `#n` of the first control on the current screen labelled [label]. */
    fun findByLabel(lane: String, label: String): Int? =
        lines(lastTree[lane].orEmpty()).firstOrNull { it.interactive && !it.noise && rowLabel(it.label) == label }?.index

    /** A map screen in brief, for explore's report. */
    fun screenBrief(lane: String, pkg: String, sig: String): String {
        val sc = appMap(pkg)?.screens?.get(sig)
        return "${screenRef(lane, pkg, sig)} ${sc?.title.orEmpty()} - ${sc?.features?.take(8)?.joinToString(", ").orEmpty()}"
    }

    /** `recap`: what a host that compacted its context needs to carry on. */
    fun recapJson(lane: String): JSONObject {
        // A hook asking after a compaction comes from another process (another session id): answer
        // with the graph that was worked on last in this lane.
        val own = graph(lane)
        val fallbackKey = lastActive[lane] ?: memoryRoot?.let { java.io.File(it, "sessions") }
            ?.listFiles { f -> f.name.endsWith("~$lane.json") }?.maxByOrNull { it.lastModified() }?.name?.removeSuffix(".json")
        val g = if (own.goal.isBlank() && own.checkpoints.length() == 0 && own.screens.isEmpty() && own.items.isEmpty() && fallbackKey != null)
            graphs.getOrPut(fallbackKey) { loadGraph(fallbackKey) ?: Graph() } else own
        val here = g.alias[lastSnapshotId[lane].orEmpty()]?.let { g.screens[it] }
        return synchronized(g) {
            JSONObject().put("ok", true)
                .put("goal", g.goal).put("task_date", g.taskDate).put("task_time", g.taskTime).put("steps", g.checkpoints).put("feedback", g.feedback)
                .put("now", here?.let { "${it.id} ${it.pkg} · ${it.title}" } ?: "unknown - read the screen")
                .put("noted", JSONArray((g.items.values.filter { it.note.isNotBlank() }.map { "${it.id} ${it.label.take(50)} - ${it.note.take(160)}" } +
                    g.screens.values.filter { it.note.isNotBlank() }.map { "${it.id} ${it.title} - ${it.note.take(160)}" }).take(40)))
                .put("pinned", JSONArray(g.items.values.filter { it.id.startsWith("@p") }.takeLast(20).map { "${it.id} ${it.label.take(60)}" }))
                .put("lists", JSONArray(g.lists.entries.take(5).map { (k, v) -> "${k.substringBefore(':')}: ${v.size} rows (collect again to list them)" }))
                .put("recent_actions", JSONArray(g.actions.takeLast(15).map { "${it.id} ${it.what} ${it.target} ${it.from}->${it.to}" }))
                .put("how", "These references are still valid: use @rN/@pN as query, goto {to:@sN} to return to a screen, " +
                    "refs {query:@x} for details, map {query} to find a feature.")
        }
    }

    /** The phone's own clock: "today", weekdays and "this week" in a task mean the phone's date. */
    private fun phoneTime(): String =
        java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm z", java.util.Locale.US).format(java.util.Date())
}
