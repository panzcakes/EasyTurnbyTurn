package app.clusternav

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Takes the Google Maps navigation notification, works out the cluster packet and keeps the
 * cluster fed. Also records every Maps notification (the "dump") so the parser can be corrected
 * from real data.
 */
object Relay {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val END_DELAY_MS = 4000L
    private const val END_SHOW_MS = 5000L
    private const val AUTO_DISCONNECT_MS = 20000L

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dumps = ArrayDeque<String>()
    private var lastDumpBody = ""

    private var prefs: android.content.SharedPreferences? = null

    private var active = false           // a navigation notification was seen and has not ended
    private var autoConnected = false
    private var last: ParsedNav? = null
    private var lastLetter: Char = 'G'
    private var lastLogged = ""
    private var updates = 0
    private var lastDistance = -1
    private var lastRoad = ""
    private var currentIcon = ""
    private val seenIcons = java.util.LinkedHashMap<String, Bitmap>()
    private val iconCounts = HashMap<String, Int>()

    var statusListener: (() -> Unit)? = null

    /** What the cluster is being told right now; the home screen draws it. Null when no trip. */
    data class Shown(
        val letter: Char, val kind: Maneuver, val distanceM: Int,
        val etaHour24: Int, val etaMinute: Int, val remainingM: Int, val exit: Int,
        val rerouting: Boolean,
    )
    @Volatile var shown: Shown? = null
    val tripActive: Boolean get() = active

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("cluster", Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ settings

    var enabled: Boolean
        get() = prefs?.getBoolean("relay", false) ?: false
        set(v) {
            prefs?.edit()?.putBoolean("relay", v)?.apply()
            if (!v) {
                main.removeCallbacks(endRunnable)
                active = false
                shown = null
            }
            statusListener?.invoke()
        }

    var extended: Boolean
        get() = prefs?.getBoolean("extended", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("extended", v)?.apply() }

    var originalDistance: Boolean
        get() = prefs?.getBoolean("origDist", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("origDist", v)?.apply() }

    /** Lowercase letters for turns under 200 m, like the official app. Off: the cluster was only
     *  ever tested with uppercase letters, and lowercase ones may show "start navigation". */
    var lowercaseNear: Boolean
        get() = prefs?.getBoolean("lowerNear", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("lowerNear", v)?.apply() }

    /** Road names are off by default: the cluster does not show them, and a name it dislikes
     *  (v0.5: one containing "/") made it fall back to "start navigation" for the rest of the trip. */
    var sendRoad: Boolean
        get() = prefs?.getBoolean("sendRoad", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("sendRoad", v)?.apply() }

    /** Show the real turn arrow only when the turn is closer than this (metres); farther away the
     *  straight arrow is shown. 0 = always show the turn arrow. */
    var arrowDistance: Int
        get() = prefs?.getInt("arrowDist2", 1000) ?: 1000
        set(v) { prefs?.edit()?.putInt("arrowDist2", v.coerceIn(0, 5000))?.apply() }

    /** Real distance on the straight arrow instead of "0". Untested on the bike: turn off if the
     *  cluster falls back to "start navigation" on straight roads. */
    var straightDistance: Boolean
        get() = prefs?.getBoolean("straightDist", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("straightDist", v)?.apply() }

    /** Use the other roundabout symbol (N instead of U) if the default direction looks wrong. */
    var roundaboutSwap: Boolean
        get() = prefs?.getBoolean("roundSwap", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("roundSwap", v)?.apply() }

    var gpsLost: Boolean
        get() = prefs?.getBoolean("gpsLost", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("gpsLost", v)?.apply() }

    /** Letters confirmed on the bike with the built-in letter tester. */
    private fun defaultLetter(m: Maneuver): Char = when (m) {
        Maneuver.STRAIGHT -> 'G'
        Maneuver.LEFT -> 'I'
        Maneuver.RIGHT -> 'J'
        Maneuver.SLIGHT_LEFT -> 'C'
        Maneuver.SLIGHT_RIGHT -> 'D'
        Maneuver.SHARP_LEFT -> 'E'
        Maneuver.SHARP_RIGHT -> 'F'
        Maneuver.UTURN_LEFT -> 'O'
        Maneuver.UTURN_RIGHT -> 'P'
        Maneuver.ROUNDABOUT -> if (roundaboutSwap) 'N' else 'U'   // U = roundabout left, N = right
        Maneuver.ROUNDABOUT_LEFT -> 'U'
        Maneuver.ROUNDABOUT_RIGHT -> 'N'
        Maneuver.KEEP_LEFT -> 'Z'
        Maneuver.KEEP_RIGHT -> 'X'
        Maneuver.MERGE -> 'V'
        Maneuver.EXIT_LEFT -> 'K'
        Maneuver.EXIT_RIGHT -> 'L'
        Maneuver.ARRIVE -> 'H'
        Maneuver.FERRY -> 'Y'
        Maneuver.START -> 'T'
        Maneuver.REROUTE -> 'B'
        Maneuver.UNKNOWN -> 'G'
    }

    fun letterFor(m: Maneuver): Char =
        prefs?.getString("map_" + m.name, null)?.firstOrNull() ?: defaultLetter(m)

    fun setLetter(m: Maneuver, c: Char) {
        prefs?.edit()?.putString("map_" + m.name, c.toString())?.apply()
    }

    // ------------------------------------------------------------------ status

    fun statusText(): String {
        val p = last
        return when {
            !enabled -> "Relay is off"
            p == null && !active -> "Waiting for a Google Maps navigation..."
            p == null -> "Navigation seen, nothing readable yet (see the dump)"
            p.distanceM < 0 -> "Navigation seen, waiting for the first distance..."
            else -> "Relaying: ${lastLetter} ${p.distanceM} m  ${p.maneuver}" +
                (if (p.road.isNotEmpty()) "  ${p.road}" else "") + "   ($updates updates)"
        }
    }

    // ------------------------------------------------------------------ icons
    // The turn arrow only exists as a picture in the notification, so it is recognised by a hash
    // of the picture and the user assigns each one a cluster letter once.

    fun seenIcons(): List<Triple<String, Bitmap, Int>> =
        seenIcons.entries.map { Triple(it.key, it.value, iconCounts[it.key] ?: 0) }.reversed()

    fun iconLetter(hash: String): Char? = prefs?.getString("icon_$hash", null)?.firstOrNull()

    fun setIconLetter(hash: String, c: Char?) {
        prefs?.edit()?.apply { if (c == null) remove("icon_$hash") else putString("icon_$hash", c.toString()) }?.apply()
    }

    /** What a given arrow picture means, learned from notifications whose text said it. */
    // Pictures seen in real Maps notifications; learned values override these.
    private val builtInIcons = mapOf(
        "92d9aa00" to Maneuver.STRAIGHT,
        "260bda8a" to Maneuver.LEFT,
        "d71f2297" to Maneuver.RIGHT,
        "614894b2" to Maneuver.UTURN_LEFT,
        "27264b40" to Maneuver.ARRIVE,
    )

    private fun kindForIcon(hash: String): Maneuver? =
        prefs?.getString("ik_$hash", null)?.let { n -> Maneuver.values().firstOrNull { it.name == n } }
            ?: builtInIcons[hash]

    private fun learnIcon(hash: String, m: Maneuver) {
        if (hash == "none" || hash == "n/a" || m == Maneuver.UNKNOWN || m == Maneuver.ARRIVE) return
        if (prefs?.getString("ik_$hash", null) != m.name) prefs?.edit()?.putString("ik_$hash", m.name)?.apply()
    }

    fun dumpText(): String = dumps.joinToString("\n\n")

    // ------------------------------------------------------------------ notification events

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PACKAGE) return
        val n = sbn.notification ?: return
        val texts = collectTexts(n)
        var parsed = parse(texts)
        var remote = emptyList<String>()
        if (parsed == null) {
            remote = remoteTexts(ctx, n)
            if (remote.isNotEmpty()) parsed = parse(texts + remote)
        }
        val icon = iconInfo(ctx, n)
        currentIcon = icon
        record(ctx, sbn, n, texts, remote, parsed, icon)

        if (enabled && parsed == null && last != null && texts.any { it.startsWith("Rerouting", ignoreCase = true) }) {
            push(last!!, forced = letterFor(Maneuver.REROUTE))
            statusListener?.invoke()
            return
        }
        if (!enabled || parsed == null) return
        main.removeCallbacks(endRunnable)
        active = true
        if (parsed.road.isNotEmpty()) lastRoad = parsed.road
        updates++
        ensureConnected(ctx)
        if (parsed.distanceM < 0) {
            // Maps leaves the distance out while standing still. Keep the last one; if there is
            // none yet, wait for the first real one instead of inventing a number.
            if (lastDistance < 0) {
                last = parsed
                statusListener?.invoke()
                return
            }
            parsed = parsed.copy(distanceM = lastDistance)
        }
        lastDistance = parsed.distanceM
        last = parsed
        push(parsed)
        statusListener?.invoke()
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PACKAGE || !enabled || !active) return
        // Maps removes and re-posts its notification now and then; only treat it as the end
        // of the trip if nothing comes back shortly.
        main.removeCallbacks(endRunnable)
        main.postDelayed(endRunnable, END_DELAY_MS)
    }

    private val endRunnable = Runnable {
        val p = last
        val arrived = p != null && (p.maneuver == Maneuver.ARRIVE || (p.remainingM in 0..150))
        Cluster.log("RELAY navigation ended (" + (if (arrived) "finished" else "cancelled") + ")")
        active = false
        shown = null
        last = null
        lastDistance = -1
        lastRoad = ""
        val ext = extended
        val pkt = if (arrived) NavPacket.finished(ext, p?.etaHour24 ?: -1, p?.etaMinute ?: 0) else NavPacket.cancelled(ext)
        Cluster.setPacket(pkt.toWire())
        Cluster.setSending(true)
        main.postDelayed({
            if (!active) {
                Cluster.setSending(false)
                if (autoConnected) main.postDelayed({ if (!active && autoConnected) autoDisconnect() }, AUTO_DISCONNECT_MS)
            }
        }, END_SHOW_MS)
        statusListener?.invoke()
    }

    private fun autoDisconnect() {
        autoConnected = false
        Cluster.log("RELAY disconnecting the cluster (trip over)")
        Cluster.disconnect() // the background service stays, ready for the next trip
    }

    private var appContext: Context? = null

    private fun ensureConnected(ctx: Context) {
        appContext = ctx.applicationContext
        if (Cluster.isActive) return
        val addr = prefs?.getString("addr", null)
        if (addr == null) {
            Cluster.log("RELAY no cluster chosen - open the app and choose it")
            return
        }
        Cluster.init(ctx)
        Cluster.log("RELAY navigation started - connecting to the cluster")
        try {
            ctx.startForegroundService(Intent(ctx, ClusterService::class.java))
        } catch (e: Exception) {
            Cluster.log("RELAY could not start the foreground service: ${e.javaClass.simpleName}")
        }
        Cluster.connect(addr)
        autoConnected = true
    }

    private const val ARRIVE_NEAR_M = 100

    private fun push(p: ParsedNav, forced: Char? = null) {
        var kind = p.maneuver
        if (kind != Maneuver.UNKNOWN) learnIcon(currentIcon, kind)
        else kindForIcon(currentIcon)?.let { kind = it }
        // Only a place name and no instruction (the last leg to the destination).
        // Arrive only if the picture says so; any other unknown picture stays straight.
        if (kind == Maneuver.UNKNOWN) kind = Maneuver.STRAIGHT
        // Far from the turn / the destination: straight on, with the distance counting down.
        if (kind == Maneuver.ARRIVE && p.distanceM > ARRIVE_NEAR_M) kind = Maneuver.STRAIGHT
        val far = arrowDistance > 0 && p.distanceM > arrowDistance
        if (far && kind != Maneuver.ARRIVE) kind = Maneuver.STRAIGHT

        val letter = forced ?: (if (kind == Maneuver.STRAIGHT || kind == Maneuver.ROUNDABOUT) null else iconLetter(currentIcon)) ?: letterFor(kind)
        lastLetter = letter
        val turn = if (lowercaseNear) NavFormat.turnLetter(letter, p.distanceM) else letter.uppercaseChar()
        val zero = turn == 'H' || (turn == 'G' && !straightDistance)
        val packet = NavPacket(
            turn = turn,
            distanceM = p.distanceM,
            etaHour24 = p.etaHour24,
            etaMinute = p.etaMinute,
            roundaboutExit = if (kind == Maneuver.ROUNDABOUT) p.roundaboutExit.coerceIn(0, 4) else 0,
            roadName = if (!sendRoad) "" else if (p.road.isNotEmpty()) p.road else lastRoad,
            remainingM = if (p.remainingM >= 0) p.remainingM else 0,
            extended = extended,
            gpsLost = gpsLost,
            originalDistanceStyle = originalDistance,
            zeroDistance = zero,
        )
        Cluster.setPacket(packet.toWire())
        Cluster.setSending(true)
        shown = Shown(turn.uppercaseChar(), kind, p.distanceM, p.etaHour24, p.etaMinute, p.remainingM,
            packet.roundaboutExit, forced != null)
        // Log what is sent whenever the arrow changes, so a wrong cluster screen can be traced.
        val key = "$turn $kind ${p.maneuver} $currentIcon ${forced != null}"
        if (key != lastLogged) {
            lastLogged = key
            Cluster.log("RELAY sending turn=$turn kind=$kind (text said ${p.maneuver}) icon=$currentIcon dist=${p.distanceM} body=${packet.body()}")
        }
    }

    private fun parse(texts: List<String>): ParsedNav? {
        val c = Calendar.getInstance()
        return MapsParser.parse(texts, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ------------------------------------------------------------------ reading the notification

    private fun collectTexts(n: Notification): List<String> {
        val e = n.extras ?: return emptyList()
        val out = ArrayList<String>()
        for (k in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_TITLE_BIG)) {
            e.getCharSequence(k)?.toString()?.let { out.add(it) }
        }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { out.add(it.toString()) }
        return out
    }

    private fun remoteTexts(ctx: Context, n: Notification): List<String> {
        val out = ArrayList<String>()
        for (rv in listOf(n.bigContentView, n.contentView, n.headsUpContentView)) {
            if (rv == null) continue
            try {
                val v = rv.apply(ctx.applicationContext, FrameLayout(ctx.applicationContext))
                walk(v, out)
            } catch (_: Throwable) {
            }
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun walk(v: View, out: MutableList<String>) {
        if (v is TextView) v.text?.toString()?.let { out.add(it) }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
    }

    private fun iconInfo(ctx: Context, n: Notification): String {
        return try {
            val d = n.getLargeIcon()?.loadDrawable(ctx) ?: return "none"
            val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 32, 32)
            d.draw(Canvas(bmp))
            val px = IntArray(1024)
            bmp.getPixels(px, 0, 32, 0, 0, 32, 32)
            val hash = "%08x".format(px.contentHashCode())
            iconCounts[hash] = (iconCounts[hash] ?: 0) + 1
            if (!seenIcons.containsKey(hash)) {
                val big = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96)
                d.draw(Canvas(big))
                seenIcons[hash] = big
                while (seenIcons.size > 16) seenIcons.remove(seenIcons.keys.first())
            }
            hash
        } catch (_: Throwable) {
            "n/a"
        }
    }

    private fun record(ctx: Context, sbn: StatusBarNotification, n: Notification, texts: List<String>,
                       remote: List<String>, parsed: ParsedNav?, icon: String) {
        val sb = StringBuilder()
        sb.append("id=").append(sbn.id).append(" ongoing=").append(sbn.isOngoing)
            .append(" category=").append(n.category).append(" icon=").append(icon).append('\n')
        sb.append("extras=").append(n.extras?.keySet()?.joinToString(",") ?: "").append(" template=")
            .append(n.extras?.getString(Notification.EXTRA_TEMPLATE)).append(" views(content/big/heads)=")
            .append(n.contentView != null).append('/').append(n.bigContentView != null).append('/')
            .append(n.headsUpContentView != null).append('\n')
        sb.append("texts=").append(texts.joinToString(" | ") { "\"$it\"" }).append('\n')
        if (remote.isNotEmpty()) sb.append("remoteViews=").append(remote.joinToString(" | ") { "\"$it\"" }).append('\n')
        n.actions?.let { a -> if (a.isNotEmpty()) sb.append("actions=").append(a.joinToString { it.title.toString() }).append('\n') }
        sb.append("parsed=").append(parsed ?: "NOT RECOGNISED")
        val body = sb.toString()
        if (body == lastDumpBody) return
        lastDumpBody = body
        dumps.addFirst(clock.format(Date()) + "  " + body)
        while (dumps.size > 200) dumps.removeLast()
    }
}
