package kira.phoneuse.agentmode

import android.os.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class OwnedInputClient {
    private fun shell(vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); throw TextEditFailure("INPUT_SETUP_TIMEOUT", "Input service setup timed out.") }
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() != 0) throw TextEditFailure("OWNED_INPUT_NOT_READY", output.take(400))
        return output
    }
    fun edit(displayId: Int, args: JSONObject, shouldStop: () -> Boolean): JSONObject {
        var lease = false
        var original = ""
        var receipt: JSONObject? = null
        fun reply(value: JSONObject): JSONObject { receipt = value; return value }
        val token = UUID.randomUUID().toString()
        fun transact(command: JSONObject): JSONObject {
            // Content's shell entry point can acquire a provider without an application thread.
            // Arguments go directly to ProcessBuilder, never through shell interpolation.
            val process = ProcessBuilder("content", "call",
                "--uri", "content://kira.phoneuse.input", "--method", "command", "--arg", command.toString())
                .redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread({ process.inputStream.bufferedReader().use { out.append(it.readText()) } }, "owned-input-reply")
            reader.start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly(); reader.join(200)
                throw TextEditFailure("INPUT_RESULT_UNKNOWN", "Input command timed out; inspect before retrying.", command.optString("edit_action") !in setOf("", "read"))
            }
            reader.join(500)
            val raw = out.toString()
            if (process.exitValue() != 0 || !raw.contains("result=")) throw TextEditFailure("OWNED_INPUT_NOT_READY", raw.takeLast(700))
            return JSONObject(raw.substringAfter("result=").substringBeforeLast("}]").trim())
        }
        try {
            if (shouldStop()) throw TextEditFailure("OPERATION_STOPPED", "Input cancelled before acquiring its lease.")
            val begin = transact(JSONObject().put("command", "begin").put("token", token))
            if (!begin.optBoolean("ok")) return reply(begin)
            lease = true
            original = begin.optString("original_input_method")
            shell("ime", "enable", OwnedInputService.Component)
            shell("ime", "set", OwnedInputService.Component)
            val request = JSONObject(args.toString()).put("token", token).put("display_id", displayId)
            val until = SystemClock.uptimeMillis() + 1800
            var read = JSONObject()
            do {
                if (shouldStop()) throw TextEditFailure("OPERATION_STOPPED", "Input cancelled before dispatch.")
                read = transact(JSONObject(request.toString()).put("edit_action", "read"))
                if (read.optBoolean("ok") || read.optString("code") != "INPUT_CONNECTION_UNAVAILABLE") break
                SystemClock.sleep(60)
            } while (SystemClock.uptimeMillis() < until)
            if (!read.optBoolean("ok")) return reply(read)
            if (request.optString("edit_action") == "read") return reply(read)
            if (shouldStop()) throw TextEditFailure("OPERATION_STOPPED", "Input cancelled before dispatch.")
            // Single write only. Unknown/rejected results are never retried through another backend.
            return reply(transact(request))
        } catch (error: Throwable) { return reply(TextEdits.failure(error)) }
        finally {
            if (lease) {
                runCatching { transact(JSONObject().put("command", "restore").put("token", token)) }
                runCatching {
                    val current = shell("settings", "get", "secure", "default_input_method").trim()
                    // Synchronize InputMethodManager as well as Settings. A later manual choice wins.
                    if (original.isNotBlank() && current in setOf(original, OwnedInputService.Component)) shell("ime", "set", original)
                    val after = shell("settings", "get", "secure", "default_input_method").trim()
                    if (after == OwnedInputService.Component) throw TextEditFailure("INPUT_RESTORE_FAILED", "The owned input service is still selected; its previous service was not restored.")
                    receipt?.put("input_service_restored", after == original)?.put("input_service_released", true)
                }.onFailure { error ->
                    receipt?.put("input_service_restored", false)?.put("input_restore_error", error.message)
                    if (receipt?.optBoolean("ok") == true) receipt?.put("ok", false)?.put("code", "INPUT_RESTORE_FAILED")
                        ?.put("errmsg", "Input result retained, but input-service restoration failed. Inspect before retrying.")
                }
            }
        }
    }
}
