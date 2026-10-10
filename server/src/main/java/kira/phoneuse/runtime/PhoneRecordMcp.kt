package kira.phoneuse.runtime

import kira.phoneuse.data.UpaMcpProtocolVersion
import org.json.JSONArray
import org.json.JSONObject

/** `phone_record`: screen recording of the display the agent works on, plus its action timeline. */
internal object PhoneRecordMcp {
    const val PluginId = "phone-use-record"
    const val ToolName = "phone_record"

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "phone_record").put("version", "1"))

    fun listToolsResult(): JSONObject = JSONObject().put(
        "tools",
        JSONArray().put(
            JSONObject()
                .put("name", ToolName)
                .put(
                    "description",
                    "Record the phone screen the agent is working on (virtual display, or the real screen after " +
                        "switch_display) to MP4, with an actions.jsonl timeline of every agent_display call and " +
                        "its offset into the video. action=start (optional name, fps 2-30 default 15, " +
                        "bitrate_kbps default 3000, segment_minutes default 10), action=status, action=stop " +
                        "(the files are copied to this computer and their paths returned). Recording continues " +
                        "across display switches. Nothing is recorded until start; there is no audio.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "stop", "status"))))
                                .put("name", JSONObject().put("type", "string"))
                                .put("fps", JSONObject().put("type", "integer"))
                                .put("bitrate_kbps", JSONObject().put("type", "integer"))
                                .put("max_edge", JSONObject().put("type", "integer"))
                                .put("segment_minutes", JSONObject().put("type", "number")),
                        )
                        .put("required", JSONArray().put("action"))
                        .put("additionalProperties", false),
                ),
        ),
    )

    fun call(arguments: JSONObject, recorder: PhoneUseRecorder): JSONObject {
        val result = when (arguments.optString("action")) {
            "start" -> recorder.start(arguments)
            "stop" -> recorder.stop()
            "status" -> recorder.status()
            else -> JSONObject().put("ok", false).put("errmsg", "action must be start, stop or status")
        }
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
            .put("isError", !result.optBoolean("ok", true))
    }
}
