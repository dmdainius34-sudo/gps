package lt.example.gnss

import java.util.Locale

/**
 * Kosminiai orai GNSS tikslumui: NOAA Kp (geomagnetinis aktyvumas) ir NASA DONKI vainikinės masės išmetimai (CME), nukreipti į Žemę.
 * Audra – Kp ≥ 5 arba CME pasiekė Žemę per paskutines ~24 h: jonosfera neramiai, vieno dažnio (L1) padėtis prastesnė,
 * RTK FIX gali užtrukti. Tada programos padėtis glodinama stipriau (PosFilter.storm).
 */
object SpaceWeather {
    @Volatile var kp = Double.NaN
    @Volatile var kpTime = ""

    /** CME, kurio kryptis ±45° nuo Saulės–Žemės linijos: atvykimas = 21,5 R☉ momentas + (215 − 21,5) R☉ / greitis. */
    fun earthArrivals(list: List<Donki.Cme>): List<Pair<Donki.Cme, Long>> = list.filter { Donki.hitsEarthLatLon(it) && it.speed > 100 }
        .map { it to (it.t215Ms + ((Donki.AU_RSUN - 21.5) * 696_000.0 / it.speed * 1000.0).toLong()) }

    /** CME, pasiekęs Žemę per paskutines 24 h (arba pasieksiantis per 6 h); null – nėra. */
    fun cmeNow(now: Long, list: List<Donki.Cme> = Donki.list): Pair<Donki.Cme, Long>? =
        earthArrivals(list).filter { now in (it.second - 6 * 3_600_000L)..(it.second + 24 * 3_600_000L) }.maxByOrNull { it.first.speed }

    fun storm(now: Long = System.currentTimeMillis()): Boolean = (!kp.isNaN() && kp >= 5.0) || cmeNow(now) != null

    /** Lentelės tekstas; null – ramu. */
    fun banner(now: Long = System.currentTimeMillis()): Pair<String, String>? {
        if (!storm(now)) return null
        val g = when { kp.isNaN() -> ""; kp >= 8 -> " (G4–G5, labai stipri)"; kp >= 7 -> " (G3, stipri)"; kp >= 6 -> " (G2, vidutinė)"; kp >= 5 -> " (G1, silpna)"; else -> "" }
        val c = cmeNow(now)
        val sub = buildString {
            if (!kp.isNaN()) append("Kp %.1f%s. ".format(Locale.US, kp, g))
            if (c != null) append(if (c.second > now) "Saulės išmetimas (CME, %.0f km/s) pasieks Žemę po %d h. ".format(Locale.US, c.first.speed, (c.second - now) / 3_600_000L)
                else "Saulės išmetimas (CME, %.0f km/s) pasiekė Žemę prieš %d h. ".format(Locale.US, c.first.speed, (now - c.second) / 3_600_000L))
            append("Jonosfera neramiai – padėtis gali šokinėti keliais metrais, RTK FIX gali užtrukti.")
        }
        return "☀ Saulės audra" to sub
    }

    fun text(now: Long = System.currentTimeMillis()): String = buildString {
        append("Kosminiai orai: ")
        append(if (kp.isNaN()) "Kp nežinomas" else "Kp %.2f (%s UTC)".format(Locale.US, kp, kpTime))
        val arr = earthArrivals(Donki.list).filter { kotlin.math.abs(it.second - now) < 3 * 86_400_000L }
        if (arr.isEmpty()) append("; į Žemę nukreiptų CME (±3 paros) nėra")
        else arr.forEach { (c, t) -> append("; CME %.0f km/s %s Žemę %s".format(Locale.US, c.speed, if (t <= now) "pasiekė" else "pasieks",
            java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(t)))) }
        append(if (storm(now)) " – AUDRA: padėtis glodinama stipriau" else " – ramu")
    }
}
