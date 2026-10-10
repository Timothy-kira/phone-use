package kira.phoneuse.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything Phone Use keeps on the phone - learned flows (SOPs), trajectories, scenes, signals,
 * settlements, skills, diagnostics, the recording timeline - goes through here before it is
 * written. Personal values become named slots ({{phone}}, {{email}}, ...): a flow learned while
 * typing the user's number keeps working for any number, because replay fills the slot from the
 * task's arguments, and the stored file no longer holds the number.
 *
 * Patterns cover what can be recognised by shape (phone numbers, e-mail, Chinese ID numbers,
 * card numbers that pass the Luhn check, verification codes). Names and addresses cannot be
 * recognised that way; the user lists them as terms in privacy.json (phone_sops action=privacy).
 */
object PrivacyRedactor {
    data class Term(val value: String, val slot: String)

    data class Config(
        val enabled: Boolean = true,
        val maskScreenshots: Boolean = true,
        val terms: List<Term> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("enabled", enabled)
            .put("mask_screenshots", maskScreenshots)
            // Terms are secrets themselves: report how many and which slots, never the values.
            .put("terms", terms.size)
            .put("term_slots", JSONArray(terms.map { it.slot }.distinct()))
    }

    @Volatile
    private var file: File? = null

    @Volatile
    var config: Config = Config()
        private set

    /** Keys whose values are identifiers, hashes or enums - never personal text. */
    private val SafeKeys = setOf(
        "id", "sop_id", "scene_id", "job_id", "attempt_id", "turn_id", "session_id", "package_name",
        "action", "kind", "resource_id", "class", "class_name", "provenance", "maturity", "validation_state",
        "risk_level", "input_slot", "role", "region", "shape", "relative", "environment_fingerprint",
        "ui_fingerprint", "to_fingerprint", "fingerprint", "tool_name", "scene_key", "source", "code",
        "last_failure_code", "status", "verdict", "display_mode", "used_backend", "key", "screenshot_path",
        "skill_id", "segment", "result_code",
    )

    private val Email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val CnId = Regex("(?<![0-9A-Za-z])[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![0-9A-Za-z])")
    private val CardCandidate = Regex("(?<![\\d.])(?:\\d[ -]?){12,18}\\d(?![\\d.])")
    private val CnMobile = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}(?!\\d)")
    private val IntlPhone = Regex("(?<![\\w+])\\+\\d{1,3}[ -]?\\(?\\d{1,4}\\)?[ -]?\\d{3,4}[ -]?\\d{3,4}(?!\\d)")
    private val Code = Regex("(验证码|校验码|动态码|动态密码|短信码|verification code|security code|one-time code|OTP|PIN)([^0-9\\n]{0,10})(\\d{4,8})(?!\\d)", RegexOption.IGNORE_CASE)
    private val SlotName = Regex("[a-z][a-z0-9_]{0,30}")

    fun init(dataDir: File) {
        val f = File(dataDir, "privacy.json")
        file = f
        config = runCatching { parse(JSONObject(f.readText())) }.getOrDefault(Config())
    }

    fun update(change: (Config) -> Config): Config = synchronized(this) {
        val next = change(config)
        config = next
        file?.let { f ->
            val json = JSONObject()
                .put("enabled", next.enabled)
                .put("mask_screenshots", next.maskScreenshots)
                .put("terms", JSONArray().apply { next.terms.forEach { put(JSONObject().put("value", it.value).put("slot", it.slot)) } })
            runCatching { f.writeText(json.toString()) }
        }
        next
    }

    private fun parse(json: JSONObject): Config {
        val terms = json.optJSONArray("terms") ?: JSONArray()
        return Config(
            enabled = json.optBoolean("enabled", true),
            maskScreenshots = json.optBoolean("mask_screenshots", true),
            terms = (0 until terms.length()).mapNotNull { i ->
                val t = terms.optJSONObject(i) ?: return@mapNotNull null
                val value = t.optString("value").trim()
                if (value.length < 2) null else Term(value, slotName(t.optString("slot"), "private"))
            },
        )
    }

    fun slotName(raw: String, fallback: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').takeIf { SlotName.matches(it) } ?: fallback

    /** Redacted copy of [text]; unchanged when redaction is off. */
    fun text(text: String): String {
        if (!config.enabled || text.isEmpty()) return text
        var out = text
        // User terms first: a listed name may also contain digits that a pattern would split.
        config.terms.sortedByDescending { it.value.length }.forEach { term ->
            out = out.replace(term.value, "{{${term.slot}}}", ignoreCase = true)
        }
        out = Email.replace(out, "{{email}}")
        out = CnId.replace(out, "{{id_number}}")
        out = CardCandidate.replace(out) { m -> if (luhn(m.value)) "{{card_number}}" else m.value }
        out = CnMobile.replace(out, "{{phone}}")
        out = IntlPhone.replace(out, "{{phone}}")
        out = Code.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + "{{code}}" }
        return out
    }

    /** Which slots [text] would produce, in order of appearance (for naming input slots). */
    fun slotsIn(text: String): List<String> =
        Regex("\\{\\{([a-z0-9_]+)\\}\\}").findAll(text(text)).map { it.groupValues[1] }.distinct().toList()

    fun json(value: JSONObject): JSONObject {
        if (!config.enabled) return value
        val out = JSONObject()
        value.keys().forEach { key ->
            out.put(key, if (key in SafeKeys) value.opt(key) else any(value.opt(key)))
        }
        return out
    }

    fun json(value: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until value.length()) put(any(value.opt(i)))
    }

    private fun any(value: Any?): Any? = when (value) {
        is String -> text(value)
        is JSONObject -> json(value)
        is JSONArray -> json(value)
        else -> value
    }

    /** A JSON document or JSON-lines text, redacted; other text is redacted as plain text. */
    fun jsonText(raw: String): String {
        if (!config.enabled) return raw
        val trimmed = raw.trim()
        return runCatching {
            when {
                trimmed.startsWith("{") && !trimmed.contains("}\n{") -> json(JSONObject(trimmed)).toString()
                trimmed.startsWith("[") -> json(JSONArray(trimmed)).toString()
                else -> raw.lines().joinToString("\n") { line ->
                    val l = line.trim()
                    if (l.startsWith("{")) runCatching { json(JSONObject(l)).toString() }.getOrDefault(text(line)) else text(line)
                }
            }
        }.getOrDefault(text(raw))
    }

    /** Values that still look personal after redaction - the export/scrub audit. */
    fun audit(raw: String): List<String> {
        val findings = ArrayList<String>()
        val cleaned = text(raw)
        Regex("(?<![\\d{])\\d{6,}(?![\\d}])").findAll(cleaned).take(20).forEach { findings += "long number ${mask(it.value)}" }
        Regex("@[A-Za-z0-9-]+\\.").findAll(cleaned).take(5).forEach { findings += "possible address ${it.value}" }
        if (cleaned != raw) findings.add(0, "redaction would change ${diffCount(raw, cleaned)} value(s)")
        return findings
    }

    private fun diffCount(raw: String, cleaned: String): Int = Regex("\\{\\{[a-z0-9_]+\\}\\}").findAll(cleaned).count() -
        Regex("\\{\\{[a-z0-9_]+\\}\\}").findAll(raw).count()

    fun mask(value: String): String = if (value.length <= 4) "****" else value.take(2) + "*".repeat(value.length - 4) + value.takeLast(2)

    /** Does [text] hold something that must not appear in a screenshot? */
    fun isSensitiveText(text: String): Boolean = config.enabled && text.isNotBlank() && text(text) != text

    private fun luhn(raw: String): Boolean {
        val digits = raw.filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        if (digits.all { it == digits[0] }) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
