package kira.phoneuse.data

import java.io.File

/*
 * The parts of a host app that the Agent Mode code leans on, reduced to what a standalone
 * `app_process` server needs. Everything here replaces an app-level service (settings store,
 * telemetry, the Alpine workspace bridge) with a file-backed or no-op equivalent.
 */

/** How the privileged half is reached. In the plugin it is always this process under the shell uid. */
enum class AgentModeAuthorizationMethod(val storageValue: String, val displayName: String) {
    Root("root", "Root"),
    Shizuku("shizuku", "Shizuku"),
    Adb("adb", "adb shell"),
}

/** The two settings Agent Mode reads. The plugin has no settings screen; adb is the authorization. */
data class AppSettings(
    val agentModeAuthorizationEnabled: Boolean = true,
    val agentModeAuthorizationMethod: AgentModeAuthorizationMethod = AgentModeAuthorizationMethod.Adb,
)

/** Product telemetry hook. The plugin sends none. */
object AetherAnalytics {
    fun capture(event: String, properties: Map<String, Any> = emptyMap()) = Unit
    fun captureException(throwable: Throwable, properties: Map<String, Any> = emptyMap()) = Unit
}

/** The two output helpers of the tool executor that `agent_display` uses. */
object AetherToolExecutor {
    fun sanitizeToolOutputForConversation(toolName: String, output: String): String {
        if (toolName != "agent_display") return output
        val parsed = runCatching { org.json.JSONObject(output) }.getOrNull() ?: return output
        if (!parsed.has("screenshot_base64")) return output
        parsed.remove("screenshot_base64")
        parsed.remove("screenshot_injected_into_next_model_request")
        return parsed.toString()
    }

    fun inferToolOutputOk(output: String): Boolean {
        val parsed = runCatching { org.json.JSONObject(output) }.getOrNull() ?: return true
        if (parsed.optBoolean("visual_only", false)) return true
        return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
    }
}

/** Name of the phone operator subagent profile; learning uses it to recognise phone sessions. */
const val KimiPhoneSubagentProfileName = "phone"

/**
 * Where `agent_display` writes files it was asked to keep (persist_path screenshots, the device app
 * snapshot). Here it is a directory on the phone
 * that the bridge pulls back into the agent's working directory on the computer.
 */
class RuntimeWorkspaceFileBridge(private val root: File) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun writeWorkspaceBytes(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        absolutePath: String,
        bytes: ByteArray,
    ): Result<Long> = runCatching {
        val relative = absolutePath.removePrefix(workspaceDirectory).trimStart('/')
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.canonicalPath)) { "path escapes the workspace: $absolutePath" }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        bytes.size.toLong()
    }
}

/** ABI folder name used by `list_apps` defaults (x86_64 emulators include system apps). */
internal fun alpineHostAbiFolder(abis: Array<out String>): String {
    val primary = abis.firstOrNull().orEmpty()
    if (primary == "x86_64" || primary == "x86") {
        return if ("x86_64" in abis) "x86_64" else ""
    }
    if (primary == "arm64-v8a" || primary == "armeabi-v7a") {
        return if ("arm64-v8a" in abis) "arm64-v8a" else ""
    }
    return when {
        "x86_64" in abis -> "x86_64"
        "arm64-v8a" in abis -> "arm64-v8a"
        else -> ""
    }
}

internal fun sanitizeEvidenceRelativePath(raw: String): String {
    val trimmed = raw.trim().replace('\\', '/').trimStart('/')
    if (trimmed.isBlank()) return ""
    val parts = trimmed.split('/').filter { segment ->
        segment.isNotBlank() && segment != "." && segment != ".."
    }
    if (parts.isEmpty()) return ""
    val joined = parts.joinToString("/")
    val allowed = joined.startsWith("reports/") ||
        joined.startsWith(".aether/") ||
        joined.startsWith("workspace/reports/")
    return if (allowed) joined.removePrefix("workspace/") else ""
}

internal fun resolveEvidencePersistPath(persist: Boolean, persistPath: String, nowMillis: Long): String {
    val explicit = sanitizeEvidenceRelativePath(persistPath)
    if (explicit.isNotBlank()) return explicit
    if (!persist) return ""
    return "reports/evidence/shots/$nowMillis.jpg"
}

data class InstalledSkill(val id: String, val path: String)

/**
 * Where a settled phone flow becomes a reusable skill. A host app would install it into its agent's skill
 * library; the plugin writes the same SKILL.md under its data directory on the phone, and the bridge
 * mirrors that directory to ~/.phone-use/skills on the computer.
 */
class AgentSkillManager(private val root: File) {
    suspend fun upsertAgentModeSkill(
        skillId: String,
        name: String,
        description: String,
        bodyMarkdown: String,
    ): Result<InstalledSkill> = runCatching {
        val safeId = skillId.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "phone-skill" }
        val dir = File(root, safeId).apply { mkdirs() }
        val file = File(dir, "SKILL.md")
        file.writeText(
            buildString {
                appendLine("---")
                append("name: ").appendLine(yamlDoubleQuoted(safeId))
                append("description: ").appendLine(yamlDoubleQuoted(PrivacyRedactor.text(description.ifBlank { name })))
                appendLine("compatibility: phone-use")
                appendLine("---")
                appendLine()
                append("# ").appendLine(PrivacyRedactor.text(name))
                appendLine()
                append(PrivacyRedactor.text(bodyMarkdown.trim()))
                appendLine()
            },
        )
        InstalledSkill(id = safeId, path = file.absolutePath)
    }

    private fun yamlDoubleQuoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}

data class AgentToolEvent(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val outputJson: String? = null,
    val isRunning: Boolean? = null,
    val kind: String = "",
    val status: String = "",
    val diffsJson: String? = null,
)
