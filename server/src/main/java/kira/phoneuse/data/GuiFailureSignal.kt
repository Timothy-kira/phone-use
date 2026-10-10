package kira.phoneuse.data

import org.json.JSONObject

internal object GuiFailureSignals {
    data class RepairHint(
        val strategy: String,
        val instruction: String,
    )

    fun attach(
        result: JSONObject,
        action: String,
        snapshot: GuiSnapshot? = null,
        previous: GuiSnapshot? = null,
    ): JSONObject {
        val code = result.optString("code")
        val hint = repairHintFor(code) ?: return result
        result.put(
            "failure",
            JSONObject()
                .put("code", code)
                .put("stage", stageFor(action))
                .put("severity", severityFor(code))
                .put("retryable", retryable(code))
                .put("repair_strategy", hint.strategy)
                .put("repair_instruction", hint.instruction)
                .put("environment_fingerprint", snapshot?.fingerprint ?: 0L)
                .put("previous_fingerprint", previous?.fingerprint ?: 0L),
        )
        return result
    }

    fun failureCode(result: JSONObject): String = result.optJSONObject("failure")
        ?.optString("code")
        .orEmpty()
        .ifBlank { result.optString("code") }

    private fun stageFor(action: String): String = when (action) {
        "launch", "start" -> "start"
        "dump_tree", "screenshot", "list_targets", "wait_idle", "wait_for_label" -> "observe"
        "tap", "click_node", "scroll", "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down",
        "text", "search", "clear_text", "undo", "key", "long_press", "double_tap", "pinch",
        "fling", "scroll_until",
        -> "execute"
        else -> "agent"
    }

    private fun severityFor(code: String): String = when (code) {
        AgentModeSafety.ProtectedWindowCode,
        AgentModeSafety.SensitiveActionCode,
        -> "fatal"
        AgentModeSafety.StalledCode,
        AgentModeSafety.MacroMismatchCode,
        AgentModeSafety.SceneNotSopCode,
        -> "error"
        AgentModeDisplayGate.DisplayBusyCode,
        AgentModeSafety.CaptureTimeoutCode,
        AgentModeSafety.StaleSnapshotCode,
        -> "transient"
        else -> "error"
    }

    private fun retryable(code: String): Boolean = code in setOf(
        AgentModeDisplayGate.DisplayBusyCode,
        AgentModeSafety.CaptureTimeoutCode,
        AgentModeSafety.StaleSnapshotCode,
    )

    private fun repairHintFor(code: String): RepairHint? = when (code) {
        AgentModeDisplayGate.DisplayBusyCode -> RepairHint(
            strategy = "retry_after_short_wait",
            instruction = "Another GUI call is still running on the virtual display. Wait briefly and retry the same segment; do not relaunch or tap the launcher.",
        )
        AgentModeSafety.CaptureTimeoutCode -> RepairHint(
            strategy = "switch_to_dump_tree",
            instruction = "Screenshot capture timed out. Replan this segment with dump_tree/observe=ax before trying another image.",
        )
        AgentModeSafety.StaleSnapshotCode -> RepairHint(
            strategy = "redump_snapshot",
            instruction = "The snapshot_id is stale. Run dump_tree again, then click_node with the new snapshot_id.",
        )
        AgentModeSafety.StalledCode -> RepairHint(
            strategy = "change_input_method",
            instruction = "Recent mutating gestures produced no UI change. Replan the segment; try dump_tree, a different control, or a bounded wait_idle.",
        )
        AgentModeSafety.ProtectedWindowCode -> RepairHint(
            strategy = "abort_and_escalate",
            instruction = "The current window is protected. Stop operating it and ask for user confirmation or teaching before touching this area again.",
        )
        AgentModeSafety.SensitiveActionCode -> RepairHint(
            strategy = "require_explicit_confirmation",
            instruction = "The target looks sensitive. Ask for confirmation before retrying, or choose a safer control.",
        )
        AgentModeSafety.MacroMismatchCode -> RepairHint(
            strategy = "repair_from_current_page",
            instruction = "The stored GUI flow no longer matches the page. Use dump_tree to relocate the control or re-teach the step; do not reuse stale coordinates.",
        )
        AgentModeSafety.SceneNotSopCode -> RepairHint(
            strategy = "recall_flow_instead",
            instruction = "This scene does not have a verified SOP. Recall a verified flow or fall back to raw agent_display controls.",
        )
        "SCROLL_NO_CHANGE" -> RepairHint("inspect_scroll_boundary", "Use the returned screenshot and container reference. Do not repeat unchanged scrolls; inspect the boundary or select another container.")
        "SYSTEM_INTERRUPTION" -> RepairHint("wait_for_overlay", "A notification covers the task. Wait for it to disappear, inspect the screen once, then resume the current goal.")
        "TEXT_NOT_APPLIED" -> RepairHint("verify_editable_field", "Input did not reach a focused editable field. Resolve the correct field once and verify the text; do not submit an unverified query.")
        else -> null
    }
}
