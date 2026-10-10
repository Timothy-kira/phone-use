package kira.phoneuse.runtime

import java.io.File
import kira.phoneuse.data.AgentModeSop
import kira.phoneuse.data.AgentModeSopMaturity
import kira.phoneuse.data.PhoneAppPrimitiveClassifier
import kira.phoneuse.data.PrivacyRedactor
import kira.phoneuse.data.UpaMcpProtocolVersion
import org.json.JSONArray
import org.json.JSONObject

/**
 * `phone_sops`: manage what Phone Use has learned (SOPs - the replayable flows behind
 * recall_gui_flows / run_gui_flow) and the privacy rules every stored file goes through.
 */
internal object PhoneSopsMcp {
    const val PluginId = "phone-use-sops"
    const val ToolName = "phone_sops"

    /** Learned-state folders under the data dir that scrub and audit go through. */
    private val StoreDirs = listOf(
        "agent-mode-sops", "agent-mode-scenes", "agent-mode-trajectories", "agent-mode-settlements",
        "agent-mode-apps", "agent-mode-gui-steps", "skills", "diagnostics", "memory",
    )

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", ToolName).put("version", "1"))

    fun listToolsResult(): JSONObject {
        fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf(
                "list", "show", "rename", "set", "delete_step", "set_slot", "delete", "restore", "trash", "empty_trash",
                "history", "revert", "export", "import", "scrub", "audit", "privacy",
            ))))
            .put("id", str("SOP id (sop-xxxxxxxx)."))
            .put("ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "export: SOP ids (default all)."))
            .put("app", str("list: filter by app name or package."))
            .put("query", str("list: filter by words in the goal."))
            .put("goal", str("rename: the new goal text."))
            .put("promoted", JSONObject().put("type", "boolean").put("description", "set: offer it for replay (true) or not."))
            .put("maturity", str("set: candidate | verified | ready | degraded."))
            .put("risk_level", str("set: low | medium | high."))
            .put("index", JSONObject().put("type", "integer").put("description", "delete_step / set_slot: 0-based step index."))
            .put("slot", str("set_slot: parameter name the step types (e.g. phone, query); empty = fixed text."))
            .put("version", str("revert: version id from history."))
            .put("bundle", JSONObject().put("type", "object").put("description", "import: a bundle produced by export."))
            .put("dry_run", JSONObject().put("type", "boolean").put("description", "scrub: report only (default true)."))
            .put("confirm", JSONObject().put("type", "boolean").put("description", "empty_trash / scrub with dry_run=false: required true."))
            .put("enabled", JSONObject().put("type", "boolean").put("description", "privacy: redact stored data (default on)."))
            .put("mask_screenshots", JSONObject().put("type", "boolean").put("description", "privacy: black out passwords and personal values in screenshots (default on)."))
            .put("add_terms", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")).put("description", "privacy: [{value, slot}] names, addresses, etc. that patterns cannot recognise."))
            .put("remove_terms", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("dest", str("export: file on this computer to write the bundle to (default ./phone-sops-<time>.json)."))
            .put("file", str("import: bundle file on this computer (instead of bundle)."))
        return JSONObject().put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("name", ToolName)
                    .put(
                        "description",
                        "Manage learned phone flows (SOPs) and privacy. list (app, query) | show (id: steps, slots, stats) | " +
                            "rename (id, goal) | set (id, promoted, maturity, risk_level) | delete_step / set_slot (id, index, slot) | " +
                            "delete (id: to trash) | restore (id) | trash | empty_trash (confirm) | history (id) | revert (id, version) | " +
                            "export (ids: redacted bundle + audit, saved on this computer) | import (bundle) | " +
                            "scrub (dry_run, confirm: redact everything already stored) | audit (what still looks personal) | " +
                            "privacy (enabled, mask_screenshots, add_terms [{value, slot}], remove_terms). Personal values in learned " +
                            "flows are stored as {{slot}} parameters (phone, email, id_number, card_number, code, or your own terms) " +
                            "and filled in again on replay.",
                    )
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                        .put("required", JSONArray().put("action")).put("additionalProperties", false)),
            ),
        )
    }

    fun call(arguments: JSONObject, runtime: PhoneUseRuntime): JSONObject {
        val result = runCatching { dispatch(arguments, runtime) }
            .getOrElse { JSONObject().put("ok", false).put("errmsg", it.message ?: it.toString()) }
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
            .put("isError", !result.optBoolean("ok", true))
    }

    private fun summary(sop: AgentModeSop): JSONObject = JSONObject()
        .put("id", sop.id)
        .put("goal", sop.goal)
        .put("app", sop.appName.ifBlank { sop.packageName })
        .put("package_name", sop.packageName)
        .put("steps", sop.steps.size)
        .put("slots", JSONArray(sop.steps.map { it.inputSlot }.filter { it.isNotBlank() }.distinct()))
        .put("successes", sop.successCount)
        .put("failures", sop.failureCount)
        .put("confidence", "%.2f".format(sop.confidence))
        .put("promoted", sop.promoted)
        .put("maturity", sop.maturity.storageValue)
        .put("provenance", sop.provenance.storageValue)
        .put("risk_level", sop.riskLevel)
        .put("updated_at_millis", sop.updatedAtMillis)

    private fun notFound(id: String) = JSONObject().put("ok", false).put("code", "NO_SUCH_SOP").put("errmsg", "No SOP '$id'. Use list.")

    /** The replay tools built from a SOP follow its edits (or disappear with it). */
    private fun rebuildCatalog(runtime: PhoneUseRuntime, sop: AgentModeSop?, id: String) {
        runtime.catalogStore.removeSop(id)
        if (sop == null || !sop.promoted || sop.packageName.isBlank()) return
        val primitives = PhoneAppPrimitiveClassifier.classify(
            goal = sop.goal, steps = sop.steps, packageName = sop.packageName, appName = sop.appName, sopId = sop.id,
            successCount = sop.successCount, failureCount = sop.failureCount, confidence = sop.confidence,
            promoted = sop.promoted, provenance = sop.provenance,
        )
        if (primitives.isNotEmpty()) runtime.catalogStore.merge(sop.packageName, sop.appName, primitives)
    }

    private fun dispatch(a: JSONObject, runtime: PhoneUseRuntime): JSONObject {
        val store = runtime.sopStore
        val id = a.optString("id").trim()
        return when (a.optString("action")) {
            "list" -> {
                val app = a.optString("app").trim().lowercase()
                val query = a.optString("query").trim().lowercase()
                val all = store.loadAll()
                    .filter { app.isBlank() || (it.appName + " " + it.packageName).lowercase().contains(app) }
                    .filter { q -> query.isBlank() || query.split(Regex("\\s+")).all { q.goal.lowercase().contains(it) } }
                    .sortedByDescending { it.updatedAtMillis }
                JSONObject().put("ok", true).put("count", all.size).put("sops", JSONArray(all.map(::summary)))
                    .put("trash", store.trash().size)
            }
            "show" -> {
                val sop = store.findById(id) ?: return notFound(id)
                val steps = JSONArray()
                sop.steps.forEachIndexed { i, s ->
                    steps.put(JSONObject().put("index", i).put("action", s.action)
                        .put("target", s.locator.ifBlank { s.guiLabel.ifBlank { s.target } })
                        .put("slot", s.inputSlot).put("text", s.text).put("key", s.key)
                        .put("resource_id", s.resourceId))
                }
                summary(sop).put("ok", true).put("step_list", steps).put("versions", store.history(id).size)
            }
            "rename" -> {
                val goal = a.optString("goal").trim().ifBlank { return JSONObject().put("ok", false).put("errmsg", "rename needs goal") }
                val sop = store.update(id) { it.copy(goal = goal) } ?: return notFound(id)
                rebuildCatalog(runtime, sop, id)
                summary(sop).put("ok", true)
            }
            "set" -> {
                val sop = store.update(id) { s ->
                    var next = s
                    if (a.has("promoted")) next = next.copy(promoted = a.optBoolean("promoted"))
                    if (a.has("maturity")) next = next.copy(maturity = AgentModeSopMaturity.fromStorage(a.optString("maturity")))
                    if (a.has("risk_level")) next = next.copy(riskLevel = a.optString("risk_level").ifBlank { next.riskLevel })
                    next
                } ?: return notFound(id)
                rebuildCatalog(runtime, sop, id)
                summary(sop).put("ok", true)
            }
            "delete_step", "set_slot" -> {
                val index = a.optInt("index", -1)
                val current = store.findById(id) ?: return notFound(id)
                if (index !in current.steps.indices) return JSONObject().put("ok", false).put("errmsg", "index out of range 0..${current.steps.size - 1}")
                val sop = store.update(id) { s ->
                    if (a.optString("action") == "delete_step") {
                        s.copy(steps = s.steps.filterIndexed { i, _ -> i != index })
                    } else {
                        val slot = PrivacyRedactor.slotName(a.optString("slot"), "")
                        s.copy(steps = s.steps.mapIndexed { i, step ->
                            if (i != index) step else step.copy(inputSlot = slot, text = if (slot.isBlank()) step.text else "")
                        })
                    }
                } ?: return notFound(id)
                rebuildCatalog(runtime, sop, id)
                summary(sop).put("ok", true)
            }
            "delete" -> {
                if (!store.delete(id)) return notFound(id)
                val tools = runtime.catalogStore.removeSop(id)
                JSONObject().put("ok", true).put("deleted", id).put("replay_tools_removed", tools).put("hint", "restore brings it back.")
            }
            "restore" -> {
                val sop = store.restore(id) ?: return notFound(id)
                rebuildCatalog(runtime, sop, id)
                summary(sop).put("ok", true)
            }
            "trash" -> JSONObject().put("ok", true).put("sops", JSONArray(store.trash().map(::summary)))
            "empty_trash" -> if (!a.optBoolean("confirm")) {
                JSONObject().put("ok", false).put("code", "CONFIRM_REQUIRED").put("errmsg", "Deletes ${store.trash().size} SOP(s) for good; repeat with confirm:true after the user agrees.")
            } else {
                JSONObject().put("ok", true).put("deleted", store.emptyTrash())
            }
            "history" -> {
                val versions = store.history(id)
                JSONObject().put("ok", true).put("id", id).put("versions", JSONArray(versions.map { (v, s) ->
                    JSONObject().put("version", v).put("goal", s.goal).put("steps", s.steps.size).put("updated_at_millis", s.updatedAtMillis)
                }))
            }
            "revert" -> {
                val sop = store.revert(id, a.optString("version")) ?: return JSONObject().put("ok", false).put("errmsg", "No version '${a.optString("version")}' for $id. Use history.")
                rebuildCatalog(runtime, sop, id)
                summary(sop).put("ok", true)
            }
            "export" -> {
                val wanted = a.optJSONArray("ids")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
                val sops = store.loadAll().filter { wanted == null || it.id in wanted }
                val items = JSONArray()
                val findings = JSONArray()
                sops.forEach { sop ->
                    // Redacted again on the way out, whatever the privacy setting was when it was learned.
                    val json = PrivacyRedactor.json(sop.toJson())
                    items.put(json)
                    PrivacyRedactor.audit(json.toString()).forEach { findings.put("${sop.id}: $it") }
                }
                JSONObject().put("ok", true).put("count", sops.size)
                    .put("bundle", JSONObject().put("format", "phone-use-sops/1").put("exported_at_millis", System.currentTimeMillis()).put("sops", items))
                    .put("audit", findings)
            }
            "import" -> {
                val bundle = a.optJSONObject("bundle") ?: return JSONObject().put("ok", false).put("errmsg", "import needs bundle (from export).")
                val items = bundle.optJSONArray("sops") ?: JSONArray()
                val imported = JSONArray()
                for (i in 0 until items.length()) {
                    val json = items.optJSONObject(i) ?: continue
                    val sop = store.importSop(PrivacyRedactor.json(json))
                    rebuildCatalog(runtime, sop, sop.id)
                    imported.put(summary(sop))
                }
                JSONObject().put("ok", true).put("imported", imported)
            }
            "scrub", "audit" -> scrub(runtime.dataDir, write = a.optString("action") == "scrub" && !a.optBoolean("dry_run", true), confirm = a.optBoolean("confirm"))
            "privacy" -> {
                val cfg = PrivacyRedactor.update { c ->
                    var next = c
                    if (a.has("enabled")) next = next.copy(enabled = a.optBoolean("enabled"))
                    if (a.has("mask_screenshots")) next = next.copy(maskScreenshots = a.optBoolean("mask_screenshots"))
                    a.optJSONArray("add_terms")?.let { arr ->
                        val added = (0 until arr.length()).mapNotNull { i ->
                            val t = arr.opt(i)
                            val (value, slot) = when (t) {
                                is JSONObject -> t.optString("value").trim() to t.optString("slot")
                                is String -> t.trim() to ""
                                else -> return@mapNotNull null
                            }
                            if (value.length < 2) null else PrivacyRedactor.Term(value, PrivacyRedactor.slotName(slot, "private"))
                        }
                        next = next.copy(terms = (next.terms.filterNot { old -> added.any { it.value.equals(old.value, true) } } + added))
                    }
                    a.optJSONArray("remove_terms")?.let { arr ->
                        val gone = (0 until arr.length()).map { arr.optString(it).trim().lowercase() }.toSet()
                        next = next.copy(terms = next.terms.filterNot { it.value.lowercase() in gone })
                    }
                    next
                }
                cfg.toJson().put("ok", true).put("hint", "New rules apply to what is stored from now on; scrub (dry_run:false, confirm:true) applies them to what is already stored.")
            }
            else -> JSONObject().put("ok", false).put("errmsg", "Unknown action '${a.optString("action")}'.")
        }
    }

    private fun scrub(dataDir: File, write: Boolean, confirm: Boolean): JSONObject {
        if (write && !confirm) {
            return JSONObject().put("ok", false).put("code", "CONFIRM_REQUIRED")
                .put("errmsg", "Rewrites stored files in place. Run with dry_run:true first, then confirm:true after the user agrees.")
        }
        var scanned = 0
        var changed = 0
        val findings = JSONArray()
        StoreDirs.map { File(dataDir, it) }.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension in setOf("json", "jsonl", "md") && it.length() < 8_000_000 }.forEach { file ->
                scanned++
                val raw = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val clean = if (file.extension == "md") PrivacyRedactor.text(raw) else PrivacyRedactor.jsonText(raw)
                if (clean != raw) {
                    changed++
                    if (findings.length() < 40) findings.put(file.relativeTo(dataDir).path)
                    if (write) runCatching { file.writeText(clean) }
                }
            }
        }
        return JSONObject().put("ok", true).put("written", write).put("files_scanned", scanned)
            .put(if (write) "files_redacted" else "files_with_personal_values", changed).put("files", findings)
            .put("privacy", PrivacyRedactor.config.toJson())
    }
}
