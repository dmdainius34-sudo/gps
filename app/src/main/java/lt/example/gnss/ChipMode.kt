package lt.example.gnss

/**
 * Lusto režimas (ilgai palaikius lusto indikatorių apačioje): rodoma tik paties GNSS lusto vieta (Android GPS fiksas, kaip ją gauna
 * AlpineQuest) – be Vault skaičiavimo ir apribojimų (tik perskaičiuota į ETRS89, kaip Lietuvos žemėlapiai); bandomoji vieta kitoms programoms nebeperduodama,
 * todėl jos gauna tikrą lusto vietą. Žemėlapiai ir planai lieka.
 */
object ChipMode {
    @Volatile var on = false
    @Volatile private var lat = Double.NaN; @Volatile private var lon = Double.NaN
    @Volatile private var acc = Double.NaN; @Volatile private var ms = 0L

    fun update(la: Double, lo: Double, a: Double) { lat = la; lon = lo; acc = a; ms = System.currentTimeMillis() }

    /**
     * (lat, lon, tikslumas) – šviežias ≤ 3 s, arba null. Vienintelis pataisymas – ITRF/WGS84 → ETRS89 (tik koordinačių sistema,
     * ~1 m Lietuvoje), kad lusto vieta sutaptų su Lietuvos žemėlapiais (ortofoto, planai); lusto skaičiavimas nekeičiamas.
     */
    fun pos(): DoubleArray? {
        if (lat.isNaN() || System.currentTimeMillis() - ms > 3_000L) return null
        if (!Etrs.enabled) return doubleArrayOf(lat, lon, acc)
        val e = Etrs.llh(Etrs.yearOf(ms), lat, lon, 0.0)
        return doubleArrayOf(e[0], e[1], acc)
    }
}
