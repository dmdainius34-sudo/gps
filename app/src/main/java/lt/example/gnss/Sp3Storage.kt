package lt.example.gnss

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** SP3 vietos įrenginyje: Download, programos aplankas, vieną kartą per SAF pasirinktas failas. */
object Sp3Storage {
    private const val PREFS = "sp3_source"
    private const val KEY_URI = "picked_uri"
    private const val KEY_OFFERED = "picker_offered"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun search(ctx: Context): Sp3Locator.Search {
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val own = ctx.getExternalFilesDir(null)
            ?: File(Environment.getExternalStorageDirectory(), "Android/data/${ctx.packageName}/files")
        return Sp3Locator.search(listOf(download, own)) { picked(ctx) }
    }

    private fun picked(ctx: Context): Pair<Sp3Locator.Check, Sp3Locator.Found?> {
        val saved = prefs(ctx).getString(KEY_URI, null)
            ?: return Sp3Locator.Check("SAF (failas nepasirinktas)", Sp3Locator.ABSENT) to null
        val uri = Uri.parse(saved)
        return try {
            val resolver = ctx.contentResolver
            val input = resolver.openInputStream(uri) ?: throw SecurityException("nėra srauto")
            input.close()
            val found = Sp3Locator.Found(displayName(ctx, uri), saved) {
                resolver.openInputStream(uri) ?: throw FileNotFoundException(saved)
            }
            Sp3Locator.Check(saved, Sp3Locator.FOUND) to found
        } catch (e: FileNotFoundException) {
            Sp3Locator.Check(saved, Sp3Locator.ABSENT) to null
        } catch (e: Exception) {
            Sp3Locator.Check(saved, Sp3Locator.BLOCKED) to null
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /** Išsaugo pasirinktą failą su ilgalaikiu skaitymo leidimu; netinkamas vardas – false. */
    fun savePick(ctx: Context, uri: Uri): Boolean {
        val ok = try {
            Sp3Locator.matches(displayName(ctx, uri))
        } catch (e: Exception) {
            false
        }
        if (!ok) return false
        return try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs(ctx).edit().putString(KEY_URI, uri.toString()).apply()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun pickerOffered(ctx: Context) = prefs(ctx).getBoolean(KEY_OFFERED, false)

    fun markPickerOffered(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_OFFERED, true).apply()
}
