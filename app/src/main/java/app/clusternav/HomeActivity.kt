package app.clusternav

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The everyday screen: one round showcase, a status line and one button. */
class HomeActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var ring: RingView
    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var hint: TextView
    private lateinit var button: TextView
    private var hintAction: (() -> Unit)? = null
    private var hintBlocks = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cluster.init(this)
        Relay.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(build())
        if (Relay.enabled) {
            try {
                startForegroundService(Intent(this, ClusterService::class.java))
            } catch (_: Exception) {
            }
        }
        Ui.requestPermissions(this)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            setPadding(Ui.dp(this@HomeActivity, 20), Ui.dp(this@HomeActivity, 8), Ui.dp(this@HomeActivity, 20), Ui.dp(this@HomeActivity, 20))
        }
        // header
        val header = FrameLayout(this)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Easy Turn by Turn"; textSize = 22f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        titles.addView(TextView(this).apply { text = "Hero Xpulse 210"; textSize = 13f; setTextColor(Ui.MUTED) })
        header.addView(titles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        val burger = HamburgerView(this).apply { setOnClickListener { showMenu() } }
        header.addView(burger, FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER_VERTICAL or Gravity.END))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))

        // round showcase
        val size = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        ring = RingView(this)
        ring.setOnClickListener { openMaps() }
        root.addView(ring, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@HomeActivity, 28)
        })
        status = TextView(this).apply {
            textSize = 26f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@HomeActivity, 30) })
        sub = TextView(this).apply { textSize = 14f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER }
        root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@HomeActivity, 6) })

        // push the hint and button to the bottom
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        hint = TextView(this).apply {
            textSize = 13f; setTextColor(Ui.AMBER)
            background = Ui.rounded(this@HomeActivity, 0xFF241C10.toInt(), 14, 0xFF5A3D12.toInt())
            setPadding(Ui.dp(this@HomeActivity, 14), Ui.dp(this@HomeActivity, 11), Ui.dp(this@HomeActivity, 14), Ui.dp(this@HomeActivity, 11))
            visibility = View.GONE
            setOnClickListener { hintAction?.invoke() }
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(this@HomeActivity, 12) })
        button = TextView(this).apply {
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@HomeActivity, 18), 0, Ui.dp(this@HomeActivity, 18))
            setOnClickListener { onButton() }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        // what is missing, most important first
        hintAction = null; hintBlocks = false
        var hintText: String? = null
        when {
            Ui.missingPermissions(this).isNotEmpty() -> {
                hintText = "Bluetooth permission is needed. Tap to allow."
                hintAction = { Ui.requestPermissions(this) }; hintBlocks = true
            }
            Ui.deviceAddress(this) == null -> {
                hintText = "Choose your bike's cluster. Tap to pick it."
                hintAction = { Ui.chooseDevice(this) { refresh() } }; hintBlocks = true
            }
            !Ui.hasNotificationAccess(this) -> {
                hintText = "Notification access is off. Tap to turn it on for Easy Turn by Turn."
                hintAction = { Ui.openNotificationAccess(this) }; hintBlocks = true
            }
            !Ui.batteryUnrestricted(this) -> {
                hintText = "Battery restriction is on. Tap to set the app to Unrestricted."
                hintAction = { Ui.openBattery(this) }
            }
        }
        if (hintText == null) hint.visibility = View.GONE
        else { hint.visibility = View.VISIBLE; hint.text = hintText }

        val idleTap = Relay.enabled && Relay.shown == null
        if (ring.accent != idleTap) { ring.accent = idleTap; ring.invalidate() }
        val shown = Relay.shown
        val ready = Cluster.state == ClusterLink.State.READY
        when {
            !Relay.enabled -> {
                ring.update(Ui.GREY, false, null, 0, "", "", "Relay is off")
                status.text = "Relay off"; sub.text = "Not connected"
            }
            ready && shown != null -> {
                if (shown.rerouting) ring.update(Ui.GREEN, false, null, 0, "", "", "Rerouting...")
                else ring.update(Ui.GREEN, true, shown.kind, shown.exit, Ui.fmtDistance(shown.distanceM),
                    listOf(Ui.fmtEta(shown.etaHour24, shown.etaMinute).let { if (it.isEmpty()) "" else "ETA $it" },
                        if (shown.remainingM > 0) Ui.fmtDistance(shown.remainingM) else "").filter { it.isNotEmpty() }.joinToString("  ·  "), "")
                status.text = "Connected"
                sub.text = if (shown.rerouting) "Finding a new route" else "Next: ${Ui.describe(shown.kind)} in ${Ui.fmtDistance(shown.distanceM)}"
            }
            ready -> {
                ring.update(Ui.GREEN, false, null, 0, "", "", "Tap to open\nGoogle Maps")
                status.text = "Connected"; sub.text = "Bike cluster is ready"
            }
            Relay.tripActive -> {
                ring.update(Ui.AMBER, false, null, 0, "", "", "Looking for the bike...")
                status.text = "Waiting for the bike"; sub.text = "Switch the bike on - it connects by itself"
            }
            else -> {
                ring.update(Ui.GREY, false, null, 0, "", "", "Tap to open\nGoogle Maps")
                status.text = "Relay on"; sub.text = "The bike connects when navigation starts"
            }
        }
        if (Relay.enabled) {
            button.text = "Turn relay off"; button.setTextColor(Ui.TEXT)
            button.background = Ui.rounded(this, Ui.LINE, 18)
        } else {
            button.text = "Turn relay on"; button.setTextColor(0xFF06121F.toInt())
            button.background = Ui.rounded(this, Ui.ACCENT, 18)
        }
    }

    private fun onButton() {
        if (!Relay.enabled) {
            if (hintBlocks) { hintAction?.invoke(); return }
            Relay.enabled = true
            try { startForegroundService(Intent(this, ClusterService::class.java)) } catch (_: Exception) {}
            Cluster.setSending(false)
            Cluster.log("Relay on - start navigation in Google Maps")
        } else {
            Relay.enabled = false
            startService(Intent(this, ClusterService::class.java).setAction(ClusterService.ACTION_STOP))
        }
        refresh()
    }

    private fun openMaps() {
        if (!Relay.enabled) {
            Ui.toast(this, "Turn the relay on first")
            return
        }
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (intent == null) {
            Ui.toast(this, "Google Maps is not installed")
            return
        }
        startActivity(intent)
    }

    private fun showMenu() {
        val items = arrayOf("Settings", "Help", "Developer tools")
        Ui.dialog(this).setItems(items) { _, i ->
            when (i) {
                0 -> startActivity(Intent(this, SettingsActivity::class.java))
                1 -> startActivity(Intent(this, HelpActivity::class.java))
                else -> startActivity(Intent(this, MainActivity::class.java))
            }
        }.show()
    }
}
