package lt.example.gnss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    @Test
    fun warmsThenReady() {
        val t0 = 1_000_000L
        Readiness.coldStartMs = 0L
        for (k in 0..70) { val t = t0 + k * 1000L; Readiness.onFix(if (k < 20) 8.0 else 1.5, t); Readiness.onStatus(if (k < 20) 10 + k else 30, t) }
        assertFalse(Readiness.state(false, null, t0 + 40_000).ready)
        for (k in 71..140) { val t = t0 + k * 1000L; Readiness.onFix(1.5, t); Readiness.onStatus(30, t) }
        assertTrue(Readiness.state(false, null, t0 + 140_000).ready)
        assertFalse(Readiness.state(true, 1.2, t0 + 140_000).ready)          // RTK dar nesuartėjęs
        assertTrue(Readiness.state(true, 0.3, t0 + 140_000).ready)
        Readiness.coldStartMs = t0 + 100_000
        assertFalse(Readiness.state(false, null, t0 + 140_000).ready)       // < 5 min po šalto starto
        Readiness.coldStartMs = 0L
    }
}
