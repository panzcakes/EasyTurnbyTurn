// Standalone check of the packet encoder (no Android needed).
// Run:  kotlinc app/src/main/java/app/clusternav/NavPacket.kt app/src/main/java/app/clusternav/MapsParser.kt tools/selftest/SelfTest.kt -include-runtime -d selftest.jar && java -jar selftest.jar
import app.clusternav.MapsParser
import app.clusternav.Maneuver
import app.clusternav.NavFormat
import app.clusternav.NavPacket

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("ok    $name")
    } else {
        failures++
        println("FAIL  $name\n        expected: $expected\n        actual:   $actual")
    }
}

fun main() {
    // Extended packet: straight 500 m, ETA 14:35, 12.3 km remaining
    val ext = NavPacket('E', 500, 14, 35, 0, "Delhi Road, Jaipur", 12345, extended = true, gpsLost = false)
    check("extended body", ext.body(), "1E0500 102p35m00000012k~~~~~04Delhi Road, Jaipur")
    check("extended body, zero-padded distance style", NavPacket('E', 500, 14, 35, 0, "Delhi Road, Jaipur", 12345, true, false, false).body(),
        "1E00500102p35m00000012k~~~~~04Delhi Road, Jaipur")
    check("extended wire length", ext.toWire().size, 50)
    check("wire framed by newlines", ext.toWire().let { it.first() to it.last() }, '\n'.code.toByte() to '\n'.code.toByte())

    // Basic packet, near turn (lowercase), roundabout exit 3
    val basic = NavPacket(NavFormat.turnLetter('C', 150), 150, 14, 35, 3, "ignored", 99999, extended = false, gpsLost = false)
    check("basic body", basic.body(), "1c0150 102P35M030")
    check("basic wire length", basic.toWire().size, 19)

    check("cancelled", NavPacket.cancelled(false).body(), "1G00000100A00M000")
    check("finished, blank clock", NavPacket.finished(false).body(), "1H00000100A00M000")
    check("finished keeps the trip ETA", NavPacket.finished(false, 14, 35).body(), "1H00000102P35M000")
    check("cancelled extended length", NavPacket.cancelled(true).body().length, 48)

    // Distances
    // Mappls steps: <=100 m by 5, <=300 m by 10, <975 m by 50 (min one step); km from 975 m,
    // one decimal up to 9.5 km, none above. Original wire style keeps a space after the digits.
    check("distance 500 m", NavFormat.distance(500), "0500 " to "1")
    check("distance 500 m, zero-padded style", NavFormat.distance(500, false), "00500" to "1")
    check("distance 430 m rounds to 450", NavFormat.distance(430), "0450 " to "1")
    check("distance 120 m", NavFormat.distance(120), "0120 " to "1")
    check("distance 80 m", NavFormat.distance(80), "0080 " to "1")
    check("distance 7 m is at least one step", NavFormat.distance(7), "0005 " to "1")
    check("distance negative", NavFormat.distance(-5), "0005 " to "1")
    check("distance 1234 m", NavFormat.distance(1234), "01.2 " to "k")
    check("distance 1234 m, zero-padded style", NavFormat.distance(1234, false), "001.2" to "k")
    check("distance 996 m is 1 km", NavFormat.distance(996), "0001 " to "k")
    check("distance 9400 m", NavFormat.distance(9400), "09.4 " to "k")
    check("distance 9600 m has no decimal", NavFormat.distance(9600), "0010 " to "k")
    check("distance 12400 m", NavFormat.distance(12400), "0012 " to "k")

    check("remaining 850 m", NavFormat.remaining(850), "00850" to "1")
    check("remaining 1000 m", NavFormat.remaining(1000), "00001" to "k")
    check("remaining 9400 m", NavFormat.remaining(9400), "009.4" to "k")
    check("remaining 9600 m", NavFormat.remaining(9600), "00010" to "k")
    check("remaining 12345 m", NavFormat.remaining(12345), "00012" to "k")

    // Road name rules
    check("road short", NavFormat.road("Short"), "Short" + "~".repeat(13))
    check("road exact 18", NavFormat.road("Delhi Road, Jaipur"), "Delhi Road, Jaipur")
    check("road long is cut to exactly 18", NavFormat.road("A very long road name here"), "A very long road n")
    check("road non-ascii", NavFormat.road("Café"), "Caf" + "~".repeat(15))
    check("road slash removed", NavFormat.road("Church Street / Kadamba Depot Rd"), "Church Street Kada")
    check("road always 18", NavFormat.road("Church Street / Kadamba Depot Rd").length, 18)
    check("road blank", NavFormat.road(""), "~".repeat(18))

    // Clock
    check("clock 00:05", NavFormat.clock(0, 5), NavFormat.Clock("12", "05", "A"))
    check("clock 12:00", NavFormat.clock(12, 0), NavFormat.Clock("12", "00", "P"))
    check("clock 23:59", NavFormat.clock(23, 59), NavFormat.Clock("11", "59", "P"))
    check("clock blank", NavFormat.clock(-1, 0), NavFormat.Clock("00", "00", "A"))

    // Letter case rule
    check("turn near lower", NavFormat.turnLetter('E', 199), 'e')
    check("turn far upper", NavFormat.turnLetter('e', 200), 'E')

    // ---- Google Maps notification parser
    fun p(vararg t: String) = MapsParser.parse(t.toList(), 10, 0)
    val a = p("500 m", "Turn left onto MG Road", "12 min · 5.3 km · 10:45 am")
    check("maps: distance", a?.distanceM, 500)
    check("maps: maneuver", a?.maneuver, Maneuver.LEFT)
    check("maps: road", a?.road, "MG Road")
    check("maps: remaining", a?.remainingM, 5300)
    check("maps: eta 12h", a?.etaHour24 to a?.etaMinute, 10 to 45)
    val b = p("1.2 km", "At the roundabout, take the 2nd exit onto Ring Road toward Airport", "1 h 5 min · 40 km")
    check("maps: km distance", b?.distanceM, 1200)
    check("maps: roundabout", b?.maneuver to b?.roundaboutExit, Maneuver.ROUNDABOUT to 2)
    check("maps: road before toward", b?.road, "Ring Road")
    check("maps: eta from duration", b?.etaHour24 to b?.etaMinute, 11 to 5)
    check("maps: remaining 40 km", b?.remainingM, 40000)
    val c = p("200 ft · Slight right onto NH 48", "5 min · 2 km · 2:30 pm")
    check("maps: feet", c?.distanceM, 61)
    check("maps: slight right", c?.maneuver, Maneuver.SLIGHT_RIGHT)
    check("maps: pm clock", c?.etaHour24 to c?.etaMinute, 14 to 30)
    check("maps: u-turn", MapsParser.classify("Make a U-turn").first, Maneuver.UTURN_LEFT)
    check("maps: continue", MapsParser.classify("Continue straight").first, Maneuver.STRAIGHT)
    check("maps: arrive", MapsParser.classify("Your destination is on the right").first, Maneuver.ARRIVE)
    val still = p("", "towards Ln 6", "8 min \u00B7 3.8 km \u00B7 14:05 ETA")
    check("maps: standing still (real dump)", still?.distanceM to still?.remainingM, -1 to 3800)
    check("maps: standing still eta/road", Triple(still?.etaHour24, still?.etaMinute, still?.road), Triple(14, 5, "Ln 6"))
    val zero = p("0 m", "towards Ln 6", "8 min \u00B7 3.8 km \u00B7 14:01 ETA")
    check("maps: 0 m (real dump)", zero?.distanceM to zero?.road, 0 to "Ln 6")
    check("maps: bare place name is the road", p("", "Construx Symphony", "1 min \u00B7 50 m \u00B7 17:52 ETA")?.road, "Construx Symphony")
    check("maps: ferry", MapsParser.classify("Take the ferry").first, Maneuver.FERRY)
    check("maps: not navigation", p("Location sharing", "Sharing with Anna"), null)
    check("maps: rerouting", p("Rerouting..."), null)

    // straight arrow with a real distance, and the old zero-distance behaviour
    check("G keeps zero distance by default", NavPacket('G', 500, 14, 35, 0, "", 12345, true, false).body().substring(2, 8), "000001")
    check("G with real distance", NavPacket('G', 500, 14, 35, 0, "", 12345, true, false, true, false).body().substring(2, 8), "0500 1")

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
