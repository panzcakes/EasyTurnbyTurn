package app.clusternav

import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The navigation packet the cluster understands.
 *
 * Packet format worked out for interoperability (see docs/PROTOCOL.md). On the wire it is
 *
 *     "\n" + body + "\n"            encoded as windows-1252
 *
 * and body is a plain concatenation of fixed-width text fields with no separators:
 *
 *     id(1) turn(1) distance(5) unit(1) etaHour(2) ampm(1) etaMin(2) m(1) roundabout(2) gpsLost(1)
 *     [extended only]  remainDist(5) remainUnit(1) "~~~~" "~" "04" roadName(18)
 *
 * The official app re-sends the current packet every 800 ms.
 */
class NavPacket(
    /** Direction letter. Lowercase = next turn is close (< 200 m). 'G' = cancelled, 'H' = finished. */
    val turn: Char,
    val distanceM: Int,
    /** 0..23, or -1 to send the blank clock "00"/"00" used for cancel/finish. */
    val etaHour24: Int,
    val etaMinute: Int,
    val roundaboutExit: Int,
    val roadName: String,
    val remainingM: Int,
    /** true = the longer packet with remaining distance and road name. */
    val extended: Boolean,
    val gpsLost: Boolean,
    /** true = distance field exactly like the official app ("0050 ", digits then a space); false = "00050". */
    val originalDistanceStyle: Boolean = true,
    /** Send the literal "00000" distance. Default: only for the G / H letters, like the official app. */
    val zeroDistance: Boolean = turn == 'G' || turn == 'H',
) {
    fun body(): String {
        val special = zeroDistance // cancelled / finished carry a literal zero distance
        val (dist, unit) = if (special) "00000" to "1" else NavFormat.distance(distanceM, originalDistanceStyle)
        val clock = NavFormat.clock(etaHour24, etaMinute)
        val sb = StringBuilder(64)
        sb.append('1')
        sb.append(turn)
        sb.append(dist).append(unit)
        sb.append(clock.hour)
        sb.append(if (extended) clock.ampm.lowercase(Locale.ROOT) else clock.ampm)
        sb.append(clock.minute)
        sb.append(if (extended) 'm' else 'M')
        sb.append(roundaboutExit.coerceIn(0, 99).toString().padStart(2, '0'))
        sb.append(if (gpsLost) '1' else '0')
        if (extended) {
            val (rd, ru) = NavFormat.remaining(remainingM)
            sb.append(rd).append(ru)
            sb.append("~~~~") // remaining time: the official app always sends this placeholder
            sb.append('~') // next-turn letter: placeholder in the official app
            sb.append("04") // second roundabout exit: constant in the official app
            sb.append(NavFormat.road(roadName))
        }
        return sb.toString()
    }

    fun toWire(): ByteArray = ("\n" + body() + "\n").toByteArray(NavFormat.charset)

    companion object {
        /** "Navigation cancelled" packet (official app sends turn letter G with zeroed fields). */
        fun cancelled(extended: Boolean) = NavPacket('G', 0, -1, 0, 0, "", 0, extended, false)

        /**
         * "Arrived / trip finished" packet (turn letter H). The official app keeps the trip's ETA on
         * the clock for this one and only clears the distances and road name.
         */
        fun finished(extended: Boolean, etaHour24: Int = -1, etaMinute: Int = 0) =
            NavPacket('H', 0, etaHour24, etaMinute, 0, "", 0, extended, false)
    }
}

object NavFormat {
    val charset: Charset = Charset.forName("windows-1252")

    /** Turn letters switch to lowercase when the next turn is closer than this. */
    const val NEAR_TURN_M = 200

    data class Clock(val hour: String, val minute: String, val ampm: String)

    private fun decimal(pattern: String, value: Double): String =
        java.text.DecimalFormat(pattern, java.text.DecimalFormatSymbols(Locale.US)).format(value)

    /**
     * Distance to the next turn: 5 characters plus unit ("1" = metres, "k" = km).
     *
     * The distance text ("50 m" / "1.2 km") has its unit letters removed (keeping the space) and is left-padded with zeros to 5 characters, so the wire value is
     * "0050 " / "01.2 ". With [original] = false the space is dropped ("00050" / "001.2").
     *
     * Rounding (same steps as the official app): up to 100 m in steps of 5, up to 300 m in steps of 10, below 975 m in steps
     * of 50 (never less than one step); from 975 m km with one decimal (none above 9.5 km).
     */
    fun distance(meters: Int, original: Boolean = true): Pair<String, String> {
        val m = meters.coerceAtLeast(0)
        val text: String
        val unit: String
        if (m >= 975) {
            val km = m / 1000.0
            text = if (m > 9500) decimal("0", km) else decimal("0.#", km)
            unit = "k"
        } else {
            val step = when {
                m <= 100 -> 5
                m <= 300 -> 10
                else -> 50
            }
            text = maxOf((m.toDouble() / step).roundToInt() * step, step).toString()
            unit = "1"
        }
        val z = if (original) "$text " else text
        return z.padStart(5, '0') to unit
    }

    /** Remaining trip distance: "%05d" metres up to 999 m, then km as "%05.1f" (or "%05.0f" above 9.5 km). */
    fun remaining(meters: Int): Pair<String, String> {
        val m = meters.coerceAtLeast(0)
        if (m <= 999) return m.toString().padStart(5, '0') to "1"
        val km = m / 1000.0
        val d = (if (m > 9500) decimal("0", km) else decimal("0.#", km)).toDouble()
        val text = if (d % 1.0 == 0.0) String.format(Locale.US, "%05.0f", d) else String.format(Locale.US, "%05.1f", d)
        return text to "k"
    }

    /** 12-hour clock text. Hour -1 gives the blank clock ("00", "00", "A"). */
    fun clock(hour24: Int, minute: Int): Clock {
        if (hour24 < 0) return Clock("00", "00", "A")
        val h = hour24.coerceIn(0, 23)
        val h12 = when {
            h == 0 -> 12
            h <= 12 -> h
            else -> h - 12
        }
        return Clock(
            h12.toString().padStart(2, '0'),
            minute.coerceIn(0, 59).toString().padStart(2, '0'),
            if (h >= 12) "P" else "A",
        )
    }

    /**
     * Road name field, always exactly 18 characters: padded with '~' or cut. Only letters, digits,
     * space and . , - are sent; anything else (such as the "/" in "Church Street / Kadamba Depot Rd")
     * becomes a space. A road name with a "/" made the cluster drop back to "start navigation".
     */
    fun road(name: String): String {
        val clean = buildString {
            for (ch in name) append(if (ch.isLetterOrDigit() && ch.code < 128 || ch in " .,-") ch else ' ')
        }.replace(Regex(" +"), " ").trim()
        return clean.take(18).trimEnd().padEnd(18, '~')
    }

    /** Turn letter with the case rule applied (lowercase when the turn is near). */
    fun turnLetter(base: Char, distanceM: Int): Char =
        if (distanceM < NEAR_TURN_M) base.lowercaseChar() else base.uppercaseChar()
}
