package kira.phoneuse.runtime

import android.graphics.Color
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import kira.phoneuse.agentmode.AetherAgentModeShizukuService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records whatever display the agent is working on - the virtual display or, when the user moved
 * the work there, the real screen - to H.264 MP4, independent of any app on the phone.
 *
 * Frames are drawn at a fixed rate from the server's own frame mirror onto a MediaCodec input
 * surface, so a switch between virtual and real screen continues in the same file instead of
 * starting a new recording. Files are cut into segments so an interrupted run loses at most one,
 * and every agent action is written to `actions.jsonl` with its offset into the recording, which
 * is what an experiment or a test run needs to line the video up with what was done.
 */
class PhoneUseRecorder(
    private val service: AetherAgentModeShizukuService,
    private val root: File,
    private val currentDisplay: () -> Int?,
    /** Width and height of the display being recorded, to size the video to its aspect. */
    private val currentSize: () -> Pair<Int, Int>? = { null },
) {
    private val lock = Any()
    @Volatile
    private var session: Session? = null

    private inner class Session(
        val name: String,
        val dir: File,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val segmentMillis: Long,
    ) {
        val startedAt = System.currentTimeMillis()
        val startedUptime = SystemClock.uptimeMillis()
        val files = ArrayList<String>()
        val actions = File(dir, "actions.jsonl")
        @Volatile var running = true
        @Volatile var framesWritten = 0L
        @Volatile var emptyFrames = 0L
        @Volatile var failed = false
        @Volatile var lastError = ""
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        var track = -1
        var muxerStarted = false
        var segmentStartedUptime = 0L
        var segmentIndex = 0
        lateinit var thread: Thread
    }

    fun start(args: JSONObject): JSONObject = synchronized(lock) {
        session?.let { return status().put("ok", false).put("errmsg", "Already recording '${it.name}'. Stop it first.") }
        val displayId = currentDisplay()
            ?: return JSONObject().put("ok", false).put("errmsg", "No display yet. Do one phone action (or open the phone window) first.")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = args.optString("name").trim().replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "rec-$stamp" }
        val dir = File(root, name).apply { mkdirs() }
        val fps = args.optInt("fps", 15).coerceIn(2, 30)
        val bitrate = args.optInt("bitrate_kbps", 3000).coerceIn(300, 20_000) * 1000
        val maxEdge = args.optInt("max_edge", 1280).coerceIn(360, 1920)
        val segmentMinutes = args.optDouble("segment_minutes", 10.0).coerceIn(0.5, 120.0)
        // The video takes the display's own aspect; content that rotates later is letterboxed.
        val (srcW, srcH) = currentSize()?.takeIf { it.first > 0 && it.second > 0 } ?: (1080 to 2340)
        val longEdge = (maxEdge / 16) * 16
        val shortEdge = ((longEdge * minOf(srcW, srcH).toFloat() / maxOf(srcW, srcH)).toInt() / 16) * 16
        val width = if (srcW <= srcH) shortEdge else longEdge
        val height = if (srcW <= srcH) longEdge else shortEdge
        val s = Session(name, dir, width, height, fps, bitrate, (segmentMinutes * 60_000).toLong())
        runCatching { openSegment(s) }.onFailure { error ->
            return JSONObject().put("ok", false).put("errmsg", "Encoder could not start: ${error.message}")
        }
        s.thread = Thread({ loop(s) }, "phone-use-recorder").apply { isDaemon = true; start() }
        session = s
        JSONObject().put("ok", true).put("recording", true).put("name", name).put("dir", dir.absolutePath)
            .put("width", width).put("height", height).put("fps", fps).put("display_id", displayId)
    }

    fun stop(): JSONObject {
        val s = synchronized(lock) { session.also { session = null } }
            ?: return JSONObject().put("ok", false).put("errmsg", "Not recording.")
        s.running = false
        // The recorder thread finishes the file itself; closing it from here would race the loop.
        s.thread.join(10_000)
        if (s.thread.isAlive) s.lastError = "encoder did not finish within 10 s; the last segment may be incomplete"
        val seconds = (SystemClock.uptimeMillis() - s.startedUptime) / 1000.0
        return JSONObject()
            .put("ok", true)
            .put("recording", false)
            .put("name", s.name)
            .put("dir", s.dir.absolutePath)
            .put("files", JSONArray(s.files))
            .put("actions_log", s.actions.takeIf { it.exists() }?.absolutePath.orEmpty())
            .put("seconds", "%.1f".format(seconds))
            .put("frames", s.framesWritten)
            .put("empty_frames", s.emptyFrames)
            .put("failed", s.failed)
            .put("last_error", s.lastError)
    }

    fun status(): JSONObject {
        val s = session ?: return JSONObject().put("ok", true).put("recording", false)
        return JSONObject()
            .put("ok", true)
            .put("recording", true)
            .put("name", s.name)
            .put("seconds", (SystemClock.uptimeMillis() - s.startedUptime) / 1000)
            .put("segments", s.files.size)
            .put("frames", s.framesWritten)
            .put("empty_frames", s.emptyFrames)
            .put("failed", s.failed)
            .put("last_error", s.lastError)
    }

    val isRecording: Boolean get() = session != null

    /** One line per agent action, with its offset into the recording. */
    fun logAction(arguments: JSONObject, rawResult: String) {
        val s = session ?: return
        val result = runCatching { JSONObject(rawResult) }.getOrNull()
        val line = JSONObject()
            .put("t_ms", SystemClock.uptimeMillis() - s.startedUptime)
            .put("at", System.currentTimeMillis())
            .put("segment", s.segmentIndex)
            .put("action", arguments.optString("action"))
            .put("args", kira.phoneuse.data.PrivacyRedactor.json(JSONObject(arguments.toString())).apply { remove("text") ; arguments.optString("text").takeIf { it.isNotBlank() }?.let { put("text_chars", it.length) } })
            .put("ok", result?.optBoolean("ok", true) ?: true)
            .put("code", result?.optString("code").orEmpty())
            .put("display_mode", result?.optString("display_mode").orEmpty())
            .put("diff", kira.phoneuse.data.PrivacyRedactor.text(result?.optJSONObject("tree_diff")?.optString("summary").orEmpty()))
        synchronized(s) { runCatching { s.actions.appendText(line.toString() + "\n") } }
    }

    private fun loop(s: Session) {
        val frameMillis = 1000L / s.fps
        val dst = RectF(0f, 0f, s.width.toFloat(), s.height.toFloat())
        while (s.running) {
            val tick = SystemClock.uptimeMillis()
            try {
                if (tick - s.segmentStartedUptime >= s.segmentMillis) {
                    closeSegment(s)
                    try {
                        openSegment(s)
                    } catch (error: Throwable) {
                        // No encoder: stop cleanly instead of failing on every frame.
                        s.failed = true
                        s.lastError = "new segment could not start: ${error.message.orEmpty().take(160)}"
                        s.running = false
                        break
                    }
                }
                val surface = s.surface
                val displayId = currentDisplay()
                if (surface != null && displayId != null) {
                    val canvas = surface.lockHardwareCanvas()
                    try {
                        canvas.drawColor(Color.BLACK)
                        if (service.drawLatestFrame(displayId, canvas, dst)) s.framesWritten += 1 else s.emptyFrames += 1
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                }
                drain(s, endOfStream = false)
            } catch (error: Throwable) {
                s.lastError = error.message.orEmpty().take(200)
            }
            val spent = SystemClock.uptimeMillis() - tick
            if (spent < frameMillis) SystemClock.sleep(frameMillis - spent)
        }
        runCatching { closeSegment(s) }.onFailure { s.lastError = "closing the file failed: ${it.message.orEmpty().take(160)}" }
    }

    private fun openSegment(s: Session) {
        s.segmentIndex += 1
        val file = File(s.dir, "%s_%03d.mp4".format(s.name, s.segmentIndex))
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, s.width, s.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, s.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep emitting frames on a static screen, so the timeline does not stall.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / s.fps)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        s.surface = codec.createInputSurface()
        codec.start()
        s.codec = codec
        s.muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        s.track = -1
        s.muxerStarted = false
        s.segmentStartedUptime = SystemClock.uptimeMillis()
        s.files += file.absolutePath
    }

    private fun closeSegment(s: Session) {
        val codec = s.codec ?: return
        runCatching { codec.signalEndOfInputStream() }
        runCatching { drain(s, endOfStream = true) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { s.surface?.release() }
        if (s.muxerStarted) runCatching { s.muxer?.stop() }
        runCatching { s.muxer?.release() }
        s.codec = null
        s.muxer = null
        s.surface = null
    }

    private fun drain(s: Session, endOfStream: Boolean) {
        val codec = s.codec ?: return
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.uptimeMillis() + if (endOfStream) 3_000 else 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || SystemClock.uptimeMillis() > deadline) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val muxer = s.muxer ?: return
                    s.track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    s.muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && s.muxerStarted && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        s.muxer?.writeSampleData(s.track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
