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
 * Galinė kamera kaip taikiklis: be vaizdo rodymo, mažiausia ekspozicija; ryškiausia dėmė (Saulė, Mėnulis) randama kadre,
 * jos kryptis kameros ašies atžvilgiu perskaičiuojama į prietaiso koordinates (natūralioje padėtyje: X dešinėn, Y aukštyn, nugarėlė = −Z).
 */
class SunCam(private val ctx: Context, private val onFrame: (Frame) -> Unit, private val onError: (String) -> Unit) {
    /** found – rasta ryški dėmė; nx, ny – padėtis kadre 0..1; vec – vienetinis vektorius prietaiso (natūraliame) kadre; sat – dėmės plotas, % kadro. */
    class Frame(val found: Boolean, val nx: Double, val ny: Double, val vec: DoubleArray?, val peak: Int, val areaPct: Double, val aspect: Double,
                /** Sumažintas pilkas kadras (neapdoroto jutiklio padėtis) ir jo dydis – kad vartotojas matytų, ką mato kamera. */
                val thumb: IntArray? = null, val tw: Int = 0, val th: Int = 0)

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
                    fun none() { if (!closed) onFrame(Frame(false, 0.0, 0.0, null, max, 0.0, asp, thumb, tw, tht)) }
                    if (max < 200) { none(); return@setOnImageAvailableListener }
                    // ryškiausių taškų jungiosios sritys: imama didžiausia (≥ 3 taškai) – pavieniai „karšti“ taškai ir kitos lempos nesumaišomos
                    val thr = maxOf(200, (max * 0.9).toInt())
                    val lb = lab?.takeIf { it.size == w * hh } ?: IntArray(w * hh).also { lab = it }
                    val q = queue?.takeIf { it.size == w * hh } ?: IntArray(w * hh).also { queue = it }
                    java.util.Arrays.fill(lb, 0)
                    var label = 0; var bestN = 0; var bestSx = 0.0; var bestSy = 0.0; var total = 0
                    for (y in 0 until hh) { val o = y * rs; for (x in 0 until w) {
                        val i = y * w + x
                        if (lb[i] != 0 || (arr[o + x].toInt() and 0xFF) < thr) continue
                        label++; var head = 0; var tail = 0; q[tail++] = i; lb[i] = label
                        var n = 0; var sx = 0.0; var sy = 0.0
                        while (head < tail) {
                            val c = q[head++]; val cx = c % w; val cy = c / w
                            n++; sx += cx; sy += cy
                            for (dy in -1..1) for (dx in -1..1) {
                                val nx = cx + dx; val ny = cy + dy
                                if (nx < 0 || ny < 0 || nx >= w || ny >= hh) continue
                                val j = ny * w + nx
                                if (lb[j] == 0 && (arr[ny * rs + nx].toInt() and 0xFF) >= thr) { lb[j] = label; q[tail++] = j }
                            }
                        }
                        total += n
                        if (n > bestN) { bestN = n; bestSx = sx; bestSy = sy }
                    } }
                    if (bestN < 3) { none(); return@setOnImageAvailableListener }
                    val cx = bestSx / bestN; val cy = bestSy / bestN
                    val tu = (cx + 0.5 - w / 2.0) / fpx; val tv = (cy + 0.5 - hh / 2.0) / fpx
                    val v = DoubleArray(3) { tu * right[it] + tv * down[it] + (if (it == 2) -1.0 else 0.0) }
                    val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                    val f = Frame(true, (cx + 0.5) / w, (cy + 0.5) / hh, DoubleArray(3) { v[it] / l }, max, 100.0 * bestN / (w * hh), asp, thumb, tw, tht)
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
