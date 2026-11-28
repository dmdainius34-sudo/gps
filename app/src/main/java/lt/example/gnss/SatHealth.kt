package lt.example.gnss

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Palydovų sveikatos žyma iš efemeridžių: valdymo centras ja pažymi sugedusį, manevruojantį ar bandomą palydovą.
 * Toks palydovas programos padėtyje ir RTK nenaudojamas (3D vaizde – vartosi kūliais, su „užtildymo“ ženkliuku).
 *  - GPS LNAV: 1 subkadris, 3 žodis, 6 bitai (≠ 0 – nesveikas);
 *  - Galileo I/NAV: 5 žodis, E1-B HS (2 b.) ir DVS (1 b.) (≠ 0 – nesveikas / be garantijos);
 *  - BRDC (RINEX): „SV health“ laukas (Galileo – tik E1-B bitai).
 * Žinutės iš paties palydovo svarbesnės už interneto failą; žyma galioja 6 h nuo gavimo.
 */
object SatHealth {
    class H(val bad: Boolean, val code: Int, val src: String, val ms: Long)
    private val m = ConcurrentHashMap<String, H>()
    private const val KEEP_MS = 6 * 3600_000L

    private fun key(sys: Char, prn: Int) = "%c%02d".format(Locale.US, sys, prn)
    private fun sysOf(const: String): Char? = when (const) { "GPS" -> 'G'; "GAL" -> 'E'; "BDS" -> 'C'; "QZS" -> 'J'; "GLO" -> 'R'; else -> null }

    /** Iš paties palydovo žinutės (pirmenybė). */
    fun fromNav(sys: Char, prn: Int, code: Int, src: String) { m[key(sys, prn)] = H(code != 0, code, src, System.currentTimeMillis()) }

    /** Iš interneto failo – tik jei šviežios žinutės iš palydovo nėra. */
    fun fromFile(sys: Char, prn: Int, code: Int, src: String) {
        val k = key(sys, prn); val old = m[k]
        if (old != null && old.src != src && System.currentTimeMillis() - old.ms < KEEP_MS) return
        m[k] = H(code != 0, code, src, System.currentTimeMillis())
    }

    fun get(sys: Char, prn: Int): H? = m[key(sys, prn)]?.takeIf { System.currentTimeMillis() - it.ms < KEEP_MS }
    fun bad(sys: Char, prn: Int): Boolean = get(sys, prn)?.bad == true
    fun bad(const: String, svid: Int): Boolean = sysOf(const)?.let { bad(it, svid) } == true
    fun info(const: String, svid: Int): H? = sysOf(const)?.let { get(it, svid) }

    fun text(): String {
        val b = m.entries.filter { it.value.bad && System.currentTimeMillis() - it.value.ms < KEEP_MS }.sortedBy { it.key }
        val n = m.values.count { System.currentTimeMillis() - it.ms < KEEP_MS }
        return "Palydovų sveikata (žinoma $n): " + if (b.isEmpty()) "visi sveiki" else
            "NESVEIKI – " + b.joinToString(", ") { "${it.key} (kodas ${it.value.code}, ${it.value.src})" } + " – nenaudojami"
    }
}
