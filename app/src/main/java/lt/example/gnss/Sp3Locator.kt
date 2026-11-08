package lt.example.gnss

import java.io.File
import java.io.InputStream

/** SP3 failo paieška vietų eilės tvarka; be Android priklausomybių (tikrinama vienetų testais). */
object Sp3Locator {
    const val NOT_FOUND = "SP3 failo nėra"
    const val FOUND = "found"
    const val ABSENT = "not found"
    const val BLOCKED = "not accessible"

    class Check(val location: String, val status: String)

    /** name – failo vardas; location – pilnas kelias arba URI. */
    class Found(val name: String, val location: String, val open: () -> InputStream)

    class Search(val used: Found?, val checks: List<Check>) {
        fun lines(): List<String> = checks.map { "${it.location} – ${it.status}" }
    }

    private val DATE = Regex("_(\\d{11})_")

    fun matches(name: String) = name.endsWith(".sp3.gz", ignoreCase = true)

    /** Metai + diena + laikas iš vardo (_20263120000_ -> 20263120000); be datos – -1. */
    fun dateKey(name: String): Long = DATE.find(name)?.groupValues?.get(1)?.toLong() ?: -1L

    private val NEWEST = compareBy<File>({ dateKey(it.name) }, { it.name })

    /** Naujausias tinkamas ir skaitomas failas aplanke (arba null). */
    fun newestIn(dir: File): File? = inspect(dir).first

    private fun inspect(dir: File): Pair<File?, String> = try {
        val all = dir.listFiles()
        when {
            all == null -> null to (if (dir.exists()) BLOCKED else ABSENT)
            else -> {
                val hits = all.filter { it.isFile && matches(it.name) }
                val readable = hits.filter { it.canRead() }
                val best = readable.maxWithOrNull(NEWEST)
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
        var used: Found? = null
        for (dir in dirs) {
            val (file, status) = inspect(dir)
            checks.add(Check(dir.path, status))
            if (used == null && file != null) {
                used = Found(file.name, file.absolutePath) { file.inputStream() }
            }
        }
        val (check, found) = picked()
        checks.add(check)
        return Search(used ?: found, checks)
    }
}
