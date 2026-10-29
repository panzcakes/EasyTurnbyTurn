package app.clusternav

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * BLE connection to the cluster.
 *
 * Reproduces the handshake of the official app (Telit "TIO" serial service with credits) but
 * fixes the things that make that app flaky:
 *  - every GATT operation goes through ONE queue and waits for its callback (Android only allows
 *    one operation at a time; overlapping writes fail silently),
 *  - every step has a timeout, nothing can silently stall,
 *  - failed steps and unexpected disconnects reconnect with back-off,
 *  - the link is forced to the LE transport and the GATT status code of every drop is logged.
 *
 * Everything runs on the main thread; Bluetooth callbacks are re-posted to it.
 */
@SuppressLint("MissingPermission")
class ClusterLink(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onStateChanged: (State) -> Unit,
) {
    enum class State { IDLE, WAITING_BLUETOOTH, BONDING, CONNECTING, DISCOVERING, NEGOTIATING, SETUP, READY, WAITING_RETRY }

    companion object {
        // Cluster's own service (what the XPulse cluster advertises in the official app's code)
        val SERVICE_CLUSTER: UUID = UUID.fromString("E837D9A2-9C49-4493-9547-8E6918A59CA8")
        val CLUSTER_RX: UUID = UUID.fromString("64AECB40-849A-44F1-934F-ADDC4B316423") // phone -> cluster data
        val CLUSTER_RX_CREDITS: UUID = UUID.fromString("F535DD6E-7975-4ABD-9719-491E38A81179") // phone -> cluster credits
        val CLUSTER_TX: UUID = UUID.fromString("B792A4BB-DB87-436A-9066-DB63C5FB3F00") // cluster -> phone data (notify)
        val CLUSTER_TX_CREDITS: UUID = UUID.fromString("36E8614B-2DED-45C1-9AD3-C9F59A01F21E") // cluster -> phone credits (indicate)

        // Plain Telit TIO layout, used as a fallback by the official app
        val SERVICE_TELIT: UUID = UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB")
        val TELIT_RX: UUID = UUID.fromString("00000001-0000-1000-8000-008025000000")
        val TELIT_TX: UUID = UUID.fromString("00000002-0000-1000-8000-008025000000")
        val TELIT_RX_CREDITS: UUID = UUID.fromString("00000003-0000-1000-8000-008025000000")
        val TELIT_TX_CREDITS: UUID = UUID.fromString("00000004-0000-1000-8000-008025000000")

        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WANT_MTU = 65 // same as the official app; the full packet is 50 bytes
        const val INITIAL_CREDITS = 10 // credits granted to the cluster after setup
        const val SEND_INTERVAL_MS = 800L // the official app re-sends every 800 ms
        private const val AUTO_WATCHDOG_MS = 120000L
        private const val DISCOVER_DELAY_MS = 1000L
        private const val MTU_TIMEOUT_MS = 4000L
        private const val OP_TIMEOUT_MS = 5000L
        private const val SETUP_TIMEOUT_MS = 25000L
        private const val BOND_TIMEOUT_MS = 40000L
        private const val NAV_FAILS_BEFORE_RECONNECT = 3

        fun statusName(status: Int): String = when (status) {
            0 -> "SUCCESS"
            5 -> "INSUFFICIENT_AUTHENTICATION"
            8 -> "CONNECTION_TIMEOUT (link lost / out of range)"
            15 -> "INSUFFICIENT_ENCRYPTION"
            19 -> "REMOTE_USER_TERMINATED (cluster hung up)"
            22 -> "LOCAL_HOST_TERMINATED"
            34 -> "LMP_RESPONSE_TIMEOUT"
            62 -> "CONNECTION_FAILED_TO_ESTABLISH"
            133 -> "GATT_ERROR (generic, often a stale/busy connection)"
            257 -> "GATT_FAILURE"
            else -> "status $status"
        }
    }

    private class Chars(
        val service: BluetoothGattService,
        val rx: BluetoothGattCharacteristic,
        val rxCredits: BluetoothGattCharacteristic,
        val tx: BluetoothGattCharacteristic,
        val txCredits: BluetoothGattCharacteristic,
    )

    private sealed class Op {
        class Notify(val char: BluetoothGattCharacteristic, val indicate: Boolean, val label: String) : Op()
        class Write(val char: BluetoothGattCharacteristic, val value: ByteArray, val label: String, val isNav: Boolean) : Op()
        object MarkReady : Op()
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    var state: State = State.IDLE
        private set
    var mtu: Int = 23
        private set

    // counters shown in the UI
    var navSent = 0
        private set
    var navOk = 0
        private set
    var navFailed = 0
        private set
    var navSkipped = 0
        private set
    var rxPackets = 0
        private set

    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var chars: Chars? = null
    private var wantConnected = false
    private var attempt = 0
    private var receiversRegistered = false
    private var autoMode = false
    private var directNext = false
    private var scanCb: ScanCallback? = null

    private val queue = java.util.ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var inFlightSince = 0L
    private var navFailStreak = 0
    private var creditAcks = 0

    @Volatile
    private var latest: ByteArray? = null
    private var sending = false
    private var warnedSize = false

    // ---------------------------------------------------------------- public API

    fun start(dev: BluetoothDevice) {
        wantConnected = true
        device = dev
        attempt = 0
        warnedSize = false
        registerReceivers()
        main.removeCallbacks(sendTick)
        main.postDelayed(sendTick, SEND_INTERVAL_MS)
        log("Starting link to ${dev.name ?: "?"} (${dev.address}), bond state ${bondName(dev.bondState)}")
        beginConnect()
    }

    fun stop() {
        wantConnected = false
        cancelTimers()
        main.removeCallbacks(retry)
        main.removeCallbacks(sendTick)
        cleanupGatt()
        unregisterReceivers()
        setState(State.IDLE)
        log("Link stopped")
    }

    fun setPacket(bytes: ByteArray) {
        latest = bytes
    }

    fun setSending(on: Boolean) {
        sending = on
        log(if (on) "Continuous sending ON (every ${SEND_INTERVAL_MS} ms)" else "Continuous sending OFF")
        if (on && state == State.READY) trySendNav()
    }

    /** Send the given bytes once, right away (if the link is ready). */
    fun sendOnce(bytes: ByteArray) {
        if (state != State.READY) {
            log("Not connected yet - cannot send")
            return
        }
        latest = bytes
        enqueueNav(bytes, force = true)
    }

    fun statsLine(): String = "sent $navSent  ok $navOk  failed $navFailed  skipped $navSkipped  from-cluster $rxPackets  mtu $mtu"

    // ---------------------------------------------------------------- connecting

    private fun beginConnect() {
        val dev = device ?: return
        if (!wantConnected) return
        cancelTimers()
        cleanupGatt()
        mtu = 23 // a new connection starts at the default MTU
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            setState(State.WAITING_BLUETOOTH)
            log("Bluetooth is off - will continue when it is turned on")
            return
        }
        when (dev.bondState) {
            BluetoothDevice.BOND_NONE -> {
                setState(State.BONDING)
                log("Cluster is not paired - asking Android to pair (accept the pairing prompt if one appears)")
                if (!dev.createBond()) {
                    scheduleRetry("createBond() was refused")
                    return
                }
                main.removeCallbacks(bondTimeout)
                main.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
                return
            }
            BluetoothDevice.BOND_BONDING -> {
                setState(State.BONDING)
                log("Pairing in progress - waiting")
                main.removeCallbacks(bondTimeout)
                main.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
                return
            }
        }
        setState(State.CONNECTING)
        // First try: direct connect. After any drop: "auto connect", where Android keeps waiting in
        // the background until the cluster shows up again (bike switched back on) instead of giving
        // up after about 30 s, which is what made reconnecting unreliable.
        autoMode = attempt >= 1 && !directNext
        directNext = false
        log("connectGatt (LE transport${if (autoMode) ", waiting for the cluster to appear" else ""}), attempt ${attempt + 1}")
        gatt = dev.connectGatt(ctx, autoMode, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            scheduleRetry("connectGatt returned null")
            return
        }
        main.removeCallbacks(setupTimeout)
        main.removeCallbacks(connectWatchdog)
        if (autoMode) {
            main.postDelayed(connectWatchdog, AUTO_WATCHDOG_MS)
            startWatchScan()
        } else {
            main.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
        }
    }

    // While waiting for the cluster (bike off), also listen for its advertising. As soon as it is
    // heard, connect directly: quicker and more reliable on some phones than Android's background
    // "auto connect" alone.
    private fun startWatchScan() {
        stopWatchScan()
        val dev = device ?: return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if (scanCb === this && wantConnected && state == State.CONNECTING) {
                        stopWatchScan()
                        log("Cluster is advertising - connecting now")
                        directNext = true
                        beginConnect()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { log("Watch scan failed ($errorCode) - relying on auto connect") }
            }
        }
        try {
            val filter = ScanFilter.Builder().setDeviceAddress(dev.address).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            scanner.startScan(listOf(filter), settings, cb)
            scanCb = cb
        } catch (e: Exception) {
            log("Could not start the watch scan: ${e.javaClass.simpleName} - relying on auto connect")
        }
    }

    private fun stopWatchScan() {
        val cb = scanCb ?: return
        scanCb = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }

    private fun scheduleRetry(reason: String) {
        cancelTimers()
        cleanupGatt()
        if (!wantConnected) return
        attempt++
        val delay = minOf(1000L shl minOf(attempt - 1, 4), 15000L) // 1, 2, 4, 8, 15, 15 ... s
        setState(State.WAITING_RETRY)
        log("$reason - retry #$attempt in ${delay / 1000.0} s")
        if (attempt == 3 || attempt == 8) {
            log("HINT: still failing. Make sure the official Hero app is fully closed (it also tries to grab the cluster), " +
                "then, if it keeps failing, forget the cluster in Android's Bluetooth settings and connect again to re-pair.")
        }
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    private val retry = Runnable { beginConnect() }

    private fun pairingInProgress(): Boolean = device?.bondState == BluetoothDevice.BOND_BONDING

    private val setupTimeout = object : Runnable {
        override fun run() {
            if (state == State.READY || !wantConnected) return
            if (pairingInProgress()) {
                log("Waiting for the pairing prompt to be answered")
                main.postDelayed(this, SETUP_TIMEOUT_MS)
                return
            }
            scheduleRetry("Setup did not finish within ${SETUP_TIMEOUT_MS / 1000} s (state $state)")
        }
    }

    // Safety net for auto connect: if nothing happened for a long time, start over from scratch.
    private val connectWatchdog = Runnable {
        if (state == State.CONNECTING && wantConnected) scheduleRetry("Still waiting for the cluster (is the bike on?)")
    }

    private val bondTimeout = Runnable {
        if (state == State.BONDING && wantConnected) scheduleRetry("Pairing timed out")
    }

    private val opTimeout = object : Runnable {
        override fun run() {
            val op = inFlight ?: return
            if (!wantConnected) return
            if (pairingInProgress()) {
                log("Waiting for pairing before: ${describe(op)}")
                main.postDelayed(this, OP_TIMEOUT_MS)
                return
            }
            scheduleRetry("GATT operation timed out: ${describe(op)}")
        }
    }

    private val mtuTimeout = Runnable {
        if (state == State.NEGOTIATING) {
            log("No MTU answer from the cluster - continuing with MTU $mtu")
            startSetup()
        }
    }

    private fun cancelTimers() {
        stopWatchScan()
        main.removeCallbacks(setupTimeout)
        main.removeCallbacks(connectWatchdog)
        main.removeCallbacks(bondTimeout)
        main.removeCallbacks(opTimeout)
        main.removeCallbacks(mtuTimeout)
    }

    private fun cleanupGatt() {
        queue.clear()
        inFlight = null
        chars = null
        val g = gatt
        gatt = null
        if (g != null) {
            // Ask for a clean disconnect first and only close the handle a moment later; closing
            // immediately can leave the link half-open on some phones. Callbacks from this old
            // handle are ignored because it is no longer `gatt`.
            try {
                g.disconnect()
            } catch (_: Exception) {
            }
            main.postDelayed({
                try {
                    g.close()
                } catch (_: Exception) {
                }
            }, 800)
        }
    }

    // ---------------------------------------------------------------- GATT callbacks (re-posted to main)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post { onConnection(g, status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { onServices(g, status) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { onMtu(g, mtu, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { onOpDone(g, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post { onOpDone(g, status) }
        }

        @Deprecated("Deprecated in API 33, still delivered on every Android version")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            val uuid = characteristic.uuid
            main.post { onIncoming(g, uuid, value) }
        }
    }

    private fun onConnection(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    scheduleRetry("Connected with error ${statusName(status)}")
                    return
                }
                log("Connected. Discovering services in ${DISCOVER_DELAY_MS} ms")
                setState(State.DISCOVERING)
                // the setup clock starts now, not when we began waiting
                main.removeCallbacks(connectWatchdog)
                main.removeCallbacks(setupTimeout)
                main.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
                main.postDelayed({
                    if (g === gatt && state == State.DISCOVERING && !g.discoverServices()) {
                        scheduleRetry("discoverServices() was refused")
                    }
                }, DISCOVER_DELAY_MS)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                val wasReady = state == State.READY
                scheduleRetry("Disconnected, ${statusName(status)}${if (wasReady) " (was working)" else " (during setup, state $state)"}")
            }
        }
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            scheduleRetry("Service discovery failed, ${statusName(status)}")
            return
        }
        for (s in g.services) log("  service ${s.uuid}")
        val found = findChars(g)
        if (found == null) {
            // Almost certainly the wrong device was picked: do not loop forever.
            log("This device does not have the cluster service (E837D9A2-... or 0000FEFB-...). Wrong device selected?")
            wantConnected = false
            cancelTimers()
            cleanupGatt()
            setState(State.IDLE)
            return
        }
        chars = found
        log("Using service ${found.service.uuid}")
        log("  data-in props=0x${found.rx.properties.toString(16)}  credits-in=0x${found.rxCredits.properties.toString(16)}" +
            "  data-out=0x${found.tx.properties.toString(16)}  credits-out=0x${found.txCredits.properties.toString(16)}")
        setState(State.NEGOTIATING)
        if (g.requestMtu(WANT_MTU)) {
            main.removeCallbacks(mtuTimeout)
            main.postDelayed(mtuTimeout, MTU_TIMEOUT_MS)
        } else {
            log("requestMtu() was refused - continuing with MTU $mtu")
            startSetup()
        }
    }

    private fun findChars(g: BluetoothGatt): Chars? {
        g.getService(SERVICE_CLUSTER)?.let { s ->
            val rx = s.getCharacteristic(CLUSTER_RX)
            val rc = s.getCharacteristic(CLUSTER_RX_CREDITS)
            val tx = s.getCharacteristic(CLUSTER_TX)
            val tc = s.getCharacteristic(CLUSTER_TX_CREDITS)
            if (rx != null && rc != null && tx != null && tc != null) return Chars(s, rx, rc, tx, tc)
            log("Cluster service found but a characteristic is missing")
        }
        g.getService(SERVICE_TELIT)?.let { s ->
            val rx = s.getCharacteristic(TELIT_RX)
            val rc = s.getCharacteristic(TELIT_RX_CREDITS)
            val tx = s.getCharacteristic(TELIT_TX)
            val tc = s.getCharacteristic(TELIT_TX_CREDITS)
            if (rx != null && rc != null && tx != null && tc != null) return Chars(s, rx, rc, tx, tc)
            log("Telit service found but a characteristic is missing")
        }
        return null
    }

    private fun onMtu(g: BluetoothGatt, newMtu: Int, status: Int) {
        if (g !== gatt || state != State.NEGOTIATING) return
        main.removeCallbacks(mtuTimeout)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mtu = newMtu
            log("MTU is $mtu (payload up to ${mtu - 3} bytes)")
        } else {
            log("MTU request failed (${statusName(status)}) - continuing with MTU $mtu")
        }
        startSetup()
    }

    // ---------------------------------------------------------------- setup sequence + operation queue

    /** Credits handshake of the Telit serial service, in the order the official app does it. */
    private fun startSetup() {
        val c = chars ?: return
        setState(State.SETUP)
        enqueue(Op.Notify(c.txCredits, indicate = true, label = "indications on cluster credits"))
        enqueue(Op.Notify(c.tx, indicate = false, label = "notifications on cluster data"))
        enqueue(Op.Write(c.rxCredits, byteArrayOf(INITIAL_CREDITS.toByte()), "grant $INITIAL_CREDITS credits", isNav = false))
        enqueue(Op.MarkReady)
    }

    private fun enqueue(op: Op) {
        queue.add(op)
        pump()
    }

    private fun pump() {
        if (inFlight != null) return
        val g = gatt ?: return
        while (true) {
            if (inFlight != null) return // onReady() may already have started a nav write
            val op = queue.poll() ?: return
            when (op) {
                is Op.MarkReady -> onReady()
                is Op.Notify -> {
                    g.setCharacteristicNotification(op.char, true)
                    val d = op.char.getDescriptor(CCCD)
                    if (d == null) {
                        scheduleRetry("No notification descriptor for ${describe(op)}")
                        return
                    }
                    d.value = if (op.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(d)) {
                        scheduleRetry("writeDescriptor refused: ${describe(op)}")
                        return
                    }
                    startOp(op)
                    return
                }
                is Op.Write -> {
                    op.char.value = op.value
                    if (!g.writeCharacteristic(op.char)) {
                        if (op.isNav) {
                            navFailed++
                            navFailStreak++
                            log("Nav write refused by Android (busy?) streak=$navFailStreak")
                            if (navFailStreak >= NAV_FAILS_BEFORE_RECONNECT) {
                                scheduleRetry("Nav writes keep being refused")
                                return
                            }
                            continue
                        }
                        if (state == State.READY) {
                            log("${describe(op)} refused by Android (ignored, link still up)")
                            continue
                        }
                        scheduleRetry("writeCharacteristic refused: ${describe(op)}")
                        return
                    }
                    if (op.isNav) navSent++
                    startOp(op)
                    return
                }
            }
        }
    }

    private fun startOp(op: Op) {
        inFlight = op
        inFlightSince = SystemClock.elapsedRealtime()
        main.removeCallbacks(opTimeout)
        main.postDelayed(opTimeout, OP_TIMEOUT_MS)
    }

    private fun onOpDone(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        val op = inFlight ?: return
        inFlight = null
        main.removeCallbacks(opTimeout)
        val tookMs = SystemClock.elapsedRealtime() - inFlightSince
        if (op is Op.Write && op.isNav) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                navOk++
                navFailStreak = 0
                if (navOk == 1 || navOk % 50 == 0) log("Nav write #$navOk ok (${tookMs} ms)")
            } else {
                navFailed++
                navFailStreak++
                log("Nav write failed: ${statusName(status)} streak=$navFailStreak")
                if (navFailStreak >= NAV_FAILS_BEFORE_RECONNECT) {
                    scheduleRetry("Nav writes keep failing")
                    return
                }
            }
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            if (state == State.READY) {
                log("${describe(op)} failed: ${statusName(status)} (ignored, link still up)")
            } else {
                scheduleRetry("Setup step failed (${describe(op)}): ${statusName(status)}")
                return
            }
        } else {
            if (op is Op.Write && op.label == "return 1 credit") {
                creditAcks++
                if (creditAcks <= 3) log("Setup: ${describe(op)} ok (${tookMs} ms) - further ones are not logged")
            } else {
                log("Setup: ${describe(op)} ok (${tookMs} ms)")
            }
        }
        pump()
    }

    private fun onReady() {
        cancelTimers()
        attempt = 0
        navFailStreak = 0
        setState(State.READY)
        log("READY - cluster link is up")
        // The first packet goes out on the next tick (up to 800 ms), which gives the cluster a
        // moment to settle after the credits handshake.
    }

    // ---------------------------------------------------------------- data from the cluster

    private fun onIncoming(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (g !== gatt) return
        val c = chars ?: return
        when (uuid) {
            c.txCredits.uuid -> {
                val n = if (value.isNotEmpty()) value[0].toInt() and 0xFF else 0
                log("Cluster credits: +$n")
            }
            c.tx.uuid -> {
                rxPackets++
                if (rxPackets <= 3 || hex(value) != "0A 41 00 32 65 0A") {
                    log("From cluster: ${hex(value)} \"${printable(value)}\"" + if (rxPackets <= 3) " (the cluster's regular heartbeat is not logged again)" else "")
                }
                // Give the credit back so the cluster can keep sending its button events.
                if (queue.size < 20) enqueue(Op.Write(c.rxCredits, byteArrayOf(1), "return 1 credit", isNav = false))
            }
        }
    }

    // ---------------------------------------------------------------- sending navigation data

    private val sendTick = object : Runnable {
        override fun run() {
            if (sending && state == State.READY) trySendNav()
            main.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    private fun trySendNav() {
        val data = latest ?: return
        enqueueNav(data, force = false)
    }

    private fun enqueueNav(data: ByteArray, force: Boolean) {
        val c = chars ?: return
        // Never pile up stale packets: if the previous write is still pending, skip this tick.
        if (!force && (inFlight != null || queue.isNotEmpty())) {
            navSkipped++
            return
        }
        if (!warnedSize && data.size > mtu - 3) {
            warnedSize = true
            log("WARNING: packet is ${data.size} bytes but only ${mtu - 3} fit in one write (MTU $mtu). " +
                "Switch to the basic packet or retry the connection.")
        }
        enqueue(Op.Write(c.rx, data, "nav packet", isNav = true))
    }

    // ---------------------------------------------------------------- bonding / Bluetooth state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d == null || d.address != device?.address) return
                    val s = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    log("Pairing state: ${bondName(s)}")
                    if (state == State.BONDING && wantConnected) {
                        when (s) {
                            BluetoothDevice.BOND_BONDED -> {
                                main.removeCallbacks(bondTimeout)
                                main.postDelayed({ if (wantConnected && state == State.BONDING) beginConnect() }, 1000)
                            }
                            BluetoothDevice.BOND_NONE -> scheduleRetry("Pairing failed or was declined")
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (s == BluetoothAdapter.STATE_ON && wantConnected && state == State.WAITING_BLUETOOTH) {
                        log("Bluetooth is back on")
                        beginConnect()
                    } else if (s == BluetoothAdapter.STATE_TURNING_OFF && wantConnected) {
                        log("Bluetooth turning off")
                        cancelTimers()
                        cleanupGatt()
                        setState(State.WAITING_BLUETOOTH)
                    }
                }
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val f = IntentFilter()
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        ctx.registerReceiver(receiver, f)
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        receiversRegistered = false
    }

    // ---------------------------------------------------------------- helpers

    private fun setState(s: State) {
        if (state == s) return
        state = s
        onStateChanged(s)
    }

    private fun describe(op: Op): String = when (op) {
        is Op.Notify -> op.label
        is Op.Write -> op.label
        Op.MarkReady -> "ready"
    }

    private fun bondName(s: Int) = when (s) {
        BluetoothDevice.BOND_NONE -> "not paired"
        BluetoothDevice.BOND_BONDING -> "pairing"
        BluetoothDevice.BOND_BONDED -> "paired"
        else -> "state $s"
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private fun printable(b: ByteArray) = String(CharArray(b.size) { i ->
        val c = b[i].toInt() and 0xFF
        if (c in 0x20..0x7E) c.toChar() else '.'
    })
}
