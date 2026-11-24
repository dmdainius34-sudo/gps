package lt.example.gnss

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Tikrasis tikslumas nuo nepriklausomai žinomos vietos (NLO „Žinomos koordinatės“; aukštis – „Žinomas aukštis“):
 * kiekvienai sekundei ΔE, ΔN, e = √(ΔE² + ΔN²); ataskaitoje – horizontali RMSE, 95-asis procentilis, vidurkis, didžiausia,
 * aukščio paklaida atskirai. Krane (įvesta bokšto ašis) – tik nuokrypis nuo kabinos apskritimo (apatinė paklaidos riba).
 * Kiekvienam skaičiavimo būdui atskirai (Vault, Vault be apribojimų, lustas, RTK). Tik atmintyje (iki ~6 val. sekundžių).
 */
object AccRef {
    private class S { val e = ArrayList<Double>(); val dh = ArrayList<Double>(); val ring = ArrayList<Double>(); var sE = 0.0; var sN = 0.0 }
    private val m = LinkedHashMap<String, S>()
    private var refKey = ""
    private const val MAX = 20_000
    private const val M = 111_320.0

    @Synchronized fun reset() { m.clear() }

    /** hEll – elipsoidinis aukštis (m) arba NaN. */
    @Synchronized fun add(name: String, lat: Double, lon: Double, hEll: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        val key = if (KnownPos.valid) "%.7f,%.7f".format(Locale.US, KnownPos.lat, KnownPos.lon) else ""
        if (key != refKey) { refKey = key; m.clear() }
        val s = m.getOrPut(name) { S() }
        if (KnownPos.valid) {
            val dN = (lat - KnownPos.lat) * M; val dE = (lon - KnownPos.lon) * M * cos(Math.toRadians(KnownPos.lat))
            if (s.e.size < MAX) { s.e.add(sqrt(dE * dE + dN * dN)); s.sE += dE; s.sN += dN }
        }
        val msl = KnownHeight.deviceMsl()
        if (msl != null && !hEll.isNaN()) Geoid.undulation(lat, lon)?.let { n -> if (s.dh.size < MAX) s.dh.add(hEll - n - msl) }
        if (Crane.valid) {
            val d = PositionSolver.horizDist(lat, lon, Crane.lat, Crane.lon)
            // tik prie krano (≤ 300 m nuo ašies) – toliau (namie) nuokrypis nuo kabinos apskritimo beprasmis
            if (d <= 300.0 && s.ring.size < MAX) s.ring.add(abs(d - Crane.radius))
        }
    }

    private fun p(l: List<Double>, q: Double): Double { val s = l.sorted(); return s[((s.size - 1) * q).toInt()] }
    private fun rms(l: List<Double>) = sqrt(l.sumOf { it * it } / l.size)

    @Synchronized fun text(): String = buildString {
        appendLine("=== TIKRASIS TIKSLUMAS NUO ŽINOMOS VIETOS (RMSE, 95 %) ===")
        if (!KnownPos.valid && KnownHeight.deviceMsl() == null && !Crane.valid) {
            appendLine("Žinoma vieta neįvesta – tikrojo tikslumo apskaičiuoti negalima (tik tikėtinas). Įveskite NLO „Žinomos koordinatės“ taško, kurio koordinatės žinomos nepriklausomai (geodezinis taškas, RTK imtuvas).")
            return@buildString
        }
        if (KnownPos.valid) appendLine("Etalonas: žinomos koordinatės" + (if (KnownPos.use) " (naudojamos ir kaip apribojimas – Vault paklaida tada neobjektyvi, žiūrėkite „be apribojimų“)" else ""))
        for ((name, s) in m) {
            val parts = ArrayList<String>()
            if (s.e.isNotEmpty()) parts.add("horizontaliai RMSE %.2f m, 95 %% %.2f m, vid. %.2f m, didž. %.1f m, vid. poslinkis į rytus %+.2f, į šiaurę %+.2f m (%d s)".format(Locale.US,
                rms(s.e), p(s.e, 0.95), s.e.average(), s.e.maxOrNull() ?: 0.0, s.sE / s.e.size, s.sN / s.e.size, s.e.size))
            if (s.dh.isNotEmpty()) parts.add("aukštis vid. %+.2f m, RMSE %.2f m, 95 %% |%.2f| m (%d s)".format(Locale.US,
                s.dh.average(), rms(s.dh), p(s.dh.map { abs(it) }, 0.95), s.dh.size))
            if (s.ring.isNotEmpty()) parts.add("nuo kabinos apskritimo (apatinė riba) RMSE %.2f m, 95 %% %.2f m (%d s)".format(Locale.US,
                rms(s.ring), p(s.ring, 0.95), s.ring.size))
            if (parts.isNotEmpty()) appendLine("  $name: " + parts.joinToString("; "))
        }
        if (m.isEmpty()) appendLine("Duomenų dar nėra")
    }
}
