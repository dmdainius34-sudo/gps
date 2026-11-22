package lt.example.gnss

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Žemės paviršiaus aukštis (ir jūros gylis) iš „Terrain Tiles“ (AWS atvirieji duomenys, Mapzen „terrarium“ formatas):
 * PNG plytelė, aukštis = R·256 + G + B/256 − 32768 (m). Sausuma – SRTM, EU-DEM (Copernicus) ir kt., jūra – ETOPO1/GEBCO.
 * Leidžiama naudoti ir komerciškai su šaltinių nuoroda (žr. Credits). Lygis 12 (~22 m pikselis Lietuvoje).
 */
object Elev {
    private const val Z = 12
    @Volatile private var cacheKey = ""
    @Volatile private var cachePx: IntArray? = null

    /** Tinklelio (x, y) plytelių vienetais (trupmeniniai). */
    fun tileXY(latDeg: Double, lonDeg: Double, z: Int = Z): DoubleArray {
        val n = (1 shl z).toDouble(); val la = Math.toRadians(latDeg.coerceIn(-85.0, 85.0))
        return doubleArrayOf((lonDeg + 180.0) / 360.0 * n, (1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * n)
    }

    fun decode(argb: Int): Double {
        val r = (argb shr 16) and 255; val g = (argb shr 8) and 255; val b = argb and 255
        return r * 256.0 + g + b / 256.0 - 32768.0
    }

    /** Aukštis virš jūros (m; jūroje – neigiamas) arba null. Tinklo užklausa – kviesti ne pagrindinėje gijoje. */
    fun get(latDeg: Double, lonDeg: Double): Double? {
        val xy = tileXY(latDeg, lonDeg); val tx = floor(xy[0]).toInt(); val ty = floor(xy[1]).toInt()
        val key = "$Z/$tx/$ty"
        var px = if (key == cacheKey) cachePx else null
        if (px == null) {
            val c = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$key.png").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.setRequestProperty("User-Agent", "GnssApp/1.0 (Android)")
            try {
                if (c.responseCode != 200) return null
                val bmp = android.graphics.BitmapFactory.decodeStream(c.inputStream) ?: return null
                val a = IntArray(bmp.width * bmp.height); bmp.getPixels(a, 0, bmp.width, 0, 0, bmp.width, bmp.height); bmp.recycle()
                px = a; cachePx = a; cacheKey = key
            } finally { c.disconnect() }
        }
        val size = kotlin.math.sqrt(px.size.toDouble()).toInt()
        val fx = (xy[0] - tx) * size - 0.5; val fy = (xy[1] - ty) * size - 0.5
        val x0 = floor(fx).toInt().coerceIn(0, size - 1); val y0 = floor(fy).toInt().coerceIn(0, size - 1)
        val x1 = (x0 + 1).coerceAtMost(size - 1); val y1 = (y0 + 1).coerceAtMost(size - 1)
        val ax = (fx - x0).coerceIn(0.0, 1.0); val ay = (fy - y0).coerceIn(0.0, 1.0)
        fun h(x: Int, y: Int) = decode(px[y * size + x])
        return (h(x0, y0) * (1 - ax) + h(x1, y0) * ax) * (1 - ay) + (h(x0, y1) * (1 - ax) + h(x1, y1) * ax) * ay
    }
}
