package lt.example.gnss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Garsai, sintezuojami programoje (be failų): 16 bitų PCM, mono.
 * Pūstuvas – „spjūvis per bambuko vamzdį“: staigus oro pūstelėjimas (triukšmas), tuščiavidurio ~0,75 m bambuko vamzdžio
 * rezonansas (pagrindinis ~230 Hz ir obertonai, šiek tiek krentantys – strėlytė tolsta), trumpas „tuk“ jai išlekiant
 * ir švilpiantis nuskriejimas.
 */
object SfxSynth {
    /** RBJ juostinis filtras (0 dB smailėje), koeficientai perskaičiuojami kiekvienam mėginiui (slystantis dažnis). */
    private class Bp {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun step(x: Double, f: Double, q: Double, sr: Int): Double {
            val w = 2 * PI * f / sr; val al = sin(w) / (2 * q)
            val a0 = 1 + al
            val y = (al * x - al * x2 + 2 * cos(w) * y1 - (1 - al) * y2) / a0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    fun blowgun(sr: Int = 44100, seed: Long = 11): ShortArray {
        val n = (0.5 * sr).toInt()
        val rnd = java.util.Random(seed)
        val out = DoubleArray(n)
        val breath = Bp(); val whoosh = Bp()
        val tube = Array(5) { Bp() }
        val tubeG = doubleArrayOf(1.0, 0.6, 0.45, 0.3, 0.2)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val noise = rnd.nextGaussian()
            // 1) pūstelėjimas: staigus (3 ms), greitai rimsta (~50 ms), kvėpavimo uodega
            val envB = (1 - exp(-t / 0.003)) * (exp(-t / 0.05) + 0.2 * exp(-t / 0.18))
            val puff = noise * envB
            val b = breath.step(puff, 2200.0, 0.8, sr)
            // 2) vamzdžio rezonansas – žadinamas tuo pačiu pūstelėjimu, pagrindinis dažnis krenta ~10 %
            val f0 = 230.0 * (1 - 0.10 * (1 - exp(-t / 0.08)))
            var r = 0.0
            for (k in 0 until 5) r += tubeG[k] * tube[k].step(puff, f0 * (k + 1), 18.0, sr)
            // 3) „tuk“ – strėlytė išlekia (22 ms)
            val tp = t - 0.022
            val pop = if (tp < 0) 0.0 else exp(-tp / 0.008) * sin(2 * PI * 1300 * tp) + 0.6 * exp(-tp / 0.015) * sin(2 * PI * 650 * tp)
            // 4) žemas oro stūmis
            val thump = exp(-t / 0.035) * sin(2 * PI * 110 * t) * (1 - exp(-t / 0.002))
            // 5) švilpiantis nuskriejimas: triukšmo juosta slysta 3 → 1,2 kHz
            val tw = t - 0.06
            val envW = if (tw < 0) 0.0 else (1 - exp(-tw / 0.02)) * exp(-tw / 0.15)
            val fw = 1200.0 + 1800.0 * exp(-(if (tw < 0) 0.0 else tw) / 0.12)
            val w = whoosh.step(noise * envW, fw, 4.0, sr)
            out[i] = 0.5 * b + 0.8 * r * 8.0 + 0.7 * pop + 0.35 * thump + 0.3 * w * 2.0
        }
        // pabaiga – 30 ms nutildymas, normavimas iki 0,8 (≈ −2 dB)
        val fade = (0.03 * sr).toInt()
        for (i in 0 until fade) out[n - 1 - i] *= i.toDouble() / fade
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(n) { (out[it] / peak * 0.8 * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
