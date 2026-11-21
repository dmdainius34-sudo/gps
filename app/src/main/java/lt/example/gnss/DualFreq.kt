package lt.example.gnss

import kotlin.math.abs

/**
 * Jonosferos vėlinimas L1 dažniui iš dviejų dažnių (L1 ir L5/E5a/B2a) pseudoatstumų skirtumo.
 * I1 = (P5 − P1) / (γ − 1), γ = (f1/f5)² = (1575,42/1176,45)². Vienos epochos skirtumas triukšmingas,
 * todėl kiekvienam palydovui glodinamas (jonosfera kinta lėtai) ir naudojamas tik po MIN_N epochų.
 * Imtuvo L1/L5 poslinkis visiems palydovams vienodas – jį sugeria atskiras sprendinio laikrodis.
 */
object DualFreq {
    /**
     * Programos padėtyje išjungta: Google GSDC 2023 važiavimuose (5 × Samsung S22 Ultra) L1+L5 jonosfera tikslumą blogino –
     * p50 2,3 → 3,1 m, p95 5,2 → 7,4 m (L5 kodas triukšmingesnis, palydovų L1–L5 poslinkiai), ir kaip „iono-free“, ir kaip pataisa.
     * Pixel telefonai L5 matavimų su iššifruotu TOW iš viso neteikia. Skaičiavimas paliktas diagnostikai.
     */
    @Volatile var enabled = false
    const val GAMMA = (1575.42 / 1176.45) * (1575.42 / 1176.45)
    const val K = 1.0 / (GAMMA - 1.0)              // ≈ 1,2606
    const val MIN_N = 10                           // tiek epochų (~10 s) prieš naudojant
    private const val WIN = 60                     // glodinimo langas (epochomis)
    private const val GAP_S = 5.0                  // ilgesnė pertrauka – iš naujo
    private const val JUMP_M = 15.0                // šuolis nuo vidurkio – iš naujo (atspindys, praslydimas)
    private const val MAX_ABS_M = 60.0             // netikėtai didelė reikšmė – nenaudojama

    private class S(var v: Double, var n: Int, var t: Double)
    private val st = HashMap<Int, S>()

    @Volatile var lastUsed = 0
    @Volatile var lastTotal = 0

    /** Vienos epochos jonosferos (L1, m) įvertis. */
    fun raw(pr1: Double, pr5: Double): Double = K * (pr5 - pr1)

    /** Glodintas L1 jonosferos vėlinimas (m) šiam palydovui, jei jau patikimas; kitaip null. */
    @Synchronized fun iono(m: Sp3V5.M, t: Double): Double? {
        val p5 = m.pr5 ?: return null
        val x = raw(m.pr, p5)
        if (!x.isFinite() || abs(x) > MAX_ABS_M) { st.remove(m.key); return null }
        val s = st[m.key]
        if (s == null || t - s.t > GAP_S || t < s.t || (s.n >= 3 && abs(x - s.v) > JUMP_M)) { st[m.key] = S(x, 1, t); return null }
        s.n++; s.t = t
        s.v += (x - s.v) / minOf(s.n, WIN)
        return if (s.n >= MIN_N) s.v else null
    }

    @Synchronized fun reset() { st.clear(); lastUsed = 0; lastTotal = 0 }
}
