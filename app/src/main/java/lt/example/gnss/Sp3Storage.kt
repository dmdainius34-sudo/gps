package lt.example.gnss

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException

/** SP3 vietos įrenginyje: Download, programos aplankas, vieną kartą per SAF pasirinktas aplankas. */
object Sp3Storage {
    private const val PREFS = "sp3_source"
    private const val KEY_TREE = "picked_tree"
    private const val KEY_OFFERED = "tree_picker_offered"
    private const val DOCS_AUTHORITY = "com.android.externalstorage.documents"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun search(ctx: Context): Sp3Locator.Search {
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val own = ctx.getExternalFilesDir(null)
            ?: File(Environment.getExternalStorageDirectory(), "Android/data/${ctx.packageName}/files")
        return Sp3Locator.search(listOf(download, own)) { pickedFolder(ctx) }
    }

    private fun pickedFolder(ctx: Context): Pair<Sp3Locator.Check, Sp3Locator.Found?> {
        val saved = prefs(ctx).getString(KEY_TREE, null)
            ?: return Sp3Locator.Check("SAF aplankas (nepasirinktas)", Sp3Locator.ABSENT) to null
        val tree = Uri.parse(saved)
        return try {
            val resolver = ctx.contentResolver
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val rows = ArrayList<Pair<String, String>>()
            val cursor = resolver.query(children, columns, null, null, null) ?: throw FileNotFoundException(saved)
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)
                    if (id != null && name != null && c.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) {
                        rows.add(id to name)
                    }
                }
            }
            val best = Sp3Locator.newest(rows) { it.second }
            if (best == null) {
                Sp3Locator.Check(saved, Sp3Locator.ABSENT) to null
            } else {
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, best.first)
                val found = Sp3Locator.Found(best.second, doc.toString()) {
                    resolver.openInputStream(doc) ?: throw FileNotFoundException(doc.toString())
                }
                Sp3Locator.Check(saved, Sp3Locator.FOUND, best.second) to found
            }
        } catch (e: FileNotFoundException) {
            Sp3Locator.Check(saved, Sp3Locator.ABSENT) to null
        } catch (e: Exception) {
            Sp3Locator.Check(saved, Sp3Locator.BLOCKED) to null
        }
    }

    /** Rinkiklis atsidaro ties Download. */
    fun initialUri(): Uri = DocumentsContract.buildDocumentUri(DOCS_AUTHORITY, "primary:Download")

    /** Išsaugo aplanką su ilgalaikiu skaitymo leidimu; senas leidimas atlaisvinamas. */
    fun savePick(ctx: Context, tree: Uri): Boolean {
        val resolver = ctx.contentResolver
        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(tree, flag)
        } catch (e: SecurityException) {
            return false
        }
        val old = prefs(ctx).getString(KEY_TREE, null)
        prefs(ctx).edit().putString(KEY_TREE, tree.toString()).apply()
        if (old != null && old != tree.toString()) {
            try {
                resolver.releasePersistableUriPermission(Uri.parse(old), flag)
            } catch (e: SecurityException) {
                // Senas leidimas jau nebegalioja
            }
        }
        return true
    }

    fun pickerOffered(ctx: Context) = prefs(ctx).getBoolean(KEY_OFFERED, false)

    fun markPickerOffered(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_OFFERED, true).apply()
}
