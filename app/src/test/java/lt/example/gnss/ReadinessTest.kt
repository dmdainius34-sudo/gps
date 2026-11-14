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
        assertTrue(Readiness.state(true, 1.2, t0 + 140_000).text.contains("RTK dar suartėja"))   // RTK pasiruošimo nestabdo
        assertTrue(Readiness.state(true, 0.3, t0 + 140_000).ready)
        Readiness.coldStartMs = t0 + 100_000
        assertFalse(Readiness.state(false, null, t0 + 140_000).ready)       // < 5 min po šalto starto
        Readiness.coldStartMs = 0L
    }
}

class KnowledgeTest {
    @Test
    fun percentages() {
        val l = (1..10).map { Sat("GPS", it, 35f, 40f, 0f, true, eph = it <= 5, alm = true) } + Sat("GPS", 30, 35f, 5f, 0f, false)
        Readiness.onSats(l)
        Readiness.onMeas((1..10).map { Triple(1000 + it, 35.0, if (it <= 8) 8 else 4) })
        val k = Readiness.knowledge()!!
        org.junit.Assert.assertEquals(50, k.eph); org.junit.Assert.assertEquals(80, k.tow); org.junit.Assert.assertEquals(100, k.alm)
        org.junit.Assert.assertEquals(10, k.n); org.junit.Assert.assertEquals(66, k.total)
    }
}
