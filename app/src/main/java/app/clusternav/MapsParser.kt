package app.clusternav

import java.util.Locale

/** Kind of manoeuvre, as far as it can be told from the Google Maps instruction text. */
enum class Maneuver {
    STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT, ROUNDABOUT, KEEP_LEFT, KEEP_RIGHT, MERGE,
    EXIT_LEFT, EXIT_RIGHT, ARRIVE, UNKNOWN,
    ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT, FERRY, START, REROUTE
}

data class ParsedNav(
    val distanceM: Int,          // to the next manoeuvre, -1 if the notification does not say
    val maneuver: Maneuver,
    val roundaboutExit: Int,     // 0 = none / unknown
    val road: String,
    val remainingM: Int,         // whole trip, -1 if the notification does not say
    val etaHour24: Int,          // -1 if unknown
    val etaMinute: Int,
    val instruction: String,
)

/**
 * Turns the texts of a Google Maps navigation notification (title, text, sub text, ...) into
 * navigation data. English text only (the notification has no machine readable fields, only
 * text and an icon). Pure Kotlin so it can be tested without a phone.
 */
object MapsParser {

    private const val UNIT = "km|kms|m|mi|miles?|ft|feet|yd|yds|yards?|metres?|meters?"
    private val distRe = Regex("""(\d+(?:[.,]\d+)?)\s*($UNIT)\b""", RegexOption.IGNORE_CASE)
    private val leadingDistRe = Regex("""^\s*(?:in\s+)?(\d+(?:[.,]\d+)?)\s*($UNIT)\b\s*""", RegexOption.IGNORE_CASE)
    private val durationRe = Regex("""\d\s*(?:min|mins|minutes?|h|hr|hrs|hours?)\b""", RegexOption.IGNORE_CASE)
    private val clockRe = Regex("""(\d{1,2})[:.](\d{2})\s*([ap])?\.?(?:m\.?)?""", RegexOption.IGNORE_CASE)
    private val hoursRe = Regex("""(\d+)\s*(?:h|hr|hrs|hours?)\b""", RegexOption.IGNORE_CASE)
    private val minsRe = Regex("""(\d+)\s*(?:min|mins|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val sepRe = Regex("""\s*[·•|–—]\s*|\s+-\s+""")

    fun toMetres(value: Double, unit: String): Int {
        val u = unit.lowercase(Locale.ROOT)
        val m = when {
            u.startsWith("km") -> value * 1000.0
            u == "m" || u.startsWith("met") -> value
            u.startsWith("mi") -> value * 1609.344
            u.startsWith("f") -> value * 0.3048
            u.startsWith("y") -> value * 0.9144
            else -> value
        }
        return Math.round(m).toInt()
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()

    /**
     * @param texts candidate strings in priority order (title, text, sub text, big text, lines...)
     * @return null if this does not look like an active navigation notification
     */
    fun parse(texts: List<String>, nowHour24: Int, nowMinute: Int): ParsedNav? {
        val items = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (items.isEmpty()) return null

        // 1. the string that starts with the distance to the next turn
        var distanceM = -1
        var distIdx = -1
        var instruction = ""
        for ((i, s) in items.withIndex()) {
            val m = leadingDistRe.find(s) ?: continue
            if (looksLikeTrip(s) && m.value.length >= s.length) continue
            distanceM = toMetres(num(m.groupValues[1]), m.groupValues[2])
            distIdx = i
            instruction = s.substring(m.value.length).trimStart(*SEP_CHARS).trim()
            break
        }

        // 2. the trip summary: "12 min · 5.3 km · 10:45"
        var tripIdx = -1
        for ((i, s) in items.withIndex()) {
            if (i != distIdx && looksLikeTrip(s)) {
                tripIdx = i
                break
            }
        }
        // Maps leaves the distance out while the bike is standing still: still navigation if the
        // trip summary is there (distance stays -1 = unknown).
        if (distIdx < 0 && tripIdx < 0) return null

        // 3. the instruction, if it was not in the distance string
        if (instruction.isEmpty()) {
            for ((i, s) in items.withIndex()) {
                if (i == distIdx || i == tripIdx) continue
                instruction = s
                break
            }
        }

        var remaining = -1
        var etaH = -1
        var etaM = 0
        if (tripIdx >= 0) {
            val trip = items[tripIdx]
            for (part in trip.split(sepRe)) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val c = clockRe.find(p)
                if (c != null && !durationRe.containsMatchIn(p)) {
                    var h = c.groupValues[1].toInt()
                    val mm = c.groupValues[2].toInt()
                    val ap = c.groupValues[3].lowercase(Locale.ROOT)
                    if (ap == "p" && h < 12) h += 12
                    if (ap == "a" && h == 12) h = 0
                    if (h in 0..23 && mm in 0..59) {
                        etaH = h
                        etaM = mm
                    }
                    continue
                }
                val d = distRe.find(p)
                if (d != null && remaining < 0 && !durationRe.containsMatchIn(p)) {
                    remaining = toMetres(num(d.groupValues[1]), d.groupValues[2])
                    continue
                }
                if (durationRe.containsMatchIn(p) && etaH < 0) {
                    val hrs = hoursRe.find(p)?.groupValues?.get(1)?.toInt() ?: 0
                    val mins = minsRe.find(p)?.groupValues?.get(1)?.toInt() ?: 0
                    val total = (nowHour24 * 60 + nowMinute + hrs * 60 + mins) % (24 * 60)
                    etaH = total / 60
                    etaM = total % 60
                }
            }
        }

        val (kind, exit) = classify(instruction)
        // A line that is not an instruction at all ("Construx Symphony" on the last leg) is a place name.
        val road = roadFrom(instruction).ifEmpty { if (kind == Maneuver.UNKNOWN) instruction else "" }
        return ParsedNav(distanceM, kind, exit, road, remaining, etaH, etaM, instruction)
    }

    private val SEP_CHARS = charArrayOf(' ', '·', '•', '|', '-', '–', '—', ',', ':')

    private fun looksLikeTrip(s: String): Boolean {
        if (durationRe.containsMatchIn(s)) return true
        return clockRe.containsMatchIn(s) && sepRe.containsMatchIn(s)
    }

    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9,
    )

    fun classify(instruction: String): Pair<Maneuver, Int> {
        val t = instruction.lowercase(Locale.ROOT)
        if (t.isEmpty()) return Maneuver.UNKNOWN to 0
        val left = t.indexOf("left")
        val right = t.indexOf("right")
        val side = when {
            left >= 0 && (right < 0 || left < right) -> -1
            right >= 0 -> 1
            else -> 0
        }
        fun pick(l: Maneuver, r: Maneuver, none: Maneuver = Maneuver.STRAIGHT) =
            when (side) { -1 -> l; 1 -> r; else -> none }

        if ("u-turn" in t || "u turn" in t) return pick(Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT, Maneuver.UTURN_LEFT) to 0
        if ("ferry" in t) return Maneuver.FERRY to 0
        if ("roundabout" in t || "traffic circle" in t || "rotary" in t) {
            var exit = Regex("""(\d)(?:st|nd|rd|th)\s+exit""").find(t)?.groupValues?.get(1)?.toInt() ?: 0
            if (exit == 0) for ((w, n) in ordinals) if (Regex("""\b$w\s+exit""").containsMatchIn(t)) exit = n
            return Maneuver.ROUNDABOUT to exit.coerceIn(0, 9)
        }
        if ("destination" in t || "arrive" in t) return Maneuver.ARRIVE to 0
        if ("sharp" in t) return pick(Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT) to 0
        if ("slight" in t || "bear" in t) return pick(Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT) to 0
        if ("keep" in t || "fork" in t) return pick(Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT) to 0
        if (Regex("""\bexit\b|\bramp\b""").containsMatchIn(t)) return pick(Maneuver.EXIT_LEFT, Maneuver.EXIT_RIGHT) to 0
        if ("merge" in t) return Maneuver.MERGE to 0
        if (side != 0) return pick(Maneuver.LEFT, Maneuver.RIGHT) to 0
        if ("continue" in t || "head" in t || "straight" in t) return Maneuver.STRAIGHT to 0
        return Maneuver.UNKNOWN to 0
    }

    private val roadRe = Regex("""\b(?:onto|on to|toward|towards|to stay on|stay on|on)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun roadFrom(instruction: String): String {
        val m = roadRe.find(instruction) ?: return ""
        // "onto X toward Y": keep the first road only
        return m.groupValues[1].split(Regex("""\s+(?:toward|towards)\s+""", RegexOption.IGNORE_CASE))[0].trim()
    }
}
