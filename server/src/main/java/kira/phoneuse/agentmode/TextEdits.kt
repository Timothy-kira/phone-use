package kira.phoneuse.agentmode

import org.json.JSONObject
import java.security.MessageDigest

internal class TextEditFailure(val code: String, message: String, val dispatched: Boolean = false) : Exception(message)

/** One edit/undo protocol shared by accessibility and the owned InputConnection backend. */
internal object TextEdits {
    private data class Undo(val field: String, val before: String, val afterHash: String)
    private var undo: Undo? = null
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun fail(code: String, message: String): Nothing = throw TextEditFailure(code, message)
    @Synchronized fun desired(args: JSONObject, field: String, before: String, selectionStart: Int, selectionEnd: Int): String {
        val action = args.optString("edit_action", "set")
        val text = args.optString("text")
        val expected = args.optString("expected_sha256").lowercase()
        if (expected.isNotBlank() && expected != hash(before)) fail("TEXT_HASH_MISMATCH", "The focused field differs from the expected complete text. Nothing was written.")
        fun range(start: Int, end: Int): String {
            if (start < 0 || end < start || end > before.length) fail("INVALID_TEXT_RANGE", "Use UTF-16 [start,end) within the complete field.")
            fun split(i: Int) = i > 0 && i < before.length && before[i-1].isHighSurrogate() && before[i].isLowSurrogate()
            if (split(start) || split(end)) fail("INVALID_TEXT_RANGE", "The range would split a surrogate pair.")
            return before.substring(0, start) + (if (action == "delete") "" else text) + before.substring(end)
        }
        return when(action) {
            "set" -> text
            "clear" -> ""
            "insert" -> {
                val at = when(args.optString("position", "cursor")) { "start" -> 0; "end" -> before.length; "cursor" -> selectionStart; else -> fail("INVALID_ARGUMENTS", "position must be start, end or cursor.") }
                range(at, if (args.optString("position", "cursor") == "cursor") selectionEnd else at)
            }
            "replace", "delete" -> {
                if (expected.isBlank() || !args.has("start") || !args.has("end")) fail("INVALID_ARGUMENTS", "Range edits require start, end and expected_sha256 from a complete read.")
                range(args.getInt("start"), args.getInt("end"))
            }
            "undo" -> {
                val saved = undo ?: fail("NO_UNDO", "No verified edit is available to undo.")
                if (saved.field != field || saved.afterHash != hash(before)) fail("UNDO_FIELD_MISMATCH", "Undo belongs to a different field or its text has changed.")
                saved.before
            }
            else -> fail("INVALID_ARGUMENTS", "Unsupported edit action: $action")
        }
    }
    @Synchronized fun verified(action: String, field: String, before: String, after: String) {
        if (action == "undo") undo = null else undo = Undo(field, before, hash(after))
    }
    fun receipt(text: String, backend: String, args: JSONObject = JSONObject()): JSONObject {
        var start = args.optInt("offset_chars", 0).coerceIn(0, text.length)
        if (start > 0 && start < text.length && text[start-1].isHighSurrogate() && text[start].isLowSurrogate()) start--
        var end = minOf(text.length, start + args.optInt("limit_chars", 8192).coerceIn(1, 8192))
        if (end > start && end < text.length && text[end-1].isHighSurrogate() && text[end].isLowSurrogate()) end += if (end - start == 1) 1 else -1
        return JSONObject().put("ok", true).put("dispatched", false).put("used_backend", backend)
            .put("raw_text", text.substring(start, end)).put("field_raw", text.take(8192))
            .put("complete", start == 0 && end == text.length).put("field_complete", text.length <= 8192)
            .put("offset_chars", start).put("returned_chars", end-start).put("total_chars", text.length)
            .put("char_unit", "UTF-16").put("text_sha256", hash(text))
            .apply { if (end < text.length) put("next_offset_chars", end) }
    }
    fun failure(error: Throwable): JSONObject = JSONObject().put("ok", false)
        .put("code", (error as? TextEditFailure)?.code ?: "INPUT_BACKEND_ERROR")
        .put("errmsg", error.message ?: error.javaClass.simpleName)
        .put("dispatched", (error as? TextEditFailure)?.dispatched ?: false)
}
