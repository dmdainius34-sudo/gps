package lt.example.gnss

import java.io.File
import java.util.Locale

/**
 * RTCM 3 iššifravimas DGNSS pataisoms: rėmeliai (0xD3, ilgis, CRC-24Q), 1005/1006 (bazės koordinatės)
 * ir MSM4/5/7 (1074–1127) kodo pseudoatstumai pagal palydovą ir signalą.
 */
object Rtcm3 {
    const val C = 299_792_458.0

    fun crc24q(d: ByteArray, off: Int, len: Int): Int {
        var crc = 0
        for (i in off until off + len) {
            crc = crc xor ((d[i].toInt() and 0xFF) shl 16)
            repeat(8) { crc = crc shl 1; if (crc and 0x1000000 != 0) crc = crc xor 0x1864CFB }
        }
        return crc and 0xFFFFFF
    }

    /** Srauto skaidymas į žinutes: grąžina (tipas, naudingoji dalis) su teisingu CRC. */
    class Framer {
        private val buf = java.io.ByteArrayOutputStream()
        var crcErrors = 0
            private set

        fun push(data: ByteArray, n: Int = data.size): List<Pair<Int, ByteArray>> {
            buf.write(data, 0, n)
            val b = buf.toByteArray()
            val out = ArrayList<Pair<Int, ByteArray>>()
            var i = 0
            while (i + 3 <= b.size) {
                if ((b[i].toInt() and 0xFF) != 0xD3) { i++; continue }
                val len = ((b[i + 1].toInt() and 0x03) shl 8) or (b[i + 2].toInt() and 0xFF)
                if (i + 3 + len + 3 > b.size) break
                val crc = ((b[i + 3 + len].toInt() and 0xFF) shl 16) or ((b[i + 4 + len].toInt() and 0xFF) shl 8) or (b[i + 5 + len].toInt() and 0xFF)
                if (crc24q(b, i, 3 + len) == crc && len >= 2) {
                    val p = b.copyOfRange(i + 3, i + 3 + len)
                    out.add((Bits(p).u(0, 12).toInt()) to p)
                    i += 6 + len
                } else { crcErrors++; i++ }
            }
            buf.reset(); if (i < b.size) buf.write(b, i, b.size - i)
            if (buf.size() > 1 shl 16) buf.reset()
            return out
        }
    }

    /** 1005/1006: bazės (antenos atskaitos taško) ECEF, m. */
    fun station(p: ByteArray): DoubleArray? {
        val b = Bits(p)
        val t = b.u(0, 12).toInt()
        if (t != 1005 && t != 1006) return null
        val x = b.s(34, 38) * 1e-4
        val y = b.s(74, 38) * 1e-4
        val z = b.s(114, 38) * 1e-4
        return doubleArrayOf(x, y, z)
    }

    /** 1032: fizinės bazinės stoties (iš kurios skaičiuojama VRS) ECEF, m; (stoties ID, koordinatės). */
    fun physStation(p: ByteArray): Pair<Int, DoubleArray>? {
        val b = Bits(p)
        if (b.u(0, 12).toInt() != 1032 || b.size < 156) return null
        return b.u(24, 12).toInt() to doubleArrayOf(b.s(42, 38) * 1e-4, b.s(80, 38) * 1e-4, b.s(118, 38) * 1e-4)
    }

    class Obs(val sys: Char, val prn: Int, val sig: String, val pr: Double, val cn0: Double)
    /** tow – GPS savaitės laikas (s); GLONASS – paros laikas Maskvos laiku (s), dow – savaitės diena. */
    class Msm(val sys: Char, val type: Int, val tow: Double, val dow: Int, val obs: List<Obs>)

    fun sysOf(type: Int): Char? = when (type / 10) {
        107 -> 'G'; 108 -> 'R'; 109 -> 'E'; 111 -> 'J'; 112 -> 'C'; else -> null
    }

    /** Signalo ID (1–32) -> RINEX kodas (GPS, GLONASS, Galileo, BeiDou), RTCM 10403.3 lentelės. */
    fun sigCode(sys: Char, id: Int): String = when (sys) {
        'G' -> mapOf(2 to "1C", 3 to "1P", 4 to "1W", 8 to "2C", 9 to "2P", 10 to "2W", 15 to "2S", 16 to "2L", 17 to "2X",
            22 to "5I", 23 to "5Q", 24 to "5X", 30 to "1S", 31 to "1L", 32 to "1X")[id]
        'R' -> mapOf(2 to "1C", 3 to "1P", 8 to "2C", 9 to "2P")[id]
        'E' -> mapOf(2 to "1C", 3 to "1A", 4 to "1B", 5 to "1X", 6 to "1Z", 8 to "6C", 9 to "6A", 10 to "6B", 11 to "6X", 12 to "6Z",
            14 to "7I", 15 to "7Q", 16 to "7X", 18 to "8I", 19 to "8Q", 20 to "8X", 22 to "5I", 23 to "5Q", 24 to "5X")[id]
        'C' -> mapOf(2 to "2I", 3 to "2Q", 4 to "2X", 8 to "6I", 9 to "6Q", 10 to "6X", 14 to "7I", 15 to "7Q", 16 to "7X",
            22 to "5D", 23 to "5P", 24 to "5X", 30 to "1D", 31 to "1P", 32 to "1X")[id]
        else -> null
    } ?: "?$id"

    /** MSM4/5/7 -> kodo pseudoatstumai (m). Kitos MSM rūšys – null. */
    fun msm(p: ByteArray): Msm? {
        val b = Bits(p)
        val type = b.u(0, 12).toInt()
        val sys = sysOf(type) ?: return null
        val kind = type % 10
        if (kind != 4 && kind != 5 && kind != 7) return null
        var i = 24
        val epoch = b.u(i, 30); i += 30
        val (tow, dow) = if (sys == 'R') (epoch and 0x7FFFFFF) / 1000.0 to (epoch ushr 27).toInt() else epoch / 1000.0 to 0
        i += 1 + 3 + 7 + 2 + 2 + 1 + 3                               // daugybinė žinutė, IODS, rezervas, laikrodis, glodinimas
        val sats = ArrayList<Int>(); for (k in 1..64) { if (b.bit(i) == 1) sats.add(k); i++ }
        val sigs = ArrayList<Int>(); for (k in 1..32) { if (b.bit(i) == 1) sigs.add(k); i++ }
        val ns = sats.size; val ng = sigs.size
        if (ns * ng > 64) return null
        val cells = ArrayList<Pair<Int, Int>>()                         // (palydovo indeksas, signalo indeksas)
        for (s in 0 until ns) for (g in 0 until ng) { if (b.bit(i) == 1) cells.add(s to g); i++ }
        val nc = cells.size
        // palydovų duomenys
        val rInt = IntArray(ns) { val v = b.u(i, 8).toInt(); i += 8; v }
        if (kind != 4) i += 4 * ns                                      // papildoma info
        val rMod = DoubleArray(ns) { val v = b.u(i, 10) / 1024.0; i += 10; v }
        if (kind != 4) i += 14 * ns                                     // apytikslis greitis
        // signalų duomenys
        val prBits = if (kind == 7) 20 else 15; val prScale = if (kind == 7) Math.scalb(1.0, -29) else Math.scalb(1.0, -24)
        val fine = DoubleArray(nc) { val v = b.s(i, prBits); i += prBits; if (v == -(1L shl (prBits - 1))) Double.NaN else v * prScale }
        i += nc * (if (kind == 7) 24 else 22)                           // fazė
        i += nc * (if (kind == 7) 10 else 4)                            // užrakto laikas
        i += nc                                                         // pusciklis
        val cnBits = if (kind == 7) 10 else 6; val cnScale = if (kind == 7) 1.0 / 16 else 1.0
        val cn = DoubleArray(nc) { val v = b.u(i, cnBits) * cnScale; i += cnBits; v }
        if (i > b.size) return null
        val obs = ArrayList<Obs>()
        for ((k, c) in cells.withIndex()) {
            val (s, g) = c
            if (rInt[s] == 255 || fine[k].isNaN()) continue
            val ms = rInt[s] + rMod[s] + fine[k]
            obs.add(Obs(sys, sats[s], sigCode(sys, sigs[g]), ms * 1e-3 * C, cn[k]))
        }
        return Msm(sys, type, tow, dow, obs)
    }

    fun hex(p: ByteArray) = p.joinToString("") { "%02x".format(it) }
    fun unhex(h: String) = ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) or Character.digit(h[2 * it + 1], 16)).toByte() }

    /** NMEA GGA (VRS tinklui): vieta ir UTC laikas. */
    fun gga(latDeg: Double, lonDeg: Double, hM: Double, utcMs: Long): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
        fun dm(v: Double, w: Int): String {
            val a = kotlin.math.abs(v); val d = a.toInt(); val m = (a - d) * 60.0
            return "%0${w}d%07.4f".format(Locale.US, d, m)
        }
        val body = "GPGGA,%02d%02d%02d.00,%s,%s,%s,%s,1,12,1.0,%.1f,M,0.0,M,,".format(Locale.US,
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND),
            dm(latDeg, 2), if (latDeg >= 0) "N" else "S", dm(lonDeg, 3), if (lonDeg >= 0) "E" else "W", hM)
        var cs = 0; for (ch in body) cs = cs xor ch.code
        return "$$body*%02X\r\n".format(Locale.US, cs)
    }
}

/** gnss_rtcm.csv: kiekviena RTCM žinutė su gavimo laiku (sesijos ZIP ir DGNSS skaičiavimui po įrašo). */
object RtcmRecorder {
    const val FILE = "gnss_rtcm.csv"
    const val HEADER = "laikas_ms,tipas,duomenys_hex\n"
    private var w: java.io.BufferedWriter? = null
    private var dir: File? = null
    @Volatile var written = 0L
        private set

    @Synchronized fun open(d: File, append: Boolean) {
        try { w?.close() } catch (_: Exception) {}
        dir = d
        val f = File(d, FILE)
        val fresh = !append || !f.exists()
        w = try { java.io.BufferedWriter(java.io.FileWriter(f, !fresh)).also { if (fresh) it.write(HEADER) } } catch (e: Exception) { null }
        if (fresh) written = 0
    }

    @Synchronized fun append(d: File, tMs: Long, type: Int, p: ByteArray) {
        if (w == null || dir != d) open(d, true)
        try { w?.write("$tMs,$type,${Rtcm3.hex(p)}\n"); written++; if (written % 50 == 0L) w?.flush() } catch (_: Exception) {}
    }

    @Synchronized fun file(d: File): File { try { w?.flush() } catch (_: Exception) {}; return File(d, FILE).also { if (!it.exists()) it.writeText(HEADER) } }
}
