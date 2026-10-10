package kira.phoneuse.runtime

import kira.phoneuse.data.AppConcurrencyMemory
import kira.phoneuse.data.UpaMcpProtocolVersion
import org.json.JSONArray
import org.json.JSONObject

/**
 * `phone_lanes`: parallel work on one phone. Every phone tool takes `lane` (the bridge passes it
 * along); each lane is its own virtual display. The CLI decides what runs when - this tool shows
 * the lanes, frees them, and says which apps can run side by side and which must wait their turn.
 */
internal object PhoneLanesMcp {
    const val PluginId = "phone-use-lanes"
    const val ToolName = "phone_lanes"

    private val BrowserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.microsoft.emmx", "com.huawei.browser", "com.heytap.browser",
        "com.vivo.browser", "com.mi.globalbrowser", "com.android.browser", "com.sec.android.app.sbrowser",
        "com.UCMobile", "com.tencent.mtt", "com.quark.browser", "org.mozilla.firefox",
    )

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", ToolName).put("version", "1"))

    fun listToolsResult(): JSONObject = JSONObject().put(
        "tools",
        JSONArray().put(
            JSONObject()
                .put("name", ToolName)
                .put(
                    "description",
                    "Parallel work on one phone. Pass lane:\"<name>\" to any phone tool to work in that lane - its own " +
                        "virtual display - so subagents can use different apps at the same time. action=plan (apps: names " +
                        "or packages) says which apps can run in parallel lanes and which must run one after another " +
                        "(same app twice, apps that need the real screen, the browser) - use it to shape the workflow; " +
                        "list shows lanes, their apps and who waits for what; close (lane) frees a lane's display; " +
                        "config (max_lanes, default 4). Text entry from different lanes is queued briefly; a call that " +
                        "must wait longer than its wait_s answers QUEUED and did nothing.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "plan", "close", "config"))))
                            .put("apps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                            .put("lane", JSONObject().put("type", "string").put("description", "close: the lane to free."))
                            .put("max_lanes", JSONObject().put("type", "integer")),
                    ).put("required", JSONArray().put("action")).put("additionalProperties", false),
                ),
        ),
    )

    fun call(arguments: JSONObject, runtime: PhoneUseRuntime): JSONObject {
        val result = runCatching {
            when (arguments.optString("action")) {
                "list" -> runtime.lanesJson()
                "close" -> runtime.closeLane(arguments.optString("lane"))
                "config" -> {
                    if (arguments.has("max_lanes")) runtime.maxLanes = arguments.optInt("max_lanes", 4).coerceIn(1, 8)
                    JSONObject().put("ok", true).put("max_lanes", runtime.maxLanes)
                }
                "plan" -> plan(arguments.optJSONArray("apps") ?: JSONArray(), runtime)
                else -> JSONObject().put("ok", false).put("errmsg", "action must be list, plan, close or config")
            }
        }.getOrElse { JSONObject().put("ok", false).put("errmsg", it.message ?: it.toString()) }
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
            .put("isError", !result.optBoolean("ok", true))
    }

    private fun plan(apps: JSONArray, runtime: PhoneUseRuntime): JSONObject {
        data class App(val asked: String, val pkg: String)
        val resolved = (0 until apps.length()).map { i ->
            val asked = apps.optString(i).trim()
            App(asked, runtime.resolvePackage(asked).orEmpty())
        }
        val unknown = resolved.filter { it.pkg.isBlank() }.map { it.asked }
        val known = resolved.filter { it.pkg.isNotBlank() }
        val serial = JSONArray()
        val notes = JSONArray()
        val parallel = ArrayList<App>()
        // Same app asked for twice: one instance per phone - those tasks run one after another.
        known.groupBy { it.pkg }.forEach { (pkg, group) ->
            when {
                AppConcurrencyMemory.refusesVirtual(pkg) -> Unit // handled below with the real screen
                group.size > 1 -> serial.put(JSONObject().put("apps", JSONArray(group.map { it.asked })).put("package", pkg)
                    .put("reason", "the same app can only be on one screen at a time"))
                else -> parallel += group.first()
            }
        }
        val realScreen = known.filter { AppConcurrencyMemory.refusesVirtual(it.pkg) }.distinctBy { it.pkg }
        if (realScreen.isNotEmpty()) {
            serial.put(JSONObject().put("apps", JSONArray(realScreen.map { it.asked }))
                .put("reason", "these apps do not stay on a virtual display on this phone; they need the real screen, which one lane uses at a time (ask the user before switching)"))
        }
        val browsers = parallel.filter { it.pkg in BrowserPackages }
        if (browsers.size > 1) {
            parallel.removeAll(browsers.toSet())
            serial.put(JSONObject().put("apps", JSONArray(browsers.map { it.asked })).put("reason", "web pages share one browser; run them in one lane, or as tabs in phone_browser"))
        }
        val free = (runtime.maxLanes - runtime.laneNames().size + 1).coerceAtLeast(1) // +1: main lane is reusable
        val lanesOut = JSONArray()
        parallel.forEachIndexed { i, app ->
            val owner = runtime.scheduler.ownerOf("pkg:${app.pkg}")
            lanesOut.put(JSONObject().put("lane", owner ?: "lane${i + 1}").put("app", app.asked).put("package", app.pkg)
                .apply { if (owner != null) put("note", "already open in lane $owner - reuse that lane") })
        }
        if (parallel.size > free) notes.put("${parallel.size} parallel apps but ${free} lane(s) free (max_lanes ${runtime.maxLanes}): run them in waves of $free.")
        notes.put("Typing from different lanes is queued for a moment (one editor input focus); everything else runs side by side.")
        return JSONObject().put("ok", true)
            .put("parallel", lanesOut)
            .put("serial", serial)
            .put("unknown_apps", JSONArray(unknown))
            .put("lanes_free", free)
            .put("notes", notes)
    }
}
