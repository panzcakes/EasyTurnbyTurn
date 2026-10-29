package app.clusternav

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import kotlin.math.hypot

/** Shared look and small helpers for the home and settings screens. */
object Ui {
    val BG = Color.parseColor("#0D0E10")
    val CARD = Color.parseColor("#17191D")
    val LINE = Color.parseColor("#23262B")
    val TEXT = Color.parseColor("#ECEEF2")
    val MUTED = Color.parseColor("#8B909A")
    val ACCENT = Color.parseColor("#2F9BFF")
    val GREEN = Color.parseColor("#35D07F")
    val AMBER = Color.parseColor("#FFB865")
    val GREY = Color.parseColor("#44474D")
    val CLUSTER = Color.parseColor("#8E9390")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, color: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(ctx, radiusDp.toFloat())
            if (stroke != 0) setStroke(dp(ctx, 1), stroke)
        }

    fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

    fun prefs(ctx: Context) = ctx.getSharedPreferences("cluster", Context.MODE_PRIVATE)

    fun deviceName(ctx: Context): String? = prefs(ctx).getString("name", null)
    fun deviceAddress(ctx: Context): String? = prefs(ctx).getString("addr", null)

    fun hasNotificationAccess(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    fun batteryUnrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun openNotificationAccess(ctx: Context) =
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openBattery(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    private fun neededPermissions(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
            l.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) l.add("android.permission.POST_NOTIFICATIONS")
        return l
    }

    fun missingPermissions(a: Activity): List<String> =
        neededPermissions().filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun requestPermissions(a: Activity) {
        val m = missingPermissions(a)
        if (m.isNotEmpty()) a.requestPermissions(m.toTypedArray(), 1)
    }

    fun dialog(a: Activity): AlertDialog.Builder = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)

    /** Pick the bike's cluster from the phone's paired Bluetooth devices. */
    fun chooseDevice(a: Activity, done: () -> Unit) {
        if (missingPermissions(a).isNotEmpty()) {
            requestPermissions(a)
            return
        }
        val ad = (a.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (ad == null || !ad.isEnabled) {
            toast(a, "Turn Bluetooth on first")
            return
        }
        val bonded = ad.bondedDevices.sortedBy { it.name ?: "" }
        if (bonded.isEmpty()) {
            toast(a, "No paired devices. Pair the bike in Bluetooth settings first")
            return
        }
        dialog(a).setTitle("Choose your bike")
            .setItems(bonded.map { "${it.name ?: "(no name)"}\n${it.address}" }.toTypedArray()) { _, i ->
                val d = bonded[i]
                prefs(a).edit().putString("addr", d.address).putString("name", d.name ?: d.address).apply()
                Cluster.log("Selected ${d.name} (${d.address})")
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun fmtDistance(m: Int): String = when {
        m < 0 -> ""
        m >= 10000 -> "${Math.round(m / 1000.0)} km"
        m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000.0)
        m >= 100 -> "${Math.round(m / 10.0) * 10} m"
        else -> "${maxOf(5, Math.round(m / 5.0).toInt() * 5)} m"
    }

    fun fmtEta(h24: Int, min: Int): String {
        if (h24 < 0) return ""
        val h = when { h24 == 0 -> 12; h24 > 12 -> h24 - 12; else -> h24 }
        return String.format(java.util.Locale.US, "%d:%02d %s", h, min, if (h24 >= 12) "PM" else "AM")
    }

    fun describe(k: Maneuver): String = when (k) {
        Maneuver.LEFT -> "turn left"
        Maneuver.RIGHT -> "turn right"
        Maneuver.SLIGHT_LEFT -> "bear left"
        Maneuver.SLIGHT_RIGHT -> "bear right"
        Maneuver.SHARP_LEFT -> "sharp left"
        Maneuver.SHARP_RIGHT -> "sharp right"
        Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "make a U-turn"
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> "roundabout"
        Maneuver.KEEP_LEFT -> "keep left"
        Maneuver.KEEP_RIGHT -> "keep right"
        Maneuver.EXIT_LEFT -> "take the exit on the left"
        Maneuver.EXIT_RIGHT -> "take the exit on the right"
        Maneuver.ARRIVE -> "arrive"
        else -> "continue straight"
    }
}

/** Three horizontal lines; tap target is the whole view. */
class HamburgerView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; strokeWidth = Ui.dpf(ctx, 2.5f); strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val w = Ui.dpf(context, 11f); val gap = Ui.dpf(context, 7f)
        for (i in -1..1) c.drawLine(cx - w, cy + i * gap, cx + w, cy + i * gap, p)
    }
}

/** On/off switch drawn by hand so it matches the dark theme on every phone. */
class ToggleView(ctx: Context) : View(ctx) {
    var on = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.dp(context, 46), Ui.dp(context, 26))

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = if (on) Ui.ACCENT else Color.parseColor("#33363C")
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = Color.WHITE
        c.drawCircle(if (on) width - r else r, r, r - Ui.dpf(context, 3f), p)
    }
}

/** The round "cluster" showcase: ring, grey panel with the turn arrow and distance. */
class RingView(ctx: Context) : View(ctx) {
    var ringColor = Ui.GREY
    var panel = false            // true: grey cluster-style disc; false: dark disc with a message
    var kind: Maneuver? = null
    var exit = 0
    var big = ""
    var small = ""
    var message = ""
    var accent = false           // idle message shown as a tappable button

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun update(ring: Int, panel: Boolean, kind: Maneuver?, exit: Int, big: String, small: String, message: String) {
        if (ring == ringColor && panel == this.panel && kind == this.kind && exit == this.exit &&
            big == this.big && small == this.small && message == this.message) return
        ringColor = ring; this.panel = panel; this.kind = kind; this.exit = exit
        this.big = big; this.small = small; this.message = message
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val outer = width / 2f - Ui.dpf(context, 4f)
        p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dpf(context, 5f); p.color = ringColor
        c.drawCircle(cx, cy, outer, p)
        val rr = outer - Ui.dpf(context, 12f)
        p.style = Paint.Style.FILL; p.color = if (panel) Ui.CLUSTER else Ui.CARD
        c.drawCircle(cx, cy, rr, p)

        p.textAlign = Paint.Align.CENTER
        p.style = Paint.Style.FILL
        p.color = if (panel) Color.WHITE else Ui.MUTED
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textSize = rr * 0.085f
        p.letterSpacing = 0.18f
        c.drawText("NAVIGATION", cx, cy - rr * 0.70f, p)
        p.letterSpacing = 0f

        if (!panel) {
            p.typeface = if (accent) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            if (accent) p.color = Ui.ACCENT
            p.textSize = if (accent) rr * 0.14f else rr * 0.11f
            val lines = message.split("\n")
            val step = if (accent) rr * 0.18f else rr * 0.14f
            var y = cy - (lines.size - 1) * step / 2f + rr * 0.05f
            for (l in lines) { c.drawText(l, cx, y, p); y += step }
            return
        }
        val k = kind
        if (k != null) drawArrow(c, k, exit, cx, cy - rr * 0.25f, rr * 0.62f)
        p.color = Color.WHITE
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textSize = rr * 0.30f
        c.drawText(big, cx, cy + rr * 0.50f, p)
        p.typeface = android.graphics.Typeface.DEFAULT
        p.textSize = rr * 0.10f
        c.drawText(small, cx, cy + rr * 0.72f, p)
    }

    // ---- arrows, drawn on a 100 x 100 grid scaled to [size]

    private fun drawArrow(c: Canvas, kind: Maneuver, exit: Int, cx: Float, cy: Float, size: Float) {
        val s = size / 100f
        fun X(v: Float) = cx + (v - 50f) * s
        fun Y(v: Float) = cy + (v - 50f) * s
        val stroke = 13f * s
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.strokeJoin = Paint.Join.MITER; p.strokeCap = Paint.Cap.BUTT

        fun line(pts: List<Pair<Float, Float>>, head: Boolean = true, dim: Boolean = false) {
            p.color = if (dim) Color.argb(110, 255, 255, 255) else Color.WHITE
            path.reset()
            path.moveTo(X(pts[0].first), Y(pts[0].second))
            for (i in 1 until pts.size) path.lineTo(X(pts[i].first), Y(pts[i].second))
            p.style = Paint.Style.STROKE
            c.drawPath(path, p)
            if (!head) return
            val (ex, ey) = pts.last(); val (bx, by) = pts[pts.size - 2]
            val len = hypot(ex - bx, ey - by); val dx = (ex - bx) / len; val dy = (ey - by) / len
            val nx = -dy; val ny = dx
            path.reset()
            path.moveTo(X(ex + dx * 16f), Y(ey + dy * 16f))
            path.lineTo(X(ex + nx * 16f), Y(ey + ny * 16f))
            path.lineTo(X(ex - nx * 16f), Y(ey - ny * 16f))
            path.close()
            p.style = Paint.Style.FILL
            c.drawPath(path, p)
        }
        fun mir(pts: List<Pair<Float, Float>>) = pts.map { (100f - it.first) to it.second }

        val left = when (kind) {
            Maneuver.LEFT -> listOf(60f to 95f, 60f to 50f, 18f to 50f)
            Maneuver.SLIGHT_LEFT -> listOf(55f to 95f, 55f to 65f, 28f to 30f)
            Maneuver.SHARP_LEFT -> listOf(62f to 95f, 62f to 35f, 22f to 72f)
            Maneuver.UTURN_LEFT -> listOf(72f to 95f, 72f to 32f, 30f to 32f, 30f to 66f)
            Maneuver.KEEP_LEFT, Maneuver.EXIT_LEFT -> listOf(50f to 95f, 50f to 62f, 24f to 28f)
            else -> null
        }
        val right = when (kind) {
            Maneuver.RIGHT -> mir(listOf(60f to 95f, 60f to 50f, 18f to 50f))
            Maneuver.SLIGHT_RIGHT -> mir(listOf(55f to 95f, 55f to 65f, 28f to 30f))
            Maneuver.SHARP_RIGHT -> mir(listOf(62f to 95f, 62f to 35f, 22f to 72f))
            Maneuver.UTURN_RIGHT -> mir(listOf(72f to 95f, 72f to 32f, 30f to 32f, 30f to 66f))
            Maneuver.KEEP_RIGHT, Maneuver.EXIT_RIGHT -> mir(listOf(50f to 95f, 50f to 62f, 24f to 28f))
            else -> null
        }
        when {
            left != null -> {
                if (kind == Maneuver.KEEP_LEFT || kind == Maneuver.EXIT_LEFT) line(listOf(50f to 62f, 76f to 28f), head = false, dim = true)
                line(left)
            }
            right != null -> {
                if (kind == Maneuver.KEEP_RIGHT || kind == Maneuver.EXIT_RIGHT) line(listOf(50f to 62f, 24f to 28f), head = false, dim = true)
                line(right)
            }
            kind == Maneuver.ROUNDABOUT || kind == Maneuver.ROUNDABOUT_LEFT || kind == Maneuver.ROUNDABOUT_RIGHT -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = stroke * 0.8f; p.color = Color.WHITE
                c.drawCircle(cx, Y(46f), 20f * s, p)
                line(listOf(50f to 98f, 50f to 70f), head = false)
                val tip = when (exit) {
                    1 -> listOf(30f to 40f, 8f to 30f)      // first exit: left
                    3 -> listOf(70f to 40f, 92f to 30f)     // third exit: right
                    4 -> listOf(30f to 52f, 8f to 66f)
                    else -> listOf(50f to 26f, 50f to 2f)   // second exit / unknown: straight on
                }
                line(tip)
                if (exit in 1..4) {
                    p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = 22f * s
                    c.drawText(exit.toString(), cx, Y(53f), p)
                }
            }
            kind == Maneuver.ARRIVE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 8f * s; p.color = Color.WHITE
                c.drawLine(X(32f), Y(95f), X(32f), Y(8f), p)
                p.style = Paint.Style.FILL
                c.drawRect(X(32f), Y(8f), X(84f), Y(48f), p)
            }
            else -> line(listOf(50f to 95f, 50f to 22f))
        }
        p.style = Paint.Style.FILL
    }
}
