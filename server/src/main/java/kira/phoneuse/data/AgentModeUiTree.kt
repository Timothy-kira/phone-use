package kira.phoneuse.data

import java.util.Locale

data class AgentModeUiTarget(
    val label: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val bounds: String get() = "($left,$top)-($right,$bottom)"
    val tapLine: String get() = "$label tap $bounds"
}

/**
 * Compact accessibility dump for the phone operator. Native apps have no HTML
 * DOM; this is the closest structured tree (text, tap targets, 0..1000 bounds).
 */
internal object AgentModeUiTree {
    private val NodeTag = Regex("""<node\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
    private val Attr = Regex("""([\w-]+)="([^"]*)"""")
    private val Bounds = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    private val CompactBounds = Regex("""\((\d+),(\d+)\)-\((\d+),(\d+)\)""")
    private val DecorativeClasses = setOf(
        "imageview", "image", "view", "framelayout", "linearlayout", "relativelayout",
        "constraintlayout", "viewgroup", "recyclerview", "nestedscrollview", "scrollview",
        "horizontalscrollview", "viewpager", "viewpager2", "toolbar", "appbarlayout",
        "surfaceview", "textureview", "space", "viewstub", "composeview", "androidview",
        "cardview", "coordinatorlayout", "drawerlayout", "swiperefreshlayout",
    )
    private val ArtIdHints = listOf(
        "banner", "cover", "artwork", "thumbnail", "hero", "poster", "watermark",
    )

    data class CompactNode(
        val text: String,
        val desc: String,
        val klass: String,
        val id: String,
        val clickable: Boolean,
        val checkable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val ownLabel: String get() = text.ifBlank { desc }
        val fallbackLabel: String get() = ownLabel.ifBlank { id }.ifBlank { klass.ifBlank { "view" } }
    }

    fun compactFromUiautomatorXml(
        xml: String,
        width: Int,
        height: Int,
        maxLines: Int = DefaultDumpLimit,
    ): String {
        if (xml.isBlank() || !xml.contains("<node", ignoreCase = true)) return ""
        return formatCompact(parseXmlNodes(xml, width, height), maxLines)
    }

    fun formatCompact(nodes: List<CompactNode>, maxLines: Int = DefaultDumpLimit): String {
        val lines = ArrayList<String>(maxLines.coerceAtLeast(1))
        for (node in nodes) {
            if (lines.size >= maxLines) break
            if (!shouldEmitCompact(node)) continue
            val flags = buildString {
                if (node.clickable || node.checkable) append(" tap")
                if (node.focused) append(" focus")
            }
            val idSuffix = if (node.id.isNotBlank()) " #${node.id}" else ""
            lines += "${node.fallbackLabel}$flags (${node.left},${node.top})-(${node.right},${node.bottom})$idSuffix"
        }
        return lines.joinToString("\n")
    }

    fun shouldEmitCompact(
        klass: String,
        text: String,
        desc: String,
        id: String,
        clickable: Boolean,
        focused: Boolean,
    ): Boolean = shouldEmitCompact(
        CompactNode(
            text = text,
            desc = desc,
            klass = klass,
            id = id,
            clickable = clickable,
            checkable = false,
            editable = false,
            focused = focused,
            left = 0,
            top = 0,
            right = 0,
            bottom = 0,
        ),
    )

    fun clickTargetsFromCompact(
        compact: String,
        maxTargets: Int = DefaultTargetLimit,
        simplify: Boolean = false,
    ): List<AgentModeUiTarget> {
        val parsed = compact.lineSequence().mapNotNull(::parseCompactLine).toList()
        if (parsed.isEmpty()) return emptyList()
        val out = ArrayList<AgentModeUiTarget>(maxTargets.coerceAtLeast(1))
        val seen = HashSet<String>()
        for (node in parsed) {
            if (out.size >= maxTargets) break
            if (!node.tap && !node.focus) continue
            val label = effectiveCompactLabel(node, parsed)
            if (label.isBlank()) continue
            if (simplify && (isDecorativeLabel(label) || isFullBleedArt(node, label))) continue
            val key = "$label:${node.left},${node.top},${node.right},${node.bottom}"
            if (!seen.add(key)) continue
            out += AgentModeUiTarget(
                label = label,
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = node.bottom,
            )
        }
        return out
    }

    fun shouldSimplifyRich(node: RichNode): Boolean {
        val label = node.label.ifBlank { node.text }
        if (isDecorativeLabel(label)) return true
        val area = (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)
        val klass = node.klass.substringAfterLast('.').lowercase()
        return area >= 500_000 && (klass.contains("image") || isDecorativeLabel(label))
    }

    fun isCluttered(targetCount: Int): Boolean = targetCount >= ClutteredTargetCount

    fun clickTargetsText(
        compact: String,
        maxTargets: Int = DefaultTargetLimit,
        simplify: Boolean = false,
    ): String = clickTargetsFromCompact(compact, maxTargets, simplify).joinToString("\n") { it.tapLine }

    fun isSparse(compact: String): Boolean =
        compact.lineSequence().count { it.isNotBlank() } < 2

    /**
     * Geometry/structure signature for chrome, not pixels.
     * Images, video surfaces, GIF/Lottie, and unnamed artwork are omitted so a
     * live feed does not look like the layout is still moving. Child counts are
     * ignored for the same reason (RecyclerView recycling).
     */
    data class LayoutNode(
        val klass: String,
        val id: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val childCount: Int,
        val label: String = "",
    )

    fun layoutFingerprint(nodes: List<LayoutNode>, windowKey: Int = 0): Long {
        val kept = nodes.filter { !isIgnoredMedia(it.klass, it.id, it.clickable, it.label) }
        var hash = FnvOffset
        hash = mix(hash, windowKey)
        hash = mix(hash, kept.size)
        for (node in kept) {
            hash = mix(hash, node.klass)
            hash = mix(hash, node.id)
            hash = mix(hash, node.left)
            hash = mix(hash, node.top)
            hash = mix(hash, node.right)
            hash = mix(hash, node.bottom)
            hash = mix(hash, if (node.clickable) 1 else 0)
        }
        return hash
    }

    fun isIgnoredMediaNode(node: RichNode): Boolean =
        isIgnoredMedia(
            klass = node.klass,
            id = node.id,
            interactive = node.clickable || node.longClickable || node.checkable || node.editable,
            label = node.label.ifBlank { node.text }.ifBlank { node.desc },
        )

    fun isIgnoredMedia(
        klass: String,
        id: String,
        interactive: Boolean,
        label: String = "",
    ): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        if (
            k.contains("surface") ||
            k.contains("texture") ||
            k.contains("video") ||
            k.contains("lottie") ||
            k.contains("gif") ||
            k.contains("animation")
        ) {
            return true
        }
        if (!looksLikeMediaClass(klass) && !looksLikeMediaId(id) && !looksLikeMediaId(label)) {
            return false
        }
        val name = label.ifBlank { id }
        if (
            interactive &&
            name.isNotBlank() &&
            !looksLikeArtId(name) &&
            !looksLikeArtId(id) &&
            !looksLikeMediaId(name) &&
            !looksLikeMediaId(id)
        ) {
            return false
        }
        return true
    }

    fun isOverlayClass(klass: String): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        return k.contains("dialog") ||
            k.contains("popup") ||
            k.contains("toast") ||
            k.contains("bottomsheet") ||
            k.contains("modal") ||
            k.contains("float")
    }

    fun isOverlayWindowType(windowType: Int): Boolean {
        // AccessibilityWindowInfo: TYPE_APPLICATION=1, TYPE_INPUT_METHOD=2.
        return windowType > 0 && windowType != 1 && windowType != 2
    }

    fun isScrimHint(value: String): Boolean {
        val n = value.trim().lowercase()
        if (n.isBlank()) return false
        return n.contains("scrim") ||
            n.contains("mask") ||
            n.contains("dim") ||
            n.contains("overlay") ||
            n.contains("barrier") ||
            n.contains("interstitial") ||
            n.contains("close_ad") ||
            n.contains("ad_close")
    }

    fun shouldKeepForCheapDump(node: RichNode): Boolean =
        node.isInteractive || isOverlayCandidate(node)

    fun isOverlayCandidate(node: RichNode): Boolean {
        if (isOverlayWindowType(node.windowType)) return true
        if (isOverlayClass(node.klass)) return true
        if (isScrimHint(node.id) || isScrimHint(node.label) || isScrimHint(node.desc)) return true
        return false
    }

    fun toLayoutNode(node: RichNode): LayoutNode = LayoutNode(
        klass = node.klass,
        id = node.id,
        left = node.left,
        top = node.top,
        right = node.right,
        bottom = node.bottom,
        clickable = node.clickable,
        childCount = 0,
        label = node.label.ifBlank { node.text }.ifBlank { node.desc },
    )

    private const val ClutteredTargetCount = 24
    private const val FnvOffset = -3750763034362895579L
    private const val FnvPrime = 1099511628211L

    private fun mix(hash: Long, value: Int): Long = (hash xor value.toLong()) * FnvPrime

    private fun mix(hash: Long, value: String): Long {
        var next = hash
        for (index in 0 until value.length) {
            next = (next xor value[index].code.toLong()) * FnvPrime
        }
        return next
    }

    fun findTarget(compact: String, expectedLabel: String): AgentModeUiTarget? {
        val expected = normalizeLabel(expectedLabel)
        if (compact.isBlank() || expected.isBlank()) return null
        return compact.lineSequence()
            .mapNotNull(::parseCompactTarget)
            .map { target -> target to labelScore(expected, normalizeLabel(target.label)) }
            .filter { (_, score) -> score > 0.0 }
            .maxWithOrNull(
                compareBy<Pair<AgentModeUiTarget, Double>> { it.second }
                    .thenBy { -(it.first.right - it.first.left) * (it.first.bottom - it.first.top) },
            )
            ?.first
    }

    private fun parseXmlNodes(xml: String, width: Int, height: Int): List<CompactNode> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val nodes = ArrayList<CompactNode>(64)
        for (match in NodeTag.findAll(xml)) {
            val attrs = HashMap<String, String>()
            for (attr in Attr.findAll(match.groupValues[1])) {
                attrs[attr.groupValues[1]] = attr.groupValues[2]
            }
            val bounds = Bounds.find(attrs["bounds"].orEmpty())
            val nx1: Int
            val ny1: Int
            val nx2: Int
            val ny2: Int
            if (bounds != null) {
                nx1 = bounds.groupValues[1].toInt() * 1000 / w
                ny1 = bounds.groupValues[2].toInt() * 1000 / h
                nx2 = bounds.groupValues[3].toInt() * 1000 / w
                ny2 = bounds.groupValues[4].toInt() * 1000 / h
            } else {
                nx1 = 0
                ny1 = 0
                nx2 = 0
                ny2 = 0
            }
            nodes += CompactNode(
                text = attrs["text"].orEmpty(),
                desc = attrs["content-desc"].orEmpty(),
                klass = attrs["class"].orEmpty().substringAfterLast('.'),
                id = attrs["resource-id"].orEmpty().substringAfterLast('/'),
                clickable = attrs["clickable"] == "true",
                checkable = attrs["checkable"] == "true",
                editable = attrs["class"].orEmpty().contains("EditText", ignoreCase = true) ||
                    attrs["password"] == "true",
                focused = attrs["focused"] == "true",
                left = nx1,
                top = ny1,
                right = nx2,
                bottom = ny2,
            )
        }
        return nodes
    }

    private fun shouldEmitCompact(node: CompactNode): Boolean {
        if (node.focused || node.editable) return true
        if (node.ownLabel.isNotBlank()) return true
        return node.clickable || node.checkable
    }

    private fun isArtClass(klass: String): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        return k == "imageview" || k == "image" || k == "surfaceview" ||
            k == "textureview" || k == "space" || k == "viewstub"
    }

    private fun looksLikeMediaClass(klass: String): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        return k.contains("image") ||
            k.contains("surface") ||
            k.contains("texture") ||
            k.contains("video") ||
            k.contains("gif") ||
            k.contains("lottie") ||
            k == "videoview" ||
            (k.contains("player") && k.contains("view")) ||
            k.contains("animation")
    }

    private fun looksLikeMediaId(value: String): Boolean {
        val n = value.trim().lowercase()
        if (n.isBlank()) return false
        return n.contains("gif") ||
            n.contains("video") ||
            n.contains("player") ||
            n.contains("lottie") ||
            n.contains("artwork") ||
            n.contains("thumbnail") ||
            n.contains("poster") ||
            n.contains("banner") ||
            n.contains("cover") ||
            n.contains("audio") ||
            n.contains("sound") ||
            n.contains("_anim") ||
            n.endsWith("anim")
    }

    private fun nodeArea(node: RichNode): Int =
        (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)

    private fun intersectionArea(a: RichNode, b: RichNode): Int {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        return width * height
    }

    private fun isAncestorOrSelf(candidate: RichNode, target: RichNode, nodes: List<RichNode>): Boolean {
        if (candidate.index == target.index) return true
        val byIndex = nodes.associateBy { it.index }
        var parent = target.parent
        var guard = 0
        while (parent >= 0 && guard < 64) {
            if (parent == candidate.index) return true
            parent = byIndex[parent]?.parent ?: -1
            guard += 1
        }
        return false
    }

    private fun isDecorativeLabel(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return true
        if (DecorativeClasses.contains(normalizeClass(trimmed))) return true
        if (looksLikeArtId(trimmed)) return true
        return false
    }

    private fun looksLikeArtId(id: String): Boolean {
        val n = id.trim().lowercase()
        if (n.isBlank()) return false
        if (n.startsWith("iv_") || n.startsWith("img_") || n.startsWith("image")) return true
        if (n.endsWith("_bg") || n.endsWith("_bg_iv")) return true
        return ArtIdHints.any { hint -> n.contains(hint) }
    }

    private fun normalizeClass(value: String): String = value.substringAfterLast('.').lowercase()

    private fun isFullBleedArt(node: ParsedCompactLine, label: String): Boolean {
        val area = (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)
        if (area < 500_000) return false
        return isDecorativeLabel(label) || isArtClass(node.label)
    }

    private fun effectiveCompactLabel(node: ParsedCompactLine, all: List<ParsedCompactLine>): String {
        if (!isDecorativeLabel(node.label)) return node.label
        val child = all
            .filter { candidate ->
                candidate !== node &&
                    node.contains(candidate) &&
                    !isDecorativeLabel(candidate.label)
            }
            .minByOrNull { candidate ->
                (candidate.right - candidate.left).coerceAtLeast(0) *
                    (candidate.bottom - candidate.top).coerceAtLeast(0)
            }
        return child?.label ?: node.label
    }

    private data class ParsedCompactLine(
        val label: String,
        val tap: Boolean,
        val focus: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        fun contains(other: ParsedCompactLine): Boolean =
            left <= other.left &&
                top <= other.top &&
                right >= other.right &&
                bottom >= other.bottom &&
                (left < other.left || top < other.top || right > other.right || bottom > other.bottom)
    }

    private fun parseCompactLine(rawLine: String): ParsedCompactLine? {
        val line = rawLine.trim()
        val bounds = CompactBounds.find(line) ?: return null
        var label = line.substring(0, bounds.range.first).trim()
        val focus = label.endsWith(" focus")
        if (focus) label = label.removeSuffix(" focus").trim()
        val tap = label.endsWith(" tap")
        if (tap) label = label.removeSuffix(" tap").trim()
        if (label.isBlank()) return null
        return ParsedCompactLine(
            label = label,
            tap = tap,
            focus = focus,
            left = bounds.groupValues[1].toInt(),
            top = bounds.groupValues[2].toInt(),
            right = bounds.groupValues[3].toInt(),
            bottom = bounds.groupValues[4].toInt(),
        )
    }

    private fun parseCompactTarget(rawLine: String): AgentModeUiTarget? {
        val parsed = parseCompactLine(rawLine) ?: return null
        return AgentModeUiTarget(
            label = parsed.label,
            left = parsed.left,
            top = parsed.top,
            right = parsed.right,
            bottom = parsed.bottom,
        )
    }

    private fun labelScore(expected: String, actual: String): Double = when {
        expected == actual -> 1.0
        actual.contains(expected) || expected.contains(actual) -> 0.8
        else -> {
            val left = expected.toSet()
            val right = actual.toSet()
            if (left.isEmpty() || right.isEmpty()) 0.0 else {
                left.intersect(right).size.toDouble() / left.union(right).size
            }.takeIf { it >= 0.6 } ?: 0.0
        }
    }

    private fun normalizeLabel(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    data class RichNode(
        val index: Int,
        val label: String,
        val text: String,
        val desc: String,
        val hint: String,
        val klass: String,
        val id: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val enabled: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val parent: Int,
        val depth: Int,
        val drawingOrder: Int = 0,
        val password: Boolean = false,
        val packageName: String = "",
        val windowType: Int = 0,
        val windowIndex: Int = 0,
        val windowId: Int = -1,
        val windowParentId: Int = -1,
        val windowAnchorId: String = "",
        val windowFocused: Boolean = false,
        val windowActive: Boolean = false,
        val windowPictureInPicture: Boolean = false,
        /** Normalized touchable rectangles; blank means unavailable, [] means no touch region. */
        val windowTouchRegion: String = "",
        val windowTitle: String = "",
        /** SeekBar/slider/progress: AccessibilityNodeInfo.RangeInfo, NaN when the node has none. */
        val rangeValue: Float = Float.NaN,
        val rangeMin: Float = Float.NaN,
        val rangeMax: Float = Float.NaN,
        /** AccessibilityNodeInfo.stateDescription (API 30), e.g. "50%" or "On". */
        val state: String = "",
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val bounds: String get() = "($left,$top)-($right,$bottom)"
        val clickIndex: Int get() = index + 1
        val hasRange: Boolean get() = !rangeValue.isNaN()
        val isInteractive: Boolean
            get() = clickable || checkable || editable || scrollable || focused ||
                longClickable || password || hasRange

        /** "37/100" for a ranged node, "" otherwise. */
        val rangeText: String
            get() = if (!hasRange) "" else buildString {
                append(formatRange(rangeValue))
                if (!rangeMax.isNaN()) append('/').append(formatRange(rangeMax))
            }

        /** Identity without position: survives scrolling and re-layout. */
        fun semanticKey(): String = "${packageName}|${id}|${klass}|${label}"

        fun identityKey(): String =
            "${packageName}|${id}|${klass}|${left / 8},${top / 8},${right / 8},${bottom / 8}"

        fun matches(query: String): Boolean {
            val q = query.trim()
            if (q.isBlank()) return true
            val hashed = q.removePrefix("#")
            val numbered = hashed.toIntOrNull()
            if (q.startsWith("#") && numbered != null) {
                if (numbered == clickIndex || numbered == index) return true
                if (id.equals(hashed, ignoreCase = true)) return true
            }
            if (q.equals(id, ignoreCase = true) || q.equals("#$id", ignoreCase = true)) return true
            if (label.contains(q, ignoreCase = true)) return true
            if (text.contains(q, ignoreCase = true)) return true
            if (desc.contains(q, ignoreCase = true)) return true
            if (hint.contains(q, ignoreCase = true)) return true
            if (klass.contains(q, ignoreCase = true)) return true
            return false
        }

        fun inRegion(region: String): Boolean {
            val r = region.trim().lowercase()
            if (r.isBlank()) return true
            return when (r) {
                "top" -> centerY < 334
                "middle" -> centerY in 334..666
                "bottom" -> centerY > 666
                "left" -> centerX < 500
                "right" -> centerX >= 500
                else -> true
            }
        }
    }

    data class PagedDump(
        val nodes: List<RichNode>,
        val offset: Int,
        val limit: Int,
        val total: Int,
    ) {
        val hasMore: Boolean get() = offset + nodes.size < total
    }

    fun pageRichNodes(
        nodes: List<RichNode>,
        query: String = "",
        region: String = "",
        offset: Int = 0,
        limit: Int = DefaultDumpLimit,
    ): PagedDump {
        val filtered = nodes.filter { it.matches(query) && it.inRegion(region) }
        val start = offset.coerceAtLeast(0)
        val pageLimit = limit.coerceIn(1, MaxDumpLimit)
        return PagedDump(
            nodes = filtered.drop(start).take(pageLimit),
            offset = start,
            limit = pageLimit,
            total = filtered.size,
        )
    }

    /** Short structural role for compact lines (P3-3). */
    fun roleOf(node: RichNode): String = when {
        node.editable || node.password -> "input"
        node.checkable -> "switch"
        node.scrollable -> "list"
        node.clickable || node.longClickable -> "btn"
        else -> "text"
    }

    /** 334/666 band marker reused from the region convention. */
    fun layoutBand(node: RichNode): String = when {
        node.centerY < 334 -> "top"
        node.centerY in 334..666 -> "mid"
        else -> "bot"
    }

    private fun intersectsViewport(node: RichNode): Boolean =
        node.right > 0 && node.bottom > 0 && node.left < 1000 && node.top < 1000

    /**
     * P3-2 deterministic four-pass pruning (no embedding model):
     * 1. viewport: drop subtrees fully outside [0,1000]^2 (overlays exempt),
     * 2. retain structural wrappers for parent and drawing-order identity,
     * 3. interaction penetration: a clickable container absorbs non-interactive
     *    child text/icons into its label,
     * 4. proximity merge: TextView label/hint adjacent to an EditText becomes
     *    the input's accessible name.
     * Card/list-item parents are kept (no leaf flattening) so sibling "+"
     * buttons stay attributable.
     */
    fun pruneRich(nodes: List<RichNode>): List<RichNode> {
        if (nodes.isEmpty()) return nodes
        val byIndex = nodes.associateBy { it.index }
        val kept = LinkedHashMap<Int, RichNode>()
        nodes.forEach { node -> kept[node.index] = node }

        // 1. Viewport crop: remove nodes (and their subtrees) fully off-screen.
        val dropped = mutableSetOf<Int>()
        nodes.forEach { node ->
            if (isOverlayWindowType(node.windowType)) return@forEach
            if (!intersectsViewport(node)) {
                collectSubtreeIndexes(node.index, nodes).forEach { dropped += it }
            }
        }
        dropped.forEach { kept.remove(it) }

        // 2-3. Wrapper collapse + interaction penetration, deepest first.
        nodes.filter { it.index in kept.keys }
            .sortedByDescending { it.depth }
            .forEach { node ->
                val parent = node.parent.takeIf { it >= 0 }?.let { kept[it] } ?: return@forEach
                val absorbable = !node.isInteractive &&
                    node.hint.isBlank() &&
                    node.password.not() &&
                    !isOverlayWindowType(node.windowType)
                // Only small controls (a button with its icon and caption, a list row) absorb their
                // children. A WebView, a scroll container or anything covering a quarter of the
                // screen is content, not a control: folding it would erase a whole web page.
                val parentArea = (parent.right - parent.left).toLong() * (parent.bottom - parent.top)
                val canAbsorb = parent.isInteractive &&
                    !parent.klass.contains("WebView", ignoreCase = true) &&
                    !parent.scrollable &&
                    parentArea < 250_000L
                // A non-interactive wrapper can contain independent buttons (toolbar
                // menus, favorites, row actions). Absorbing its subtree would erase them.
                val subtree = collectSubtreeIndexes(node.index, nodes)
                val hasInteractiveChild = subtree.any { index ->
                    index != node.index && byIndex[index]?.isInteractive == true
                }
                if (absorbable && canAbsorb && !hasInteractiveChild) {
                    // 3. Penetration: fold plain text/icon children into the
                    // interactive parent's label so they are not tappable crumbs.
                    if (node.label.isNotBlank() &&
                        !parent.label.contains(node.label, ignoreCase = true) &&
                        node.label != parent.label
                    ) {
                        kept[parent.index] = parent.copy(
                            label = (parent.label + " " + node.label).trim().take(96),
                        )
                    }
                    collectSubtreeIndexes(node.index, nodes).forEach { kept.remove(it) }
                }
            }
        // Empty structural parents must stay in the canonical tree: child parent indexes and
        // sibling drawing orders otherwise become orphaned, making a real scrim invisible to
        // occlusion checks. The display shaper hides empty layout rows from the compact outline.

        // 4. Proximity merge: label TextView immediately above/left of an EditText.
        val keptNodes = kept.values.toList()
        val inputs = keptNodes.filter { it.editable && it.hint.isBlank() }
        inputs.forEach { input ->
            val label = keptNodes.firstOrNull { candidate ->
                candidate.index != input.index &&
                    !candidate.isInteractive &&
                    candidate.klass.contains("TextView") &&
                    candidate.label.isNotBlank() &&
                    candidate.bottom <= input.top + 24 &&
                    candidate.bottom >= input.top - 120 &&
                    candidate.left < input.right
            } ?: return@forEach
            kept[input.index] = (kept[input.index] ?: input).copy(
                hint = label.label.take(48),
            )
            // A text container can also expose links/buttons; retain its descendants.
            collectSubtreeIndexes(label.index, nodes).filter { index ->
                index == label.index || byIndex[index]?.isInteractive != true
            }.forEach { kept.remove(it) }
        }

        // Re-index densely with a stable (top, left) reading order.
        val ordered = kept.values.sortedWith(compareBy({ it.top }, { it.left }, { it.depth }))
        val remap = ordered.mapIndexed { newIndex, node -> node.index to newIndex }.toMap()
        return ordered.map { node ->
            node.copy(
                index = remap.getValue(node.index),
                parent = if (node.parent >= 0) remap[node.parent] ?: -1 else -1,
            )
        }
    }

    private fun collectSubtreeIndexes(root: Int, nodes: List<RichNode>): Set<Int> {
        val out = linkedSetOf(root)
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            val children = nodes.filter { it.parent in frontier }.map { it.index }
            out += children
            frontier = children
        }
        return out
    }

    /** True when the tree is structurally blind (Flutter/游戏/WebView single surface). */
    fun isStructureBlind(nodes: List<RichNode>): Boolean {
        if (nodes.isEmpty()) return true
        val interactive = nodes.count { it.isInteractive }
        val surfaceArea = nodes.count { node ->
            val k = node.klass.substringAfterLast('.').lowercase()
            (k == "surfaceview" || k == "textureview" || k == "webview") &&
                (node.right - node.left) * (node.bottom - node.top) >= 500 * 500
        }
        return interactive <= 2 && surfaceArea > 0
    }

    fun deltaLines(previous: List<RichNode>, current: List<RichNode>): List<String> {
        if (current.isEmpty()) return emptyList()
        if (previous.isEmpty()) return current.map(::compactLine).take(24)
        val diff = treeDiff(previous, current)
        if (!diff.changed) return emptyList()
        val prevByKey = previous.associateBy { it.identityKey() }
        val currByKey = current.associateBy { it.identityKey() }
        val lines = LinkedHashSet<String>()
        diff.added.forEach { label ->
            currByKey.values.firstOrNull { it.label == label || it.klass == label }?.let {
                lines += "+ " + compactLine(it)
            }
        }
        diff.removed.forEach { label ->
            prevByKey.values.firstOrNull { it.label == label || it.klass == label }?.let {
                lines += "- " + compactLine(it)
            }
        }
        diff.textChanged.forEach { label ->
            currByKey.values.firstOrNull { it.label == label || it.id == label || it.klass == label }?.let {
                lines += "~ " + compactLine(it)
            }
        }
        if (lines.isEmpty() && diff.fingerprintChanged) {
            lines += "~ layout changed"
        }
        return lines.take(24)
    }

    fun scrollSwipe(nodes: List<RichNode>): Pair<Pair<Int, Int>, Pair<Int, Int>> =
        directionalSwipe(nodes, "up")

    fun parseSwipeDirection(raw: String): String = when (
        raw.trim().lowercase(Locale.ROOT).replace('-', '_')
    ) {
        "left", "swipe_left", "left_swipe" -> "left"
        "right", "swipe_right", "right_swipe" -> "right"
        "up", "swipe_up", "up_swipe" -> "up"
        "down", "swipe_down", "down_swipe" -> "down"
        else -> ""
    }

    /**
     * Finger-direction swipe inside the first scrollable node (or the whole
     * screen). `left`/`right` cover ViewPager / story / tab strips; `up` is
     * the existing list-scroll default.
     */
    fun directionalSwipe(
        nodes: List<RichNode>,
        direction: String,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val dir = parseSwipeDirection(direction).ifBlank { "up" }
        val scrollable = nodes.filter { it.scrollable && it.right > it.left && it.bottom > it.top }
            .filter { if (dir == "up" || dir == "down") it.bottom - it.top >= 180 else it.right - it.left >= 180 }
            .maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        val left = (scrollable?.left ?: 80).coerceIn(40, 960)
        val right = (scrollable?.right ?: 920).coerceIn(40, 960)
        val top = (scrollable?.top ?: 220).coerceIn(40, 960)
        val bottom = (scrollable?.bottom ?: 780).coerceIn(40, 960)
        val cx = (scrollable?.centerX ?: 500).coerceIn(40, 960)
        val cy = (scrollable?.centerY ?: 500).coerceIn(40, 960)
        val insetX = ((right - left) / 8).coerceIn(40, 120)
        val insetY = ((bottom - top) / 8).coerceIn(40, 120)
        return when (dir) {
            "left" -> (right - insetX to cy) to (left + insetX to cy)
            "right" -> (left + insetX to cy) to (right - insetX to cy)
            "down" -> (cx to top + insetY) to (cx to bottom - insetY)
            else -> {
                val y1 = (bottom - 80).coerceAtLeast(top + 120)
                val y2 = (top + 80).coerceAtMost(y1 - 40)
                (cx to y1) to (cx to y2)
            }
        }
    }

    fun looksLikeSearch(node: RichNode): Boolean {
        val hay = listOf(node.label, node.hint, node.desc, node.id, node.text)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return hay.contains("search") ||
            hay.contains("搜索") ||
            hay.contains("查找") ||
            hay.contains("搜一下") ||
            hay.contains("搜一搜") ||
            hay.contains("query")
    }

    /** Prefer a labeled search box, then the focused field, then a top EditText. */
    fun findSearchField(nodes: List<RichNode>): RichNode? {
        nodes.firstOrNull { it.focused && it.editable && !it.password && looksLikeSearch(it) }
            ?.let { return it }
        nodes.firstOrNull { it.editable && !it.password && looksLikeSearch(it) }?.let { return it }
        nodes.firstOrNull { it.focused && it.editable && !it.password }?.let { return it }
        nodes.firstOrNull { it.editable && !it.password && it.top < 400 }?.let { return it }
        return nodes.firstOrNull { it.clickable && looksLikeSearch(it) }
    }

    fun compactLine(node: RichNode): String {
        val flags = buildString {
            if (node.clickable || node.checkable) append(" tap")
            if (node.longClickable) append(" long")
            if (node.scrollable) append(" scroll")
            if (node.focused) append(" focus")
            if (node.password) append(" password")
            if (node.hasRange) append(" value=${node.rangeText}")
            if (node.state.isNotBlank()) append(" state=${node.state.take(24)}")
            if (node.checkable) append(if (node.checked) " checked" else " unchecked")
            if (node.selected) append(" selected")
            if (!node.enabled) append(" disabled")
        }
        val idSuffix = if (node.id.isNotBlank()) " #${node.id}" else ""
        val hintSuffix = if (node.hint.isNotBlank()) " hint=${node.hint.take(24)}" else ""
        return "#${node.clickIndex} [${roleOf(node)}] ${node.label}$flags$hintSuffix ${node.bounds}$idSuffix [${layoutBand(node)}]"
    }

    fun interactiveNodes(nodes: List<RichNode>): List<RichNode> =
        nodes.filter { it.isInteractive }

    data class TreeDiff(
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        /** "label: 'before' → 'after'" for text that changed on a control that stayed. */
        val textChanged: List<String> = emptyList(),
        val checkedChanged: List<String> = emptyList(),
        /** "label: 30/100 → 70/100" for sliders/progress, and stateDescription changes. */
        val valueChanged: List<String> = emptyList(),
        /** "label: selected→false" / enabled / focused flips. */
        val stateChanged: List<String> = emptyList(),
        /** "down 420" / "up 300" when content moved together (normalized 0..1000 units). */
        val scrolled: String = "",
        val packageBefore: String = "",
        val packageAfter: String = "",
        /** A window that was not there before and sits above the page (dialog, sheet, popup). */
        val newWindow: String = "",
        val windowClosed: Boolean = false,
        val fingerprintChanged: Boolean = false,
        val scopedToTarget: Boolean = false,
        val targetPresent: Boolean = true,
        val targetChanged: Boolean = false,
        val blockingOverlay: Boolean = false,
        val blockingLabel: String = "",
        val blockingIndex: Int = -1,
    ) {
        val packageChanged: Boolean
            get() = packageBefore.isNotBlank() && packageAfter.isNotBlank() && packageBefore != packageAfter

        val changed: Boolean
            get() = if (scopedToTarget) {
                !targetPresent || targetChanged || blockingOverlay || windowClosed || valueChanged.isNotEmpty() || stateChanged.isNotEmpty() ||
                    textChanged.isNotEmpty() || checkedChanged.isNotEmpty() || packageChanged || newWindow.isNotBlank()
            } else {
                fingerprintChanged ||
                    added.isNotEmpty() ||
                    removed.isNotEmpty() ||
                    textChanged.isNotEmpty() ||
                    checkedChanged.isNotEmpty() ||
                    valueChanged.isNotEmpty() ||
                    stateChanged.isNotEmpty() ||
                    scrolled.isNotBlank() ||
                    packageChanged ||
                    newWindow.isNotBlank() ||
                    windowClosed ||
                    blockingOverlay
            }

        /** One line a model can act on without reading the lists. */
        val summary: String
            get() {
                if (!changed) return "no visible change"
                val parts = ArrayList<String>()
                if (packageChanged) parts += "app $packageBefore → $packageAfter"
                if (newWindow.isNotBlank()) parts += "new window: $newWindow"
                if (windowClosed) parts += "a window closed"
                if (scrolled.isNotBlank()) parts += "scrolled $scrolled"
                if (valueChanged.isNotEmpty()) parts += "value " + valueChanged.take(2).joinToString("; ")
                if (checkedChanged.isNotEmpty()) parts += "toggled " + checkedChanged.take(3).joinToString(", ")
                if (textChanged.isNotEmpty()) parts += "text " + textChanged.take(2).joinToString("; ")
                if (stateChanged.isNotEmpty()) parts += stateChanged.take(2).joinToString("; ")
                if (added.isNotEmpty()) parts += "+${added.size} (" + added.take(4).joinToString(", ") + ")"
                if (removed.isNotEmpty()) parts += "-${removed.size} (" + removed.take(4).joinToString(", ") + ")"
                if (blockingOverlay) parts += "blocked by '$blockingLabel'"
                if (parts.isEmpty()) parts += "layout changed"
                return parts.joinToString(" | ").take(400)
            }

        fun toJson(): org.json.JSONObject = org.json.JSONObject()
            .put("summary", summary)
            .put("added", org.json.JSONArray(added.take(12)))
            .put("removed", org.json.JSONArray(removed.take(12)))
            .put("text_changed", org.json.JSONArray(textChanged.take(12)))
            .put("checked_changed", org.json.JSONArray(checkedChanged.take(12)))
            .put("value_changed", org.json.JSONArray(valueChanged.take(12)))
            .put("state_changed", org.json.JSONArray(stateChanged.take(12)))
            .put("scrolled", scrolled)
            .put("package_before", packageBefore)
            .put("package_after", packageAfter)
            .put("package_changed", packageChanged)
            .put("new_window", newWindow)
            .put("window_closed", windowClosed)
            .put("fingerprint_changed", fingerprintChanged)
            .put("changed", changed)
            .put("scoped_to_target", scopedToTarget)
            .put("target_present", targetPresent)
            .put("target_changed", targetChanged)
            .put("blocking_overlay", blockingOverlay)
            .put("blocking_label", blockingLabel)
            .put("blocking_index", blockingIndex)
    }

    private fun shortLabel(node: RichNode): String =
        node.label.ifBlank { node.id }.ifBlank { node.klass }.take(40)

    private fun quoted(value: String): String = "'" + value.replace("\n", " ").take(60) + "'"

    /** The package that owns most of the page (overlays from systemui do not count as a switch). */
    private fun dominantPackage(nodes: List<RichNode>): String =
        nodes.filter { it.packageName.isNotBlank() && !isOverlayWindowType(it.windowType) }
            .groupingBy { it.packageName }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            .orEmpty()

    /** Title of the topmost window that is new in [current]: a dialog, sheet or popup. */
    fun windowKey(node: RichNode): String = if (node.windowId >= 0) "id:${node.windowId}" else "${node.windowIndex}|${node.windowType}|${node.packageName}"

    private fun newTopWindow(previous: List<RichNode>, current: List<RichNode>): String {
        val prevWindows = previous.map(::windowKey).toSet()
        val prevTop = previous.maxOfOrNull { it.windowIndex } ?: -1
        val fresh = current.filter { windowKey(it) !in prevWindows }
        if (fresh.isEmpty()) return ""
        val topIndex = fresh.maxOf { it.windowIndex }
        val window = fresh.filter { it.windowIndex == topIndex }
        val dialogish = window.any { isOverlayClass(it.klass) || isOverlayWindowType(it.windowType) } ||
            topIndex > (current.minOfOrNull { it.windowIndex } ?: 0)
        if (!dialogish) return ""
        val title = window.firstOrNull { it.text.isNotBlank() && !it.clickable }?.text
            ?: window.firstOrNull { it.label.isNotBlank() }?.label
            ?: window.first().klass
        return title.replace("\n", " ").take(60)
    }

    fun treeDiff(
        previous: List<RichNode>,
        current: List<RichNode>,
        target: RichNode? = null,
        guardTarget: Boolean = false,
        scrollContainer: Boolean = false,
    ): TreeDiff {
        // A gesture receipt is an interactive-only dump; dump_tree is a full one. Comparing the two
        // would report every plain text and layout as "removed". When either side is interactive-
        // only, compare interactive nodes on both sides.
        fun interactiveOnly(nodes: List<RichNode>) = nodes.isNotEmpty() && nodes.all { it.isInteractive || isOverlayCandidate(it) }
        val sameKind = interactiveOnly(previous) == interactiveOnly(current)
        val prevKept = previous.filter { !isIgnoredMediaNode(it) && (sameKind || it.isInteractive || isOverlayCandidate(it)) }
        val currKept = current.filter { !isIgnoredMediaNode(it) && (sameKind || it.isInteractive || isOverlayCandidate(it)) }
        val located = target?.let { if (scrollContainer) findScrollContainer(previous, current, it)
            else if (guardTarget) findStableControl(previous, current, it) else findSameControl(current, it) }
        val overlay = target?.let { blockingOverlay(located ?: it, current) }
        if (previous.isEmpty()) {
            return TreeDiff(
                added = currKept.map { it.label.ifBlank { it.klass } }.filter { it.isNotBlank() }.distinct().take(12),
                packageAfter = dominantPackage(current),
                scopedToTarget = target != null,
                targetPresent = target == null || located != null,
                targetChanged = false,
                blockingOverlay = overlay != null,
                blockingLabel = overlay?.label.orEmpty(),
                blockingIndex = overlay?.clickIndex ?: -1,
            )
        }

        // Pair nodes in two passes: exact identity (same place), then semantic identity (same control,
        // moved). The second pass is what turns a scroll into "scrolled" instead of add+remove noise.
        val prevByKey = prevKept.associateBy { it.identityKey() }
        val currByKey = currKept.associateBy { it.identityKey() }
        val pairs = ArrayList<Pair<RichNode, RichNode>>()
        currByKey.forEach { (key, node) -> prevByKey[key]?.let { pairs += it to node } }
        val unmatchedPrev = prevByKey.filterKeys { it !in currByKey }.values.groupBy { it.semanticKey() }
            .mapValues { it.value.toMutableList() }.toMutableMap()
        val unmatchedCurr = ArrayList<RichNode>()
        val moves = ArrayList<Int>()
        currByKey.filterKeys { it !in prevByKey }.values.forEach { node ->
            val candidates = unmatchedPrev[node.semanticKey()]
            val before = candidates?.minByOrNull { kotlin.math.abs(it.centerX - node.centerX) + kotlin.math.abs(it.centerY - node.centerY) }
            val ambiguous = node.label.isBlank() || node.label == node.klass || node.label == node.id
            if (before != null && !ambiguous) {
                candidates.remove(before)
                pairs += before to node
                moves += node.centerY - before.centerY
            } else {
                unmatchedCurr += node
            }
        }
        val added = unmatchedCurr.map { shortLabel(it) }.filter { it.isNotBlank() }.distinct()
        val removed = unmatchedPrev.values.flatten().map { shortLabel(it) }.filter { it.isNotBlank() }.distinct()

        val textChanged = ArrayList<String>()
        val checkedChanged = ArrayList<String>()
        val valueChanged = ArrayList<String>()
        val stateChanged = ArrayList<String>()
        pairs.forEach { (before, node) ->
            val name = shortLabel(node)
            if (before.text != node.text && (before.text.isNotBlank() || node.text.isNotBlank())) {
                val stable = node.id.ifBlank { node.desc }.ifBlank { node.klass }.take(40)
                textChanged += "$stable: ${quoted(before.text)} → ${quoted(node.text)}"
            }
            if (before.checked != node.checked) checkedChanged += "$name: ${if (node.checked) "on" else "off"}"
            if (before.hasRange && node.hasRange && before.rangeValue != node.rangeValue) {
                valueChanged += "$name: ${before.rangeText} → ${node.rangeText}"
            }
            if (before.state != node.state && (before.state.isNotBlank() || node.state.isNotBlank())) {
                valueChanged += "$name: ${quoted(before.state)} → ${quoted(node.state)}"
            }
            if (before.selected != node.selected) stateChanged += "$name: selected=${node.selected}"
            if (before.enabled != node.enabled) stateChanged += "$name: enabled=${node.enabled}"
            if (before.focused != node.focused && (node.editable || before.editable)) stateChanged += "$name: focused=${node.focused}"
        }

        // Scrolled: most moved controls shifted vertically by about the same amount.
        val scrolled = if (moves.size >= 3) {
            val sorted = moves.sorted()
            val median = sorted[sorted.size / 2]
            val agreeing = moves.count { kotlin.math.abs(it - median) <= 12 }
            if (kotlin.math.abs(median) >= 20 && agreeing * 2 >= moves.size) {
                // Content moving up on screen means the user scrolled down the list.
                if (median < 0) "down ${-median}" else "up $median"
            } else ""
        } else ""

        val prevFp = layoutFingerprint(prevKept.map(::toLayoutNode))
        val currFp = layoutFingerprint(currKept.map(::toLayoutNode))
        val targetChanged = when {
            target == null -> false
            located == null -> true
            scrollContainer -> controlChanged(target, located, 3)
            else -> controlChanged(target, located) ||
                (target.hasRange && located.hasRange && target.rangeValue != located.rangeValue) ||
                target.checked != located.checked ||
                target.text != located.text
        }
        val prevTopWindow = previous.maxOfOrNull { it.windowIndex } ?: 0
        val currTopWindow = current.maxOfOrNull { it.windowIndex } ?: 0
        return TreeDiff(
            added = added.take(12),
            removed = removed.take(12),
            textChanged = if (guardTarget) emptyList() else textChanged.distinct().take(12),
            checkedChanged = if (guardTarget) emptyList() else checkedChanged.distinct().take(12),
            valueChanged = if (guardTarget) emptyList() else valueChanged.distinct().take(12),
            stateChanged = if (guardTarget) emptyList() else stateChanged.distinct().take(12),
            scrolled = scrolled,
            packageBefore = dominantPackage(previous),
            packageAfter = dominantPackage(current),
            newWindow = if (guardTarget && overlay == null) "" else newTopWindow(previous, current),
            windowClosed = if (guardTarget && target != null) current.none { windowKey(it) == windowKey(target) }
                else (previous.map(::windowKey).toSet() - current.map(::windowKey).toSet()).isNotEmpty(),
            fingerprintChanged = prevFp != currFp,
            scopedToTarget = target != null,
            targetPresent = target == null || located != null,
            targetChanged = targetChanged,
            blockingOverlay = overlay != null,
            blockingLabel = overlay?.label.orEmpty(),
            blockingIndex = overlay?.clickIndex ?: -1,
        )
    }

    internal fun formatRange(value: Float): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString() else "%.2f".format(value)

    fun findSameControl(nodes: List<RichNode>, target: RichNode): RichNode? {
        val candidates = nodes.filter { node ->
            node.packageName == target.packageName && node.klass == target.klass &&
                node.windowType == target.windowType &&
                (target.windowId < 0 || node.windowId == target.windowId) &&
                if (target.id.isNotBlank()) node.id == target.id else node.label == target.label
        }
        val semantic = candidates.filter { (target.text.isBlank() || it.text == target.text) &&
            (target.desc.isBlank() || it.desc == target.desc) }
        val nearby = semantic.filter { !controlChanged(target, it, 3) }
        return nearby.singleOrNull() ?: semantic.singleOrNull()
    }

    /** Container identity survives row recycling and a collapsing toolbar, not a new window. */
    fun scrollContainerPath(nodes: List<RichNode>, node: RichNode): String {
        val byIndex = nodes.associateBy { it.index }
        return generateSequence(byIndex[node.parent]) { byIndex[it.parent] }.take(60)
            .map { "${it.packageName}:${it.klass}:${it.id}" }.toList().asReversed().joinToString("/")
    }

    fun findScrollContainer(previous: List<RichNode>, current: List<RichNode>, target: RichNode): RichNode? {
        if (!target.scrollable) return null
        val candidates = current.filter { it.scrollable && it.enabled && it.packageName == target.packageName &&
            it.klass == target.klass && it.windowType == target.windowType &&
            (target.windowId < 0 || it.windowId == target.windowId) && (target.id.isBlank() || it.id == target.id) }
        val path = scrollContainerPath(previous, target)
        val related = candidates.filter { scrollContainerPath(current, it) == path }
        if (target.id.isNotBlank() && related.size == 1) return related.single()
        // Anonymous sibling containers need geometry to disambiguate, without binding their rows.
        val overlapping = related.filter { intersectionArea(target, it) * 100 >=
            minOf(nodeArea(target), nodeArea(it)).coerceAtLeast(1) * 60 }
        return overlapping.singleOrNull()
    }

    fun scrollEvidence(previous: List<RichNode>, current: List<RichNode>, container: RichNode,
        direction: String, ignored: Set<String> = emptySet()): org.json.JSONObject {
        val live = findScrollContainer(previous, current, container)
        fun content(nodes: List<RichNode>, root: RichNode?) = if (root == null) emptyList() else nodes.filter {
            it.index != root.index && it.windowId == root.windowId && isAncestorOrSelf(root, it, nodes) &&
                "${it.windowId}|${it.identityKey()}" !in ignored &&
                (it.text.isNotBlank() || it.desc.isNotBlank()) && !it.password
        }
        val before = content(previous, container); val after = content(current, live)
        fun label(n: RichNode) = "${n.klass}|${n.id}|${n.text}|${n.desc}"
        val a = before.groupBy(::label); val b = after.groupBy(::label)
        val horizontal = direction in setOf("left", "right")
        val shifts = a.mapNotNull { (key, old) ->
            val newer = b[key]; if (old.size != 1 || newer?.size != 1) null
            else if (horizontal) newer.single().centerX - old.single().centerX else newer.single().centerY - old.single().centerY
        }.filter { kotlin.math.abs(it) >= 6 }.sorted()
        val median = shifts.getOrNull(shifts.size / 2) ?: 0
        val displacement = kotlin.math.abs(median) >= 12 && shifts.count { kotlin.math.abs(it - median) <= 16 } >= 1
        val contentChanged = a.keys != b.keys && before.isNotEmpty() && after.isNotEmpty()
        return org.json.JSONObject().put("moved", displacement || contentChanged)
            .put("displacement", median).put("matched_moving_anchors", shifts.size)
            .put("visible_content_changed", contentChanged).put("container_present", live != null)
            .put("scope", "container").put("ignored_volatile_nodes", ignored.size)
    }

    /** Bind a recorded control to the current tree without treating its child caption as its id. */
    fun findStableControl(previous: List<RichNode>, current: List<RichNode>, target: RichNode): RichNode? {
        val candidate = findSameControl(current, target) ?: return null
        if (controlChanged(target, candidate, 3) || target.text != candidate.text || target.desc != candidate.desc ||
            target.hint != candidate.hint || target.checked != candidate.checked || target.selected != candidate.selected ||
            target.state != candidate.state || target.rangeText != candidate.rangeText) return null
        val uniqueId = target.id.isNotBlank() &&
            previous.count { it.packageName == target.packageName && it.id == target.id } == 1 &&
            current.count { it.packageName == target.packageName && it.id == target.id } == 1
        val derivedCaption = uniqueId && target.text.isBlank() && target.desc.isBlank() && !target.editable
        if (!derivedCaption && target.label != candidate.label) return null
        fun ancestors(nodes: List<RichNode>, node: RichNode): List<RichNode> {
            val byIndex = nodes.associateBy { it.index }
            val chain = ArrayList<RichNode>()
            var parent = node.parent
            val visited = HashSet<Int>()
            while (parent >= 0 && visited.add(parent) && chain.size < 60) {
                val ancestor = byIndex[parent] ?: break
                chain += ancestor
                parent = ancestor.parent
            }
            return chain
        }
        val beforeParents = ancestors(previous, target)
        val afterParents = ancestors(current, candidate)
        if (beforeParents.size != afterParents.size || beforeParents.zip(afterParents).any { (a, b) ->
                a.packageName != b.packageName || a.id != b.id || a.klass != b.klass ||
                    a.windowId != b.windowId || controlChanged(a, b, 3) || a.text != b.text || a.desc != b.desc
            }) return null
        return candidate
    }

    fun controlChanged(before: RichNode, after: RichNode, slack: Int = 24): Boolean {
        if (before.clickable != after.clickable) return true
        if (before.enabled != after.enabled) return true
        if (before.editable != after.editable) return true
        if (before.focused != after.focused && before.editable) return true
        return kotlin.math.abs(before.left - after.left) > slack ||
            kotlin.math.abs(before.top - after.top) > slack ||
            kotlin.math.abs(before.right - after.right) > slack ||
            kotlin.math.abs(before.bottom - after.bottom) > slack
    }

    /** Pixel input over ordinary accessible controls uses the same node gate. */
    fun controlsAt(nodes: List<RichNode>, x: Int, y: Int): List<RichNode> = nodes.filter {
        it.enabled && (it.clickable || it.editable || it.checkable || it.hasRange) && !it.scrollable &&
            x >= it.left && x < it.right && y >= it.top && y < it.bottom &&
            !Regex("WebView|SurfaceView|TextureView|MapView", RegexOption.IGNORE_CASE).containsMatchIn(it.klass) &&
            (it.editable || it.checkable || it.hasRange || (it.right - it.left) * (it.bottom - it.top) < 350_000)
    }.sortedBy { (it.right - it.left) * (it.bottom - it.top) }

    fun blockingOverlay(target: RichNode, nodes: List<RichNode>): RichNode? {
        // A different window's touchable region determines interception, including transparent holes.
        val windows = nodes.groupBy(::windowKey).values.sortedByDescending { it.first().windowIndex }
        for (window in windows) {
            val root = window.minBy { it.depth }
            if (windowKey(root) == windowKey(target) || root.windowIndex <= target.windowIndex) continue
            if (root.windowTouchRegion.isNotBlank()) {
                val region = runCatching { org.json.JSONArray(root.windowTouchRegion) }.getOrNull()
                if (region != null) {
                    val covered = (0 until region.length()).any { i ->
                        val box = region.optJSONArray(i) ?: return@any false
                        target.centerX >= box.optInt(0) && target.centerX < box.optInt(2) &&
                            target.centerY >= box.optInt(1) && target.centerY < box.optInt(3)
                    }
                    if (covered) return window.firstOrNull { it.text.isNotBlank() || it.desc.isNotBlank() } ?: root
                    continue
                }
            }
            // Legacy data: a transparent system wrapper is not an opaque surface.
            window.firstOrNull { node ->
                val wrapper = node.windowType == 3 && !node.isInteractive && node.klass.contains("Layout") &&
                    node.text.isBlank() && node.desc.isBlank() && !isScrimHint(node.id) &&
                    nodes.any { it.parent == node.index && windowKey(it) == windowKey(node) }
                !wrapper && intersectionArea(target, node) * 100 >= nodeArea(target).coerceAtLeast(1) * 35
            }?.let { return it }
        }
        val byIndex = nodes.associateBy { it.index }
        return nodes.firstOrNull { node ->
            if (windowKey(node) != windowKey(target) || node.index == target.index ||
                isAncestorOrSelf(node, target, nodes) || isAncestorOrSelf(target, node, nodes)) return@firstOrNull false
            if (target.centerX !in node.left until node.right || target.centerY !in node.top until node.bottom) return@firstOrNull false
            // Overlay is a discovery hint, not proof that a transparent parent consumes touches.
            val touchSurface = node.enabled && (node.clickable || node.checkable || node.editable || node.longClickable || node.hasRange)
            val explicitMask = Regex("(^|[_.])(?:scrim|mask|dim|barrier|interstitial)(?:[_.]|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(node.id) || node.klass.endsWith("ScrimView")
            if (!touchSurface && !explicitMask) return@firstOrNull false
            // Drawing order belongs to siblings; compare branches at the common parent.
            val targetPath = generateSequence(target) { byIndex[it.parent] }.take(60).toList()
            val nodePath = generateSequence(node) { byIndex[it.parent] }.take(60).toList()
            val common = targetPath.firstOrNull { a -> nodePath.any { it.index == a.index } }
            val targetBranch = common?.let { c -> targetPath.firstOrNull { it.parent == c.index } }
            val overlayBranch = common?.let { c -> nodePath.firstOrNull { it.parent == c.index } }
            targetBranch != null && overlayBranch != null && overlayBranch.drawingOrder > targetBranch.drawingOrder &&
                (touchSurface || explicitMask)
        }
    }

    /** Complete observation identity; layout-only hashes cannot bind a recycled row or changed text. */
    fun observationFingerprint(nodes: List<RichNode>): Long {
        var hash = layoutFingerprint(nodes.map(::toLayoutNode))
        for (node in nodes) {
            hash = mix(hash, node.index)
            hash = mix(hash, node.parent)
            hash = mix(hash, node.windowId)
            hash = mix(hash, node.packageName)
            hash = mix(hash, node.label)
            hash = mix(hash, node.text)
            hash = mix(hash, node.desc)
            hash = mix(hash, node.hint)
            hash = mix(hash, node.state)
            hash = mix(hash, node.rangeText)
            hash = mix(hash, if (node.enabled) 1 else 0)
            hash = mix(hash, if (node.focused) 1 else 0)
            hash = mix(hash, if (node.checked) 1 else 0)
            hash = mix(hash, if (node.selected) 1 else 0)
        }
        return hash
    }

    fun snapshotId(
        displayId: Int,
        width: Int,
        height: Int,
        packageName: String,
        fingerprint: Long,
        nodeCount: Int,
    ): String {
        var hash = FnvOffset
        hash = mix(hash, displayId)
        hash = mix(hash, width)
        hash = mix(hash, height)
        hash = mix(hash, packageName)
        hash = mix(hash, fingerprint.toInt())
        hash = mix(hash, (fingerprint ushr 32).toInt())
        hash = mix(hash, nodeCount)
        return hash.toULong().toString(16)
    }

    fun wrapUntrustedGuiContent(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return trimmed
        return "$UntrustedGuiOpen\n$trimmed\n$UntrustedGuiClose"
    }

    fun parseIndexQuery(query: String): Int? {
        val trimmed = query.trim()
        if (!trimmed.startsWith("#")) return null
        return trimmed.drop(1).toIntOrNull()
    }

    const val UntrustedGuiOpen = "<untrusted_gui_content>"
    const val UntrustedGuiClose = "</untrusted_gui_content>"

    const val DefaultDumpLimit = 400
    const val DefaultTargetLimit = 120
    const val MaxDumpLimit = 800
    const val CheapDumpQuery = "__aether_cheap__"
}

