package lt.example.gnss

/** Signalų ir unikalių palydovų (konsteliacija, svid) skaičiavimas – GnssStatus pateikia signalus, ne palydovus. */
object SatCount {
    class Sig(val const: String, val svid: Int, val used: Boolean)

    /** perConst: konsteliacija -> [palydovų matoma, palydovų naudojama, signalų matoma, signalų naudojama]. */
    class Result(val sigVisible: Int, val sigUsed: Int, val satVisible: Int, val satUsed: Int, val perConst: Map<String, IntArray>)

    fun count(list: List<Sig>): Result {
        val sats = HashMap<Pair<String, Int>, Boolean>()
        val per = java.util.TreeMap<String, IntArray>()
        for (s in list) {
            val k = s.const to s.svid
            sats[k] = (sats[k] ?: false) || s.used
            val a = per.getOrPut(s.const) { IntArray(4) }
            a[2]++; if (s.used) a[3]++
        }
        for ((k, used) in sats) { val a = per[k.first]!!; a[0]++; if (used) a[1]++ }
        return Result(list.size, list.count { it.used }, sats.size, sats.values.count { it }, per)
    }
}

/** Sausumos ir vandens kaukė (earth_ocean.png R kanalas: 0 – sausuma, > 0 – vandenyno gylis), lygiakampė. */
object OceanMask {
    fun isWater(red: (x: Int, y: Int) -> Int, w: Int, h: Int, latDeg: Double, lonDeg: Double): Boolean {
        val x = (((lonDeg + 180.0) / 360.0 * w).toInt() % w + w) % w
        val y = ((90.0 - latDeg) / 180.0 * h).toInt().coerceIn(0, h - 1)
        return red(x, y) > 0
    }
}
