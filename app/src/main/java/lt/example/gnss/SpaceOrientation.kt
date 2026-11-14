package lt.example.gnss

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kaip stebėtojo horizontas iš tikrųjų orientuotas erdvėje: Žemės orbitos plokštuma (ekliptika), judėjimo orbitoje kryptis,
 * Žemės ašis (Polaris), Saulė, sukimasis ir Saulės kelias Galaktikoje – visa stebėtojo horizonto (azimutas, aukštis) sistemoje.
 * Skaičiuojama iš vietos (GNSS) ir laiko; palydovų kryptys (SP3 / efemeridės) patikrina, kad ta pati geometrija teisinga.
 */
object SpaceOrientation {
    const val EARTH_ORBIT_KMS = 29.78
    const val GALAXY_KMS = 230.0
    const val ECLIPTIC_OBLIQUITY = 23.4397

    class Result(
        val sunAz: Double, val sunAlt: Double,
        val eclPoleAlt: Double, val eclTilt: Double, val eclCross: Double,
        val motionAz: Double, val motionAlt: Double,
        val polarisAz: Double, val polarisAlt: Double,
        val poleDepression: Double, val poleChordKm: Double,
        val rotationMs: Double,
        val galAz: Double, val galAlt: Double,
        val sunMaxAlt: Double, val sunMinAlt: Double)

    /** Saulės geocentrinė ekliptinė ilguma (rad) – ta pati formulė kaip Astro.sunEq. */
    fun sunEclLon(ms: Long): Double {
        val d = Astro.daysJ2000(ms)
        val m = Math.toRadians(357.5291 + 0.98560028 * d)
        val c = Math.toRadians(1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        return m + c + Math.toRadians(102.9372) + Math.PI
    }

    /** Žemės judėjimo orbitoje kryptis (pusiaujo vienetinis vektorius): ekliptinė ilguma λ☉ − 90°. */
    fun motionEq(ms: Long): DoubleArray = Astro.eclToEq(sunEclLon(ms) - Math.PI / 2, 0.0)

    fun compute(latDeg: Double, lonDeg: Double, ms: Long): Result {
        val (sAz, sAlt) = Astro.altAz(Astro.sunEq(ms), latDeg, lonDeg, ms)
        val pole = Astro.eclToEq(0.0, Math.PI / 2)
        val (pAz, pAlt) = Astro.altAz(pole, latDeg, lonDeg, ms)
        // Ekliptikos ir horizonto susikirtimo linija: statmena zenitui ir ekliptikos poliui -> azimutas pAz ± 90°
        val cross = (pAz + 90.0) % 360.0
        val (mAz, mAlt) = Astro.altAz(motionEq(ms), latDeg, lonDeg, ms)
        val (polAz, polAlt) = Astro.altAzRaDec(37.95456, 89.26411, latDeg, lonDeg, ms)
        val colat = 90.0 - latDeg
        val (gRa, gDec) = Astro.galToEq(90.0, 0.0)
        val (gAz, gAlt) = Astro.altAzRaDec(gRa, gDec, latDeg, lonDeg, ms)
        val dec = Math.toDegrees(asin(Astro.sunEq(ms)[2].coerceIn(-1.0, 1.0)))
        return Result(sAz, sAlt, pAlt, 90.0 - pAlt, cross, mAz, mAlt, polAz, polAlt,
            colat / 2.0, 2 * 6371.0 * sin(Math.toRadians(colat / 2.0)),
            465.1 * cos(Math.toRadians(latDeg)), gAz, gAlt,
            90.0 - abs(latDeg - dec), latDeg + dec - 90.0)
    }

    /** Ekliptikos ir horizonto kampo paros ribos (ekliptikos polius: Dec 90° − ε). */
    fun tiltRange(latDeg: Double): Pair<Double, Double> {
        val d = 90.0 - ECLIPTIC_OBLIQUITY
        val phi = abs(latDeg)
        val altMax = 90.0 - abs(phi - d); val altMin = phi + d - 90.0
        fun tilt(alt: Double) = 90.0 - abs(alt)
        val a = tilt(altMax); val b = tilt(altMin)
        return minOf(a, b) to maxOf(a, b)
    }

    private fun dir(az: Double): String {
        val n = arrayOf("Š", "ŠR", "R", "PR", "P", "PV", "V", "ŠV")
        return n[(((az + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)]
    }

    private fun f(v: Double, d: Int = 1) = String.format(Locale.US, "%.${d}f", v)

    /** Trumpas tekstas 3D vaizdo kampui. */
    fun shortText(r: Result): String = buildString {
        appendLine("Orbitos plokštuma ∠ jūsų horizontui: ${f(r.eclTilt, 0)}°")
        appendLine("Žemė orbitoje juda: az ${f(r.motionAz, 0)}° (${dir(r.motionAz)}), " +
            (if (r.motionAlt >= 0) "${f(r.motionAlt, 0)}° virš horizonto" else "${f(-r.motionAlt, 0)}° po horizontu"))
        appendLine("Saulė: az ${f(r.sunAz, 0)}°, aukštis ${f(r.sunAlt, 0)}°")
        append("Ašis → Polaris: az ${f(r.polarisAz, 0)}°, ${f(r.polarisAlt, 0)}° virš horizonto")
    }

    /** Išsamus tekstas ataskaitai. */
    fun report(r: Result, latDeg: Double): String = buildString {
        appendLine("=== ERDVĖS ORIENTACIJA (stebėtojo horizonto sistemoje) ===")
        appendLine("Žemės orbitos plokštuma (ekliptika) dabar pasvirusi į jūsų horizontą ${f(r.eclTilt)}° " +
            "(ekliptikos polius ${f(r.eclPoleAlt)}° aukštyje); ji kerta horizontą azimutuose ${f(r.eclCross, 0)}° ir ${f((r.eclCross + 180) % 360, 0)}°. " +
            "Per parą šis kampas kinta nuo ${f(tiltRange(latDeg).first, 0)}° iki ${f(tiltRange(latDeg).second, 0)}° – " +
            "ne horizontalus „blynas“ ir ne vertikalus „liftas“, o besisukantis pasviręs diskas.")
        appendLine("Žemė orbitoje aplink Saulę juda ${f(EARTH_ORBIT_KMS, 2)} km/s link azimuto ${f(r.motionAz)}° (${dir(r.motionAz)}), " +
            (if (r.motionAlt >= 0) "${f(r.motionAlt)}° virš" else "${f(-r.motionAlt)}° po") + " jūsų horizonto (90° nuo Saulės krypties, ekliptikoje).")
        appendLine("Saulė: azimutas ${f(r.sunAz)}°, aukštis ${f(r.sunAlt)}°; šiandien aukščiausiai ${f(r.sunMaxAlt)}°, žemiausiai ${f(r.sunMinAlt)}° (po kojomis).")
        appendLine("Žemės ašis rodo į Polaris: azimutas ${f(r.polarisAz)}°, aukštis ${f(r.polarisAlt)}° (≈ platuma ${f(latDeg, 2)}°). " +
            "Pati ašis eina per Žemės centrą – Šiaurės ašigalis (paviršiuje) nuo jūsų yra ${f(r.poleDepression)}° po horizontu, ${f(r.poleChordKm, 0)} km tiesia linija. " +
            "Polaris tokia toli (~430 šviesmečių), kad žvilgsnis į ją ir ašis lygiagretūs – todėl ji matoma virš horizonto, nors ašis po kojomis.")
        appendLine("Žemės sukimasis: jūsų vieta juda ${f(r.rotationMs, 0)} m/s į rytus (az 90°), 15,04° per valandą apie ašį.")
        appendLine("Saulė su Žeme skrieja Galaktikoje ~${f(GALAXY_KMS, 0)} km/s link Gulbės (l = 90°): azimutas ${f(r.galAz)}°, aukštis ${f(r.galAlt)}°.")
    }
}
