package lt.example.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Galinė kamera kaip taikiklis: mažiausia ekspozicija; Saulė ar Mėnulis randami kaip kontrastingiausia dėmė tinkamame aukštyje (ir dieną tarp debesų),
 * jos kryptis kameros ašies atžvilgiu perskaičiuojama į prietaiso koordinates (natūralioje padėtyje: X dešinėn, Y aukštyn, nugarėlė = −Z).
 */
class SunCam(private val ctx: Context, private val onFrame: (Frame) -> Unit, private val onError: (String) -> Unit) {
    /** found – rasta ryški dėmė; nx, ny – padėtis kadre 0..1; vec – vienetinis vektorius prietaiso (natūraliame) kadre; sat – dėmės plotas, % kadro. */
    class Frame(val found: Boolean, val nx: Double, val ny: Double, val vec: DoubleArray?, val peak: Int, val areaPct: Double, val aspect: Double,
                /** Sumažintas pilkas kadras (neapdoroto jutiklio padėtis) ir jo dydis – kad vartotojas matytų, ką mato kamera. */
                val thumb: IntArray? = null, val tw: Int = 0, val th: Int = 0,
                /** Visi kontrastingi kandidatai ir padidinta (64×64) sritis aplink pasirinktą. */
                val cands: List<Cand> = emptyList(), val crop: IntArray? = null)
    /** Kontrastinga dėmė: padėtis kadre 0..1, kryptis prietaiso kadre, plotas (taškais), kontrasto suma, didžiausias ryškumas. */
    class Cand(val nx: Double, val ny: Double, val vec: DoubleArray, val n: Int, val strength: Double, val peak: Int)

    /** Kandidato atranka (pvz. pagal aukštį – Mėnulio/Saulės aukštis žinomas tiksliai); null – visi tinka. */
    @Volatile var accept: ((DoubleArray) -> Boolean)? = null

    private var thread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var closed = false
    var info = ""
        private set
    /** Jutiklio orientacija (°) – kadrui pasukti į ekrano padėtį. */
    var orient = 90
        private set
    private var lab: IntArray? = null
    private var queue: IntArray? = null

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: return onError("Galinės kameros nėra")
            val ch = cm.getCameraCharacteristics(id)
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val pix = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
            val act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return onError("Nėra židinio nuotolio")
            val orient = (ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90).also { this.orient = it }
            val aeRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val effW = phys.width.toDouble() * act.width() / pix.width
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val aspect = act.width().toDouble() / act.height()
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width in 400..1100 }
            val size = sizes.filter { abs(it.width.toDouble() / it.height - aspect) < 0.02 }.maxByOrNull { it.width }
                ?: sizes.maxByOrNull { it.width } ?: return onError("Nėra tinkamo kadro dydžio")
            val fpx = focal.toDouble() * size.width / effW
            // sensoriaus ašys prietaiso (natūralios padėties) koordinatėse: „dešinė“ ir „žemyn“ neapdorotame kadre
            val right: DoubleArray; val down: DoubleArray
            when (orient) {
                0 -> { right = doubleArrayOf(1.0, 0.0, 0.0); down = doubleArrayOf(0.0, -1.0, 0.0) }
                180 -> { right = doubleArrayOf(-1.0, 0.0, 0.0); down = doubleArrayOf(0.0, 1.0, 0.0) }
                270 -> { right = doubleArrayOf(0.0, 1.0, 0.0); down = doubleArrayOf(1.0, 0.0, 0.0) }
                else -> { right = doubleArrayOf(0.0, -1.0, 0.0); down = doubleArrayOf(-1.0, 0.0, 0.0) }
            }
            info = "kamera $id, ${size.width}×${size.height}, f=%.1f mm, regos laukas %.0f°, orientacija $orient°".format(
                java.util.Locale.US, focal, Math.toDegrees(2 * Math.atan(effW / (2.0 * focal))))
            val th = HandlerThread("suncam").also { it.start() }; thread = th
            val h = Handler(th.looper)
            val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2); reader = rd
            rd.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val pl = img.planes[0]; val w = img.width; val hh = img.height; val rs = pl.rowStride
                    val buf = pl.buffer; val arr = ByteArray(buf.remaining()); buf.get(arr)
                    var max = 0
                    for (y in 0 until hh step 2) { val o = y * rs; for (x in 0 until w step 2) { val v = arr[o + x].toInt() and 0xFF; if (v > max) max = v } }
                    // sumažintas kadras rodymui (~160 px pločio)
                    val st = maxOf(1, w / 160); val tw = w / st; val tht = hh / st
                    val thumb = IntArray(tw * tht) { i -> val g = arr[(i / tw) * st * rs + (i % tw) * st].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    val asp = size.width.toDouble() / size.height
                    // Kontrastas, ne absoliutus ryškumas: dieną Mėnulis ne šviesesnis už debesis, bet šviesesnis už aplinką.
                    // Pusinė raiška (2×2 vidurkis), fonas – 25×25 langas (integralinis vaizdas), kontrastas = taškas − fonas.
                    val w2 = w / 2; val h2 = hh / 2
                    val y2 = IntArray(w2 * h2) { i -> val x = (i % w2) * 2; val y = (i / w2) * 2
                        ((arr[y * rs + x].toInt() and 0xFF) + (arr[y * rs + x + 1].toInt() and 0xFF) + (arr[(y + 1) * rs + x].toInt() and 0xFF) + (arr[(y + 1) * rs + x + 1].toInt() and 0xFF)) / 4 }
                    val ii = LongArray((w2 + 1) * (h2 + 1))
                    for (y in 0 until h2) { var row = 0L; for (x in 0 until w2) { row += y2[y * w2 + x]; ii[(y + 1) * (w2 + 1) + x + 1] = ii[y * (w2 + 1) + x + 1] + row } }
                    val rad = 12
                    val con = IntArray(w2 * h2)
                    for (y in 0 until h2) for (x in 0 until w2) {
                        val xa = maxOf(0, x - rad); val xb = minOf(w2, x + rad + 1); val ya = maxOf(0, y - rad); val yb = minOf(h2, y + rad + 1)
                        val sum = ii[yb * (w2 + 1) + xb] - ii[ya * (w2 + 1) + xb] - ii[yb * (w2 + 1) + xa] + ii[ya * (w2 + 1) + xa]
                        con[y * w2 + x] = y2[y * w2 + x] - (sum / ((xb - xa) * (yb - ya))).toInt()
                    }
                    // triukšmo lygis: kontrasto modulio mediana (atsitiktinė 4000 taškų imtis)
                    val smp = IntArray(4000) { kotlin.math.abs(con[(it * 7919) % con.size]) }; smp.sort()
                    val thr = maxOf(10, 6 * maxOf(1, smp[smp.size / 2]))
                    val lb = lab?.takeIf { it.size == w2 * h2 } ?: IntArray(w2 * h2).also { lab = it }
                    val q = queue?.takeIf { it.size == w2 * h2 } ?: IntArray(w2 * h2).also { queue = it }
                    java.util.Arrays.fill(lb, 0)
                    var label = 0
                    val cands = ArrayList<Cand>()
                    for (i0 in 0 until w2 * h2) {
                        if (lb[i0] != 0 || con[i0] < thr) continue
                        label++; var head = 0; var tail = 0; q[tail++] = i0; lb[i0] = label
                        var n = 0; var sx = 0.0; var sy = 0.0; var str = 0L; var pk = 0
                        while (head < tail) {
                            val c = q[head++]; val cx = c % w2; val cy = c / w2
                            n++; sx += cx; sy += cy; str += con[c]; if (y2[c] > pk) pk = y2[c]
                            for (dy in -1..1) for (dx in -1..1) {
                                val nx = cx + dx; val ny = cy + dy
                                if (nx < 0 || ny < 0 || nx >= w2 || ny >= h2) continue
                                val j = ny * w2 + nx
                                if (lb[j] == 0 && con[j] >= thr) { lb[j] = label; if (tail < q.size) q[tail++] = j }
                            }
                        }
                        if (n < 2 || n > 3000) continue            // pavieniai karšti taškai ir dideli debesų kraštai – ne
                        val cx = (sx / n) * 2 + 1.0; val cy = (sy / n) * 2 + 1.0
                        val tu = (cx - w / 2.0) / fpx; val tv = (cy - hh / 2.0) / fpx
                        val v = DoubleArray(3) { tu * right[it] + tv * down[it] + (if (it == 2) -1.0 else 0.0) }
                        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                        cands.add(Cand(cx / w, cy / hh, DoubleArray(3) { v[it] / l }, n * 4, str.toDouble(), pk))
                    }
                    // pasirinkimas: tinkamo aukščio (accept) ir stipriausio kontrasto dėmė
                    val ok = cands.filter { c -> accept?.invoke(c.vec) ?: true }.sortedByDescending { it.strength }
                    val best = ok.firstOrNull()
                    // padidinta sritis aplink pasirinktą dėmę (64×64 taškai) – kad būtų matyti, kas rasta
                    var crop: IntArray? = null
                    if (best != null) {
                        val bx = (best.nx * w).toInt(); val by = (best.ny * hh).toInt()
                        crop = IntArray(64 * 64) { i -> val x = (bx - 32 + i % 64).coerceIn(0, w - 1); val y = (by - 32 + i / 64).coerceIn(0, hh - 1)
                            val g = arr[y * rs + x].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    }
                    val f = if (best == null) Frame(false, 0.0, 0.0, null, max, 0.0, asp, thumb, tw, tht, cands)
                        else Frame(true, best.nx, best.ny, best.vec, best.peak, 100.0 * best.n / (w * hh), asp, thumb, tw, tht, cands, crop)
                    if (!closed) onFrame(f)
                } finally { img.close() }
            }, h)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camera = c
                    try {
                        @Suppress("DEPRECATION")
                        c.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val rq = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(rd.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        // tamsiausias vaizdas: Saulė išsiskiria, jutiklis neperkaista
                                        aeRange?.let { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it.lower) }
                                    }
                                    s.setRepeatingRequest(rq.build(), null, h)
                                } catch (e: Exception) { onError("Kameros užklausa: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { onError("Kameros sesija nepavyko") }
                        }, h)
                    } catch (e: Exception) { onError("Kameros sesija: ${e.message}") }
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, error: Int) { c.close(); this@SunCam.onError("Kamera nepasiekiama (kodas $error)") }
            }, h)
        } catch (e: Exception) { onError("Kamera: ${e.message}") }
    }

    fun stop() {
        closed = true
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        session = null; camera = null; reader = null; thread = null
    }
}
