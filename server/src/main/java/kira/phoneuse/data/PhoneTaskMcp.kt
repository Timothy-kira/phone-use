package kira.phoneuse.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * `phone_task`: how the host agent tells Phone Use where a phone task starts and how it ended.
 *
 * The learning runtime watches the host agent's turn: it started a trajectory when the user sent
 * a message and read the phone subagent's GUI_TASK_* markers from its output. A plugin cannot see
 * the host's turns or text, so the host reports both ends explicitly here. Everything downstream is
 * Unchanged learning code: the report goes through [AgentModeLearningRuntime.observeNativeAgentEvent]
 * exactly as a phone subagent's output would, and success still distils SOPs, scenes and settlement.
 */
internal object PhoneTaskMcp {
    const val PluginId = "phone-use-task"
    const val ServerName = "phone_task"
    const val ToolName = "phone_task"

    private val reportCounter = AtomicInteger(0)

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", ServerName).put("version", "1"))

    fun listToolsResult(): JSONObject = JSONObject().put("tools", JSONArray().put(tool()))

    private fun tool(): JSONObject = JSONObject()
        .put("name", ToolName)
        .put(
            "description",
            "Frame a phone task so Phone Use can learn from it. action=begin with the user's goal before the " +
                "first agent_display call of a task (returns similar verified flows and scene notes). " +
                "action=report when the task ends or pauses: status=succeeded|failed|needs_teaching|watching " +
                "with a one-line report of what was done and seen; watching also takes interval (e.g. 10m). " +
                "succeeded/failed close the task and return what was learned: sop_id, an everme_fact to save " +
                "in your long-term memory if you have one, and for watching a watch timer to schedule. " +
                "action=commit_teaching after the user demonstrated on the phone window; action=cancel drops " +
                "the task without learning. begin may include steps (up to 12 small goals). " +
                "checkpoint updates only the current step: step, status=done|blocked, report, checks. Done requires fresh @a receipt checks: package/node/text_equal/text_concat/count/order. " +
                "progress returns steps and feedback without capturing the phone. UI change is not task success.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("action", JSONObject().put("type", "string")
                            .put("enum", JSONArray(listOf("begin", "checkpoint", "progress", "report", "commit_teaching", "cancel"))))
                        .put("goal", JSONObject().put("type", "string").put("description", "For begin: the user's goal, in their words."))
                        .put("steps", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12).put("items", JSONObject().put("type", "string")))
                        .put("checks", checkSchema())
                        .put("step", JSONObject().put("type", "integer").put("minimum", 1))
                        .put("status", JSONObject().put("type", "string")
                            .put("enum", JSONArray(listOf("succeeded", "failed", "needs_teaching", "watching", "done", "blocked"))))
                        .put("report", JSONObject().put("type", "string").put("description", "For report: what was done and seen."))
                        .put("interval", JSONObject().put("type", "string").put("description", "For status=watching: how often to check back, e.g. 10m.")),
                )
                .put("required", JSONArray().put("action"))
                .put("additionalProperties", false),
        )

    private fun checkSchema(): JSONObject = JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12)
        .put("description", "Required for checkpoint done. Each check references a fresh evidence_ref @aN. package: expected package; node: query and expected fields; text_equal: expected raw string or sources; text_concat: independent earlier sources and separator (never reuse ref as its own source, or specify both expected and sources); count/order: exact selector, scope=visible, expected count/list (field defaults to text). refs reads immutable receipt facts.")
        .put("items", JSONObject().put("type", "object").put("required", JSONArray(listOf("kind", "ref")))
            .put("additionalProperties", false).put("properties", JSONObject()
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("package", "node", "text_equal", "text_concat", "count", "order"))))
                .put("ref", JSONObject().put("type", "string").put("description", "Receipt evidence_ref @aN returned by the tool; never #node or UI text."))
                .put("query", JSONObject().put("type", "string").put("description", "For node: #index or unique id/label inside the referenced receipt."))
                .put("expected", JSONObject().put("description", "For node: an object of exact fields, e.g. {text: requiredValue}. For package/text: a string. For count: integer. For order: ordered array."))
                .put("sources", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12).put("items", JSONObject().put("type", "string")))
                .put("separator", JSONObject().put("type", "string"))
                .put("scope", JSONObject().put("type", "string").put("enum", JSONArray().put("visible")))
                .put("selector", JSONObject().put("type", "object"))
                .put("field", JSONObject().put("type", "string"))))

    fun call(
        arguments: JSONObject,
        sessionId: String,
        learning: AgentModeLearningRuntime,
        controller: AgentModeController,
        lane: String,
    ): JSONObject {
        val result = when (arguments.optString("action")) {
            "begin" -> {
                if (arguments.optString("goal").isBlank()) JSONObject().put("ok", false).put("errmsg", "begin needs goal")
                else {
                    val state = AgentDisplayShaper.taskState(lane, arguments)
                    if (!state.optBoolean("ok")) state else begin(arguments, sessionId, learning).put("plan", state)
                }
            }
            "checkpoint", "progress" -> AgentDisplayShaper.taskState(lane, arguments)
            "report" -> {
                if (arguments.has("summary") || arguments.optString("report").isBlank()) return JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject()
                        .put("ok", false).put("code", "BAD_ARGS").put("errmsg", "report requires nonblank report (not summary); status=succeeded|failed|needs_teaching|watching.").toString())))
                    .put("isError", true)
                val state = AgentDisplayShaper.taskState(lane, JSONObject().put("action", "progress"))
                if (arguments.optString("status") == "succeeded" && state.optInt("current_step") > 0)
                    JSONObject().put("ok", false).put("code", "INCOMPLETE_GOAL").put("plan", state)
                        .put("errmsg", "Declared steps remain pending or blocked; do not learn this as a successful task.")
                else if (arguments.optString("status") == "succeeded" && (
                    state.optString("goal").isBlank() || (state.optJSONArray("steps")?.length() ?: 0) == 0 ||
                    state.optBoolean("result_changed_after_verification") ||
                    state.optJSONArray("unknown_operations")?.length()?.let { it > 0 } == true ||
                    (0 until (state.optJSONArray("steps")?.length() ?: 0)).any {
                        state.optJSONArray("steps")?.optJSONObject(it)?.optJSONObject("obstacle")?.optBoolean("unresolved") == true
                    } ||
                    (0 until (state.optJSONArray("steps")?.length() ?: 0)).any {
                        state.optJSONArray("steps")?.optJSONObject(it)?.optJSONObject("verification")?.optBoolean("verified") != true
                    })) JSONObject().put("ok", false).put("code", "UNVERIFIED_GOAL").put("plan", state)
                        .put("errmsg", "Success requires verified step checks and no unknown operation results; no successful flow was learned.")
                else report(arguments, sessionId, learning)
            }
            "commit_teaching" -> {
                controller.finishTeaching()
                val completion = learning.commitTeaching(sessionId)
                JSONObject()
                    .put("ok", completion != null)
                    .put("sop_id", completion?.sop?.id.orEmpty())
                    .put("everme_fact", learning.consumeEverMeCommitPrompt())
                    .put("stdout", if (completion != null) "Teaching saved as a reusable flow." else "No demonstration to save.")
            }
            "cancel" -> {
                learning.cancelTurn(sessionId, arguments.optString("report").ifBlank { "Cancelled by the agent." })
                JSONObject().put("ok", true)
            }
            else -> JSONObject().put("ok", false).put("errmsg", "action must be begin, checkpoint, progress, report, commit_teaching or cancel")
        }
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
            .put("isError", result.optBoolean("ok", true).not())
    }

    private fun begin(arguments: JSONObject, sessionId: String, learning: AgentModeLearningRuntime): JSONObject {
        val goal = arguments.optString("goal").trim()
        if (goal.isBlank()) return JSONObject().put("ok", false).put("errmsg", "begin needs goal")
        learning.beginTurn(sessionId, "task-${UUID.randomUUID().toString().take(8)}", goal)
        val scenes = JSONArray()
        learning.recallScenes(goal).take(3).forEach { scenes.put(it.toJson()) }
        return JSONObject()
            .put("ok", true)
            .put("scenes", scenes)
            .put("stdout", "Task started. agent_display results now carry gui_learning and candidate_sops.")
    }

    private fun report(arguments: JSONObject, sessionId: String, learning: AgentModeLearningRuntime): JSONObject {
        val status = arguments.optString("status").trim().lowercase()
        val marker = when (status) {
            "succeeded" -> AgentModeLearningRuntime.SuccessMarker
            "failed" -> AgentModeLearningRuntime.FailureMarker
            "needs_teaching" -> AgentModeLearningRuntime.NeedsTeachingMarker
            "watching" -> AgentModeLearningRuntime.WatchingMarker
            else -> return JSONObject().put("ok", false).put("errmsg", "status must be succeeded, failed, needs_teaching or watching")
        }
        val text = arguments.optString("report").trim().ifBlank { status }
        val interval = arguments.optString("interval").trim()
        val output = buildString {
            append(marker).append(' ').append(text)
            if (status == "watching" && interval.isNotBlank()) append(" interval=").append(interval)
        }
        // The same path a phone subagent's final output takes.
        val decorated = learning.observeNativeAgentEvent(
            sessionId,
            AgentToolEvent(
                id = "host-report-${reportCounter.incrementAndGet()}",
                name = "Phone agent",
                argumentsJson = JSONObject().put("subagent_type", KimiPhoneSubagentProfileName).toString(),
                outputJson = output,
                isRunning = false,
            ),
        )
        val result = JSONObject().put("ok", true).put("status", status)
        if (status == "succeeded" || status == "failed") {
            val completion = learning.completeTurn(sessionId, outerTurnSucceeded = status == "succeeded", summary = text)
            result.put("verdict", completion?.attempt?.verdict?.storageValue.orEmpty())
            result.put("sop_id", completion?.sop?.id.orEmpty())
        }
        val fact = learning.consumeEverMeCommitPrompt()
        if (fact.isNotBlank()) result.put("everme_fact", fact)
        learning.consumeWatchSchedule()?.let { watch ->
            if (status == "watching") {
                result.put(
                    "watch",
                    JSONObject()
                        .put("interval_ms", watch.intervalMillis ?: DefaultWatchingIntervalMillis)
                        .put("resume_prompt", GuiWatchResumePrompt),
                )
            }
        }
        if (!decorated.isNullOrBlank()) result.put("notes", decorated)
        return result
    }
}
