package kira.phoneuse.runtime

import kira.phoneuse.data.AgentDisplayMcp
import kira.phoneuse.data.AgentModeDisplayGate
import kira.phoneuse.data.AgentModeSafety
import kira.phoneuse.data.AetherLearningSessionHeader
import kira.phoneuse.data.DeviceCatalogMcp
import kira.phoneuse.data.PhoneAppFlowMcp
import kira.phoneuse.data.PhoneTaskMcp
import kira.phoneuse.data.PhoneUiMcp
import kira.phoneuse.data.UpaMcpHttpPathPrefix
import kira.phoneuse.data.UpaMcpProtocolVersion
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

private const val MaxHttpBodyBytes = 1_048_576
private const val ClientSoTimeoutMillis = 15_000
private const val ExclusiveGuiCallTimeoutMillis = 20_000L
/** Replayed flows and pipelines run many steps; the bridge waits 90 s per call. */
private const val FlowCallTimeoutMillis = 85_000L
/** How long a call waits for a busy lane / app / text entry before answering QUEUED. */
private const val DefaultWaitMillis = 30_000L
/** Actions that go through the phone's single editor input focus. */
private val TypingActions = setOf("text", "edit_text", "read_field", "search", "clear_text", "undo")
private const val StreamFrameIntervalMillis = 66L

/**
 * Loopback HTTP for the plugin, reached from the computer through `adb forward`.
 *
 * - `/mcp/<id>`: the Agent Mode MCP endpoints, dispatched per endpoint
 *   (one GUI call at a time, 20 s per call), plus `phone_task`.
 * - `/ui/...`: the computer-side phone window - a live MJPEG stream of the display and the
 *   takeover gestures from the computer-side phone window.
 * - `/health`: liveness and version, for the bridge.
 */
class PhoneUseGateway(
    private val port: Int,
    private val runtime: PhoneUseRuntime,
    private val version: String,
) {
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "phone-use-http").apply { isDaemon = true }
    }
    /**
     * One GUI call on the phone at a time. A permit, not a lock: when a call outlives its time
     * limit the caller answers, but the permit stays with the worker until the phone really
     * finishes, so the next call gets DISPLAY_BUSY instead of overlapping it.
     */
    private val guiPermit = Semaphore(1, true)
    // A compound operation owns its lane until all substeps stop. Other sessions share the same
    // physical lane, so a timed-out collect cannot interleave with their fresh observation.
    private val operationPermits = ConcurrentHashMap<String, Semaphore>()
    private val activeOperations = ConcurrentHashMap<String, CallControl>()
    private val finishedOperations = ConcurrentHashMap<String, CallControl>()
    private class CallControl(val key: String, val deadline: Long, val args: JSONObject) {
        val running = AtomicBoolean(false)
        val progress = AtomicReference<String>("{}")
        val lastRaw = AtomicReference<String>()
        val finalMcp = AtomicReference<String>()
        var deferredCleanup: (() -> Unit)? = null
        val cancelled = AtomicBoolean(false)
        fun remaining() = (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)
        fun check() { if (cancelled.get() || remaining() == 0L) throw CallStopped() }
    }
    private class CallStopped : RuntimeException("The operation stopped before its next substep.")

    private val sessionId = UUID.randomUUID().toString()
    private var serverSocket: ServerSocket? = null

    fun start() {
        val socket = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                client.soTimeout = ClientSoTimeoutMillis
                workers.execute { runCatching { handleClient(client) } }
            }
        }, "phone-use-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
    }

    private fun handleClient(client: Socket) {
        val request = readHttpRequest(BufferedInputStream(client.getInputStream())) ?: run {
            client.use { writeHttp(it, 400, jsonRpcError(null, -32700, "invalid request").toString()) }
            return
        }
        val path = request.path.substringBefore('?')
        if (request.method == "GET" && path == "/ui/stream") {
            // Long-lived: the stream thread owns the socket until the window goes away.
            streamFrames(client, queryParam(request.path, "lane"))
            return
        }
        client.use { socket ->
            try {
                route(socket, request, path)
            } catch (thrown: Throwable) {
                // Never close a call without an answer: the caller would only see a dropped
                // connection and restart a server that is fine. Log it and say what happened.
                System.err.println("phone-use: ${request.method} $path failed: $thrown")
                thrown.printStackTrace()
                runCatching {
                    writeHttp(socket, 500, jsonRpcError(null, -32000,
                        "phone server error: ${thrown.javaClass.simpleName}: ${thrown.message.orEmpty().take(300)}").toString())
                }
            }
        }
    }

    private fun route(socket: Socket, request: HttpRequest, path: String) {
        run {
            when {
                request.method == "GET" && path == "/health" -> writeHttp(
                    socket,
                    200,
                    JSONObject().put("ok", true).put("server", "phone-use").put("version", version)
                        .put("data_dir", runtime.dataDir.absolutePath).toString(),
                )
                request.method == "POST" && path == "/calls/cancel" -> {
                    val body = JSONObject(request.body.ifBlank { "{}" })
                    val owner = request.headers["x-aether-session-id"].orEmpty()
                    val key = "$owner|${body.optString("request_id")}"; val operation = activeOperations[key]
                    operation?.cancelled?.set(true)
                    writeHttp(socket, 200, JSONObject().put("ok", true).put("cancel_requested", operation != null)
                        .put("note", "An in-flight gesture finishes; subsequent steps stop.").toString())
                }
                request.method == "GET" && path == "/calls/status" -> {
                    val owner = request.headers["x-aether-session-id"].orEmpty()
                    val id = queryParam(request.path, "request_id").orEmpty()
                    val operation = activeOperations["$owner|$id"] ?: finishedOperations["$owner|$id"]
                    val response = if (owner.isBlank() || operation == null) JSONObject().put("ok", false).put("code", "NO_SUCH_OPERATION")
                    else synchronized(operation) {
                        JSONObject().put("ok", true).put("operation_id", id).put("cancel_requested", operation.cancelled.get())
                            .put("progress", JSONObject(operation.progress.get())).apply {
                                if (operation.running.get() || operation.lastRaw.get() == null) put("status", "running")
                                else {
                                    if (operation.finalMcp.get() == null) {
                                        kira.phoneuse.data.AgentDisplayShaper.session.set(owner)
                                        val raw = JSONObject(operation.lastRaw.get()).put("operation_id", id).put("operation_finished", true)
                                            .put("cancel_requested", operation.cancelled.get())
                                        val lane = operation.args.optString("_operation_lane").ifBlank { "main" }
                                        operation.finalMcp.set(AgentDisplayMcp.wrapCallResult(raw.toString(), operation.args, lane).toString())
                                    }
                                    put("status", "done").put("result", JSONObject(operation.finalMcp.get()))
                                }
                            }
                    }
                    writeHttp(socket, 200, response.toString())
                }
                request.method == "GET" && path == "/debug/a11y" -> writeHttp(socket, 200, runtime.service.accessibilityDiagnosticsJson())
                // Host-side tools (phone_browser) add their steps to a running recording's timeline.
                request.method == "POST" && path == "/record/log" -> {
                    val line = runCatching { JSONObject(request.body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    runtime.recorder.logAction(line.optJSONObject("arguments") ?: JSONObject(), line.optJSONObject("result")?.toString() ?: "{}")
                    runCatching { runtime.noteHostAction(line.optJSONObject("arguments") ?: JSONObject(), line.optJSONObject("result") ?: JSONObject()) }
                    writeHttp(socket, 200, JSONObject().put("ok", true).put("recording", runtime.recorder.isRecording).toString())
                }
                // The memory (app maps, session reference graphs) mirrored by the bridge to the computer.
                path == "/memory/list" || path == "/memory/file" -> handleMemory(socket, request, path)
                path == "/ui" || path == "/ui/" -> writeHttp(socket, 200, PhoneWindowPage.html, contentType = "text/html; charset=utf-8")
                path.startsWith("/ui/") -> handleUi(socket, request.method, path, request.body, queryParam(request.path, "lane"), queryParam(request.path, "client") == "monitor")
                request.method == "OPTIONS" && path.startsWith(UpaMcpHttpPathPrefix) ->
                    writeHttp(socket, 204, "", extraHeaders = listOf("Allow: POST, OPTIONS"))
                path.startsWith(UpaMcpHttpPathPrefix) -> handleMcp(socket, request, path)
                else -> writeHttp(socket, 404, jsonRpcError(null, -32601, "not found").toString())
            }
        }
    }

    /**
     * `GET /memory/list` - files under the memory directory (path, size, mtime);
     * `GET /memory/file?path=` - one file; `PUT /memory/file?path=` - write one (the bridge restoring
     * the computer's copy). Paths stay inside the memory directory.
     */
    private fun handleMemory(socket: Socket, request: HttpRequest, path: String) {
        val root = kira.phoneuse.data.AgentDisplayShaper.memoryRoot
            ?: return writeHttp(socket, 503, JSONObject().put("ok", false).put("errmsg", "no memory directory").toString())
        if (path == "/memory/list") {
            val files = JSONArray()
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                files.put(JSONObject().put("path", f.relativeTo(root).path.replace('\\', '/')).put("size", f.length()).put("mtime", f.lastModified()))
            }
            return writeHttp(socket, 200, JSONObject().put("ok", true).put("files", files).toString())
        }
        val rel = queryParam(request.path, "path").orEmpty()
        val file = java.io.File(root, rel).canonicalFile
        if (rel.isBlank() || !file.path.startsWith(root.canonicalPath)) {
            return writeHttp(socket, 400, JSONObject().put("ok", false).put("errmsg", "bad path").toString())
        }
        when (request.method) {
            "GET" -> if (file.isFile) writeHttp(socket, 200, kira.phoneuse.data.PrivacyRedactor.jsonText(file.readText())) else writeHttp(socket, 404, JSONObject().put("ok", false).toString())
            "PUT", "POST" -> {
                file.parentFile?.mkdirs()
                file.writeText(kira.phoneuse.data.PrivacyRedactor.jsonText(request.body))
                kira.phoneuse.data.AgentDisplayShaper.forget(rel)
                writeHttp(socket, 200, JSONObject().put("ok", true).toString())
            }
            else -> writeHttp(socket, 405, JSONObject().put("ok", false).toString())
        }
    }

    // --- MCP -------------------------------------------------------------------------------

    private fun handleMcp(socket: Socket, request: HttpRequest, path: String) {
        val pluginId = URLDecoder.decode(path.removePrefix(UpaMcpHttpPathPrefix).trim('/'), Charsets.UTF_8.name())
        if (pluginId !in Endpoints) {
            writeHttp(socket, 404, jsonRpcError(null, -32601, "not found").toString())
            return
        }
        if (request.method != "POST") {
            writeHttp(socket, 405, jsonRpcError(null, -32601, "POST required").toString())
            return
        }
        val session = request.headers[AetherLearningSessionHeader.lowercase()].orEmpty().ifBlank { sessionId }
        runtime.lastSessionId = session
        val call = CallContext(
            session = session,
            lane = laneKey(request.headers["x-phone-lane"]),
            waitMillis = (request.headers["x-phone-wait"]?.toDoubleOrNull()?.times(1000))?.toLong()?.coerceIn(0, 80_000) ?: DefaultWaitMillis,
            budgetMillis = (request.headers["x-phone-timeout"]?.toDoubleOrNull()?.times(1000))?.toLong()?.minus(5000)?.coerceIn(1000, 80_000) ?: 80_000,
        )
        val trimmed = request.body.trim()
        if (trimmed.startsWith("[")) {
            val batch = JSONArray(trimmed)
            val responses = JSONArray()
            for (index in 0 until batch.length()) {
                val message = batch.optJSONObject(index) ?: continue
                dispatch(pluginId, message, call)?.let(responses::put)
            }
            writeHttp(socket, 200, responses.toString(), accept = request.accept)
            return
        }
        val message = runCatching { JSONObject(trimmed.ifBlank { "{}" }) }.getOrElse {
            writeHttp(socket, 400, jsonRpcError(null, -32700, "parse error").toString())
            return
        }
        val response = dispatch(pluginId, message, call)
        if (response == null) writeHttp(socket, 202, "") else writeHttp(socket, 200, response.toString(), accept = request.accept)
    }

    private fun dispatch(pluginId: String, message: JSONObject, call: CallContext): JSONObject? {
        val hasId = message.has("id") && !message.isNull("id")
        if (!hasId) return null
        val requestId = message.get("id")
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val result = runCatching {
            when (method) {
                "initialize" -> initializeResult(pluginId)
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> listTools(pluginId)
                "tools/call" -> runOperation(pluginId, name, arguments, call, requestId.toString())
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("result", result)
    }

    private fun initializeResult(pluginId: String): JSONObject = when (pluginId) {
        AgentDisplayMcp.PluginId -> AgentDisplayMcp.initializeResult()
        PhoneUiMcp.PluginId -> PhoneUiMcp.initializeResult()
        PhoneAppFlowMcp.PluginId -> PhoneAppFlowMcp.initializeResult()
        DeviceCatalogMcp.PluginId -> DeviceCatalogMcp.initializeResult()
        PhoneRecordMcp.PluginId -> PhoneRecordMcp.initializeResult()
        PhoneSopsMcp.PluginId -> PhoneSopsMcp.initializeResult()
        PhoneLanesMcp.PluginId -> PhoneLanesMcp.initializeResult()
        else -> PhoneTaskMcp.initializeResult()
    }

    private fun listTools(pluginId: String): JSONObject = when (pluginId) {
        AgentDisplayMcp.PluginId -> AgentDisplayMcp.listToolsResult()
        PhoneUiMcp.PluginId -> PhoneUiMcp.listToolsResult()
        PhoneAppFlowMcp.PluginId -> runtime.flowTools()
        DeviceCatalogMcp.PluginId -> DeviceCatalogMcp.listToolsResult()
        PhoneRecordMcp.PluginId -> PhoneRecordMcp.listToolsResult()
        PhoneSopsMcp.PluginId -> PhoneSopsMcp.listToolsResult()
        PhoneLanesMcp.PluginId -> PhoneLanesMcp.listToolsResult()
        else -> PhoneTaskMcp.listToolsResult()
    }

    private data class CallContext(val session: String, val lane: String, val waitMillis: Long,
        val budgetMillis: Long = 80_000, val control: CallControl? = null)

    private fun runOperation(pluginId: String, name: String, arguments: JSONObject, call: CallContext, requestId: String): JSONObject {
        kira.phoneuse.data.AgentDisplayShaper.session.set(call.session)
        val control = CallControl("${call.session}|$requestId", android.os.SystemClock.uptimeMillis() + call.budgetMillis, JSONObject(arguments.toString()))
        activeOperations[control.key] = control
        val permit = operationPermits.getOrPut(call.lane) { Semaphore(1, true) }
        var acquired = false
        try {
            acquired = permit.tryAcquire(minOf(call.waitMillis, control.remaining()), TimeUnit.MILLISECONDS)
            if (!acquired) return AgentDisplayMcp.wrapCallResult(JSONObject().put("ok", false).put("code", "QUEUED")
                .put("dispatched", false).put("errmsg", "This lane still has an active operation. It has not been overlapped or replayed.").toString(), arguments, call.lane)
            control.check()
            val answer = callTool(pluginId, name, arguments, call.copy(control = control))
            val responseCode = runCatching { JSONObject(answer.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()).optString("code") }.getOrDefault("")
            if (!control.running.get() && responseCode != "STILL_RUNNING") control.finalMcp.set(answer.toString())
            return answer
        } catch (_: CallStopped) {
            return AgentDisplayMcp.wrapCallResult(JSONObject().put("ok", false).put("code", "OPERATION_STOPPED")
                .put("operation_id", requestId).put("cancelled", control.cancelled.get())
                .put("errmsg", "The operation reached its deadline or was cancelled. Subsequent gestures stopped. Read the current screen before another action.").toString(), arguments, call.lane)
        } finally {
            finishedOperations[control.key] = control
            if (finishedOperations.size > 64) finishedOperations.entries.filter { !it.value.running.get() }
                .minByOrNull { it.value.deadline }?.let { finishedOperations.remove(it.key, it.value) }
            synchronized(control) {
                val cleanup = { activeOperations.remove(control.key, control); if (acquired) permit.release(); Unit }
                if (control.running.get()) control.deferredCleanup = cleanup else cleanup()
            }
        }
    }

    private fun callTool(pluginId: String, name: String, arguments: JSONObject, call: CallContext): JSONObject {
        val session = call.session
        kira.phoneuse.data.AgentDisplayShaper.session.set(session)
        return when (pluginId) {
            AgentDisplayMcp.PluginId -> {
                val textTool = name.equals(AgentDisplayMcp.TextToolName, true) || name.endsWith("_" + AgentDisplayMcp.TextToolName, true)
                if (!textTool && !name.equals(AgentDisplayMcp.ToolName, true) && !name.endsWith(AgentDisplayMcp.ToolName, true)) {
                    error("Unknown tool: $name")
                }
                if (textTool) {
                    AgentDisplayMcp.normalizeTextArguments(arguments)
                    kira.phoneuse.data.AgentDisplayShaper.resolveTextSourceRefs(call.lane, arguments)?.let {
                        return AgentDisplayMcp.wrapCallResult(it.toString(), arguments, call.lane)
                    }
                }
                val shaper = kira.phoneuse.data.AgentDisplayShaper
                shaper.normalizeArguments(arguments)
                shaper.validateArguments(arguments)?.let {
                    return AgentDisplayMcp.wrapCallResult(it.toString(), arguments, call.lane)
                }
                val (resolved, refError) = resolveRefs(arguments, call)
                if (refError != null) {
                    return AgentDisplayMcp.wrapCallResult(refError.toString(), arguments, call.lane)
                }
                val args = resolved!!
                val act = args.optString("action").trim().lowercase()
                shaper.repetitionGate(call.lane, args)?.let {
                    return AgentDisplayMcp.wrapCallResult(it.toString(), JSONObject().put("detail", "full"), call.lane)
                }
                args.put("_feedback_key", shaper.feedbackKey(call.lane, args))
                shaper.feedbackTarget(call.lane, args)?.let { args.put("_feedback_target", it) }
                // A read right after gestures names the screen they led to: wait for it to settle
                // (a page sliding in is not a page). map / recap need that screen too.
                if (shaper.hasPending(call.lane) && act in setOf("map", "recap", "refs")) {
                    displayStep(JSONObject().put("action", "wait_idle").put("quiet_ms", 300).put("timeout_ms", 2500).put("_internal_observation", true), call)
                    if (act in setOf("map", "recap", "refs")) settledRead(1, call)
                }
                when (act) {
                    "peek" -> return peek(args, call)
                    "collect" -> return collect(args, call)
                    "pin", "note", "refs" -> return refsAction(act, args, call)
                    "map" -> return memoryAnswer(kira.phoneuse.data.AgentDisplayShaper.mapJson(call.lane,
                        args.optString("app").ifBlank { args.optString("package") }, args.optString("query")), call)
                    "recap" -> return memoryAnswer(kira.phoneuse.data.AgentDisplayShaper.recapJson(call.lane), call)
                    "goto" -> return goto(args, call)
                    "explore" -> return explore(args, call)
                }
                // find / zoom become the reads they stand for; the original arguments shape the answer.
                val exec = kira.phoneuse.data.AgentDisplayShaper.prepare(args, call.lane)
                AgentDisplayMcp.wrapCallResult(
                    displayCall(exec, call) { controlledDisplay(exec, call) },
                    args,
                    call.lane,
                )
            }
            PhoneUiMcp.PluginId -> {
                if (!PhoneUiMcp.matchesToolName(name)) error("Unknown tool: $name")
                val mapped = PhoneUiMcp.toExecuteArguments(name, arguments)
                PhoneUiMcp.wrapCallResult(displayCall(mapped, call) { controlledDisplay(mapped, call) })
            }
            PhoneAppFlowMcp.PluginId -> {
                val raw = if (name.equals(PhoneAppFlowMcp.RecallToolName, ignoreCase = true)) {
                    runtime.flowCall(name, arguments, session, call.lane)
                } else {
                    val pkg = runtime.catalogStore.findReadyTool(name)?.first?.packageName.orEmpty()
                    val sticky = if (pkg.isBlank()) emptyList() else listOf("pkg:$pkg")
                    // A replayed flow may type: it holds text entry for its whole run.
                    exclusiveGuiCall(call, listOf("ime"), sticky, FlowCallTimeoutMillis, onSuccess = {
                        if (pkg.isNotBlank()) runtime.scheduler.keepOnlyPackage(call.lane, pkg)
                    }) { runtime.flowCall(name, arguments, session, call.lane) }
                }
                PhoneAppFlowMcp.wrapCallResult(raw)
            }
            DeviceCatalogMcp.PluginId -> {
                if (!DeviceCatalogMcp.matchesToolName(name.ifBlank { DeviceCatalogMcp.ToolName })) error("Unknown tool: $name")
                DeviceCatalogMcp.wrapCallResult(runtime.deviceCatalog(arguments))
            }
            PhoneSopsMcp.PluginId -> PhoneSopsMcp.call(arguments, runtime)
            PhoneLanesMcp.PluginId -> PhoneLanesMcp.call(arguments, runtime)
            PhoneRecordMcp.PluginId -> {
                if (arguments.optString("action") == "start") {
                    // Bringing the lane's display up is GUI work: it waits its turn like any call.
                    val raw = exclusiveGuiCall(call, emptyList(), emptyList()) {
                        runtime.phoneRecord(arguments, call.lane).toString()
                    }
                    val parsed = JSONObject(raw)
                    if (parsed.has("content")) parsed else JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", raw)))
                        .put("isError", true)
                } else {
                    runtime.phoneRecord(arguments, call.lane)
                }
            }
            else -> runtime.phoneTask(arguments, session, call.lane)
        }
    }

    /** agent_display: which shared resources this call needs, and what it owns afterwards. */
    /**
     * `peek {query:#n}`: what tapping an element would open, without staying there - tap it, keep the
     * outline of the new screen, press back, and check the screen is the one it started from. Never
     * presses elements that look like they commit something (send, delete, pay, save...) or text
     * fields; those answer PEEK_REFUSED.
     */
    private fun peek(arguments: JSONObject, call: CallContext): JSONObject {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        val query = arguments.optString("query").trim()
        fun answer(body: JSONObject) = AgentDisplayMcp.wrapCallResult(body.toString(), JSONObject().put("detail", "full"), call.lane)
        if (query.isBlank()) {
            return answer(JSONObject().put("ok", false).put("code", "INVALID_ARGUMENTS").put("errmsg", "peek needs query: #n from the screen outline."))
        }
        fun step(args: JSONObject) = displayStep(args, call)
        // Fingerprints come from settled full reads, so before/after compare like with like.
        fun settledRead(lines: Int) = settledRead(lines, call)
        val start = settledRead(1)
        // Judged on the screen just read (#n numbering of earlier answers can be stale).
        shaper.peekRefusal(query, call.lane)?.let {
            return answer(JSONObject().put("ok", false).put("code", "PEEK_REFUSED")
                .put("errmsg", "Not previewed: $it. Tap it yourself if the task needs it."))
        }
        val tapped = step(JSONObject().put("action", "click_node").put("query", query))
        if (!tapped.optBoolean("ok", false)) return answer(tapped.put("peeked", query).put("note", "the tap failed"))
        val opened = settledRead(40)
        if (opened.optString("snapshot_id") == start.optString("snapshot_id")) {
            return answer(JSONObject().put("ok", true).put("peeked", query).put("note", "tapping it changed nothing on screen"))
        }
        step(JSONObject().put("action", "key").put("key", "back"))
        val end = settledRead(40)
        val returned = end.optString("snapshot_id") == start.optString("snapshot_id")
        val body = JSONObject().put("ok", true).put("peeked", query)
            .put("would_open", JSONObject().put("screen", opened.opt("ui_tree") ?: "")
                .apply { opened.optJSONObject("lines")?.let { put("lines", it) } })
            .put("returned", returned)
        if (!returned) {
            body.put("code", "PEEK_LEFT_STATE_CHANGED")
                .put("hint", "Back did not lead to the starting screen; this is where it is now.")
                .put("now", end.opt("ui_tree") ?: "")
        }
        return answer(body)
    }

    /** One agent_display action run the way an agent's call would be, answered as the shaped JSON. */
    private fun displayStep(args: JSONObject, call: CallContext): JSONObject {
        call.control?.check()
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        shaper.normalizeArguments(args)
        if (!args.optBoolean("_internal_observation")) {
            shaper.validateArguments(args)?.let { return it }
            shaper.repetitionGate(call.lane, args)?.let { return it }
            args.put("_feedback_key", shaper.feedbackKey(call.lane, args))
            shaper.feedbackTarget(call.lane, args)?.let { args.put("_feedback_target", it) }
        }
        val exec = shaper.prepare(args, call.lane)
        val raw = displayCall(exec, call) { controlledDisplay(exec, call) }
        val shaped = runCatching { shaper.shape(JSONObject(raw), args, call.lane) }.getOrElse { JSONObject().put("ok", false).put("raw", raw) }
        if (shaped.optString("code") in setOf("STILL_RUNNING", "QUEUED", "OPERATION_STOPPED")) call.control?.cancelled?.set(true)
        return shaped
    }

    /** A full read of the screen after it settled (also refreshes the shaper's cached tree). */
    private fun settledRead(lines: Int, call: CallContext): JSONObject {
        return displayStep(JSONObject().put("action", "dump_tree").put("detail", "full").put("limit", lines)
            .put("_internal_observation", true), call)
    }

    private fun memoryAnswer(body: JSONObject, call: CallContext): JSONObject =
        AgentDisplayMcp.wrapCallResult(body.toString(), JSONObject().put("detail", "full"), call.lane)

    /**
     * Walks [pkg]'s map from the current screen to [target]: each step finds its control again on the
     * live screen (resource id, then label), and the screen after it must be the one the map expects.
     * Replans up to twice when it lands elsewhere. Returns whether it arrived, and the steps taken.
     */
    private fun navigate(pkg: String, target: String, call: CallContext): Pair<Boolean, JSONArray> {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        val walked = JSONArray()
        var replans = 0
        settledRead(800, call)
        while (true) {
            if (shaper.currentSig(call.lane) == target) return true to walked
            val here = if (shaper.currentPkg(call.lane) == pkg) shaper.currentSig(call.lane) else null
            val route = shaper.route(pkg, here, target) ?: return false to walked.put("no known route from here")
            var arrivedAll = true
            for (e in route) {
                when (e.kind) {
                    "launch" -> displayStep(JSONObject().put("action", "launch").put("target", pkg), call)
                    "back" -> displayStep(JSONObject().put("action", "key").put("key", "back"), call)
                    else -> {
                        var n = shaper.findControl(call.lane, e)
                        // Off screen in a long list: scroll the content forward, then back, until it shows.
                        val title = shaper.edgeTitle(e)
                        for (dir in listOf("up", "down")) {
                            if (n != null || title.isBlank()) break
                            val r = displayStep(JSONObject().put("action", "scroll_until").put("query", title)
                                .put("direction", dir).put("max_swipes", 8), call)
                            settledRead(800, call)
                            n = shaper.findControl(call.lane, e)
                            if (n == null && r.optBoolean("found", false)) break
                        }
                        if (n == null) { walked.put("${shaper.stepText(e)}: not on this screen"); arrivedAll = false; break }
                        val step = if (e.kind == "long_press") JSONObject().put("action", "long_press").put("at", "#$n")
                            else JSONObject().put("action", "click_node").put("query", "#$n")
                        displayStep(step, call)
                    }
                }
                settledRead(800, call)
                val ok = shaper.currentSig(call.lane) == e.to
                shaper.walked(pkg, e, ok)
                walked.put(shaper.stepText(e) + if (ok) "" else " (landed elsewhere)")
                if (!ok) { arrivedAll = false; break }
            }
            if (arrivedAll) continue
            if (++replans > 2) return false to walked
        }
    }

    /** `goto {to: "@sN" | words, app?}`: back to a screen seen before, or to the screen offering a feature. */
    private fun goto(arguments: JSONObject, call: CallContext): JSONObject {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        settledRead(800, call)
        val to = arguments.optString("to").ifBlank { arguments.optString("query") }.trim()
        val (pkg, target) = if (to.matches(Regex("^@s\\d+$"))) {
            shaper.sigOf(call.lane, to) ?: return memoryAnswer(JSONObject().put("ok", false).put("code", "NO_SUCH_REF")
                .put("errmsg", "$to is not a screen reference (refs lists them)."), call)
        } else {
            val app = arguments.optString("app").ifBlank { shaper.currentPkg(call.lane).orEmpty() }
            val hit = shaper.searchMap(app, to).firstOrNull() ?: return memoryAnswer(JSONObject().put("ok", false)
                .put("code", "NOT_IN_MAP").put("errmsg", "No screen in the map mentions \"$to\". explore finds more screens."), call)
            app to hit.sig
        }
        val (arrived, walked) = navigate(pkg, target, call)
        val body = JSONObject().put("ok", arrived).put("steps", walked)
            .put("now", shaper.screenBrief(call.lane, pkg, shaper.currentSig(call.lane).orEmpty()))
        if (!arrived) body.put("code", "GOTO_STOPPED")
            .put("hint", "The map's route did not hold here. Look at the screen; explore or navigate by hand - the map learns the new way.")
        return memoryAnswer(body, call)
    }

    /**
     * `explore {depth?, budget?}`: for when the way to a feature is unknown. From the current screen,
     * taps each safe control once (never send / delete / pay / save ..., never text fields), notes
     * where it leads, comes back; to [depth] levels; within [budget] taps. Ends where it started.
     */
    private fun explore(arguments: JSONObject, call: CallContext): JSONObject {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        val depth = arguments.optInt("depth", 1).coerceIn(1, 3)
        var budget = arguments.optInt("budget", 15).coerceIn(1, 40)
        settledRead(800, call)
        val pkg = shaper.currentPkg(call.lane).orEmpty()
        val start = shaper.currentSig(call.lane)
            ?: return memoryAnswer(JSONObject().put("ok", false).put("code", "NO_SCREEN").put("errmsg", "Open the app first."), call)
        val visited = hashSetOf(start)
        val found = JSONArray()
        val frontier = ArrayDeque(listOf(start to 0))
        val lost = JSONArray()
        while (frontier.isNotEmpty() && budget > 0) {
            val (sig, level) = frontier.removeFirst()
            if (shaper.currentSig(call.lane) != sig && !navigate(pkg, sig, call).first) { lost.put("could not reach ${shaper.screenBrief(call.lane, pkg, sig).take(60)}"); continue }
            // Per list: where its rows led. Two rows opening the same screen = a template list; skip the rest.
            val listDest = HashMap<Int, MutableList<String>>()
            for ((label, list) in shaper.exploreCandidates(call.lane, pkg, sig)) {
                if (budget <= 0) break
                if (list != null && listDest[list].orEmpty().let { d -> d.size >= 2 && d.toSet().size < d.size }) continue
                // Lost the start screen (a tap that changed state, a dialog): note it and go on with the next item.
                if (shaper.currentSig(call.lane) != sig && !navigate(pkg, sig, call).first) { lost.put("lost the way back before $label"); continue }
                val n = shaper.findByLabel(call.lane, label) ?: continue
                displayStep(JSONObject().put("action", "click_node").put("query", "#$n"), call)
                budget--
                settledRead(800, call)
                val now = shaper.currentSig(call.lane)
                if (now == null || now == sig) continue
                if (list != null) listDest.getOrPut(list) { mutableListOf() } += now
                if (shaper.currentPkg(call.lane) == pkg && visited.add(now)) {
                    found.put("$label -> " + shaper.screenBrief(call.lane, pkg, now))
                    if (level + 1 < depth) frontier.addLast(now to level + 1)
                }
                displayStep(JSONObject().put("action", "key").put("key", "back"), call)
                settledRead(800, call)
            }
        }
        val back = shaper.currentSig(call.lane) == start || navigate(pkg, start, call).first
        return memoryAnswer(JSONObject().put("ok", true).put("found", found).put("taps_left", budget)
            .put("back_at_start", back).apply { if (lost.length() > 0) put("not_explored", lost) }
            .put("note", "The app's map now knows these screens: map {query} searches them, goto {to:@sN} walks there."), call)
    }

    /** Swipes a list one page forward (or back) inside its own box. */
    private fun scrollList(box: IntArray, forward: Boolean, call: CallContext, containerRef: String? = null): JSONObject {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        val page = shaper.listPage(call.lane, "", box)
        val index = if (containerRef == null) page.node else shaper.locate(call.lane, containerRef)
        if (index == null) return JSONObject().put("ok", false).put("code", "SCROLL_CONTAINER_CHANGED").put("dispatched", false)
        val query = "#$index"
        return displayStep(JSONObject().put("action", "scroll").put("query", query)
            .put("snapshot_id", shaper.snapshotId(call.lane).orEmpty())
            .put("direction", if (forward) "up" else "down"), call)
    }

    private fun currentPackage(read: JSONObject): String =
        read.optString("package_name").ifBlank { read.optJSONObject("tree_diff")?.optString("package_after").orEmpty() }

    /**
     * `collect {query:#n?, max_swipes?}`: every row of a scrollable list in one answer, each with a
     * stable reference @rN by position - rows with the same title stay separate. Scrolls the list
     * ([query], or the first list that holds more), stitches pages by their overlap, stops at the end
     * (or when a page adds nothing), then scrolls back to the top.
     */
    private fun collect(arguments: JSONObject, call: CallContext): JSONObject {
        val shaper = kira.phoneuse.data.AgentDisplayShaper; val graph = shaper.graph(call.lane)
        fun answer(body: JSONObject) = AgentDisplayMcp.wrapCallResult(body.toString(), JSONObject().put("detail", "full"), call.lane)
        val cursor = arguments.optString("cursor")
        val prior = if (cursor.isBlank()) null else synchronized(graph) { graph.collections[cursor] }
        if (cursor.isNotBlank() && prior == null) return answer(JSONObject().put("ok", false).put("code", "NO_SUCH_CURSOR")
            .put("errmsg", "This cursor is not part of the current session/lane."))
        val initialRef = if (prior == null && arguments.optString("query").startsWith("#"))
            shaper.pin(call.lane, arguments.optString("query"), shaper.currentPkg(call.lane).orEmpty(), "collection container", "")?.id else null
        val boundRef = prior?.optString("container_ref") ?: initialRef
        var read = settledRead(800, call)
        if (!read.optBoolean("ok", true)) return answer(read)
        val query = if (boundRef == null) arguments.optString("query") else
            shaper.locate(call.lane, boundRef)?.let { "#$it" }
                ?: return answer(JSONObject().put("ok", false).put("code", "COLLECT_CONTAINER_CHANGED")
                    .put("errmsg", "The cursor's window or container changed. Read the current scroll_regions before a new collection."))
        var page = shaper.listPage(call.lane, query, null)
        val box = page.box ?: return answer(JSONObject().put("ok", false).put("code", "NO_LIST")
            .put("errmsg", "No unambiguous scrollable list here."))
        val pkg = currentPackage(read)
        val containerRef = boundRef ?: page.node?.let { shaper.pin(call.lane, "#$it", pkg, "collection container", "")?.id }
            ?: return answer(JSONObject().put("ok", false).put("code", "NO_LIST"))
        val seq = ArrayList<String>()
        prior?.optJSONArray("labels")?.let { labels -> for (i in 0 until labels.length()) seq += labels.optString(i) }
        shaper.mergePage(seq, page.rows.map { it.label })
        val token = if (cursor.isBlank()) synchronized(graph) { graph.next("c") } else cursor
        val maxSwipes = arguments.optInt("max_swipes", 3).coerceIn(1, 12)
        var swipes = 0; var stagnant = 0; var reason = "page_limit"; var stopped = false
        try {
            while (page.more && swipes < maxSwipes && stagnant < 2) {
                call.control?.check()
                // Leave time for one action to finish and return its observation before host timeout.
                if ((call.control?.remaining() ?: 80_000) < 25_000) { reason = "time_budget"; break }
                val moved = scrollList(page.box ?: box, forward = true, call, containerRef); swipes++
                if (!moved.optBoolean("ok", true)) { reason = moved.optString("code").ifBlank { "scroll_failed" }; break }
                // Scroll already returned a settled observation; use it, without a second full read.
                read = moved
                page = shaper.listPage(call.lane, "", page.box ?: box)
                val before = seq.size; shaper.mergePage(seq, page.rows.map { it.label })
                stagnant = if (before == seq.size) stagnant + 1 else 0
            }
            if (!page.more) reason = "boundary" else if (stagnant >= 2) reason = "no_new_rows"
        } catch (_: CallStopped) { reason = "operation_stopped"; stopped = true }
        val items = shaper.rowItems(call.lane, box, pkg, seq)
        synchronized(graph) {
            graph.collections[token] = JSONObject().put("container_ref", containerRef).put("package", pkg)
                .put("labels", JSONArray(seq)).put("bounds", JSONArray(box.toList()))
            while (graph.collections.size > 16) graph.collections.remove(graph.collections.keys.first())
            graph.dirty = true
        }
        return answer(JSONObject().put("ok", !stopped).put("count", items.size).put("complete", !page.more)
            .put("cursor", token).put("stop_reason", reason).put("swipes", swipes).put("origin", "current_viewport")
            .put("snapshot_id", read.optString("snapshot_id")).put("screen_ref", read.optString("screen_ref"))
            .put("scroll_regions", read.optJSONArray("scroll_regions") ?: JSONArray())
            .put("rows", JSONArray(items.map { "${it.id} ${it.label}" }))
            .put("note", "Collected from the starting viewport onward, without automatic rewind. Continue with collect cursor; current row refs remain available. complete describes the forward boundary, not unseen rows before the starting viewport.")
            .apply { if (stopped) put("code", "OPERATION_STOPPED") })
    }

    /**
     * Turns an item reference (@rN / @pN) in query/at/target into the `#n` it has now: on screen by
     * label and neighbours, else - for a row of a collected list - by scrolling to its position.
     * Returns the rewritten arguments, or an error answer.
     */
    private fun resolveRefs(arguments: JSONObject, call: CallContext): Pair<JSONObject?, JSONObject?> {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        val action = arguments.optString("action").trim().lowercase()
        if (action in setOf("note", "refs")) return arguments to null
        val keys = listOf("query", "at", "target").filter { arguments.optString(it).trim().matches(Regex("^@[rp]\\d+$")) }
        if (keys.isEmpty()) return arguments to null
        val out = JSONObject(arguments.toString())
        for (key in keys) {
            val id = arguments.optString(key).trim()
            val item = shaper.graph(call.lane).items[id]
                ?: return null to JSONObject().put("ok", false).put("code", "NO_SUCH_REF")
                    .put("errmsg", "$id is not a reference of this lane (refs lists them).")
            call.control?.check()
            var n = if (item.ordinal == null) shaper.locate(call.lane, id) else null
            if (n == null && item.ordinal == null) {
                settledRead(800, call)
                n = shaper.locate(call.lane, id)
            }
            if (n == null && item.ordinal != null && item.listKey != null) n = scrollToRow(item, call)
            if (n == null) n = shaper.locate(call.lane, id)
            if (n == null) {
                return null to JSONObject().put("ok", false).put("code", "REF_NOT_ON_SCREEN")
                    .put("errmsg", "$id (${item.label.take(60)}) is not on this screen. It was seen on ${item.screen}; " +
                        "refs {query:\"${item.screen}\"} shows how that screen was reached.")
                    .put("ref", shaper.refsJson(call.lane, id))
            }
            out.put(key, "#$n")
            shaper.snapshotId(call.lane)?.let { out.put("snapshot_id", it) }
            if (action in setOf("scroll_until", "wait_for_label")) {
                // Stable row resolution already brought the exact item into view. Do not feed
                // its transient #index to a text-label matcher or scroll past it again.
                out.put("action", "dump_tree").put("query", "#$n")
            }
        }
        return out to null
    }

    /** Scrolls a collected list until the row at [item]'s position is visible; its `#n`, or null. */
    private fun scrollToRow(item: kira.phoneuse.data.AgentDisplayShaper.Item, call: CallContext): Int? {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        val key = item.listKey ?: return null
        if (shaper.currentPackage(call.lane) != key.substringBefore(':')) return null
        val box = key.substringAfterLast(':').split(',').mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 4 }?.toIntArray() ?: return null
        val target = item.ordinal ?: return null
        repeat(6) {
            call.control?.check()
            if ((call.control?.remaining() ?: 80_000) < 25_000) return null
            val page = shaper.listPage(call.lane, "", box)
            val area = page.box ?: return null
            val start = shaper.pageStart(call.lane, key, page.rows.map { r -> r.label })
            if (start != null && target in start until start + page.rows.size) return page.rows[target - start].index
            val forward = start == null || target >= start + page.rows.size
            if ((forward && !page.more) || (!forward && !page.behind)) return null
            val result = scrollList(area, forward, call)
            if (!result.optBoolean("ok", true)) return null
        }
        return null
    }

    /** `pin {query:#n, note?}`, `note {query:@ref, note}`, `refs {query?:@ref}`. */
    private fun refsAction(action: String, arguments: JSONObject, call: CallContext): JSONObject {
        val shaper = kira.phoneuse.data.AgentDisplayShaper
        fun answer(body: JSONObject) = AgentDisplayMcp.wrapCallResult(body.toString(), JSONObject().put("detail", "full"), call.lane)
        val query = arguments.optString("query").trim()
        return when (action) {
            "pin" -> {
                val read = settledRead(800, call)
                val text = runCatching { displayStep(JSONObject().put("action", "read_text").put("query", query), call).optString("raw_text") }
                    .getOrDefault("")
                val item = shaper.pin(call.lane, query, currentPackage(read), arguments.optString("note"), text)
                    ?: return answer(JSONObject().put("ok", false).put("code", "NOT_ON_SCREEN")
                        .put("errmsg", "pin needs query:#n of an element on this screen."))
                answer(JSONObject().put("ok", true).put("ref", item.id).put("label", item.label).put("screen", item.screen)
                    .apply { item.ordinal?.let { put("position", it + 1) } })
            }
            "note" -> {
                val text = arguments.optString("note").ifBlank { arguments.optString("text") }
                if (shaper.note(call.lane, query, text)) answer(JSONObject().put("ok", true).put("ref", query).put("note", text))
                else answer(JSONObject().put("ok", false).put("code", "NO_SUCH_REF").put("errmsg", "$query is not a reference of this lane."))
            }
            else -> answer(shaper.refsJson(call.lane, query, arguments.optString("detail").equals("full", true)))
        }
    }

    private fun controlledDisplay(args: JSONObject, call: CallContext): String {
        val raw = runtime.agentDisplay(args, call.session, call.lane,
            shouldStop = { call.control?.let { it.cancelled.get() || it.remaining() == 0L } ?: false },
            onProgress = { progress ->
                val child = progress.optJSONObject("_step_result")
                if (child != null) {
                    kira.phoneuse.data.AgentDisplayShaper.session.set(call.session)
                    kira.phoneuse.data.AgentDisplayShaper.retainBatchStep(call.lane,
                        progress.optJSONObject("_step_arguments") ?: JSONObject(), child)
                } else call.control?.progress?.set(progress.toString())
            })
        return JSONObject(raw).apply {
            call.control?.let { put("operation_id", it.key.substringAfter('|')).put("operation_finished", true).put("cancel_requested", it.cancelled.get()) }
            args.optString("_expected_package").takeIf { it.isNotBlank() }?.let { put("expected_package", it) }
        }.toString()
    }

    private fun displayCall(arguments: JSONObject, call: CallContext, block: () -> String): String {
        val steps = buildList {
            add(arguments)
            arguments.optJSONArray("actions")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add) }
        }
        val actions = steps.map { it.optString("action").lowercase() }
        val transient = if (actions.any { it in TypingActions }) listOf("ime") else emptyList()
        val sticky = ArrayList<String>()
        var launched = ""
        steps.forEach { step ->
            when (step.optString("action").lowercase()) {
                "launch" -> {
                    val target = step.optString("target")
                    launched = runCatching { runtime.resolvePackage(target, call.lane) }.getOrNull().orEmpty().ifBlank { target }
                    if (launched.isNotBlank()) { sticky += "pkg:$launched"; arguments.put("_expected_package", launched) }
                }
                "open_url", "url", "browse" -> {
                    // The browser is one app: two lanes cannot both have it on screen.
                    val pkg = step.optString("package").ifBlank { "browser" }
                    sticky += "pkg:$pkg"
                }
                "switch_display", "display" -> if (step.optString("to").ifBlank { step.optString("mode") } == "real") sticky += "real"
            }
        }
        call.control?.check()
        val count = arguments.optJSONArray("actions")?.length() ?: 1
        val budget = minOf(80_000L, ExclusiveGuiCallTimeoutMillis + (count - 1).coerceAtLeast(0) * 8_000L)
        call.control?.let {
            arguments.put("_deadline_ms", minOf(it.deadline, android.os.SystemClock.uptimeMillis() + budget))
            it.args.put("_operation_lane", call.lane)
        }
        return exclusiveGuiCall(call, transient, sticky, timeoutMillis = budget, onSuccess = { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            if (result != null && result.optBoolean("ok", true)) {
                steps.forEach { step ->
                    when (step.optString("action").lowercase()) {
                        "launch" -> runtime.scheduler.keepOnlyPackage(call.lane, result.optString("package_name").ifBlank { launched })
                        "open_url", "url", "browse" -> runtime.scheduler.own(call.lane, "pkg:" + step.optString("package").ifBlank { "browser" })
                        "force_stop", "stop" -> {
                            val target = step.optString("target")
                            if (target.isNotBlank()) runtime.scheduler.disown(call.lane, "pkg:$target")
                            // agent_display stop (no target) releases this lane's display, the real screen included.
                            else runtime.scheduler.disown(call.lane, "real")
                        }
                        "switch_display", "display" -> {
                            if (result.optString("display_mode") == "real") runtime.scheduler.own(call.lane, "real")
                            else runtime.scheduler.disown(call.lane, "real")
                        }
                    }
                }
            }
        }, block = block)
    }

    /**
     * One GUI call: waits (fairly, up to the caller's wait) for its lane and the shared resources,
     * then runs on a worker. When a call outlives its time limit the caller answers, but the lane
     * stays held until the phone really finishes, so the next call waits instead of overlapping.
     */
    private fun exclusiveGuiCall(
        call: CallContext,
        transient: List<String>,
        sticky: List<String>,
        timeoutMillis: Long = ExclusiveGuiCallTimeoutMillis,
        onSuccess: (String) -> Unit = {},
        block: () -> String,
    ): String {
        call.control?.check()
        val held = listOf("lane:${call.lane}") + transient
        val grant = runtime.scheduler.acquire(call.lane, held, sticky, minOf(call.waitMillis, call.control?.remaining() ?: call.waitMillis))
        if (!grant.ok) return grant.info.toString()
        val result = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                kira.phoneuse.data.AgentDisplayShaper.session.set(call.session)
                call.control?.check()
                val raw = block()
                result.set(raw); call.control?.lastRaw?.set(raw)
                runCatching { onSuccess(raw) }
            } catch (thrown: Throwable) {
                error.set(thrown)
                call.control?.lastRaw?.set(JSONObject().put("ok", false).put("code", "TOOL_FAILED")
                    .put("errmsg", thrown.message.orEmpty().take(400)).toString())
            } finally {
                runtime.scheduler.release(call.lane, held)
                call.control?.let { control -> synchronized(control) {
                    control.running.set(false); control.deferredCleanup?.invoke(); control.deferredCleanup = null
                } }
                done.countDown()
            }
        }, "phone-use-gui-${call.lane}")
        worker.isDaemon = true
        try {
            call.control?.running?.set(true)
            worker.start()
        } catch (thrown: Throwable) {
            call.control?.running?.set(false)
            runtime.scheduler.release(call.lane, held)
            throw thrown
        }
        if (!done.await(minOf(timeoutMillis, call.control?.remaining() ?: timeoutMillis), TimeUnit.MILLISECONDS)) {
            call.control?.cancelled?.set(true)
            // Not interrupted: a gesture cut in half leaves the phone in an unknown state.
            return JSONObject(AgentModeSafety.captureTimeoutJson())
                .put("code", "STILL_RUNNING")
                .put("lane", call.lane).put("operation_id", call.control?.key?.substringAfter('|').orEmpty())
                .put("cancel_requested", true).put("progress", JSONObject(call.control?.progress?.get() ?: "{}"))
                .put("completed_steps", JSONObject(call.control?.progress?.get() ?: "{}").optJSONArray("completed_steps") ?: JSONArray())
                .put("running_step", JSONObject(call.control?.progress?.get() ?: "{}").optInt("running_step", -1))
                .put("errmsg", "The call is still running on the phone after ${timeoutMillis / 1000} s. " +
                    "Wait a moment, then take a screenshot or dump_tree before repeating it.")
                .toString()
        }
        error.get()?.let { thrown ->
            val message = thrown.message.orEmpty()
            if (message.startsWith("LANES_FULL")) {
                return JSONObject().put("ok", false).put("code", "LANES_FULL").put("errmsg", message.removePrefix("LANES_FULL: ")).toString()
            }
            throw thrown
        }
        return result.get() ?: AgentModeSafety.captureTimeoutJson()
    }

    // --- phone window ----------------------------------------------------------------------

    private fun handleUi(socket: Socket, method: String, path: String, body: String, lane: String?, monitor: Boolean = false) {
        if (method != "GET" && method != "POST") {
            writeHttp(socket, 405, JSONObject().put("ok", false).toString())
            return
        }
        val args = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val reply: JSONObject = when (path) {
            "/ui/state" -> runtime.windowState(lane, monitor)
            "/ui/notification-protection" -> {
                if (method != "POST" || args.opt("enabled") !is Boolean) {
                    JSONObject().put("ok", false).put("code", "BAD_ARGS").put("errmsg", "POST a boolean enabled")
                } else runtime.windowNotificationProtection(lane, args.getBoolean("enabled"))
            }
            "/ui/frame.jpg" -> {
                val frame = runtime.windowFrame(lane)
                if (frame == null) writeHttp(socket, 204, "") else writeBytes(socket, 200, frame, "image/jpeg")
                return
            }
            "/ui/display/start" -> runtime.windowStartDisplay()
            "/ui/tap" -> runtime.windowTap(args.optInt("x"), args.optInt("y"))
            "/ui/swipe" -> runtime.windowSwipe(
                args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"),
                args.optInt("duration_ms", 280),
            )
            "/ui/takeover/start" -> runtime.windowStartTakeover()
            "/ui/takeover/finish" -> runtime.windowFinishTakeover()
            else -> {
                writeHttp(socket, 404, JSONObject().put("ok", false).toString())
                return
            }
        }
        writeHttp(socket, 200, reply.toString())
    }

    private fun queryParam(rawPath: String, key: String): String? =
        rawPath.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }

    private fun streamFrames(client: Socket, lane: String? = null) {
        client.use { socket ->
            socket.soTimeout = 0
            val output = socket.getOutputStream()
            val boundary = "phoneuseframe"
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$boundary\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            while (!socket.isClosed) {
                val started = System.currentTimeMillis()
                val frame = runtime.windowFrame(lane)
                if (frame == null) {
                    Thread.sleep(300)
                    continue
                }
                try {
                    output.write("--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(frame)
                    output.write("\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                } catch (_: Exception) {
                    return // the window was closed
                }
                val spent = System.currentTimeMillis() - started
                if (spent < StreamFrameIntervalMillis) Thread.sleep(StreamFrameIntervalMillis - spent)
            }
        }
    }

    // --- HTTP plumbing -----------------------------------------------

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
        val accept: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArray(8 * 1024)
        var headerEnd = -1
        var total = 0
        while (total < headerBytes.size) {
            val byte = input.read()
            if (byte < 0) break
            headerBytes[total] = byte.toByte()
            total += 1
            if (total >= 4 &&
                headerBytes[total - 4] == '\r'.code.toByte() && headerBytes[total - 3] == '\n'.code.toByte() &&
                headerBytes[total - 2] == '\r'.code.toByte() && headerBytes[total - 1] == '\n'.code.toByte()
            ) {
                headerEnd = total
                break
            }
        }
        if (headerEnd < 0) return null
        val lines = String(headerBytes, 0, headerEnd, Charsets.US_ASCII).split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1).filter { it.contains(':') }.associate { line ->
            line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (contentLength > MaxHttpBodyBytes) return null
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBytes, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            body = String(bodyBytes, 0, read, Charsets.UTF_8),
            accept = headers["accept"].orEmpty(),
            headers = headers,
        )
    }

    private fun writeHttp(
        socket: Socket,
        status: Int,
        body: String,
        extraHeaders: List<String> = emptyList(),
        accept: String = "",
        contentType: String = "application/json; charset=utf-8",
    ) {
        val sse = status == 200 && body.isNotBlank() &&
            accept.contains("text/event-stream", ignoreCase = true) &&
            !accept.contains("application/json", ignoreCase = true)
        val payload = if (sse) "event: message\ndata: $body\n\n".toByteArray(Charsets.UTF_8) else body.toByteArray(Charsets.UTF_8)
        writeBytes(socket, status, payload, if (sse) "text/event-stream" else contentType, extraHeaders)
    }

    private fun writeBytes(
        socket: Socket,
        status: Int,
        payload: ByteArray,
        contentType: String,
        extraHeaders: List<String> = emptyList(),
    ) {
        val reason = when (status) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("MCP-Protocol-Version: $UpaMcpProtocolVersion\r\n")
            append("Mcp-Session-Id: $sessionId\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val output: OutputStream = socket.getOutputStream()
        output.write(header)
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    companion object {
        val Endpoints = setOf(
            AgentDisplayMcp.PluginId,
            PhoneUiMcp.PluginId,
            PhoneAppFlowMcp.PluginId,
            DeviceCatalogMcp.PluginId,
            PhoneTaskMcp.PluginId,
            PhoneRecordMcp.PluginId,
            PhoneSopsMcp.PluginId,
            PhoneLanesMcp.PluginId,
        )

        fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
}
