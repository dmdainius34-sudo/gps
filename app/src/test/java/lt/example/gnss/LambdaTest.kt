package lt.example.gnss

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LambdaTest {
    @Test
    fun independent() {
        val r = Lambda.solve(doubleArrayOf(2.9, -1.1, 5.05, 0.02), Array(4) { i -> DoubleArray(4) { j -> if (i == j) 0.01 else 0.0 } })
        assertNotNull(r)
        assertArrayEquals(doubleArrayOf(3.0, -1.0, 5.0, 0.0), r!!.best, 1e-9)
        assertTrue(r.ratio > 3.0)
    }

    @Test
    fun correlatedFindsTrueInteger() {
        // stipriai koreliuotos dviprasmybės (tipiška vieno dažnio DD): tikrasis [10, -7, 4]
        val q = arrayOf(doubleArrayOf(6.29, 5.978, 0.544), doubleArrayOf(5.978, 6.292, 2.34), doubleArrayOf(0.544, 2.34, 6.288))
        val truth = doubleArrayOf(10.0, -7.0, 4.0)
        val a = doubleArrayOf(10.0 + 0.05, -7.0 + 0.04, 4.0 - 0.03)
        val r = Lambda.solve(a, q)!!
        assertArrayEquals(truth, r.best, 1e-9)
        assertTrue(r.s2 >= r.s1)
    }
}
