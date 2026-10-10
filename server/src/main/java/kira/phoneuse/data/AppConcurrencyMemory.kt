package kira.phoneuse.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * What this phone has shown about running apps side by side, kept across server restarts:
 * apps that would not stay on a virtual display (they need the real screen, so only one lane at
 * a time can use them). phone_lanes action=plan reads it to tell the CLI what can run in parallel.
 */
object AppConcurrencyMemory {
    private val refusals = ConcurrentHashMap<String, Int>()
    @Volatile
    private var file: File? = null

    fun init(dataDir: File) {
        val f = File(dataDir, "app-concurrency.json")
        file = f
        runCatching {
            val json = JSONObject(f.readText()).optJSONObject("refuses_virtual") ?: JSONObject()
            json.keys().forEach { refusals[it] = json.optInt(it) }
        }
    }

    /** One more time [packageName] did not appear on (or stay on) a virtual display. */
    fun noteVirtualRefusal(packageName: String): Int {
        if (packageName.isBlank()) return 0
        val count = refusals.merge(packageName, 1, Int::plus) ?: 1
        save()
        return count
    }

    fun virtualRefusals(packageName: String): Int = refusals[packageName] ?: 0

    fun refusesVirtual(packageName: String): Boolean = virtualRefusals(packageName) >= 2

    private fun save() {
        val f = file ?: return
        runCatching {
            f.writeText(JSONObject().put("refuses_virtual", JSONObject(refusals as Map<*, *>)).toString())
        }
    }
}
