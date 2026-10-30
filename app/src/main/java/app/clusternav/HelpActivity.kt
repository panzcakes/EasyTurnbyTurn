package app.clusternav

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Help, privacy and legal notes. Kept as plain text so it is easy to update. */
class HelpActivity : Activity() {

    private val sections: List<Pair<String, String>> = listOf(
        "What this app does" to
            "Easy Turn by Turn shows Google Maps turn-by-turn directions on the display of a Hero Xpulse 210 " +
            "(the Bluetooth cluster). It reads the navigation notification that Google Maps shows on your phone " +
            "and sends the next turn, distance and arrival time to the bike over Bluetooth.\n\n" +
            "It does only navigation: no calls, music or messages.\n\n" +
            "It is made for the Hero Xpulse 210 only and tested only on that bike. It is not intended for other bikes " +
            "or other Hero models, and may not work on them.",

        "Getting started" to
            "1. Pair the bike's cluster in your phone's Bluetooth settings (the same way the official Hero app needs).\n" +
            "2. Close the official Hero app completely (Force stop). The cluster talks to one phone at a time.\n" +
            "3. Open this app. Allow Bluetooth and notifications when asked.\n" +
            "4. Tap the hints above the button to choose your bike, allow notification access and set Battery to Unrestricted.\n" +
            "5. Tap Turn relay on.\n" +
            "6. Tap the circle to open Google Maps and start a route. The bike connects by itself and shows the turns.\n\n" +
            "You can lock the phone or close this screen. To stop, tap Turn relay off, or Stop in the notification.",

        "What the bike shows" to
            "The next turn and its distance are shown as soon as Google Maps reports them, so you only need to look " +
            "when the turn is close. Under 200 m the turn icon flashes. Roundabouts show the roundabout symbol and " +
            "exit number (1 to 4). When you arrive, the finish flag appears.\n\n" +
            "At the very start Google Maps says only \"Head ...\", so the bike shows a straight arrow until Maps reports " +
            "the first turn. You can change this in Settings (show the turn arrow only within a set distance, or turn " +
            "off the flashing).",

        "Troubleshooting" to
            "Waiting for the bike: switch the bike on. It connects by itself. If it never does, force stop the official " +
            "Hero app, switch Bluetooth off and on, and try again.\n\n" +
            "The bike says \"start navigation from phone\": the bike lost the data. Turn the relay off and on, or restart " +
            "the route in Google Maps.\n\n" +
            "\"Restricted setting\" when turning on notification access: on Android 13 and newer, open Settings > Apps > " +
            "Easy Turn by Turn, tap the three dots at the top right, tap Allow restricted settings, then try again.\n\n" +
            "Nothing happens when navigating: check Notification access in Settings. Google Maps must be set to English.\n\n" +
            "Battery: if the bike is not connected for 5 minutes (change it in Settings), the app closes its background " +
            "service and notification. It starts again by itself when Google Maps navigation begins. If it happens during " +
            "navigation, the relay turns off: switch it on again when the bike is ready.\n\n" +
            "It stops working after a while: set Battery to Unrestricted, allow auto-start if your phone has that option, and " +
            "lock the app in the recent apps list.\n\n" +
            "A wrong arrow: use Settings > Arrow picture mapping to pick the cluster letter for that Google Maps arrow.\n\n" +
            "To report a problem, open Developer tools, tap Copy for the log and Copy Maps dump, and send both.",

        "Installing and updating" to
            "This app is not in the Play Store, so Google Play Protect may warn that it is from an unknown developer. " +
            "That means it is not a known app, not that it is harmful. Tap More details > Install anyway.\n\n" +
            "\"App not installed\" usually means an older copy was signed with a different key. Uninstall the old copy once " +
            "(your settings are lost), then install again. Builds signed with the same key install over each other.",

        "Privacy" to
            "The app has no internet permission and sends nothing anywhere. It reads only the Google Maps navigation " +
            "notification, and only to show it on your bike. Logs and Maps dumps stay on your phone unless you choose " +
            "Copy or Share. A log can contain your bike's Bluetooth address and place names from your route, so look at it " +
            "before sharing.",

        "Safety" to
            "This is an experimental aid. Do not look at or use your phone while riding. Stay alert to the road and " +
            "traffic signs, and do not rely on the app as your only navigation. The turns may be late, wrong or missing.",

        "Not official" to
            "Easy Turn by Turn is an independent project. It is not made, endorsed or supported by Hero MotoCorp, " +
            "MapmyIndia (Mappls) or Google. Hero, Xpulse, Mappls and Google Maps are trademarks of their owners and " +
            "are used only to say what this app works with.\n\n" +
            "The Bluetooth protocol was worked out for interoperability. The app contains no code or images from the " +
            "official Hero app.",

        "Licence and warranty" to
            "Free software under the MIT licence. It is provided as is, without any warranty. You use it at your own " +
            "risk, including any effect on your bike, its warranty or your phone. The full licence text is in the project's " +
            "LICENSE file.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@HelpActivity, 20), Ui.dp(this@HelpActivity, 12), Ui.dp(this@HelpActivity, 20), Ui.dp(this@HelpActivity, 32))
        }
        col.addView(TextView(this).apply {
            text = "Help"; textSize = 22f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(this@HelpActivity, 8), 0, Ui.dp(this@HelpActivity, 4))
        })
        col.addView(TextView(this).apply { text = "Easy Turn by Turn v${Cluster.VERSION}"; textSize = 12f; setTextColor(Ui.MUTED) })
        for ((title, body) in sections) {
            col.addView(TextView(this).apply {
                text = title; textSize = 16f; setTextColor(Ui.ACCENT); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, Ui.dp(this@HelpActivity, 24), 0, Ui.dp(this@HelpActivity, 6))
            })
            col.addView(TextView(this).apply {
                text = body; textSize = 14f; setTextColor(Ui.TEXT); setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG); fitsSystemWindows = true; addView(col)
        })
    }
}
