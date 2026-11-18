package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

class GalaxyModelTest {
    @Test fun axesMatchIauGalacticFrame() {
        val f = GalaxyModel.frame()
        fun radec(i: Int): Pair<Double, Double> =
            ((Math.toDegrees(atan2(f[i + 1], f[i])) + 360) % 360) to Math.toDegrees(asin(f[i + 2]))
        val (xr, xd) = radec(0); val (yr, yd) = radec(3)
        assertEquals(266.405, xr, 0.05); assertEquals(-28.936, xd, 0.05)   // l = 0°, b = 0°
        assertEquals(318.004, yr, 0.05); assertEquals(48.330, yd, 0.05)    // l = 90°, b = 0°
    }
}

class GalaxyPointsTest {
    @Test fun sunSitsBetweenSagittariusAndPerseus() {
        org.junit.Assert.assertTrue(GalaxyModel.ARM_R[1] < GalaxyModel.R_SUN && GalaxyModel.R_SUN < GalaxyModel.ARM_R[2])
        val p = GalaxyModel.points(5000)
        for (i in 0 until 5000) {
            val r = kotlin.math.sqrt(p[i * 7] * p[i * 7] + p[i * 7 + 1] * p[i * 7 + 1])
            org.junit.Assert.assertTrue("taškas $i už galaktikos ribų: $r kpc", r.isFinite() && r < 25f)
        }
    }
}

class NearStarsTest {
    @Test fun nearerStarsCollapseFirst() {
        val l = NearStars.list
        val st = NearStars.starts(l)
        val start = l.indices.associate { l[it].name to st[it] }
        assertEquals(0f, start["Alpha Centauri"]!!, 1e-6f)
        assertEquals(NearStars.START_MAX, start["Deneb"]!!, 1e-6f)
        org.junit.Assert.assertTrue(start["Sirius"]!! < start["Polaris"]!! && start["Polaris"]!! < start["Rigel"]!!)
        // be α Kentauro (nematoma iš Lietuvos) pirmasis – Sirijus
        val north = l.filter { it.name != "Alpha Centauri" }
        assertEquals(0f, NearStars.starts(north)[north.indexOfFirst { it.name == "Sirius" }], 1e-6f)
        for (s in l) assertEquals(1.0, kotlin.math.sqrt((s.dir[0] * s.dir[0] + s.dir[1] * s.dir[1] + s.dir[2] * s.dir[2]).toDouble()), 1e-5)
    }
}

class RealAccTest {
    @Test fun openSkyIsGoodOneSidedWeakIsBad() {
        val az = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
        val el = doubleArrayOf(30.0, 60.0, 25.0, 50.0, 35.0, 70.0, 20.0, 45.0)
        val open = RealAcc.horizontal(az, el, DoubleArray(8) { 42.0 })
        org.junit.Assert.assertTrue("atviras dangus $open m", open < 3.0)
        val waz = doubleArrayOf(200.0, 230.0, 250.0, 270.0, 290.0, 310.0)
        val wel = doubleArrayOf(30.0, 60.0, 40.0, 75.0, 25.0, 50.0)
        val roof = RealAcc.horizontal(waz, wel, DoubleArray(6) { 26.0 })
        org.junit.Assert.assertTrue("po stogu iš vakarų $roof m", roof > 10.0)
    }
}

class CourseHeadingTest {
    @Test fun learnsMountThenCorrectsDrift() {
        CourseHeading.mountDeg = Double.NaN
        var t = 1_000_000L
        // ką tik kalibruota: planšetė pasukta 30° į kairę nuo važiavimo krypties (kursas 100°, rodoma 70°)
        repeat(40) { CourseHeading.onMove(t, 100.0, 70.0, t - 1000) { }; t += 1000 }
        assertEquals(30.0, CourseHeading.mountDeg, 0.5)
        // vėliau giroskopas nudrifavo +8°: rodoma 78°, tikra 70°
        var fix = Double.NaN
        repeat(40) { CourseHeading.onMove(t, 100.0, 78.0, null) { fix = it }; t += 1000 }
        assertEquals(8.0, fix, 0.5)
    }
}

class PosFilterTest {
    @Test fun stationaryAveragingSettlesAndRejectsJumps() {
        PosFilter.reset()
        val rnd = java.util.Random(1)
        var out = DoubleArray(4)
        for (i in 0 until 300) {
            val jump = if (i % 25 == 0) 0.0005 else 0.0          // retkarčiais ~55 m šuolis
            out = PosFilter.step(1_000_000L + i * 1000L, 54.7 + rnd.nextGaussian() * 0.00006 + jump, 25.3 + rnd.nextGaussian() * 0.0001, 200.0, 10.0, false)
        }
        assertEquals(54.7, out[0], 0.00003)                     // ~3 m
        assertEquals(25.3, out[1], 0.00005)
        org.junit.Assert.assertTrue(out[3] >= 5.0)               // ne mažiau nei pusė realaus
    }
}

class GlonassTimeTest {
    @Test fun pseudorangeFromGlonassTimeOfDay() {
        // gavimo laikas: GPS savaitės 100 000 s; GLONASS paros laikas = 100 000 − 18 + 10 800 (mod 86 400); signalas skrido 70 ms
        val week = 2400L; val weekNs = 604_800_000_000_000L
        val tn = 1_000_000_000L; val fb = -(week * weekNs + 100_000_000_000_000L) + tn
        val tod = ((100_000.0 - 18.0 + 10_800.0) % 86_400.0) * 1e9
        val svt = (tod - 70e6).toLong()
        val r = Sp3V5.pseudorangeGlo(tn, fb, 0.0, 0.0, Sp3V5.STATE_GLO_TOD_DECODED, svt)!!
        assertEquals(0.070 * 299_792_458.0, r.first, 1.0)
        assertEquals(100_000.0, r.third, 1e-6)
    }
}

class SourcetableTest {
    @Test fun parsesMountsAndDetectsBeidou() {
        val t = "SOURCETABLE 200 OK\r\n" +
            "STR;VRS_RTCM32;VRS;RTCM 3.2;1005(10),1075(1),1085(1),1095(1);2;GPS+GLO+GAL;NET;LTU;54.70;25.30;1;1;sNTRIP;none;B;N;0;\r\n" +
            "STR;RTX_RTCM34;RTX;RTCM 3.4;1005(10),1077(1),1087(1),1097(1),1127(1);2;GPS+GLO+GAL+BDS;NET;LTU;54.70;25.30;1;1;x;none;B;N;0;\r\n" +
            "ENDSOURCETABLE\r\n"
        val m = NtripClient.parseSourcetable(t)
        assertEquals(2, m.size)
        assertEquals("VRS_RTCM32", m[0].name); org.junit.Assert.assertFalse(m[0].hasBds)
        assertEquals("RTCM 3.4", m[1].format); org.junit.Assert.assertTrue(m[1].hasBds)
    }
}

class MagWatchTest {
    @Test fun detectsCleanStrongAndShiftedField() {
        var t = 0L
        repeat(20) { MagWatch.onUncal(t, floatArrayOf(20f, 0f, -47f, 0f, 0f, 0f)); t += 200 }    // ~51 µT
        org.junit.Assert.assertFalse(MagWatch.why, MagWatch.disturbed)
        repeat(30) { MagWatch.onUncal(t, floatArrayOf(60f, 0f, -47f, 0f, 0f, 0f)); t += 200 }    // ~76 µT – metalas
        org.junit.Assert.assertTrue(MagWatch.disturbed)
    }
}

class DualFreqTest {
    @org.junit.Test fun ionoFromL1L5() {
        DualFreq.reset()
        // jonosfera L1 = 5 m -> L5 vėluoja γ kartų daugiau; imtuvo poslinkis 0
        val i1 = 5.0; val p1 = 2.2e7; val p5 = p1 - i1 + DualFreq.GAMMA * i1
        org.junit.Assert.assertEquals(i1, DualFreq.raw(p1, p5), 1e-6)
        var got: Double? = null
        for (k in 0 until 12) {
            val m = Sp3V5.M('E', 7, p1, 40.0, 8, 0, null, 0, null).apply { pr5 = p5 }
            got = DualFreq.iono(m, 1000.0 + k)
            if (k < DualFreq.MIN_N - 1) org.junit.Assert.assertNull(got)
        }
        org.junit.Assert.assertEquals(i1, got!!, 1e-6)
    }

    @org.junit.Test fun broadcastCoefficients() {
        val a = doubleArrayOf(2.0e-8, 1.5e-8, -6.0e-8, -6.0e-8); val b = doubleArrayOf(1.2e5, 1.6e5, -2.0e5, -2.6e5)
        val d0 = Sp3Solver.klobuchar(0.95, 0.44, 1.0, 0.6, 43200.0)
        Sp3Solver.setIono(a, b)
        org.junit.Assert.assertTrue(Sp3Solver.ionoBroadcast)
        val d1 = Sp3Solver.klobuchar(0.95, 0.44, 1.0, 0.6, 43200.0)
        Sp3Solver.setIono(null, null)
        org.junit.Assert.assertFalse(Sp3Solver.ionoBroadcast)
        org.junit.Assert.assertEquals(d0, Sp3Solver.klobuchar(0.95, 0.44, 1.0, 0.6, 43200.0), 1e-12)
        org.junit.Assert.assertNotEquals(d0, d1, 1e-6)
    }
}

class IonoSf4Test {
    @org.junit.Test fun page18() {
        fun w(b1: Int, b2: Int, b3: Int) = ((b1 and 0xFF) shl 16) or ((b2 and 0xFF) shl 8) or (b3 and 0xFF)
        // α0 = 12·2^-30, α1 = 1·2^-27, α2 = −4·2^-24, α3 = −1·2^-24, β0 = 50·2^11, β1 = 4·2^14, β2 = −4·2^16, β3 = −2·2^16
        val words = intArrayOf(0x8B0000, 0, w(56, 12, 1), w(-4, -1, 50), w(4, -4, -2), 0, 0, 0, 0, 0)
        org.junit.Assert.assertTrue(Sp3Solver.ionoFromGpsSf4(words))
        org.junit.Assert.assertEquals("GPS palydovas", Sp3Solver.ionoSource)
        Sp3Solver.setIono(null, null, "GPS palydovas")
        org.junit.Assert.assertFalse(Sp3Solver.ionoFromGpsSf4(words.copyOf().also { it[2] = w(18, 12, 1) }))
    }
}

class MoonShapeTest {
    /** Pjautuvas: disko taškai už „šešėlio“ disko, pasislinkusio nuo Saulės; iškiloji pusė – į Saulę (sx, sy). */
    private fun crescent(w: Int, h: Int, cx: Double, cy: Double, r: Double, shift: Double, sx: Double, sy: Double): IntArray =
        IntArray(w * h) { i -> val x = i % w + 0.5; val y = i / w + 0.5
            val inA = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
            val bx = cx - sx * shift; val by = cy - sy * shift
            val inB = (x - bx) * (x - bx) + (y - by) * (y - by) <= r * r
            if (inA && !inB) 220 else 60 }

    @org.junit.Test fun crescentTowardSun() {
        val w = 120; val h = 120; val d = 28.0
        val img = crescent(w, h, 60.0, 60.0, d / 2, 0.35 * d, 1.0, 0.0)         // Saulė dešinėje (apšviesta ~44 %)
        val k = 0.2
        val ok = MoonShape.check({ x, y -> img[y * w + x] }, w, h, 66.0, 60.0, MoonShape.Expect(d, k, 1.0, 0.0))
        org.junit.Assert.assertTrue(ok.why, ok.ok)
        val bad = MoonShape.check({ x, y -> img[y * w + x] }, w, h, 66.0, 60.0, MoonShape.Expect(d, k, -1.0, 0.0))
        org.junit.Assert.assertFalse(bad.why, bad.ok)
        val tooBig = MoonShape.check({ x, y -> img[y * w + x] }, w, h, 66.0, 60.0, MoonShape.Expect(d / 3, k, 1.0, 0.0))
        org.junit.Assert.assertFalse(tooBig.why, tooBig.ok)
    }

    @org.junit.Test fun phase() {
        org.junit.Assert.assertEquals(0.5, MoonShape.illuminated(Math.PI / 2), 1e-9)
        org.junit.Assert.assertEquals(1.0, MoonShape.illuminated(Math.PI), 1e-9)
        // Saulė tiesiai virš Mėnulio (zenito kryptimi) – bazėje „aukštyn“ (1, 0)
        val m = doubleArrayOf(0.0, 1.0, 0.0); val s = doubleArrayOf(0.0, 0.0, 1.0)
        val (a, c) = MoonShape.sunInUpBasis(m, s)
        org.junit.Assert.assertEquals(1.0, a, 1e-9); org.junit.Assert.assertEquals(0.0, c, 1e-9)
    }
}

class MoonSizeTest {
    @org.junit.Test fun diameterAndFlattening() {
        val ms = 1_794_830_000_000L
        val d = SunCal.moonDiamDeg(ms, 54.69, 25.28)
        org.junit.Assert.assertTrue("skersmuo $d", d in 0.48..0.57)
        org.junit.Assert.assertTrue(SunCal.refractionFlattening(45.0) > 0.995)
        org.junit.Assert.assertTrue(SunCal.refractionFlattening(1.0) < 0.95)
    }
}

/** Žinomas aukštis programos padėtyje: 3 palydovai + aukštis duoda tikslią vietą, be aukščio – nesprendžiama. */
class KnownHeightSppTest {
    private fun sat(xT: DoubleArray, lat: Double, lon: Double, azD: Double, elD: Double, svid: Int, clk: Double): Sp3V5.SatEp {
        val az = Math.toRadians(azD); val el = Math.toRadians(elD)
        val sf = kotlin.math.sin(lat); val cf = kotlin.math.cos(lat); val sl = kotlin.math.sin(lon); val cl = kotlin.math.cos(lon)
        val e = doubleArrayOf(-sl, cl, 0.0); val n = doubleArrayOf(-sf * cl, -sf * sl, cf); val u = doubleArrayOf(cf * cl, cf * sl, sf)
        val dir = DoubleArray(3) { kotlin.math.cos(el) * kotlin.math.sin(az) * e[it] + kotlin.math.cos(el) * kotlin.math.cos(az) * n[it] + kotlin.math.sin(el) * u[it] }
        val range = 21_000_000.0
        val pos = DoubleArray(3) { xT[it] + dir[it] * range }
        val probe = Sp3V5.SatEp('G', svid, pos, DoubleArray(3), 0.0, range / 299_792_458.0, 45.0, 0.0, null)
        repeat(4) { probe.rotate(xT) }
        val rp = probe.rp
        val rho = kotlin.math.sqrt((0..2).sumOf { (xT[it] - rp[it]) * (xT[it] - rp[it]) })
        val l = Sp3Solver.look(xT, rp, rho)
        val prc = rho + clk + Sp3Solver.saastamoinen(l[0], l[2], l[4])
        return Sp3V5.SatEp('G', svid, pos, DoubleArray(3), prc, range / 299_792_458.0, 45.0, 0.0, null).also { it.ifree = true }
    }

    @Test fun threeSatsWithHeight() {
        val latD = 54.70; val lonD = 25.30; val h = 150.0
        val lat = Math.toRadians(latD); val lon = Math.toRadians(lonD)
        val xT = PositionSolver.llhToEcef(lat, lon, h)
        val sats = listOf(sat(xT, lat, lon, 10.0, 60.0, 1, 1000.0), sat(xT, lat, lon, 130.0, 40.0, 2, 1000.0), sat(xT, lat, lon, 250.0, 35.0, 3, 1000.0))
        assertEquals(null, Sp3V5.solveSats(100.0, sats, isb = true, raim = true))
        val x0 = PositionSolver.llhToEcef(Math.toRadians(latD + 0.002), Math.toRadians(lonD - 0.002), h)
        val f = Sp3V5.solveSats(100.0, sats, isb = true, raim = true, hCon = Sp3V5.HCon(h, 0.3, x0))!!
        assertEquals(0.0, PositionSolver.horizDist(latD, lonD, f.sol.lat, f.sol.lon), 0.05)
        assertEquals(h, f.sol.h, 0.05)
        assertEquals(3, f.sol.sats)
    }
}

/** Automatinis signalo slenkstis: atvirame danguje kyla, kai stiprių daug; kai jų mažai – nusileidžia, kad padėtis išliktų. */
class CnoAutoTest {
    private fun sky(cn0: (Int) -> Double, n: Int, sys: String = "GPS") =
        (0 until n).map { CnoMask.S(sys, cn0(it), 20.0 + (it * 37) % 60, (it * 360.0 / n)) }

    @Test fun openSkyRaises() {
        val s = sky({ 42.0 }, 10) + sky({ 25.0 }, 6, "GAL")
        assertEquals(40.0, CnoMask.target(s), 0.0)
    }

    @Test fun fewStrongStaysLow() {
        // 3 stiprūs ir 6 vidutiniai: aukštas slenkstis paliktų tik 3 – padėties nebūtų
        val s = sky({ if (it < 3) 42.0 else 30.0 }, 9)
        val t = CnoMask.target(s)
        assert(t <= 30.0) { "slenkstis $t" }
    }

    @Test fun dropsAtOnceRisesSlowly() {
        CnoMask.auto = true; CnoMask.min = 20.0
        val strong = sky({ 42.0 }, 10)
        repeat(19) { CnoMask.autoStep(strong) }
        assertEquals(20.0, CnoMask.min, 0.0)          // dar nekyla – laukiama 20 s
        CnoMask.autoStep(strong)
        assertEquals(21.0, CnoMask.min, 0.0)          // po 1 dB
        repeat(4) { CnoMask.autoStep(strong) }
        assertEquals(21.0, CnoMask.min, 0.0)          // toliau kas 5 s
        CnoMask.autoStep(strong)
        assertEquals(22.0, CnoMask.min, 0.0)
        CnoMask.autoStep(sky({ if (it < 3) 42.0 else 18.0 }, 10))
        assert(CnoMask.min <= 18.0) { "turėjo nukristi iš karto: ${CnoMask.min}" }
        CnoMask.min = 20.0
    }

    @Test fun holdsWhileStillGood() {
        // signalai svyruoja aplink slenkstį, bet padėtis gera – slenkstis nešokinėja
        CnoMask.auto = true; CnoMask.min = 29.0
        repeat(40) { i -> CnoMask.autoStep(sky({ k -> if (k < 8) 30.0 + ((i + k) % 3) else 26.0 + (i % 4) }, 12)) }
        assertEquals(29.0, CnoMask.min, 0.0)
        CnoMask.min = 20.0
    }
}

/** Glodinimas pagal Doplerį: triukšmingas kodas (±10 m) + tikslus Doppler – po minutės triukšmas kelis kartus mažesnis, judėjimas nevėluoja. */
class DopplerSmoothTest {
    @Test fun reducesNoise() {
        val sm = Sp3V5.Smoother(doppler = true, useAdr = false)
        val rnd = java.util.Random(7)
        val v = -420.0                                   // palydovas artėja 420 m/s
        var rawSq = 0.0; var smSq = 0.0; var n = 0
        for (k in 0 until 180) {
            val t = 1000.0 + k
            val truth = 22_000_000.0 + v * k
            val noise = rnd.nextGaussian() * 10.0
            val m = Sp3V5.M('G', 5, truth + noise, 30.0, Sp3V5.STATE_TOW_DECODED, 0, null, 0, v + rnd.nextGaussian() * 0.05)
            val out = sm.apply(m, t)
            if (k >= 60) { rawSq += noise * noise; smSq += (out - truth) * (out - truth); n++ }
        }
        val rawRms = kotlin.math.sqrt(rawSq / n); val smRms = kotlin.math.sqrt(smSq / n)
        assert(smRms < rawRms / 2.5) { "glodinta %.2f m, neglodinta %.2f m".format(smRms, rawRms) }
    }

    @Test fun restartsOnJump() {
        val sm = Sp3V5.Smoother(doppler = true, useAdr = false)
        for (k in 0 until 30) sm.apply(Sp3V5.M('G', 5, 22_000_000.0, 30.0, Sp3V5.STATE_TOW_DECODED, 0, null, 0, 0.0), 1000.0 + k)
        // laikrodžio šuolis 300 km – kodas šoka, Doppler ne: glodinimas turi prasidėti iš naujo ir grąžinti naują kodą
        val out = sm.apply(Sp3V5.M('G', 5, 22_300_000.0, 30.0, Sp3V5.STATE_TOW_DECODED, 0, null, 0, 0.0), 1030.0)
        assertEquals(22_300_000.0, out, 1e-6)
    }
}

/** Patarimas planšetei: išmoksta, kad kita kryptis geresnė, ir pasiūlo pasukti; kitaip – rodo stipriausių signalų pusę. */
class TurnHintTest {
    private fun sats(base: Double) = (0 until 8).map { TurnHint.S(it * 45.0, 40.0, base + (it % 2), true) }

    @Test fun suggestsBetterHeading() {
        TurnHint.reset()
        var t = 0L
        repeat(20) { TurnHint.learn(t, 100.0, sats(36.0)); t += 1000 }   // atsukus į 100° – stipru
        repeat(10) { TurnHint.learn(t, 10.0, sats(28.0)); t += 1000 }    // dabar 10° – silpna
        val h = TurnHint.advice(t, 10.0, sats(28.0))
        assert(h is TurnHint.Hint.Turn) { "turėjo pasiūlyti pasukti: $h" }
        assertEquals(95.0, (h as TurnHint.Hint.Turn).deg, 1.0)
    }

    @Test fun pointsToStrongSide() {
        TurnHint.reset()
        val s = (0 until 16).map { TurnHint.S((it / 2) * 45.0 + 5.0 + (it % 2) * 20.0, 40.0, if (it / 2 == 2) 38.0 else 24.0, true) }
        TurnHint.learn(0L, 0.0, s)
        val h = TurnHint.advice(1000L, 0.0, s)
        assert(h is TurnHint.Hint.Toward) { "turėjo rodyti stipriausią pusę: $h" }
        assertEquals(112.5, (h as TurnHint.Hint.Toward).relDeg, 0.1)
    }

    @Test fun showsOnlyAfterTenSecondsBlue() {
        TurnHint.reset()
        val t0 = 1_000_000L
        assertEquals(false, TurnHint.quality(t0, 25f))
        assertEquals(false, TurnHint.quality(t0 + 9_000L, 25f))
        assertEquals(true, TurnHint.quality(t0 + 10_000L, 25f))
        assertEquals(true, TurnHint.quality(t0 + 12_000L, 35f))
        assertEquals(false, TurnHint.quality(t0 + 17_000L, 35f))
    }
}

/** Visos epochos glodinimas: bendras laikrodžio greitis (kode yra, Doppleryje – ne) nesikaupia skirtingai kiekvienam palydovui. */
class DopplerEpochSmoothTest {
    @Test fun commonClockRateDoesNotLeak() {
        val sm = Sp3V5.Smoother(doppler = true, useAdr = false, dopWindow = 20)
        val rnd = java.util.Random(3)
        val v = doubleArrayOf(-400.0, -150.0, 60.0, 220.0, 380.0, -30.0)
        var errSq = 0.0; var n = 0
        for (k in 0 until 120) {
            val t = 500.0 + k
            val clk = 1.2 * k                                     // imtuvo laikrodžio dreifas 1,2 m/s – tik kode
            // palydovai pradedami skirtingu metu (kaip tikrovėje – skirtingi glodinimo ilgiai)
            val ms = v.indices.filter { k >= it * 15 }.map { i ->
                Sp3V5.M('G', i + 1, 21_000_000.0 + i * 1e5 + v[i] * k + clk + rnd.nextGaussian() * 5.0, 35.0,
                    Sp3V5.STATE_TOW_DECODED, 0, null, 0, v[i] + rnd.nextGaussian() * 0.05)
            }
            val out = sm.applyEpoch(ms, t)
            if (k >= 100) {
                // klaida be bendros dalies (bendra – laikrodis, ją sugeria sprendinys)
                val e = ms.map { m -> out.getValue(m.key) - (21_000_000.0 + (m.svid - 1) * 1e5 + v[m.svid - 1] * k + clk) }
                val mean = e.average()
                e.forEach { errSq += (it - mean) * (it - mean); n++ }
            }
        }
        val rms = kotlin.math.sqrt(errSq / n)
        assert(rms < 2.5) { "palydovų tarpusavio klaida %.2f m".format(rms) }
    }
}
