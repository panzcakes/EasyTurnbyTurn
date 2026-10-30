# Easy Turn by Turn

Google Maps turn-by-turn directions on the display of a **Hero Xpulse 210**.

> **Only for the Hero Xpulse 210.** Made for and tested only with that bike's cluster. It is not intended for
> other bikes or other Hero models, and may not work or may behave unexpectedly on them.

The app reads the navigation notification that Google Maps shows on your Android phone and sends the next
turn, distance and arrival time to the bike's cluster over Bluetooth. It does only navigation: no calls,
music or messages. It runs in the background, reconnects by itself when you switch the bike on, and
disconnects when the trip ends.

> **Unofficial.** Not made, endorsed or supported by Hero MotoCorp, MapmyIndia (Mappls) or Google.
> Hero, Xpulse, Mappls and Google Maps are trademarks of their owners and are named here only to say what
> the app works with. The app contains no code or images from the official Hero app.

> **Safety.** This is an experimental aid. Do not look at or use your phone while riding. Turns may be late,
> wrong or missing, so do not rely on it as your only navigation. You use it at your own risk, including any
> effect on your bike, its warranty or your phone.

## What it does

* Shows the next turn and its distance as soon as Google Maps reports it, and flashes the icon under 200 m.
  (Settings can instead show a straight arrow until the turn is within a set distance, or stop the flashing.)
* Turn, slight, sharp, U-turn, keep left/right, exit, roundabout (with exit number 1-4), arrival flag, rerouting.
* Starts and stops with your Google Maps navigation; no tapping on the bike.
* Dark, simple home screen: one round status view and one button.

Works with English Google Maps and the Hero Xpulse 210 only. Tested on one bike and one phone; other phones may need small fixes.

## Install

1. Download the latest APK (`EasyTurnByTurn-v1.2.apk`) from **Releases** (the "Latest build" entry). The release text comes from `RELEASE_NOTES.md`; edit that file to change it.
2. Open it on your phone and allow installing from your browser or file manager.
3. **Google Play Protect warning.** The app is not in the Play Store, so Play Protect may show *"Unknown
   developer"*, *"App not scanned"* or *"Blocked by Play Protect"*. That means Google does not know the app,
   not that it is harmful: the code is open here and the app has no internet permission. Tap
   **More details > Install anyway**. If there is only an OK button, open Play Store > profile > Play Protect >
   settings and turn off **Scan apps with Play Protect** while you install; you can turn it back on afterwards.
4. "App not installed" usually means an older copy was signed with a different key. Uninstall the old copy
   once, then install again.

## Set up and use

1. Pair the bike's cluster in your phone's Bluetooth settings.
2. Fully close the official Hero app (Force stop); the cluster talks to one phone at a time.
3. Open the app and follow the hints above the button: allow Bluetooth and notifications, choose the bike,
   allow notification access, set Battery to **Unrestricted**.
   - **"Restricted setting" message?** On Android 13 and newer, apps installed outside the Play Store cannot
     get notification access until you allow it once: open **Settings > Apps > Easy Turn by Turn**, tap the
     three dots at the top right, tap **Allow restricted settings**, then go back and turn notification access
     on. This is an Android security feature and cannot be skipped by the app.
4. Tap **Turn relay on**.
5. Tap the circle to open Google Maps and start a route. The bike connects by itself.

The menu at the top right has **Settings**, **Help** and **Developer tools** (letter tester, log, Maps dump).

## Troubleshooting

| Problem | What to try |
|---|---|
| Stays on "Waiting for the bike" | Switch the bike on; force stop the official Hero app; switch Bluetooth off and on |
| Bike shows "start navigation from phone" | Turn the relay off and on, or restart the route |
| Notification access is greyed out or says "Restricted setting" | Settings > Apps > Easy Turn by Turn > three dots > Allow restricted settings |
| Nothing happens during navigation | Check notification access; Google Maps must be in English |
| Notification disappeared | The bike was not connected for 5 minutes (adjustable in Settings), so the app stopped to save battery. It starts again when Google Maps navigation begins; if it happened during navigation, switch the relay on again |
| Stops after a while | Battery > Unrestricted, allow auto-start, lock the app in recent apps |
| A wrong arrow | Settings > Arrow picture mapping |

To report a problem, open **Developer tools** and use **Copy** on the log and **Copy Maps dump**.
A log can contain the bike's Bluetooth address and place names from your route; check it before posting.

## Privacy

No internet permission; nothing is sent anywhere. The app reads only the Google Maps navigation
notification, to show it on the bike. Logs stay on the phone unless you copy or share them.

## Protocol

How the cluster talks Bluetooth, and what each letter shows: [docs/PROTOCOL.md](docs/PROTOCOL.md).
The encoder and parser have a self-test: see the top of `tools/selftest/SelfTest.kt`.

## Licence

MIT, see [LICENSE](LICENSE). Provided as is, without warranty.
