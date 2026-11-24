package lt.example.gnss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File

/**
 * AlpineQuest PGD (kalibruotas vaizdas, pvz. statybvietės planas): failo viduje – 256 px WEBP plytelių piramidė
 * (ketvirtainis medis, plytelės išdėstytos „po vaikų“: kairė viršus, dešinė viršus, kairė apačia, dešinė apačia, tada tėvas)
 * ir 4 kampų koordinatės (ilguma, platuma: kairė viršus, dešinė viršus, dešinė apačia, kairė apačia).
 * Atidarytas planas išsaugomas programos aplanke ir piešiamas ant Žemės gaublio pagal kampų koordinates.
 */
object PgdOverlay {
    class Parsed(val w: Int, val h: Int, val corners: Array<DoubleArray>, val tiles: List<IntRange>, val name: String)

    /** Kampai (lat, lon): 0 – kairė viršus, 1 – dešinė viršus, 2 – dešinė apačia, 3 – kairė apačia. */
    @Volatile var corners: Array<DoubleArray>? = null; private set
    @Volatile var bitmap: Bitmap? = null; private set
    @Volatile var version = 0; private set
    @Volatile var name = ""; private set
    @Volatile var visible = true
    /** Tamsus (naktinis) režimas kaip AlpineQuest: šviesumas apverčiamas, spalvos (atspalviai) išlieka. */
    @Volatile var dark = false
    /** Apversto šviesumo filtras: inversija + atspalvio pasukimas 180° (raudona lieka raudona, balta tampa juoda). */
    val darkFilter: android.graphics.ColorMatrixColorFilter by lazy {
        val inv = android.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        val hue = android.graphics.ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        hue.preConcat(inv)
        android.graphics.ColorMatrixColorFilter(hue)
    }
    /** Plano kampai ekrane (x, y × 4: kairė viršus, dešinė viršus, dešinė apačia, kairė apačia), kai piešiama sluoksniu; null – ne. */
    @Volatile var screenCorners: FloatArray? = null
    /** Dabartinė padėtis (lat, lon, tikslumas m) – iš fiksų; ir jos vieta ekrane (x, y, tikslumo spindulys px) sluoksnyje arti žemės. */
    @Volatile var me: DoubleArray? = null
    @Volatile var meScreen: FloatArray? = null

    private const val FILE = "overlay.pgd"

    private fun ascii(b: ByteArray, s: String, from: Int = 0): Int {
        val p = s.toByteArray(Charsets.US_ASCII)
        outer@ for (i in from..b.size - p.size) { for (j in p.indices) if (b[i + j] != p[j]) continue@outer; return i }
        return -1
    }
    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    /** Skaitinė savybė: raktas, 0xFFFFFFFE, 8 baitų skaičius (BE). */
    private fun num(b: ByteArray, key: String): Long? {
        val p = ascii(b, key); if (p < 0) return null
        val o = p + key.length
        if (o + 12 > b.size || be32(b, o) != -2) return null
        return (be32(b, o + 4).toLong() shl 32) or (be32(b, o + 8).toLong() and 0xFFFFFFFFL)
    }

    fun parse(b: ByteArray, name: String = ""): Parsed? {
        if (b.size < 16 || ascii(b, "PGD") != 0) return null
        val w = num(b, "image_width_px")?.toInt() ?: return null
        val h = num(b, "image_height_px")?.toInt() ?: return null
        val co = ascii(b, "PGD_CO"); if (co < 0) return null
        val len = be32(b, co + 6); if (len <= 0 || co + 10 + len > b.size) return null
        val pts = String(b, co + 10, len, Charsets.US_ASCII).trim().split(Regex("\\s+")).mapNotNull { p ->
            val q = p.split(","); if (q.size < 2) null else { val lon = q[0].toDoubleOrNull(); val lat = q[1].toDoubleOrNull()
                if (lon == null || lat == null) null else doubleArrayOf(lat, lon) } }
        if (pts.size < 4) return null
        val tiles = ArrayList<IntRange>()
        var i = 0
        while (true) {
            val p = ascii(b, "RIFF", i); if (p < 0 || p + 12 > b.size) break
            if (b[p + 8] == 'W'.code.toByte() && b[p + 9] == 'E'.code.toByte() && b[p + 10] == 'B'.code.toByte() && b[p + 11] == 'P'.code.toByte()) {
                val n = le32(b, p + 4) + 8
                if (n <= 8 || p + n > b.size) break
                tiles.add(p until p + n); i = p + n
            } else i = p + 4
        }
        if (tiles.isEmpty()) return null
        return Parsed(w, h, arrayOf(pts[0], pts[1], pts[2], pts[3]), tiles, name)
    }

    /** Plytelių išdėstymas: (lygis – kiek pilnos raiškos plytelių šonu dengia mazgas, x, y) → plytelės nr. */
    fun layout(w: Int, h: Int): List<Triple<Int, Int, Int>> {
        val n = maxOf((w + 255) / 256, (h + 255) / 256)
        var s = 1; while (s < n) s *= 2
        val out = ArrayList<Triple<Int, Int, Int>>()
        fun rec(x: Int, y: Int, sz: Int) {
            if (x.toLong() * sz * 256 >= w || y.toLong() * sz * 256 >= h) return
            if (sz > 1) for ((dx, dy) in arrayOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) rec(x * 2 + dx, y * 2 + dy, sz / 2)
            out.add(Triple(sz, x, y))
        }
        rec(0, 0, s)
        return out
    }

    /** Sujungia vaizdą iš plytelių: lygis parenkamas, kad šonas ≤ maxPx; grąžinama kvadratinė (2ⁿ) tekstūra. */
    fun compose(b: ByteArray, p: Parsed, maxPx: Int = 2048): Bitmap? {
        val lay = layout(p.w, p.h)
        if (lay.size != p.tiles.size) return null
        var sz = 1; while ((p.w + sz - 1) / sz > maxPx || (p.h + sz - 1) / sz > maxPx) sz *= 2
        val bw = (p.w + sz - 1) / sz; val bh = (p.h + sz - 1) / sz
        val img = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(img)
        for ((k, t) in lay.withIndex()) {
            if (t.first != sz) continue
            val r = p.tiles[k]
            val tb = BitmapFactory.decodeByteArray(b, r.first, r.last - r.first + 1) ?: continue
            c.drawBitmap(tb, t.second * 256f, t.third * 256f, null); tb.recycle()
        }
        var pot = 256; while (pot < maxOf(bw, bh) && pot < maxPx) pot *= 2
        val out = Bitmap.createScaledBitmap(img, pot, pot, true)
        if (out !== img) img.recycle()
        return out
    }

    @Volatile private var rawBytes: ByteArray? = null
    @Volatile private var rawParsed: Parsed? = null
    @Volatile private var full: Bitmap? = null
    /** Plokščiai peržiūrai – pilnos raiškos vaizdas (iki 8192 px šono), tikrų proporcijų; kviesti fone. */
    fun fullImage(): Bitmap? {
        full?.let { if (!it.isRecycled) return it }
        val b = rawBytes ?: return null; val p = rawParsed ?: return null
        val lay = layout(p.w, p.h); if (lay.size != p.tiles.size) return null
        var sz = 1; while ((p.w + sz - 1) / sz > 8192 || (p.h + sz - 1) / sz > 8192) sz *= 2
        val img = Bitmap.createBitmap((p.w + sz - 1) / sz, (p.h + sz - 1) / sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(img)
        for ((k, t) in lay.withIndex()) {
            if (t.first != sz) continue
            val r = p.tiles[k]
            val tb = BitmapFactory.decodeByteArray(b, r.first, r.last - r.first + 1) ?: continue
            c.drawBitmap(tb, t.second * 256f, t.third * 256f, null); tb.recycle()
        }
        full = img
        return img
    }

    /**
     * Taško (lat, lon) vieta plane: (u, v) 0..1 nuo kairio viršutinio kampo (už plano – už 0..1 ribų) arba null.
     * Afininė pagal tris kampus vietinėje metrinėje sistemoje (planas mažas – iškraipymas nereikšmingas).
     */
    fun uvOf(lat: Double, lon: Double): DoubleArray? {
        val c = corners ?: return null
        val k = 111_320.0 * kotlin.math.cos(Math.toRadians(c[0][0]))
        fun xy(p: DoubleArray) = doubleArrayOf((p[1] - c[0][1]) * k, (p[0] - c[0][0]) * 111_320.0)
        val a = xy(c[1]); val b = xy(c[3]); val q = xy(doubleArrayOf(lat, lon))
        val det = a[0] * b[1] - a[1] * b[0]; if (kotlin.math.abs(det) < 1e-9) return null
        return doubleArrayOf((q[0] * b[1] - q[1] * b[0]) / det, (a[0] * q[1] - a[1] * q[0]) / det)
    }

    /** Plano plotis ir aukštis metrais (pagal kampus). */
    fun sizeM(): DoubleArray? {
        val c = corners ?: return null
        val k = 111_320.0 * kotlin.math.cos(Math.toRadians(c[0][0]))
        fun d(p: DoubleArray, q: DoubleArray) = kotlin.math.hypot((q[1] - p[1]) * k, (q[0] - p[0]) * 111_320.0)
        return doubleArrayOf(d(c[0], c[1]), d(c[0], c[3]))
    }

    /** Įkelia failą (kviesti fone); ok – išsaugo kaip dabartinį planą. Grąžina klaidos tekstą arba null. */
    fun load(ctx: Context, bytes: ByteArray, srcName: String, save: Boolean = true): String? {
        val p = parse(bytes, srcName) ?: return "ne PGD failas arba nerasta kampų koordinačių"
        val bmp = compose(bytes, p) ?: return "nepavyko sudėti plytelių (${p.tiles.size} plytelių, laukta ${layout(p.w, p.h).size})"
        if (save) try { File(ctx.filesDir, FILE).writeBytes(bytes); ctx.getSharedPreferences("pgd", Context.MODE_PRIVATE).edit().putString("name", srcName).apply() } catch (_: Exception) {}
        full?.recycle(); full = null; rawBytes = bytes; rawParsed = p
        bitmap = bmp; corners = p.corners; name = srcName; visible = true; version++
        return null
    }

    /** Paleidus – anksčiau atidarytas planas. */
    fun restore(ctx: Context) {
        val f = File(ctx.filesDir, FILE); if (!f.exists()) return
        try { load(ctx, f.readBytes(), ctx.getSharedPreferences("pgd", Context.MODE_PRIVATE).getString("name", "") ?: "", save = false) } catch (_: Exception) {}
    }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {}
        full?.recycle(); full = null; rawBytes = null; rawParsed = null
        bitmap = null; corners = null; name = ""; version++
    }

    /** Plano centras (lat, lon) arba null. */
    fun centre(): DoubleArray? = corners?.let { c -> doubleArrayOf(c.sumOf { it[0] } / 4, c.sumOf { it[1] } / 4) }
}
