package kira.phoneuse.agentmode

import android.inputmethodservice.InputMethodService
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Installed companion only for fields without accessibility editing. Never creates a keyboard. */
class OwnedInputService : InputMethodService() {
    companion object {
        const val Component = "kira.phoneuse.server/kira.phoneuse.agentmode.OwnedInputService"
        @Volatile internal var active: OwnedInputService? = null
    }
    private var generation = 0L
    override fun onCreate() { super.onCreate(); active = this; OwnedInputLease.recover(this) }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
    override fun onEvaluateInputViewShown(): Boolean = false
    override fun onCreateInputView(): android.view.View? = null
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) { super.onStartInput(info, restarting); generation++ }
    override fun onFinishInput() { generation++; super.onFinishInput() }

    internal fun command(args: JSONObject): JSONObject {
        OwnedInputLease.check(this, args)
        val info = currentInputEditorInfo ?: throw TextEditFailure("INPUT_CONNECTION_UNAVAILABLE", "No active editor connection.")
        if (info.packageName != args.optString("package")) throw TextEditFailure("INPUT_CONTEXT_CHANGED", "The served editor belongs to another app.")
        if (display?.displayId != args.optInt("display_id")) throw TextEditFailure("INPUT_DISPLAY_MISMATCH", "The connection is not on the requested display.")
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if ((info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            Regex("otp|verification|验证码", RegexOption.IGNORE_CASE).containsMatchIn("${info.hintText} ${info.fieldName}"))
            throw TextEditFailure("USER_TAKEOVER_REQUIRED", "Let the user enter credentials on the phone.")
        val connection = currentInputConnection ?: throw TextEditFailure("INPUT_CONNECTION_UNAVAILABLE", "The editor provides no InputConnection.")
        val request = ExtractedTextRequest().apply { hintMaxChars = 1_048_576; hintMaxLines = 100_000; token = 21 }
        val extracted = connection.getExtractedText(request, 0) ?: throw TextEditFailure("INPUT_TEXT_UNREADABLE", "The editor does not expose complete text; no write was attempted.")
        val before = extracted.text?.toString() ?: throw TextEditFailure("INPUT_TEXT_UNREADABLE", "The editor returned no text.")
        if (extracted.startOffset != 0 || extracted.partialStartOffset >= 0 || extracted.partialEndOffset >= 0 || before.length >= request.hintMaxChars)
            throw TextEditFailure("INPUT_TEXT_INCOMPLETE", "The editor returned partial text; destructive edits are refused.")
        val capturedGeneration = generation
        val field = "ic|${args.optInt("display_id")}|${args.optString("window_identity")}|${info.packageName}|${info.fieldId}|${info.fieldName}|${info.inputType}"
        args.put("_field_identity", field)
        val action = args.optString("edit_action")
        if (action == "read") return TextEdits.receipt(before, "owned_input_connection", args).put("field_identity", field)
        if (action == "submit") {
            val imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (imeAction !in setOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT))
                throw TextEditFailure("SUBMIT_UNSUPPORTED", "The editor advertises no supported submit action.")
            val accepted = connection.performEditorAction(imeAction)
            return JSONObject().put("ok", accepted).put("code", if (accepted) "SUBMIT_DISPATCHED" else "SUBMIT_REJECTED")
                .put("dispatched", true).put("submission_verified", false).put("used_backend", "owned_input_connection")
        }
        val desired = TextEdits.desired(args, field, before, extracted.selectionStart, extracted.selectionEnd)
        OwnedInputLease.check(this, args)
        if (capturedGeneration != generation) throw TextEditFailure("INPUT_CONTEXT_CHANGED", "The editor connection changed before dispatch.")
        connection.beginBatchEdit()
        var dispatched = false
        try {
            connection.finishComposingText()
            if (!connection.setSelection(0, before.length)) throw TextEditFailure("TEXT_SELECTION_REJECTED", "The editor refused exact selection; nothing was written.")
            dispatched = true
            if (!connection.commitText(desired, 1)) throw TextEditFailure("TEXT_ACTION_REJECTED", "The editor refused commitText; no replay was sent.", true)
        } catch (error: TextEditFailure) { throw error }
        catch (error: Throwable) { throw TextEditFailure("INPUT_RESULT_UNKNOWN", error.message ?: "The editor disconnected during dispatch.", dispatched) }
        finally { connection.endBatchEdit() }
        val after = connection.getExtractedText(request, 0)
        if (generation != capturedGeneration || after == null || after.startOffset != 0 || after.partialStartOffset >= 0 || after.partialEndOffset >= 0 || after.text?.toString() != desired)
            throw TextEditFailure("TEXT_NOT_APPLIED", "Exact InputConnection read-back did not match. Inspect the result; do not replay.", true)
        TextEdits.verified(action, field, before, desired)
        return TextEdits.receipt(desired, "owned_input_connection").put("dispatched", true).put("text_verified", true).put("field_identity", field)
    }
}

/** Persistent lease survives a host crash. It never overwrites a user's later IME choice. */
internal object OwnedInputLease {
    private val handler = Handler(Looper.getMainLooper())
    private const val DurationMs = 20_000L
    private var watchdog: Runnable? = null
    private fun prefs(context: android.content.Context) = context.getSharedPreferences("owned-input-lease", 0)
    private fun arm(context: android.content.Context, token: String, delay: Long) {
        watchdog?.let(handler::removeCallbacks)
        watchdog = Runnable { if (prefs(context).getString("token", "") == token) restore(context) }
            .also { handler.postDelayed(it, delay) }
    }
    fun recover(context: android.content.Context) {
        val token = prefs(context).getString("token", "").orEmpty()
        if (token.isBlank()) return
        val left = prefs(context).getLong("expires", 0) - SystemClock.elapsedRealtime()
        if (left <= 0 || left > DurationMs) restore(context) else arm(context, token, left)
    }
    fun begin(context: android.content.Context, args: JSONObject): JSONObject {
        restore(context)
        val resolver = context.contentResolver
        val original = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        if (original.isBlank() || original == OwnedInputService.Component) throw TextEditFailure("INPUT_LEASE_UNAVAILABLE", "The user's original input service cannot be captured safely.")
        prefs(context).edit().putString("token", args.getString("token")).putString("original", original)
            .putString("enabled", enabled).putLong("expires", SystemClock.elapsedRealtime() + DurationMs).commit()
        arm(context, args.getString("token"), DurationMs)
        return JSONObject().put("ok", true).put("original_input_method", original)
    }
    fun check(context: android.content.Context, args: JSONObject) {
        val p = prefs(context)
        if (p.getString("token", "") != args.optString("token") || SystemClock.elapsedRealtime() >= p.getLong("expires", 0))
            throw TextEditFailure("INPUT_LEASE_EXPIRED", "The input lease expired; no write was attempted.")
        if (Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != OwnedInputService.Component)
            throw TextEditFailure("INPUT_SERVICE_CHANGED", "The user changed input service; operation cancelled.")
    }
    fun restore(context: android.content.Context) {
        watchdog?.let(handler::removeCallbacks); watchdog = null
        val p = prefs(context); val original = p.getString("original", "").orEmpty()
        if (p.getString("token", "").isNullOrEmpty()) return
        val resolver = context.contentResolver
        if (original.isNotBlank() && Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) == OwnedInputService.Component)
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, original)
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        val old = p.getString("enabled", "").orEmpty()
        if (Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) != OwnedInputService.Component &&
            enabled.split(':').filter { it.substringBefore(';') != OwnedInputService.Component }.joinToString(":") == old)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, old)
        p.edit().clear().commit()
    }
}

/** Shell-only command provider, acquired via Android's supported content shell entry point. */
class OwnedInputProvider : android.content.ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    override fun onCreate(): Boolean { context?.let { OwnedInputLease.recover(it) }; return true }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        if (caller != 2000 && caller != 0 && caller != Process.myUid()) throw SecurityException("Shell or plugin UID required")
        if (method != "command") throw IllegalArgumentException("Unknown input command")
        val args = JSONObject(arg ?: throw IllegalArgumentException("Missing input command"))
        var result = JSONObject(); val done = CountDownLatch(1)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        main.post {
            if (expired.get()) { done.countDown(); return@post }
            try {
                val ctx = requireNotNull(context)
                result = when(args.optString("command")) {
                    "begin" -> OwnedInputLease.begin(ctx, args)
                    "restore" -> {
                        if (ctx.getSharedPreferences("owned-input-lease", 0).getString("token", "") == args.optString("token")) OwnedInputLease.restore(ctx)
                        JSONObject().put("ok", true)
                    }
                    else -> OwnedInputService.active?.command(args) ?: throw TextEditFailure("INPUT_CONNECTION_UNAVAILABLE", "Owned input service has no active editor yet.")
                }
            } catch (error: Throwable) { result = TextEdits.failure(error).put("field_identity", args.optString("_field_identity")) } finally { done.countDown() }
        }
        if (!done.await(3, TimeUnit.SECONDS)) {
            expired.set(true)
            result = TextEdits.failure(TextEditFailure("INPUT_RESULT_UNKNOWN", "Input command did not return before its deadline. Inspect before retrying.", true))
        }
        return Bundle().apply { putString("result", result.toString()) }
    }
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = throw SecurityException("No input data store")
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("No input data store")
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("No input data store")
}
