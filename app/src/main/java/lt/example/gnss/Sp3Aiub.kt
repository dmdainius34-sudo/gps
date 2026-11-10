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

    /** Failo vardas iš katalogo sąrašo HTML: pirmenybė COD0OPSULT.SP3, kitaip naujausias datuotas; null – nerasta. */
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

    /** Atsisiunčia (kviesti ne UI gijoje): laikinas failas pervadinamas tik visiškai parsiuntus ir perskaičius antraštę. */
    fun download(target: File): Result {
        val listing = try {
            val c = open(BASE)
            if (c.responseCode != 200) return Result.Fail("sąrašas nepasiekiamas (HTTP ${c.responseCode})")
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: UnknownHostException) { return Result.Fail("nėra interneto") }
        catch (e: IOException) { return Result.Fail("nėra interneto (${e.javaClass.simpleName})") }
        val name = pickFromListing(listing) ?: return Result.Fail("failas nerastas AIUB sąraše")
        target.mkdirs()
        val tmp = File(target, "$name.part")
        try {
            val c = open(BASE + name)
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
