package lt.example.gnss

/**
 * Lusto režimas (ilgai palaikius lusto indikatorių apačioje): rodoma tik paties GNSS lusto vieta (Android GPS fiksas, kaip ją gauna
 * AlpineQuest) – be Vault skaičiavimo, ETRS89 perskaičiavimo ir apribojimų; bandomoji vieta kitoms programoms nebeperduodama,
 * todėl jos gauna tikrą lusto vietą. Žemėlapiai ir planai lieka.
 */
object ChipMode {
    @Volatile var on = false
    @Volatile private var lat = Double.NaN; @Volatile private var lon = Double.NaN
    @Volatile private var acc = Double.NaN; @Volatile private var ms = 0L

    fun update(la: Double, lo: Double, a: Double) { lat = la; lon = lo; acc = a; ms = System.currentTimeMillis() }

    /** (lat, lon, tikslumas) – šviežias ≤ 3 s, arba null. */
    fun pos(): DoubleArray? = if (lat.isNaN() || System.currentTimeMillis() - ms > 3_000L) null else doubleArrayOf(lat, lon, acc)
}
