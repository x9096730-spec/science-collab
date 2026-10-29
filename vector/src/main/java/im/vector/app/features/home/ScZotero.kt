/*
 * Science Collab: Zotero Web API client (library, collections, groups, adding items, PDF attachments).
 */

package im.vector.app.features.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

class ScZoteroException(message: String) : Exception(message)

class ScZoteroCreds(val key: String, val userId: Long, val username: String, val canWrite: Boolean)

class ScZLibrary(val prefix: String, val name: String)

class ScZCollection(val key: String, val name: String, val count: Int)

class ScZItem(
        val key: String,
        val itemType: String,
        val title: String,
        val authors: String,
        val year: String,
        val publication: String,
        val doi: String,
        val url: String,
        val contentType: String,
        val linkMode: String,
)

class ScZPage(val items: List<ScZItem>, val total: Long)

private fun JSONObject.zStr(name: String): String = if (isNull(name)) "" else optString(name, "")

object ScZotero {

    const val PAGE = 50

    private const val BASE = "https://api.zotero.org/"
    private const val PREFS = "sc_zotero"
    private const val UA = "ScienceCollab/1.0 (Android)"

    private class Resp(val code: Int, val body: String, val total: Long)

    /* ------------------------------------------------------------------------------------------
     * Stored connection (private app storage)
     * ---------------------------------------------------------------------------------------- */

    fun creds(ctx: Context): ScZoteroCreds? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = p.getString("key", "").orEmpty()
        val uid = p.getLong("userId", 0L)
        if (key.isEmpty() || uid == 0L) return null
        return ScZoteroCreds(key, uid, p.getString("username", "").orEmpty(), p.getBoolean("canWrite", false))
    }

    fun save(ctx: Context, c: ScZoteroCreds) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("key", c.key)
                .putLong("userId", c.userId)
                .putString("username", c.username)
                .putBoolean("canWrite", c.canWrite)
                .apply()
    }

    fun disconnect(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /* ------------------------------------------------------------------------------------------
     * HTTP
     * ---------------------------------------------------------------------------------------- */

    private fun call(method: String, path: String, key: String, body: String?): Resp {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Zotero-API-Key", key)
            conn.setRequestProperty("Zotero-API-Version", "3")
            conn.setRequestProperty("User-Agent", UA)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Zotero-Write-Token", UUID.randomUUID().toString().replace("-", ""))
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val total = conn.getHeaderField("Total-Results")?.toLongOrNull() ?: 0L
            return Resp(code, text, total)
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(code: Int): Nothing {
        throw ScZoteroException(
                when (code) {
                    401, 403 -> scTr(
                            "Ключ не принят или у него нет нужных прав.",
                            "The key was rejected or lacks the required permissions."
                    )
                    404 -> scTr("Не найдено. Проверьте библиотеку и ключ.", "Not found. Check the library and the key.")
                    429 -> scTr("Слишком много запросов. Подождите минуту.", "Too many requests. Wait a minute.")
                    else -> scTr("Ошибка Zotero (код $code).", "Zotero error (code $code).")
                }
        )
    }

    /* ------------------------------------------------------------------------------------------
     * Connection check, libraries, collections
     * ---------------------------------------------------------------------------------------- */

    fun verify(key: String): ScZoteroCreds {
        val r = call("GET", "keys/current", key, null)
        if (r.code !in 200..299) fail(r.code)
        val root = JSONObject(r.body)
        val uid = root.optLong("userID", 0L)
        val userAccess = root.optJSONObject("access")?.optJSONObject("user")
        val hasLibrary = userAccess?.optBoolean("library", false) ?: false
        val canWrite = userAccess?.optBoolean("write", false) ?: false
        if (uid == 0L || !hasLibrary) {
            throw ScZoteroException(
                    scTr(
                            "У ключа нет доступа к библиотеке. Создайте ключ с разрешением на доступ к библиотеке.",
                            "The key has no library access. Create a key that allows library access."
                    )
            )
        }
        return ScZoteroCreds(key, uid, root.zStr("username"), canWrite)
    }

    fun libraries(c: ScZoteroCreds): List<ScZLibrary> {
        val out = ArrayList<ScZLibrary>()
        out.add(ScZLibrary("users/${c.userId}", scTr("Моя библиотека", "My library")))
        try {
            val r = call("GET", "users/${c.userId}/groups?format=json&limit=100", c.key, null)
            if (r.code in 200..299) {
                val arr = JSONArray(r.body)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optLong("id", 0L)
                    val name = o.optJSONObject("data")?.zStr("name").orEmpty()
                    if (id != 0L && name.isNotEmpty()) out.add(ScZLibrary("groups/$id", name))
                }
            }
        } catch (e: Exception) {
            // group libraries are optional, the personal library is enough
        }
        return out
    }

    fun collections(c: ScZoteroCreds, prefix: String): List<ScZCollection> {
        val r = call("GET", "$prefix/collections?format=json&limit=100", c.key, null)
        if (r.code !in 200..299) fail(r.code)
        val arr = JSONArray(r.body)
        val out = ArrayList<ScZCollection>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.zStr("key")
            val name = o.optJSONObject("data")?.zStr("name").orEmpty()
            val count = o.optJSONObject("meta")?.optInt("numItems", 0) ?: 0
            if (key.isNotEmpty() && name.isNotEmpty()) out.add(ScZCollection(key, name, count))
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /* ------------------------------------------------------------------------------------------
     * Items
     * ---------------------------------------------------------------------------------------- */

    fun items(c: ScZoteroCreds, prefix: String, collectionKey: String?, query: String, start: Int): ScZPage {
        val base = if (collectionKey == null) "$prefix/items/top" else "$prefix/collections/$collectionKey/items/top"
        var path = "$base?format=json&limit=$PAGE&start=$start&sort=dateModified&direction=desc"
        if (query.isNotEmpty()) path += "&q=" + URLEncoder.encode(query, "UTF-8")
        val r = call("GET", path, c.key, null)
        if (r.code !in 200..299) fail(r.code)
        val arr = JSONArray(r.body)
        val out = ArrayList<ScZItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optJSONObject("data") ?: continue
            val type = d.zStr("itemType")
            if (type == "note" || type == "annotation") continue
            out.add(parseItem(o.zStr("key"), type, d))
        }
        return ScZPage(out, r.total)
    }

    private fun parseItem(key: String, type: String, d: JSONObject): ScZItem {
        val title = d.zStr("title").ifEmpty { d.zStr("filename") }.ifEmpty { scTr("Без названия", "Untitled") }
        val year = Regex("\\b(1[5-9]\\d{2}|20\\d{2})\\b").find(d.zStr("date"))?.value.orEmpty()
        val publication = d.zStr("publicationTitle")
                .ifEmpty { d.zStr("proceedingsTitle") }
                .ifEmpty { d.zStr("bookTitle") }
                .ifEmpty { d.zStr("websiteTitle") }
                .ifEmpty { d.zStr("repository") }
        return ScZItem(
                key = key,
                itemType = type,
                title = title,
                authors = creatorsText(d.optJSONArray("creators")),
                year = year,
                publication = publication,
                doi = d.zStr("DOI"),
                url = d.zStr("url"),
                contentType = d.zStr("contentType"),
                linkMode = d.zStr("linkMode"),
        )
    }

    private fun creatorsText(arr: JSONArray?): String {
        if (arr == null) return ""
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val n = c.zStr("lastName").ifEmpty { c.zStr("name") }
            if (n.isNotEmpty()) names.add(n)
        }
        val shown = names.take(3).joinToString(", ")
        return if (names.size > 3) "$shown et al." else shown
    }

    /* ------------------------------------------------------------------------------------------
     * Adding an item
     * ---------------------------------------------------------------------------------------- */

    fun addItem(
            c: ScZoteroCreds,
            prefix: String,
            collectionKey: String?,
            title: String,
            authors: List<String>,
            year: String,
            doi: String,
            url: String,
            publication: String,
    ) {
        val item = JSONObject()
        item.put("itemType", "journalArticle")
        item.put("title", title)
        if (authors.isNotEmpty()) {
            val creators = JSONArray()
            for (a in authors) creators.put(JSONObject().put("creatorType", "author").put("name", a))
            item.put("creators", creators)
        }
        if (year.isNotEmpty()) item.put("date", year)
        if (doi.isNotEmpty()) item.put("DOI", doi)
        if (url.isNotEmpty()) item.put("url", url)
        if (publication.isNotEmpty()) item.put("publicationTitle", publication)
        if (collectionKey != null) item.put("collections", JSONArray().put(collectionKey))

        val r = call("POST", "$prefix/items", c.key, JSONArray().put(item).toString())
        if (r.code !in 200..299) fail(r.code)
        val failed = JSONObject(r.body).optJSONObject("failed")
        if (failed != null && failed.length() > 0) {
            val first = failed.optJSONObject(failed.keys().next())
            throw ScZoteroException(
                    first?.optString("message").orEmpty()
                            .ifEmpty { scTr("Zotero не принял запись.", "Zotero rejected the item.") }
            )
        }
    }

    /* ------------------------------------------------------------------------------------------
     * PDF attachments
     * ---------------------------------------------------------------------------------------- */

    /** Returns the PDF attachment of an item (or the item itself if it is a PDF), or null when there is none. */
    fun findPdf(c: ScZoteroCreds, prefix: String, item: ScZItem): ScZItem? {
        if (item.itemType == "attachment") return if (item.contentType == "application/pdf") item else null
        val r = call("GET", "$prefix/items/${item.key}/children?format=json&limit=50", c.key, null)
        if (r.code !in 200..299) fail(r.code)
        val arr = JSONArray(r.body)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optJSONObject("data") ?: continue
            if (d.zStr("itemType") == "attachment" && d.zStr("contentType") == "application/pdf") {
                return parseItem(o.zStr("key"), "attachment", d)
            }
        }
        return null
    }

    /** Downloads the PDF into the app library (private storage). Throws ScZoteroException with a readable message. */
    fun savePdf(ctx: Context, c: ScZoteroCreds, prefix: String, owner: ScZItem, pdf: ScZItem): ScSavedPaper {
        val dir = File(ctx.filesDir, "sc_library")
        if (!dir.exists()) dir.mkdirs()
        val id = "zotero:$prefix:${owner.key}"
        val fileName = "z" + Integer.toHexString(id.hashCode()) + ".pdf"
        val dest = File(dir, fileName)
        val tmp = File(dir, "$fileName.part")

        val startUrl: String
        val withKeyAtStart: Boolean
        when (pdf.linkMode) {
            "linked_url" -> {
                startUrl = pdf.url
                withKeyAtStart = false
            }
            "linked_file" -> throw ScZoteroException(
                    scTr(
                            "Файл хранится только на вашем компьютере и недоступен через Zotero.",
                            "The file is stored only on your computer and is not available through Zotero."
                    )
            )
            else -> {
                startUrl = BASE + "$prefix/items/${pdf.key}/file"
                withKeyAtStart = true
            }
        }
        if (startUrl.isEmpty()) throw ScZoteroException(scTr("У вложения нет адреса файла.", "The attachment has no file address."))

        try {
            var current = startUrl
            var withKey = withKeyAtStart
            for (hop in 0..6) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", UA)
                if (withKey) {
                    conn.setRequestProperty("Zotero-API-Key", c.key)
                    conn.setRequestProperty("Zotero-API-Version", "3")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty() || hop >= 6) {
                        throw ScZoteroException(scTr("Не удалось получить файл.", "Could not get the file."))
                    }
                    current = URL(URL(current), location).toString()
                    withKey = false
                } else if (code == 404) {
                    conn.disconnect()
                    throw ScZoteroException(
                            scTr(
                                    "Файла нет на серверах Zotero (он не синхронизирован или хранится у вас на компьютере).",
                                    "The file is not on Zotero servers (it is not synced or is stored on your computer)."
                            )
                    )
                } else if (code in 200..299) {
                    conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                    conn.disconnect()
                    if (!looksLikePdf(tmp)) {
                        tmp.delete()
                        throw ScZoteroException(scTr("Это не PDF-файл.", "This is not a PDF file."))
                    }
                    dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.delete()
                        throw ScZoteroException(scTr("Не удалось сохранить файл.", "Could not save the file."))
                    }
                    val meta = listOf(owner.authors, owner.year, "Zotero").filter { it.isNotEmpty() }.joinToString(" · ")
                    val paper = ScSavedPaper(id, owner.title, meta, fileName, System.currentTimeMillis())
                    ScLibraryStore.add(ctx, paper)
                    return paper
                } else {
                    conn.disconnect()
                    fail(code)
                }
            }
            throw ScZoteroException(scTr("Слишком много перенаправлений.", "Too many redirects."))
        } catch (e: ScZoteroException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ScZoteroException(scTr("Файл не скачался: нет соединения или обрыв.", "Download failed: no connection or interrupted."))
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
