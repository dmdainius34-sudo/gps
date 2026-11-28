package lt.example.gnss

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Šiaurė iš GNSS kurso judant (metalas ir laidai jo neveikia). Kursas − rodoma kryptis = planšetės pasukimas judėjimo
 * krypties atžvilgiu (mount) + krypties klaida. Kai kryptis ką tik patikrinta (Saulė, žvaigždė, signalai) – išmokstamas mount;
 * kitu metu, kai mount žinomas (arba laikomas 0 – planšetė žiūri judėjimo kryptimi), klaida taisoma giroskopo poslinkiu.
 */
object CourseHeading {
    const val MIN_SPEED = 1.5           // m/s
    private const val TRUST_WINDOW = 10 * 60_000L
    @Volatile var mountDeg = Double.NaN
    private val hist = ArrayDeque<Pair<Long, Double>>()
    private var lastMs = 0L; private var lastCourse = Double.NaN
    @Volatile var lastFixMs = 0L
        private set

    fun wrap(d: Double) = ((d + 540.0) % 360.0) - 180.0

    /** Grąžina true, jei mount atnaujintas (išsaugoti). apply(d): d = rodoma − tikra kryptis (nudgeNorth konvencija). */
    @Synchronized fun onMove(ms: Long, course: Double, shown: Double, trustedSince: Long?, apply: (Double) -> Unit): Boolean {
        val turning = !lastCourse.isNaN() && kotlin.math.abs(wrap(course - lastCourse)) > 10.0
        if (ms - lastMs > 10_000L) hist.clear()
        lastMs = ms; lastCourse = course
        if (turning) return false
        val diff = wrap(course - shown)
        if (trustedSince != null && ms - trustedSince < TRUST_WINDOW) {
            mountDeg = if (mountDeg.isNaN()) diff else wrap(mountDeg + 0.1 * wrap(diff - mountDeg))
            hist.clear(); return true
        }
        val m = if (mountDeg.isNaN()) 0.0 else mountDeg
        hist.addLast(ms to wrap(shown - (course - m)))
        while (hist.isNotEmpty() && ms - hist.first().first > 60_000L) hist.removeFirst()
        if (hist.size < 15 || ms - lastFixMs < 30_000L) return false
        val sx = hist.sumOf { sin(Math.toRadians(it.second)) }; val cx = hist.sumOf { cos(Math.toRadians(it.second)) }
        val rl = sqrt(sx * sx + cx * cx) / hist.size
        val mean = Math.toDegrees(atan2(sx, cx))
        if (rl < 0.996 || kotlin.math.abs(mean) < 3.0) return false           // 0,996 ≈ sklaida 5°
        lastFixMs = ms; hist.clear()
        apply(mean)
        return false
    }
}
