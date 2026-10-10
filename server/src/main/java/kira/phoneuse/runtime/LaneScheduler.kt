package kira.phoneuse.runtime

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who may use what on the phone, when several lanes (one per agent / subagent, each with its own
 * virtual display) work at once. The CLI orchestrates; this only keeps lanes from colliding.
 *
 * Resources:
 * - `lane:<name>`  one call at a time per lane (held for the call)
 * - `ime`          text entry goes through the phone's single owned input-service lease / input focus (held for the call)
 * - `pkg:<name>`   an app runs in one lane at a time - a second launch would pull it away (sticky:
 *                  owned by the lane that launched it until that lane moves on or closes)
 * - `real`         the real screen; one lane at a time (sticky until it switches back)
 *
 * Waiting is first come, first served per resource; a call that cannot get everything within its
 * wait answers QUEUED with who holds what, instead of colliding.
 */
class LaneScheduler {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private val held = HashMap<String, String>()      // transient resource -> lane
    private val owners = HashMap<String, String>()    // sticky resource -> lane
    private val waiting = ArrayList<Ticket>()
    private var nextTicket = 0L

    private class Ticket(val id: Long, val lane: String, val resources: List<String>, val since: Long)

    data class Grant(val ok: Boolean, val info: JSONObject = JSONObject())

    private fun busyFor(lane: String, resource: String): String? {
        held[resource]?.let { if (it != lane || resource.startsWith("lane:")) return it }
        owners[resource]?.let { if (it != lane) return it }
        return null
    }

    /**
     * Takes every [transient] resource and checks every [sticky] one is free or this lane's.
     * Sticky resources are only checked here; [own] claims them after the call succeeded.
     */
    fun acquire(lane: String, transient: List<String>, sticky: List<String>, waitMillis: Long): Grant = lock.withLock {
        val all = (transient + sticky).distinct()
        val ticket = Ticket(nextTicket++, lane, all, System.currentTimeMillis())
        waiting += ticket
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis.coerceAtLeast(0))
        try {
            while (true) {
                val blockers = all.mapNotNull { r -> busyFor(lane, r)?.let { r to it } }
                // Fairness: an earlier waiter for the same resource goes first.
                val earlier = waiting.filter { it.id < ticket.id && it.resources.any { r -> r in all } }
                if (blockers.isEmpty() && earlier.isEmpty()) {
                    transient.forEach { held[it] = lane }
                    return@withLock Grant(true)
                }
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    return@withLock Grant(false, JSONObject()
                        .put("ok", false)
                        .put("code", "QUEUED")
                        .put("lane", lane)
                        .put("waiting_for", JSONArray(blockers.map { (r, by) -> JSONObject().put("resource", r).put("held_by", by) }))
                        .put("ahead_in_queue", earlier.size)
                        .put("waited_s", (System.currentTimeMillis() - ticket.since) / 1000.0)
                        .put("errmsg", "Another lane is using " + blockers.joinToString { (r, by) -> "$r ($by)" }.ifBlank { "these resources" } +
                            ". Nothing was done. Retry later (or with a longer wait_s), or do other work first."))
                }
                changed.awaitNanos(left)
            }
            @Suppress("UNREACHABLE_CODE")
            Grant(false)
        } finally {
            waiting.remove(ticket)
            changed.signalAll()
        }
    }

    fun release(lane: String, transient: List<String>) = lock.withLock {
        transient.forEach { if (held[it] == lane) held.remove(it) }
        changed.signalAll()
    }

    /** [lane] now owns [resource] (it launched the app / took the real screen). */
    fun own(lane: String, resource: String) = lock.withLock {
        owners[resource] = lane
        changed.signalAll()
    }

    fun disown(lane: String, resource: String) = lock.withLock {
        if (owners[resource] == lane) owners.remove(resource)
        changed.signalAll()
    }

    /** Drops every app [lane] owned except [keep] (a launch evicts the lane's other apps). */
    fun keepOnlyPackage(lane: String, keep: String) = lock.withLock {
        owners.entries.removeIf { (r, l) -> l == lane && r.startsWith("pkg:") && r != "pkg:$keep" }
        if (keep.isNotBlank()) owners["pkg:$keep"] = lane
        changed.signalAll()
    }

    fun releaseLane(lane: String) = lock.withLock {
        held.entries.removeIf { it.value == lane }
        owners.entries.removeIf { it.value == lane }
        changed.signalAll()
    }

    fun ownerOf(resource: String): String? = lock.withLock { owners[resource] }

    fun snapshot(): JSONObject = lock.withLock {
        JSONObject()
            .put("busy", JSONObject(held as Map<*, *>))
            .put("owned", JSONObject(owners as Map<*, *>))
            .put("queue", JSONArray(waiting.map { JSONObject().put("lane", it.lane).put("needs", JSONArray(it.resources)).put("waiting_s", (System.currentTimeMillis() - it.since) / 1000) }))
    }
}
