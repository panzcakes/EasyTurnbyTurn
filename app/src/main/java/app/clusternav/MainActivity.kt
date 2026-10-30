package app.clusternav

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/**
 * Test screen: connect to the cluster, edit every field of the navigation packet, send it once or
 * continuously, and step through the turn letters to see which icon each one shows.
 * The UI is built in code so the project needs no layout files.
 */
@SuppressLint("MissingPermission", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cluster", MODE_PRIVATE) }

    private val letters = ('A'..'Z').map { it.toString() } + "~"

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var deviceButton: Button
    private lateinit var connectButton: Button
    private lateinit var turnSpinner: Spinner
    private lateinit var distanceEdit: EditText
    private lateinit var etaEdit: EditText
    private lateinit var roundaboutSpinner: Spinner
    private lateinit var roadEdit: EditText
    private lateinit var remainEdit: EditText
    private lateinit var extendedBox: CheckBox
    private lateinit var gpsBox: CheckBox
    private lateinit var distStyleBox: CheckBox
    private lateinit var previewView: TextView
    private lateinit var sendSwitch: Switch
    private lateinit var logView: TextView
    private lateinit var relayStatusView: TextView
    private lateinit var relaySwitch: Switch

    private var building = true

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cluster.init(this)
        Relay.init(this)
        setContentView(buildUi())
        building = false
        Relay.extended = extendedBox.isChecked
        Relay.originalDistance = distStyleBox.isChecked
        Relay.gpsLost = gpsBox.isChecked
        refreshPacket()
        renderRelay()
        if (Relay.enabled) {
            try {
                startForegroundService(Intent(this, ClusterService::class.java))
            } catch (_: Exception) {
            }
        }
        updateDeviceButton()
        requestMissingPermissions()
        title = "Developer tools"
        if (intent?.getStringExtra("open") == "mapping") showMapping()
    }

    override fun onStart() {
        super.onStart()
        Cluster.logListener = { renderLog() }
        Cluster.stateListener = { renderStatus() }
        Relay.statusListener = { renderRelay() }
        renderRelay()
        renderLog()
        renderStatus()
        ui.post(statsTick)
    }

    override fun onStop() {
        ui.removeCallbacks(statsTick)
        Cluster.logListener = null
        Cluster.stateListener = null
        Relay.statusListener = null
        super.onStop()
    }

    private val statsTick = object : Runnable {
        override fun run() {
            statsView.text = Cluster.stats()
            ui.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, dp(8), 0, 0)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun watch(edit: EditText) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshPacket()
        })
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshPacket()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        col.addView(TextView(this).apply {
            text = "Easy Turn by Turn v${Cluster.VERSION}"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Close the official Hero app first (force stop it) - the cluster talks to one phone at a time."
            textSize = 12f
        })

        // --- connection
        col.addView(section("Connection"))
        statusView = TextView(this).apply { textSize = 15f }
        col.addView(statusView)
        statsView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        col.addView(statsView)
        deviceButton = button("Choose cluster device") { chooseDevice() }
        connectButton = button("Connect") { toggleConnect() }
        col.addView(row(deviceButton, connectButton))
        col.addView(button("Battery settings (set this app to Unrestricted)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        // --- Google Maps relay
        col.addView(section("Google Maps relay"))
        col.addView(TextView(this).apply {
            text = "Start navigation in Google Maps as usual. This app reads Maps' navigation " +
                "notification and shows it on the cluster. It needs notification access."
            textSize = 12f
        })
        relayStatusView = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(2)) }
        col.addView(relayStatusView)
        col.addView(button("1. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        relaySwitch = Switch(this).apply {
            text = "2. Relay Google Maps to the cluster"
            isChecked = Relay.enabled
            setOnCheckedChangeListener { _, on ->
                if (building) return@setOnCheckedChangeListener
                Relay.enabled = on
                if (on) startForegroundService(Intent(this@MainActivity, ClusterService::class.java))
                else startService(Intent(this@MainActivity, ClusterService::class.java).setAction(ClusterService.ACTION_STOP))
                if (on) {
                    sendSwitch.isChecked = false
                    Cluster.setSending(false)
                    Cluster.log("Relay on - start navigation in Google Maps")
                }
                renderRelay()
            }
        }
        col.addView(relaySwitch)
        col.addView(CheckBox(this).apply {
            text = "Flash the turn icon under 200 m (lowercase letters)"
            isChecked = Relay.lowercaseNear
            setOnCheckedChangeListener { _, on -> if (!building) Relay.lowercaseNear = on }
        })
        col.addView(label("Show the turn arrow only when the turn is closer than (metres, 0 = always); farther away it shows straight"))
        col.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Relay.arrowDistance.toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!building) Relay.arrowDistance = s.toString().toIntOrNull() ?: 0
                }
            })
        })
        col.addView(CheckBox(this).apply {
            text = "Use the other roundabout symbol (swap if the circling direction looks wrong)"
            isChecked = Relay.roundaboutSwap
            setOnCheckedChangeListener { _, on -> if (!building) Relay.roundaboutSwap = on }
        })
        col.addView(CheckBox(this).apply {
            text = "Show the distance on the straight arrow (test; off = shows 0 m like before)"
            isChecked = Relay.straightDistance
            setOnCheckedChangeListener { _, on -> if (!building) Relay.straightDistance = on }
        })
        col.addView(CheckBox(this).apply {
            text = "Send road names (experimental; the cluster does not show them)"
            isChecked = Relay.sendRoad
            setOnCheckedChangeListener { _, on -> if (!building) Relay.sendRoad = on }
        })
        col.addView(row(
            button("Turn icon mapping") { showMapping() },
            button("Copy Maps dump") { copyDump() },
        ))

        // --- packet
        col.addView(section("Test tools - navigation packet"))
        col.addView(label("Turn letter (case follows distance: under ${NavFormat.NEAR_TURN_M} m = lowercase)"))
        turnSpinner = spinner(letters)
        turnSpinner.setSelection(4) // E
        col.addView(turnSpinner)
        col.addView(row(
            button("< Prev letter") { stepLetter(-1) },
            button("Next letter >") { stepLetter(1) },
        ))

        col.addView(label("Distance to next turn (metres)"))
        distanceEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("500")
        }
        watch(distanceEdit)
        col.addView(distanceEdit)

        col.addView(label("Arrival time (24-hour HH:mm)"))
        etaEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(etaIn(20))
        }
        watch(etaEdit)
        col.addView(etaEdit)

        col.addView(label("Roundabout exit (0 = none)"))
        roundaboutSpinner = spinner((0..9).map { it.toString() })
        col.addView(roundaboutSpinner)

        col.addView(label("Road name (shown by the extended packet, 18 characters)"))
        roadEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText("Test Road")
        }
        watch(roadEdit)
        col.addView(roadEdit)

        col.addView(label("Remaining trip distance (metres, extended packet)"))
        remainEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("12345")
        }
        watch(remainEdit)
        col.addView(remainEdit)

        extendedBox = CheckBox(this).apply {
            text = "Extended packet (road name + remaining distance)"
            isChecked = true
            setOnCheckedChangeListener { _, _ -> refreshPacket() }
        }
        gpsBox = CheckBox(this).apply {
            text = "GPS-lost flag"
            setOnCheckedChangeListener { _, _ -> refreshPacket() }
        }
        distStyleBox = CheckBox(this).apply {
            text = "Distance exactly like the official app (digits + trailing space, e.g. \"0500 \")"
            isChecked = true
            setOnCheckedChangeListener { _, _ -> refreshPacket() }
        }
        col.addView(extendedBox)
        col.addView(distStyleBox)
        col.addView(gpsBox)

        previewView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(previewView)

        // --- sending
        col.addView(section("Sending"))
        sendSwitch = Switch(this).apply {
            text = "Send continuously (every ${ClusterLink.SEND_INTERVAL_MS} ms)"
            setOnCheckedChangeListener { _, on -> if (!building) Cluster.setSending(on) }
        }
        col.addView(sendSwitch)
        col.addView(row(
            button("Send once") { Cluster.sendOnce(currentPacket().toWire()) },
            button("Cancelled (G)") { sendSpecial(NavPacket.cancelled(extendedBox.isChecked)) },
            button("Finished (H)") {
                val (h, m) = parseEta()
                sendSpecial(NavPacket.finished(extendedBox.isChecked, h, m))
            },
        ))

        // --- log
        col.addView(section("Log"))
        col.addView(row(
            button("Copy") { copyLog() },
            button("Share") { shareLog() },
            button("Clear") { Cluster.clearLog() },
        ))
        logView = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(logView)

        return ScrollView(this).apply { addView(col) }
    }

    // ------------------------------------------------------------------ packet

    private fun currentPacket(): NavPacket {
        val dist = distanceEdit.text.toString().toIntOrNull() ?: 0
        val base = letters[turnSpinner.selectedItemPosition][0]
        val turn = if (base.isLetter() && base != 'G' && base != 'H') NavFormat.turnLetter(base, dist) else base
        val (h, m) = parseEta()
        return NavPacket(
            turn = turn,
            distanceM = dist,
            etaHour24 = h,
            etaMinute = m,
            roundaboutExit = roundaboutSpinner.selectedItemPosition,
            roadName = roadEdit.text.toString(),
            remainingM = remainEdit.text.toString().toIntOrNull() ?: 0,
            extended = extendedBox.isChecked,
            gpsLost = gpsBox.isChecked,
            originalDistanceStyle = distStyleBox.isChecked,
        )
    }

    private fun refreshPacket() {
        if (building) return
        Relay.extended = extendedBox.isChecked
        Relay.originalDistance = distStyleBox.isChecked
        Relay.gpsLost = gpsBox.isChecked
        val p = currentPacket()
        val wire = p.toWire()
        if (!Relay.enabled) Cluster.setPacket(wire) // while relaying, Maps owns the packet
        previewView.text = "Packet (${wire.size} bytes, '~' and digits are literal):\n" + p.body()
    }

    private fun parseEta(): Pair<Int, Int> {
        val parts = etaEdit.text.toString().trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
        return if (h != null && m != null) h.coerceIn(0, 23) to m.coerceIn(0, 59) else 12 to 0
    }

    private fun etaIn(minutes: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.MINUTE, minutes)
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun stepLetter(delta: Int) {
        val next = (turnSpinner.selectedItemPosition + delta + letters.size) % letters.size
        turnSpinner.setSelection(next)
        refreshPacket()
        if (!sendSwitch.isChecked) sendSwitch.isChecked = true // the cluster expects a steady stream
        Cluster.log("Showing letter ${currentPacket().turn} - note what the cluster displays")
    }

    private fun sendSpecial(p: NavPacket) {
        sendSwitch.isChecked = false
        Cluster.sendOnce(p.toWire())
    }

    // ------------------------------------------------------------------ connection

    private fun toggleConnect() {
        if (Cluster.isActive) {
            // with the relay on, the background service stays so the next trip connects by itself
            if (!Relay.enabled) startService(Intent(this, ClusterService::class.java).setAction(ClusterService.ACTION_STOP))
            Cluster.disconnect()
            return
        }
        if (missingPermissions().isNotEmpty()) {
            requestMissingPermissions()
            return
        }
        val addr = prefs.getString("addr", null)
        if (addr == null) {
            chooseDevice()
            return
        }
        startForegroundService(Intent(this, ClusterService::class.java))
        Cluster.connect(addr)
        Cluster.setSending(sendSwitch.isChecked)
    }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun chooseDevice() {
        if (missingPermissions().isNotEmpty()) {
            requestMissingPermissions()
            return
        }
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            toast("Turn Bluetooth on first")
            return
        }
        val bonded = ad.bondedDevices.sortedBy { it.name ?: "" }
        val labels = bonded.map { "${it.name ?: "(no name)"}  ${it.address}" } + "Scan for nearby devices..."
        AlertDialog.Builder(this)
            .setTitle("Paired devices")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < bonded.size) {
                    saveDevice(bonded[which].address, bonded[which].name ?: bonded[which].address)
                } else {
                    scanForDevices(ad)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanForDevices(ad: BluetoothAdapter) {
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            toast("Bluetooth is off")
            return
        }
        val found = LinkedHashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (!name.isNullOrEmpty()) found[result.device.address] = name
            }
        }
        scanner.startScan(cb)
        val wait = AlertDialog.Builder(this)
            .setTitle("Scanning for 8 seconds...")
            .setMessage("Make sure the bike is on and the cluster is awake.")
            .setCancelable(false)
            .show()
        ui.postDelayed({
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
            if (isFinishing) return@postDelayed
            wait.dismiss()
            val addrs = found.keys.toList()
            val labels = addrs.map { "${found[it]}  $it" }
            if (addrs.isEmpty()) {
                toast("Nothing found")
                return@postDelayed
            }
            AlertDialog.Builder(this)
                .setTitle("Nearby devices")
                .setItems(labels.toTypedArray()) { _, which -> saveDevice(addrs[which], found[addrs[which]] ?: addrs[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }, 8000)
    }

    private fun saveDevice(address: String, name: String) {
        prefs.edit().putString("addr", address).putString("name", name).apply()
        updateDeviceButton()
        Cluster.log("Selected $name ($address)")
    }

    private fun updateDeviceButton() {
        val name = prefs.getString("name", null)
        deviceButton.text = if (name == null) "Choose cluster device" else "Cluster: $name"
    }

    private fun renderStatus() {
        val name = prefs.getString("name", null) ?: "no device chosen"
        statusView.text = "${Cluster.state}  -  $name"
        connectButton.text = if (Cluster.isActive) "Disconnect" else "Connect"
    }

    // ------------------------------------------------------------------ relay

    private fun renderRelay() {
        if (!::relayStatusView.isInitialized) return
        val access = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        relayStatusView.text = (if (access) "Notification access: granted\n" else "Notification access: NOT granted (step 1)\n") +
            Relay.statusText()
        if (relaySwitch.isChecked != Relay.enabled) relaySwitch.isChecked = Relay.enabled
    }

    private fun showMapping() {
        val kinds = Maneuver.values().toList()
        val labels = listOf("PICTURES: turn arrows seen from Google Maps (best)") +
            kinds.map { "${it.name}  ->  ${Relay.letterFor(it)}" }
        AlertDialog.Builder(this)
            .setTitle("Which cluster letter for each turn")
            .setItems(labels.toTypedArray()) { _, idx ->
                if (idx == 0) {
                    showIcons()
                    return@setItems
                }
                val which = idx - 1
                val kind = kinds[which]
                val opts = ('A'..'Z').map { it.toString() }
                AlertDialog.Builder(this)
                    .setTitle("Letter for ${kind.name}")
                    .setItems(opts.toTypedArray()) { _, w ->
                        Relay.setLetter(kind, opts[w][0])
                        toast("${kind.name} -> ${opts[w]}")
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showIcons() {
        val icons = Relay.seenIcons()
        if (icons.isEmpty()) {
            toast("No arrows seen yet - start navigation in Google Maps first")
            return
        }
        val list = android.widget.ListView(this)
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = icons.size
            override fun getItem(position: Int) = icons[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val (hash, bmp, count) = icons[position]
                val r = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                r.addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageBitmap(bmp)
                    setBackgroundColor(0xFF555555.toInt())
                }, LinearLayout.LayoutParams(dp(64), dp(64)))
                val letter = Relay.iconLetter(hash)
                r.addView(TextView(this@MainActivity).apply {
                    text = "  ${if (letter != null) "letter $letter" else "no letter yet"}   (seen $count x)"
                    textSize = 15f
                })
                return r
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tap an arrow, then pick its letter")
            .setView(list)
            .setNegativeButton("Close", null)
            .show()
        list.setOnItemClickListener { _, _, pos, _ ->
            val (hash, _, _) = icons[pos]
            val opts = ('A'..'Z').map { it.toString() }
            AlertDialog.Builder(this)
                .setTitle("Cluster letter for this arrow")
                .setItems(opts.toTypedArray()) { _, w ->
                    Relay.setIconLetter(hash, opts[w][0])
                    (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                }
                .setNeutralButton("Clear") { _, _ ->
                    Relay.setIconLetter(hash, null)
                    (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                }
                .show()
        }
    }

    private fun copyDump() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = Relay.dumpText().ifEmpty { "(no Google Maps notifications seen yet)" }
        cm.setPrimaryClip(ClipData.newPlainText("Maps dump", text))
        toast("Maps dump copied")
    }

    // ------------------------------------------------------------------ log

    private fun renderLog() {
        logView.text = Cluster.logText()
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Easy Turn by Turn log", Cluster.logText(400)))
        toast("Log copied")
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Cluster.logText(400))
        startActivity(Intent.createChooser(i, "Share log"))
    }

    // ------------------------------------------------------------------ permissions

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

    private fun missingPermissions(): List<String> =
        neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            Cluster.log("Permission denied: ${denied.joinToString { it.substringAfterLast('.') }} - Bluetooth will not work without it")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
