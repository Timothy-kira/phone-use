package kira.phoneuse.runtime

import android.content.Context
import android.os.Looper
import android.os.Process
import java.io.File
import kira.phoneuse.data.UpaMcpHttpPort

/**
 * Phone Use server entry. Started by the bridge over adb, never installed:
 *
 *     adb push server.apk /data/local/tmp/phone-use/server.apk
 *     adb shell CLASSPATH=/data/local/tmp/phone-use/server.apk \
 *         app_process / kira.phoneuse.runtime.Main /data/local/tmp/phone-use
 *
 * It runs under the shell uid - the identity a Shizuku-hosted service would have -
 * so the phone needs nothing but USB debugging.
 */
object Main {
    const val Version = "0.8.27"

    @JvmStatic
    fun main(args: Array<String>) {
        val dataDir = File(args.getOrNull(0) ?: "/data/local/tmp/phone-use").apply { mkdirs() }
        val port = args.getOrNull(1)?.toIntOrNull() ?: UpaMcpHttpPort
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.println("phone-use: uncaught on ${thread.name}: $error")
            error.printStackTrace()
        }
        Looper.prepareMainLooper()
        val context = systemContext()
        val runtime = PhoneUseRuntime(context, dataDir)
        val gateway = PhoneUseGateway(port, runtime, Version)
        gateway.start()
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { gateway.stop() }
            runCatching { runtime.controller.stopDisplay() }
        })
        // The bridge waits for this exact line before it forwards the port.
        println("PHONE_USE_READY port=$port uid=${Process.myUid()} version=$Version")
        System.out.flush()
        // The desk Presentation and display callbacks run on this looper.
        Looper.loop()
    }

    /** A system Context for a bare app_process, the way Shizuku's user-service starter makes one. */
    private fun systemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = activityThreadClass.getMethod("systemMain").invoke(null)
        return activityThreadClass.getMethod("getSystemContext").invoke(thread) as Context
    }
}
