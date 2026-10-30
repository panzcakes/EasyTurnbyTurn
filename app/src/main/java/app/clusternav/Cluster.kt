package app.clusternav

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide owner of the cluster link, the current packet and the on-screen log.
 * The foreground service only keeps the process alive; the link itself lives here so the
 * screen can come and go without dropping the connection.
 */
object Cluster {
    const val VERSION = "1.2"
    private const val MAX_LOG_LINES = 1500

    private val main = Handler(Looper.getMainLooper())
    private val lines = java.util.ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var app: Context? = null
    private var link: ClusterLink? = null

    private var packet: ByteArray? = null
    private var sending = false

    /** Called on the main thread whenever a log line is added. */
    var logListener: (() -> Unit)? = null

    /** Called on the main thread whenever the link state changes. */
    var stateListener: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    val state: ClusterLink.State
        get() = link?.state ?: ClusterLink.State.IDLE

    val isActive: Boolean
        get() = state != ClusterLink.State.IDLE

    fun stats(): String = link?.statsLine() ?: "not connected"

    fun log(msg: String) {
        val line = clock.format(Date()) + "  " + msg
        main.post {
            lines.addLast(line)
            while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            logListener?.invoke()
        }
    }

    fun logText(lastLines: Int = 250): String {
        val all = lines.toList()
        return all.takeLast(lastLines).joinToString("\n")
    }

    fun clearLog() {
        lines.clear()
        logListener?.invoke()
    }

    fun connect(address: String) {
        val ctx = app ?: return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            log("This phone has no Bluetooth")
            return
        }
        link?.stop()
        log("Easy Turn by Turn v$VERSION")
        val dev = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            log("Bad device address: $address")
            return
        }
        val l = ClusterLink(ctx, ::log) { stateListener?.invoke() }
        link = l
        packet?.let { l.setPacket(it) }
        l.setSending(sending)
        l.start(dev)
        stateListener?.invoke()
    }

    fun disconnect() {
        link?.stop()
        link = null
        stateListener?.invoke()
    }

    fun setPacket(bytes: ByteArray) {
        packet = bytes
        link?.setPacket(bytes)
    }

    fun setSending(on: Boolean) {
        if (sending == on) return
        sending = on
        link?.setSending(on)
    }

    fun sendOnce(bytes: ByteArray) {
        packet = bytes
        val l = link
        if (l == null) {
            log("Not connected - tap Connect first")
            return
        }
        l.sendOnce(bytes)
    }
}
