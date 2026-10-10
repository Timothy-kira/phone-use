package kira.phoneuse.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-process MCP for Agent Mode. Kimi ACP has no Pi host_tools channel, so
 * `agent_display` is exposed on the same loopback HTTP gateway as UPA plugins.
 * The tools operate the apps actually installed on this phone.
 */
internal object AgentDisplayMcp {
    const val PluginId = "aether-agent-display"
    const val ToolName = "agent_display"
    const val TextToolName = "phone_text"
    const val ServerName = "agent_display"

    const val ToolDescription =
        "Control apps actually installed on this Android phone (WeChat, Settings, Chrome, " +
            "the real launcher, and other launcher icons). Every action answers with an outline of the screen " +
            "(screen, screen_info): read more only when you need it - find {grep: regex, role, region: top|middle|bottom|#n, " +
            "offset, limit} filters the current screen's lines, read_text {query:#n} gives one element's full text, " +
            "zoom {query:#n | region} a sharp crop to read small text, detail:\"full\" the complete payload; " +
            "collect {query?:#list, cursor?} reads a bounded segment of a long list from the current viewport, each with a stable reference @rN by position " +
            "(rows with the same title stay apart); pin {query:#n, note?} names any element @pN; any query/at may be " +
            "@rN/@pN and the item is brought on screen first; note {query:@ref, note} keeps what you found on an item, a " +
            "screen (@sN, every answer has screen_ref) or an action (@aN, action_ref); refs {query?:@ref} shows the graph " +
            "and how a screen was reached. phone_time is the phone's clock: today, weekdays and this week mean its date. " +
            "Each app has a map that grows as you use it (kept across sessions, per phone and app version): goto {to:@sN} " +
            "walks back to a screen seen before; map {query:\"waypoint\"} finds the screen offering a feature and the " +
            "route there; explore {depth?, budget?} - only when stuck - taps the safe controls once and comes back. " +
            "After the conversation was compacted (or references are unclear) call recap. " +
            "peek {query:#n} shows what tapping an element would open, then comes back (never presses send/delete/pay/save-" +
            "like elements). tap/long_press take at:\"center\" or at:\"#n\" instead of x/y. Maps: open_url " +
            "with package:<requested app> routes a deep link to that app; without package the system resolver may open another app. " +
            "Use supplied or observed and verified values for exact inputs; a failed lookup does not justify guessed values. " +
            "geo:LAT,LON?z=17 centres the specified map app on those coordinates; long_press at:\"center\" marks that point. " +
            "list_apps returns this device's " +
            "packages. launch opens that real package on the Agent Mode virtual phone, which " +
            "matches this device's screen. Launch directly when the app label or package is known; " +
            "use list_apps only for an ambiguous target or a failed launch. Coordinates for tap/" +
            "swipe are 0..1000 normalized to the virtual phone. Batch consecutive tap/swipe in " +
            "actions[]. Scrolling ends a batch and returns a screenshot after settling, with fresh snapshot and refs. " +
            "Other gestures return a screenshot with observe=true; screenshot is also an independent read. Do not tap an input just to type; call text/clear_text/undo on the " +
            "focused field. Use dump_tree (region=top/middle/bottom, query, offset/limit) or " +
            "click_node clicks by 1-based #index from dump_tree (ACTION_CLICK). " +
            "dump_tree defaults to interactive JSON with index, bounds, resource-id, flags; " +
            "observe=ax|full uses that tree, observe=text returns visible text. " +
            "Every mutating receipt includes tree_diff versus the previous tree — informational, " +
            "not a reason to replan after every gesture. Simple tasks finish in one stage; " +
            "complex tasks replan only at stage boundaries or on STALLED/MACRO_MISMATCH. " +
            "Use long_press, double_tap, pinch, fling, scroll_until, swipe_left/swipe_right/" +
            "swipe_up/swipe_down, search, wait_idle, wait_for_label " +
            "for real touchscreen flows. screenshot accepts crop_left/top/right/bottom or crop_region. " +
            "Pass observe=som to overlay numbered targets on the screenshot. If cluttered, skip unlabeled ImageView/layout chrome " +
            "or pass simplify=true. If unsure after retries, call request_teaching with a " +
            "简体中文 hint, then poll teaching_status until the user demonstrates on the virtual " +
            "screen. Do not invent emulator-only apps. " +
            "For a playing video or lesson, watch the screen with dump_tree or screenshot " +
            "(简介, title and quiz overlays included) and report watching through phone_task. " +
            "Never ask for MediaProjection or screen recording. If the " +
            "virtual display reports NEEDS_REAL_SCREEN, use the authorized real display or report the obstacle; do not keep retrying.  Extra actions: back, home, recents, notifications, force_stop."

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    fun listToolsResult(): JSONObject = JSONObject().put(
        "tools",
        JSONArray().put(textTool()).put(
            JSONObject()
                .put("name", ToolName)
                .put("description", ToolDescription)
                .put("inputSchema", inputSchema()),
        ),
    )

    private fun textTool(): JSONObject = JSONObject().put("name", TextToolName)
        .put("description", "Read or edit the focused Android text field using accessibility or Phone Use's owned background InputConnection. No clipboard or third-party keyboard. Actions read/set/insert/replace/delete/clear/undo/submit share exact read-back verification. Exact copies/merges may use source_refs:[@aN,...] (complete read receipts) plus optional separator instead of text, preserving all whitespace without retyping. Range edits require complete source SHA256 and UTF-16 [start,end); undo is bound to the same field and after-hash. Submit acknowledgement is neutral; verify actual results. Credentials require user takeover. Optional query/at focuses an observed node through the same click gates.")
        .put("inputSchema", JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray().put("action"))
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("read", "set", "insert", "replace", "delete", "clear", "undo", "submit"))))
                .put("text", JSONObject().put("type", "string"))
                .put("source_refs", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12).put("items", JSONObject().put("type", "string")))
                .put("separator", JSONObject().put("type", "string"))
                .put("position", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "end", "cursor"))))
                .put("query", JSONObject().put("type", "string")).put("at", JSONObject().put("type", "string"))
                .put("snapshot_id", JSONObject().put("type", "string"))
                .put("offset_chars", JSONObject().put("type", "integer").put("minimum", 0))
                .put("limit_chars", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 8192))
                .put("start", JSONObject().put("type", "integer").put("minimum", 0))
                .put("end", JSONObject().put("type", "integer").put("minimum", 0))
                .put("expected_sha256", JSONObject().put("type", "string").put("pattern", "^[a-fA-F0-9]{64}$"))
                .put("observe", JSONObject().put("type", "boolean"))
                .put("detail", JSONObject().put("type", "string"))))

    fun normalizeTextArguments(args: JSONObject) {
        val edit = args.optString("action").lowercase()
        args.put("edit_action", edit).put("action", if (edit == "read") "read_field" else "edit_text")
    }

    fun wrapCallResult(rawOutput: String, arguments: JSONObject = JSONObject(), lane: String = "main"): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val imageData = parsed?.optString("screenshot_base64").orEmpty()
        val imageMimeType = parsed?.optString("screenshot_mime_type")
            .orEmpty()
            .ifBlank { "image/jpeg" }
        val structured = parsed?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("raw", rawOutput)
        structured.remove("screenshot_base64")
        structured.remove("screenshot_injected_into_next_model_request")
        // An outline by default; the agent reads details with find / read_text / zoom (or detail:"full").
        if (parsed != null) AgentDisplayShaper.shape(structured, arguments, lane)
        val visible = AetherToolExecutor.sanitizeToolOutputForConversation(ToolName, structured.toString())
        val isError = !AetherToolExecutor.inferToolOutputOk(visible)
        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", visible),
        )
        if (imageData.isNotBlank()) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", imageData)
                    .put("mimeType", imageMimeType),
            )
        }
        // No structuredContent: hosts show it next to the text, so the model read every result twice.
        return JSONObject()
            .put("content", content)
            .put("isError", isError)
    }

    fun inputSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "action",
                    stringProperty(
                        "One of: list_apps, start, status, launch, tap, scroll, swipe, swipe_left, swipe_right, swipe_up, swipe_down, " +
                            "key, text, search, clear_text, undo, screenshot, " +
                            "list_targets, dump_tree, click_node, long_press, double_tap, pinch, fling, scroll_until, " +
                            "wait_idle, wait_for_label, request_teaching, teaching_status, " +
                            "drag, set_progress, switch_display, open_url, read_text, find, zoom, finish_teaching, " +
                            "back, home, recents, notifications, notification_protection, force_stop, stop. " +
                            "read_text (query #n, or none for the focused field) returns an element's complete text with line numbers - a whole document in an editor or a long list - without scrolling; dump_tree shortens long text. " +
                            "list_apps / launch use this phone's installed packages. open_url opens url (http/https/file) in the phone's browser (or package) on the agent screen; for real web work use the phone_browser tool. " +
                            "dump_tree returns interactive accessibility JSON (1-based i plus 0-based index) " +
                            "with resource-id, class, hint, selected, checked, enabled, scrollable, password, " +
                            "package_name, parent, and drawing-order. Pass observe=text for visible text only or delta for changed lines only. " +
                            "Pass region=top|middle|bottom and query/offset/limit to page a dense screen. " +
                            "list_targets returns numbered accessibility tap targets using the same #index as dump_tree. " +
                            "observe=ax|text|image|full|som|delta. observe=som overlays those numbers on the screenshot. " +
                            "screenshot can crop with crop_region or crop_left/top/right/bottom. " +
                            "click_node(#n, snapshot_id) performs ACTION_CLICK on that snapshot node; " +
                            "coordinates are only for WebView/games (used_backend=inject_tap). " +
                            "tree_diff is informational; replan at stage boundaries, not after every tap. " +
                            "If the page is cluttered, pass simplify=true to drop unlabeled chrome. " +
                            "request_teaching asks the user to demonstrate on the virtual screen. " +
                            "Gestures do not return a screenshot unless observe=true or action is screenshot. " +
                            "Use actions[] to send several gestures in one call. " +
                            "search types text into the search field and submits with Enter (IME search); " +
                            "most apps do not need a separate 搜索 button tap. " +
                            "Sliders: set_progress(query=#n, value) sets an accessible slider exactly; " +
                            "drag(query=#n, to=0..1) or drag(x1,y1,x2,y2) moves a thumb or handle slowly with no fling " +
                            "(hold_ms>0 first picks the item up, for drag-to-reorder). " +
                            "switch_display(to=real|virtual) moves the work - and the current app - between the private " +
                            "virtual display and the phone's real screen; use to=real only after the user agreed, and " +
                            "switch back when done. A result with suggest_display=real or code NEEDS_REAL_SCREEN means " +
                            "the task needs the real screen: ask the user first. " +
                            "swipe_left/swipe_right/swipe_up/swipe_down (or swipe/fling/scroll_until with " +
                            "direction=left|right|up|down) swipe inside the scrollable region; " +
                            "coordinates are optional for those named directions.",
                    ),
                )
                put(
                    "actions",
                    JSONObject()
                        .put("type", "array")
                        .put(
                            "description",
                            "Optional batch of gestures. Each item uses the same fields as a single call " +
                                "(action, x, y, ...). Only the last step is observed when observe is true.",
                        )
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put("additionalProperties", true),
                        ),
                )
                put(
                    "observe",
                    JSONObject()
                        .put(
                            "description",
                            "When true, capture a screenshot and compact tree after this call (or after the last " +
                                "batched action). Defaults to false. Pass ax, text, image, full, \"som\" " +
                                "(overlay numbered tap targets), or delta (changed lines only). Boolean true means full.",
                        ),
                )
                put(
                    "simplify",
                    booleanProperty(
                        "For list_targets: when true, drop unlabeled ImageView/layout chrome. " +
                            "Use only if click_targets is too crowded. Defaults to false.",
                    ),
                )
                put("query", stringProperty("For list_apps: app label/package filter. For dump_tree/list_targets/click_node: GUI text, resource-id, #index or @r/@p reference. For scroll or swipe: #index or pinned @p reference of the scrollable container; direction defaults to up (content forward). Reuse the current screenshot/tree reference rather than guessing new coordinates."))
                put("timeout_ms", integerProperty("Initial wait_idle/wait_for_label budget in milliseconds; bounded retries never replay a gesture."))
                put("quiet_ms", integerProperty("Required native quiet interval in milliseconds for wait_idle."))
                put("ignoreCase", booleanProperty("For find/grep: case-insensitive matching when true (default true)."))
                put("offset_chars", integerProperty("For read_text: exact character offset, in UTF-16 units; follows next_offset_chars."))
                put("limit_chars", integerProperty("For read_text: raw text page length, max/default 8192 UTF-16 units. complete=false requires more pages."))
                put("retries", integerProperty("For wait_idle: additional read-only stability attempts, 0..2 (default 2). timeout_ms is the initial attempt budget; later attempts extend it up to 2000 ms. No gesture is repeated."))
                put("snapshot_id", stringProperty("For click_node: dump_tree snapshot_id. Bound to the observed control. Unrelated animation may change the tree; moved, replaced, ambiguous or covered controls are rejected without a click."))
                put("enabled", booleanProperty("For notification_protection: true enables the visible-notification guard (default); false disables it. Omit to query. Stored per profile/lane; does not toggle system Do Not Disturb. BACK/HOME and explicit System UI node actions stay usable."))
                put("confirm", booleanProperty("For click_node: required true when the control label looks like pay/delete/install/grant."))
                put("region", stringProperty("For find/dump_tree/list_targets: top, middle, bottom (find also #n = inside that element). For screenshot/zoom: the area to crop."))
                put("to", stringProperty("For goto: a screen reference @sN, or words naming a feature."))
                put("app", stringProperty("For map/goto: package or app name (default: the app on screen)."))
                put("depth", JSONObject().put("type", "integer").put("description", "For explore: levels to go down (1-3, default 1)."))
                put("budget", JSONObject().put("type", "integer").put("description", "For explore: most taps to spend (default 15)."))
                put("note", stringProperty("For pin/note: what you found about this item, screen or action."))
                put("at", stringProperty("For tap/long_press: \"center\" (middle of the screen) or \"#n\" (middle of that element), instead of x/y."))
                put("grep", stringProperty("For find/dump_tree: regex matched against each screen line (text, description, resource-id, hint); case-insensitive."))
                put("role", stringProperty("For find/dump_tree: element roles to keep, e.g. button, input, check, switch, list, text, img, or interactive."))
                put("detail", stringProperty("Any action: full returns the complete payload (whole tree, all fields) instead of the outline."))
                put("offset", integerProperty("For dump_tree/find: skip this many matching lines."))
                put("limit", integerProperty("For dump_tree: page size. Defaults to 80, max 800."))
                put("crop_left", integerProperty("For screenshot: left crop edge in 0..1000 coordinates."))
                put("crop_top", integerProperty("For screenshot: top crop edge in 0..1000 coordinates."))
                put("crop_right", integerProperty("For screenshot: right crop edge in 0..1000 coordinates."))
                put("crop_bottom", integerProperty("For screenshot: bottom crop edge in 0..1000 coordinates."))
                put("crop_region", stringProperty("For screenshot: top, middle, bottom, left, or right."))
                // Constant, deliberately: this string is part of the tool declaration, which sits
                // in the system layer ahead of everything the context folder keeps byte-stable. A
                // description that varies by device is also a description that varies between the
                // cached prefix and the next request whenever anything about the environment is
                // re-read. It stated the branch rather than the rule; stating the rule is both
                // stable and more informative to the model.
                //
                // Reading `Build.SUPPORTED_ABIS` here was additionally why this tool's list could
                // not be built off-device at all: on the JVM the field is null and `listToolsResult`
                // threw. The default still differs by ABI - that decision just belongs in the
                // handler, where it is behaviour, not in the schema, where it is prompt text.
                put(
                    "include_system",
                    booleanProperty(
                        "For list_apps: include system/launcher apps. Defaults to true on x86_64 emulators, where Clock, Contacts, Gmail and Settings are system apps, and false on physical devices, where it returns user-installed apps.",
                    ),
                )
                put("max_results", integerProperty("For list_apps: maximum number of apps to return."))
                put(
                    "persist",
                    booleanProperty(
                        "For screenshot: write the JPEG under reports/ and return workspace_path. " +
                            "Defaults to false. Do not persist routine observe frames.",
                    ),
                )
                put(
                    "persist_path",
                    stringProperty(
                        "For screenshot: workspace-relative path under reports/ or .aether/. " +
                            "Implies persist. Example: reports/yunnan/shots/hotel.jpg.",
                    ),
                )
                put("target", stringProperty("For launch: package name or exact app label of an app installed on this phone."))
                listOf("x", "y", "x1", "y1", "x2", "y2").forEach { key ->
                    put(key, integerProperty("0..1000 normalized coordinate for $key. Values above 1000 are treated as pixels."))
                }
                put(
                    "duration_ms",
                    integerProperty("For swipe/fling/pinch: milliseconds. Use 300-500 for swipe; under 200 often fails to scroll."),
                )
                put(
                    "direction",
                    stringProperty(
                        "For native scroll/swipe/fling/scroll_until: finger direction left, right, up, or down. Native scroll up advances content; down returns toward the beginning. dy and to are unsupported for native scroll. " +
                            "left/right are horizontal (ViewPager, stories, tabs). " +
                            "Named actions swipe_left/swipe_right/swipe_up/swipe_down set this automatically.",
                    ),
                )
                listOf("start_span", "end_span").forEach { key ->
                    put(key, integerProperty("Normalized coordinate or span for $key."))
                }
                put("key", stringProperty("For key: Android key code name or number."))
                put("to", JSONObject().put("description", "For switch_display: real or virtual. For drag with query: target position along the control, 0..1."))
                put("bring_app", booleanProperty("For switch_display: move the current app to the target screen too. Defaults to true."))
                put("hold_ms", integerProperty("For drag: press and hold this long before moving (picks up a list item). Defaults to 0."))
                put("value", JSONObject().put("type", "number").put("description", "For set_progress: the slider value, in the slider's own range (see value= in dump_tree)."))
                put("text", stringProperty("For text: text to paste into the focused field. Do not tap the field first unless it is not focused. Prefer SET_TEXT; never open the IME. For search: the query to type, then Enter. For request_teaching: 简体中文 hint of what the user should demonstrate."))
                put("offset_lines", integerProperty("For read_text: first line to return (0-based)."))
                put("limit_lines", integerProperty("For read_text: lines to return (default 400)."))
                put("position", stringProperty("For text: start | end | cursor inserts ONLY the given text into the focused field and keeps everything already in it exactly (line breaks you cannot see included). Do not repeat the existing content. Example - a header line plus a blank line above existing text: text=\"HEADER\\n\\n\", position=\"start\". The result shows field_now (line breaks as ⏎) to check it; read_text shows the whole field. Default (no position): replace the field's whole content."))
                put("url", stringProperty("For open_url: the address to open."))
                put("package", stringProperty("For open_url: browser package to use (default: the phone's default browser, else Chrome)."))
                put("max_swipes", integerProperty("For scroll_until: default 6, at most 12. For collect: default 3 per segment, at most 12, also bounded by the call deadline."))
                put("cursor", stringProperty("For collect: continue the returned cursor in the same session/lane and container. No automatic rewind."))
                put("hint", stringProperty("For request_teaching: what the user should demonstrate (same as text)."))
                put("mode", stringProperty("For switch_display: alias of to (real or virtual)."))
            },
        )
        put("required", JSONArray().put("action"))
        put("additionalProperties", false)
    }

    private fun stringProperty(description: String): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)

    private fun integerProperty(description: String): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)

    private fun booleanProperty(description: String): JSONObject = JSONObject()
        .put("type", "boolean")
        .put("description", description)
}

internal const val AetherLearningSessionHeader = "X-Aether-Session-Id"

