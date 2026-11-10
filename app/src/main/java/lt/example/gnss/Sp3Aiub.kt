package lt.example.gnss

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * CODE ultra-greitų orbitų atsisiuntimas iš AIUB (anonimiškai, be prisijungimo):
 * https://www.aiub.unibe.ch/download/CODE/ – sąraše ieškoma COD0OPSULT.SP3, kitaip naujausio COD0OPSULT_*_ORB.SP3(.gz).
 */
object Sp3Aiub {
    const val BASE = "https://www.aiub.unibe.ch/download/CODE/"
    private const val SUBDIR = "sp3_aiub"

    private val UNDATED = Regex("COD0OPSULT\\.SP3(\\.gz)?", RegexOption.IGNORE_CASE)
    private val DATED = Regex("COD0OPSULT_\\d{11}_\\d{2}[A-Z]_\\d{2}[A-Z]_ORB\\.SP3(\\.gz)?", RegexOption.IGNORE_CASE)

    /** Programos privatus aplankas atsisiųstam failui (be leidimų). */
    fun dir(ctx: Context): File = File(ctx.filesDir, SUBDIR)

    /** Failo vardas iš katalogo sąrašo (HTML arba S3 XML „<Key>CODE/…</Key>“): pirmenybė COD0OPSULT.SP3, kitaip naujausias datuotas. */
    fun pickFromListing(html: String): String? {
        val names = Regex("[A-Za-z0-9_.]+").findAll(html).map { it.value }.toSet()
        val undated = names.filter { UNDATED.matches(it) }
        undated.firstOrNull { !it.endsWith(".gz", true) }?.let { return it }
        undated.firstOrNull()?.let { return it }
        return names.filter { DATED.matches(it) }
            .maxWithOrNull(compareBy<String>({ Sp3Locator.dateKey(it) }, { if (it.endsWith(".gz", true)) 0 else 1 }))
    }

    sealed class Result {
        class Ok(val name: String, val file: File, val start: Double, val end: Double) : Result()
        class Fail(val message: String) : Result()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 60_000
        setRequestProperty("User-Agent", "GnssApp")
        instanceFollowRedirects = true
    }

    /** Sąrašo adresai: AIUB katalogas (HTML) ir, jei jis peradresuoja į S3 saugyklą, jos XML sąrašas su prefiksu. */
    fun listingUrls(finalUrl: String?): List<String> {
        val out = arrayListOf(BASE)
        if (finalUrl != null && finalUrl != BASE) {
            // pvz. https://host/aiub/CODE/ -> https://host/aiub/?prefix=CODE/COD0OPSULT (S3 ListObjects)
            val m = Regex("^(https?://[^/]+/[^/]+/)(.*/)$").find(finalUrl)
            if (m != null) out.add("${m.groupValues[1]}?prefix=${m.groupValues[2]}COD0OPSULT&max-keys=1000")
        }
        return out
    }

    private class Fetched(val code: Int, val finalUrl: String, val body: String)

    /** GET su peradresavimų sekimu (ir į kitą pagrindinį vardą); grąžina kodą, galutinį URL ir tekstą. */
    private fun fetchText(url: String): Fetched {
        var u = url
        repeat(5) {
            val c = open(u)
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return Fetched(code, u, "")
                u = URL(URL(u), loc).toString()
            } else {
                val body = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                return Fetched(code, u, body)
            }
        }
        return Fetched(310, u, "")
    }

    /** Atsisiunčia (kviesti ne UI gijoje): laikinas failas pervadinamas tik visiškai parsiuntus ir perskaičius antraštę. */
    fun download(target: File): Result {
        var name: String? = null
        var fileBase = BASE
        try {
            val first = fetchText(BASE)
            val urls = listingUrls(first.finalUrl)
            val bodies = ArrayList<String>()
            if (first.code == 200) bodies.add(first.body)
            for (u in urls.drop(1)) { val f = fetchText(u); if (f.code == 200) bodies.add(f.body) }
            name = bodies.firstNotNullOfOrNull { pickFromListing(it) }
            if (name == null) {
                // Sąrašo nėra – bandomas tiesioginis nedatuotas failas
                for (cand in listOf("COD0OPSULT.SP3", "COD0OPSULT.SP3.gz")) {
                    val c = open(BASE + cand); c.requestMethod = "HEAD"
                    if (c.responseCode == 200) { name = cand; break }
                }
            }
            if (first.finalUrl != BASE && first.finalUrl.endsWith("/")) fileBase = first.finalUrl
        } catch (e: UnknownHostException) { return Result.Fail("nėra interneto") }
        catch (e: IOException) { return Result.Fail("nėra interneto (${e.javaClass.simpleName})") }
        if (name == null) return Result.Fail("failas nerastas AIUB sąraše")
        target.mkdirs()
        val tmp = File(target, "$name.part")
        try {
            var c = open(BASE + name)
            if (c.responseCode != 200 && fileBase != BASE) c = open(fileBase + name)
            if (c.responseCode != 200) return Result.Fail("failas nerastas (HTTP ${c.responseCode})")
            c.inputStream.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
        } catch (e: IOException) {
            tmp.delete(); return Result.Fail("atsisiuntimas nutrūko (${e.javaClass.simpleName})")
        }
        val range = try {
            val r = tmp.inputStream().use { Sp3Reader.read(it) }
            val h = tmp.inputStream().use { Sp3Reader.headerRange(it) }
            if (r.epochCount == 0 || h == null) null else r.epochs.first() to r.epochs.last()
        } catch (e: Exception) { null }
        if (range == null) { tmp.delete(); return Result.Fail("SP3 nuskaitymo klaida") }
        val dst = File(target, name)
        target.listFiles()?.forEach { if (it != tmp) it.delete() }        // laikomas tik naujausias
        if (!tmp.renameTo(dst)) { tmp.delete(); return Result.Fail("nepavyko įrašyti failo") }
        return Result.Ok(name, dst, range.first, range.second)
    }

    /** Būsenos eilutė: „SP3: nuo … iki …“ (UTC) arba „SP3: nėra“. */
    fun status(ctx: Context): String {
        val f = dir(ctx).listFiles()?.filter { Sp3Locator.matches(it.name) }?.maxByOrNull { it.lastModified() }
            ?: return "SP3: nėra"
        val r = try { f.inputStream().use { Sp3Reader.headerRange(it) } } catch (e: Exception) { null } ?: return "SP3: nėra"
        return "SP3: nuo ${Sp3Reader.utc(r.first)} iki ${Sp3Reader.utc(r.second)} UTC"
    }
}
