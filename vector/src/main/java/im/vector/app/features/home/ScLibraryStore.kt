/*
 * Science Collab: storage of saved papers (private app storage).
 */

package im.vector.app.features.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ScSavedPaper(
        val id: String,
        val title: String,
        val meta: String,
        val fileName: String,
        val savedAt: Long,
)

object ScLibraryStore {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ScienceCollab"

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "sc_library")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(ctx: Context): File = File(dir(ctx), "index.json")

    fun fileOf(ctx: Context, paper: ScSavedPaper): File = File(dir(ctx), paper.fileName)

    @Synchronized
    fun list(ctx: Context): List<ScSavedPaper> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val result = ArrayList<ScSavedPaper>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                result.add(
                        ScSavedPaper(
                                id = o.optString("id"),
                                title = o.optString("title"),
                                meta = o.optString("meta"),
                                fileName = o.optString("file"),
                                savedAt = o.optLong("savedAt"),
                        )
                )
            }
            result.sortedByDescending { it.savedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(ctx: Context, paper: ScSavedPaper) {
        val all = list(ctx).filter { it.id != paper.id } + paper
        write(ctx, all)
    }

    @Synchronized
    fun remove(ctx: Context, paper: ScSavedPaper) {
        fileOf(ctx, paper).delete()
        write(ctx, list(ctx).filter { it.id != paper.id })
    }

    private fun write(ctx: Context, all: List<ScSavedPaper>) {
        val arr = JSONArray()
        for (p in all) {
            arr.put(
                    JSONObject()
                            .put("id", p.id)
                            .put("title", p.title)
                            .put("meta", p.meta)
                            .put("file", p.fileName)
                            .put("savedAt", p.savedAt)
            )
        }
        indexFile(ctx).writeText(arr.toString())
    }

    /** Downloads a PDF into private app storage. Returns null on success, otherwise an error message. */
    fun download(ctx: Context, id: String, url: String, title: String, meta: String): String? {
        val fileName = "p" + Integer.toHexString(id.hashCode()) + ".pdf"
        val dest = File(dir(ctx), fileName)
        val tmp = File(dir(ctx), "$fileName.part")
        try {
            var current = url
            for (hop in 0..6) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/pdf,*/*")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty() || hop >= 6) return "Слишком много перенаправлений."
                    current = URL(URL(current), location).toString()
                } else if (code in 200..299) {
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    conn.disconnect()
                    if (!looksLikePdf(tmp)) {
                        tmp.delete()
                        return "По ссылке лежит не PDF, а веб-страница."
                    }
                    dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.delete()
                        return "Не удалось сохранить файл."
                    }
                    add(ctx, ScSavedPaper(id, title, meta, fileName, System.currentTimeMillis()))
                    return null
                } else {
                    conn.disconnect()
                    return "Издательство не отдало файл (код $code)."
                }
            }
            return "Слишком много перенаправлений."
        } catch (e: Exception) {
            tmp.delete()
            return "Файл не скачался: нет соединения или обрыв."
        }
    }

    private fun looksLikePdf(f: File): Boolean {
        if (f.length() < 1024) return false
        val head = ByteArray(1024)
        val n = f.inputStream().use { it.read(head) }
        if (n < 5) return false
        return String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
    }
}
