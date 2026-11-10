package lt.example.gnss

import java.io.File
import java.io.InputStream

/** SP3 failo paieška vietų eilės tvarka; be Android priklausomybių (tikrinama vienetų testais). */
object Sp3Locator {
    const val NOT_FOUND = "SP3 failo nėra"
    const val FOUND = "found"
    const val ABSENT = "not found"
    const val BLOCKED = "not accessible"

    /** location – aplanko kelias arba URI; file – jame rastas naujausias failas. */
    class Check(val location: String, val status: String, val file: String? = null)

    /** name – failo vardas; location – pilnas kelias arba URI. */
    class Found(val name: String, val location: String, val open: () -> InputStream)

    /** used – pirmas rastas paieškos tvarka; all – po vieną naujausią iš kiekvienos vietos (parinkimui pagal laiką). */
    class Search(val used: Found?, val checks: List<Check>, val all: List<Found> = listOfNotNull(used)) {
        fun lines(): List<String> = checks.map { c ->
            "${c.location} – ${c.status}" + (c.file?.let { ": $it" } ?: "")
        }
    }

    private val DATE = Regex("_(\\d{11})_")

    fun matches(name: String) = name.endsWith(".sp3.gz", ignoreCase = true) || name.endsWith(".sp3", ignoreCase = true)

    /** Metai + diena + laikas iš vardo (_20263120000_ -> 20263120000); be datos – -1. */
    fun dateKey(name: String): Long = DATE.find(name)?.groupValues?.get(1)?.toLong() ?: -1L

    /** Naujausias tinkamo vardo elementas (aplanko failai arba SAF eilutės). */
    fun <T> newest(items: List<T>, name: (T) -> String): T? =
        items.filter { matches(name(it)) }.maxWithOrNull(compareBy<T>({ dateKey(name(it)) }, { name(it) }))

    /** Naujausias tinkamas ir skaitomas failas aplanke (arba null). */
    fun newestIn(dir: File): File? = inspect(dir).first

    private fun inspect(dir: File): Pair<File?, String> = try {
        val all = dir.listFiles()
        when {
            all == null -> null to (if (dir.exists()) BLOCKED else ABSENT)
            else -> {
                val hits = all.filter { it.isFile && matches(it.name) }
                val best = newest(hits.filter { it.canRead() }) { it.name }
                best to when {
                    best != null -> FOUND
                    hits.isNotEmpty() -> BLOCKED
                    else -> ABSENT
                }
            }
        }
    } catch (e: SecurityException) {
        null to BLOCKED
    }

    /** Patikrina visas vietas; naudojama pirma vieta, kurioje failas rastas. */
    fun search(dirs: List<File>, picked: () -> Pair<Check, Found?>): Search {
        val checks = ArrayList<Check>()
        val all = ArrayList<Found>()
        for (dir in dirs) {
            val (file, status) = inspect(dir)
            checks.add(Check(dir.path, status, file?.name))
            if (file != null) all.add(Found(file.name, file.absolutePath) { file.inputStream() })
        }
        val (check, found) = picked()
        checks.add(check)
        found?.let { all.add(it) }
        return Search(all.firstOrNull(), checks, all)
    }

    /**
     * Parinkimas pagal įrašo laiką: iš failų, kurių intervalas apima visą įrašą, imamas naujausias (vėliausia pradžia);
     * jei tokių nėra – pirmas paieškos tvarka. range – failo intervalas iš antraštės (null – neperskaitytas).
     */
    fun chooseByRange(all: List<Found>, rec: Pair<Double, Double>?, range: (Found) -> Pair<Double, Double>?): Pair<Found?, Pair<Double, Double>?> {
        if (all.isEmpty()) return null to null
        val ranges = all.map { it to try { range(it) } catch (e: Exception) { null } }
        if (rec != null) {
            val cover = ranges.filter { (_, r) -> r != null && Sp3Reader.coverage(r.first, r.second, rec.first, rec.second) == Sp3Reader.COVERS }
            cover.maxByOrNull { it.second!!.first }?.let { return it }
        }
        return ranges.first()
    }
}
