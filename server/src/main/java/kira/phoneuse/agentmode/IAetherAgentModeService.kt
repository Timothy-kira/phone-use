package kira.phoneuse.agentmode

import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * The Agent Mode service contract, as a plain interface (it was an AIDL when the controller lived in the
 * app and reached this service over Binder in a Shizuku/root process; in the plugin both run in the
 * same `app_process` under the shell uid, so the calls are direct.
 */
interface IAetherAgentModeService {
    fun createDisplay(name: String, width: Int, height: Int, density: Int, surface: Surface): Int
    fun createOwnedDisplay(name: String, width: Int, height: Int, density: Int): Int
    fun attachPreviewSurface(displayId: Int, surface: Surface)
    fun detachPreviewSurface(displayId: Int)
    fun releaseDisplay(displayId: Int)
    fun launchPackage(packageName: String, displayId: Int)
    /** Opens [url] with a VIEW intent on [displayId]; returns the package that handled it. */
    fun openUrl(url: String, packageName: String?, displayId: Int): String
    fun runInputCommand(command: String)
    fun tap(displayId: Int, x: Int, y: Int)
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
    fun key(displayId: Int, keyCode: String)
    fun editText(displayId: Int, arguments: String, shouldStop: () -> Boolean = { false }): String
    /** (element label, its complete text) or null - see AgentModeUiAutomation.readFullText. */
    fun readText(displayId: Int, query: String): Pair<String, String>?
    fun captureImageToFd(displayId: Int, output: ParcelFileDescriptor, maxEdge: Int, quality: Int)
    fun listDisplaysJson(): String
    fun listInstalledAppsJson(): String
    fun launchHomeOnDisplay(displayId: Int)
    fun dumpUiTree(displayId: Int): String
    fun waitUntilLayoutStable(displayId: Int, quietMs: Int, timeoutMs: Int, targetJson: String = ""): Boolean
    fun waitUntilLayoutStableDetails(displayId: Int, quietMs: Int, timeoutMs: Int, targetJson: String = "", shouldStop: () -> Boolean = { false }): String
    fun dumpUiTreePaged(displayId: Int, query: String, region: String, offset: Int, limit: Int): String
    fun clickNodeDetails(displayId: Int, query: String): String
    fun clickNode(displayId: Int, query: String): Boolean
    fun scrollNode(displayId: Int, query: String, direction: String): Boolean
    fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int)
    fun drag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int)
    fun setProgress(displayId: Int, query: String, value: Float): Boolean?
    fun doubleTap(displayId: Int, x: Int, y: Int)
    fun pinch(displayId: Int, cx: Int, cy: Int, startSpan: Int, endSpan: Int, durationMs: Int)
    fun waitForLabel(displayId: Int, label: String, timeoutMs: Int): Boolean
    fun adoptDefaultDisplay(): Int
    fun packageVisibleOnDisplay(displayId: Int, packageName: String): Boolean
    fun movePackageToDisplay(packageName: String, displayId: Int): Boolean
    fun windowsOnDisplayJson(displayId: Int): String
    fun destroy()
}
