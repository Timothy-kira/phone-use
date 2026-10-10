package kira.phoneuse.agentmode

import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap

class AgentModeDeskPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display, AgentModeDeskThemeRes) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(DeskBackgroundColor))
        window?.setWindowAnimations(0)
        setContentView(
            createAgentModeDeskView(context) { app ->
                startDeskApp(context, this.display.displayId, app)
            },
        )
    }
}

internal data class DeskApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: android.graphics.Bitmap?,
)

internal fun createAgentModeDeskView(
    context: Context,
    onLaunch: (DeskApp) -> Unit,
): View {
    val density = context.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(DeskBackgroundColor)
        setPadding(pad, (28 * density).toInt(), pad, pad)
    }
    root.addView(
        TextView(context).apply {
            text = "虚拟桌面"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, pad)
        },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    val apps = loadAgentModeDeskApps(context.packageManager, PhoneUseSelfPackageName)
    val grid = GridView(context).apply {
        numColumns = 4
        adapter = DeskAppAdapter(context, apps, onLaunch)
        isVerticalScrollBarEnabled = false
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
    }
    root.addView(
        grid,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ),
    )
    return root
}

internal fun loadAgentModeDeskApps(
    packageManager: PackageManager,
    selfPackageName: String,
): List<DeskApp> {
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launch, PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { resolve ->
            val activity = resolve.activityInfo ?: return@mapNotNull null
            if (activity.packageName == selfPackageName) return@mapNotNull null
            val label = resolve.loadLabel(packageManager).toString().ifBlank { activity.packageName }
            val icon = runCatching { resolve.loadIcon(packageManager).toBitmap(96, 96) }.getOrNull()
            DeskApp(
                packageName = activity.packageName,
                activityName = activity.name,
                label = label,
                icon = icon,
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

internal fun startDeskApp(context: Context, displayId: Int, app: DeskApp) {
    if (displayId == Display.DEFAULT_DISPLAY) return
    val intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.packageName, app.activityName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    val options = ActivityOptions.makeBasic()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        options.launchDisplayId = displayId
    }
    runCatching { context.startActivity(intent, options.toBundle()) }
}

private const val DeskBackgroundColor = 0xFF111111.toInt()

/** Theme.Aether.AgentDesk was a dark, animation-free fullscreen theme; the framework one plus the
 * background and setWindowAnimations(0) above give the same desk without app resources. */
internal val AgentModeDeskThemeRes: Int = android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen

/** The server is never installed, so no package on the phone is "us". */
internal const val PhoneUseSelfPackageName = "kira.phoneuse.server"

private class DeskAppAdapter(
    private val context: Context,
    private val apps: List<DeskApp>,
    private val onLaunch: (DeskApp) -> Unit,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val iconSize = (48 * density).toInt()
    private val cellPad = (4 * density).toInt()

    override fun getCount(): Int = apps.size
    override fun getItem(position: Int): DeskApp = apps[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val column = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(cellPad, cellPad, cellPad, cellPad)
            addView(
                ImageView(context),
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
            addView(
                TextView(context).apply {
                    setTextColor(0xFFE8E8E8.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 2
                    setPadding(0, (6 * density).toInt(), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val app = apps[position]
        column.setOnClickListener { onLaunch(app) }
        val iconView = column.getChildAt(0) as ImageView
        val labelView = column.getChildAt(1) as TextView
        if (app.icon != null) {
            iconView.setImageBitmap(app.icon)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.setImageDrawable(null)
            iconView.visibility = View.INVISIBLE
        }
        labelView.text = app.label
        return column
    }
}
