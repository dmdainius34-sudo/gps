package lt.example.gnss

import android.location.GnssNavigationMessage
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Navigacijos pranešimų įrašymas į gnss_nav.csv (bendras veiklai ir NavRecordService).
 * Kiekvienas pranešimas įrašomas ir iškart nuplaunamas; failas laikomas iki dalinimosi.
 */
object NavRecorder {
    const val FILE = "gnss_nav.csv"
    const val HEADER = "laikas_ms,konstelacija,svid,tipas,statusas,duomenys_hex\n"

    @Volatile var received = 0L
        private set
    @Volatile var written = 0L
        private set
    private var rows = 0L
    private var writer: BufferedWriter? = null

    /** Konsteliacija pagal getType() vyresnįjį baitą. */
    fun navConst(type: Int): String = when ((type shr 8) and 0xFF) {
        0x01 -> "GPS"; 0x02 -> "SBS"; 0x03 -> "GLO"; 0x04 -> "QZS"; 0x05 -> "BDS"; 0x06 -> "GAL"; 0x07 -> "NVC"
        else -> "?"
    }

    @Synchronized
    private fun reset(dir: File) {
        try { writer?.close() } catch (_: Exception) {}
        rows = 0
        writer = try {
            BufferedWriter(FileWriter(File(dir, FILE), false), 1 shl 14).also { it.write(HEADER) }
        } catch (e: Exception) { Log.e("GnssApp", "Navigacijos failas nepavyko: ${e.message}"); null }
    }

    @Synchronized
    fun append(dir: File, n: GnssNavigationMessage) {
        received++
        if (writer == null || rows > 400_000) reset(dir)
        val w = writer ?: return
        val data = n.data ?: ByteArray(0)
        val hex = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            hex.append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0xF, 16))
        }
        try {
            w.write("${System.currentTimeMillis()},${navConst(n.type)},${n.svid},${n.type},${n.status},$hex\n")
            w.flush()
            rows++
            written++
            if (rows == 1L) Log.i("GnssApp", "gnss_nav.csv: gautas pirmas navigacijos pranešimas (${navConst(n.type)} ${n.svid})")
        } catch (e: Exception) {
            Log.e("GnssApp", "Navigacijos įrašas nepavyko: ${e.message}")
        }
    }

    /** Nuplauna buferį ir grąžina failą (sukuria su antrašte, jei dar nėra). */
    @Synchronized
    fun file(dir: File): File {
        try { writer?.flush() } catch (_: Exception) {}
        val f = File(dir, FILE)
        if (!f.exists()) f.writeText(HEADER)
        return f
    }
}
