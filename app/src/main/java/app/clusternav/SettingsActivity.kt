package app.clusternav

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cluster.init(this)
        Relay.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); fitsSystemWindows = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 20), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 20), Ui.dp(this@SettingsActivity, 28))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "Settings"; textSize = 22f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(this@SettingsActivity, 8), 0, Ui.dp(this@SettingsActivity, 8))
        })

        section("Navigation")
        row("Show turn arrow within", "The cluster shows straight until then", arrowText()) { askArrowDistance() }
        toggle("Distance on straight arrow", "Off shows 0 m on straight roads", Relay.straightDistance) { Relay.straightDistance = it }
        toggle("Other roundabout symbol", "Swap if the circling direction looks wrong", Relay.roundaboutSwap) { Relay.roundaboutSwap = it }

        section("Bike")
        row("Cluster device", Ui.deviceName(this) ?: "Not chosen", "›") { Ui.chooseDevice(this) { build() } }
        row("Arrow picture mapping", "Which cluster letter each Google Maps arrow uses", "›") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open", "mapping"))
        }

        section("Phone")
        val access = Ui.hasNotificationAccess(this)
        row("Notification access", "Lets the app read Google Maps", if (access) "On" else "Off ›", if (access) Ui.GREEN else Ui.AMBER) { Ui.openNotificationAccess(this) }
        val battery = Ui.batteryUnrestricted(this)
        row("Battery", "Unrestricted keeps the link alive", if (battery) "Unrestricted" else "Restricted ›", if (battery) Ui.GREEN else Ui.AMBER) { Ui.openBattery(this) }

        section("Advanced")
        row("Developer tools", "Letter tester, logs, Maps dump", "›") { startActivity(Intent(this, MainActivity::class.java)) }
        row("Version", "Easy Turn by Turn", Cluster.VERSION) {}
    }

    private fun arrowText(): String {
        val d = Relay.arrowDistance
        return when { d == 0 -> "Always"; d >= 1000 && d % 1000 == 0 -> "${d / 1000} km"; else -> "$d m" }
    }

    private fun section(text: String) {
        list.addView(TextView(this).apply {
            this.text = text.uppercase(); textSize = 11f; setTextColor(Ui.MUTED); letterSpacing = 0.12f
            setPadding(0, Ui.dp(this@SettingsActivity, 22), 0, Ui.dp(this@SettingsActivity, 4))
        })
    }

    private fun line(): View = View(this).apply { setBackgroundColor(Ui.LINE) }

    private fun row(title: String, subtitle: String, value: String, valueColor: Int = Ui.MUTED, click: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 14), 0, Ui.dp(this@SettingsActivity, 14))
            setOnClickListener { click() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(Ui.TEXT) })
        if (subtitle.isNotEmpty()) t.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(Ui.MUTED) })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(valueColor) })
        list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun toggle(title: String, subtitle: String, on: Boolean, changed: (Boolean) -> Unit) {
        val tv = ToggleView(this).apply { this.on = on }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 14), 0, Ui.dp(this@SettingsActivity, 14))
            setOnClickListener { tv.on = !tv.on; changed(tv.on) }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(Ui.TEXT) })
        t.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(Ui.MUTED) })
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tv)
        list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun askArrowDistance() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Relay.arrowDistance.toString())
            setSelection(text.length)
        }
        Ui.dialog(this).setTitle("Show turn arrow within (metres)")
            .setMessage("Farther away the cluster shows a straight arrow with the distance. 0 = always show the turn arrow.")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                Relay.arrowDistance = edit.text.toString().toIntOrNull() ?: 0
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
