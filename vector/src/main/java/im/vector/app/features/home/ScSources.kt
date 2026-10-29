/*
 * Science Collab: extra open sources for "My library" (arXiv, Europe PMC). No API keys needed.
 */

package im.vector.app.features.home

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ScFoundWork(
        val id: String,
        val title: String,
        val meta: String,
        val pdfUrl: String?,
        val landingUrl: String?,
)

private fun JSONObject.sStr(name: String): String = if (isNull(name)) "" else optString(name, "")

object ScSources {

    private const val USER_AGENT = "ScienceCollab/1.0 (Android; academic reader)"

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun clean(s: String): String = s.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()

    /* ------------------------------------------------------------------------------------------
     * arXiv (Atom feed)
     * ---------------------------------------------------------------------------------------- */

    fun searchArxiv(query: String): List<ScFoundWork> {
        val words = query.trim()
                .split(Regex("\\s+"))
                .map { it.replace(Regex("[\":()]"), "") }
                .filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val q = words.joinToString(" AND ") { "all:$it" }
        val url = "https://export.arxiv.org/api/query?search_query=" + URLEncoder.encode(q, "UTF-8") +
                "&start=0&max_results=25"
        return parseArxiv(httpGet(url))
    }

    private fun parseArxiv(xml: String): List<ScFoundWork> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<ScFoundWork>()
        var inEntry = false
        var id = ""
        var title = ""
        var published = ""
        var pdf: String? = null
        val authors = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                if (tag == "entry") {
                    inEntry = true
                    id = ""
                    title = ""
                    published = ""
                    pdf = null
                    authors.clear()
                } else if (inEntry) {
                    when (tag) {
                        "id" -> id = parser.nextText().trim()
                        "title" -> title = clean(parser.nextText())
                        "published" -> published = parser.nextText().trim()
                        "name" -> authors.add(clean(parser.nextText()))
                        "link" -> if (parser.getAttributeValue(null, "title") == "pdf") {
                            pdf = parser.getAttributeValue(null, "href")
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry" && inEntry) {
                inEntry = false
                if (title.isNotEmpty()) {
                    val abs = id.replace("http://", "https://")
                    val pdfUrl = (pdf ?: abs.replace("/abs/", "/pdf/")).replace("http://", "https://")
                    val year = if (published.length >= 4) published.substring(0, 4) else ""
                    val meta = listOf(authors.take(3).joinToString(", "), year, "arXiv")
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                    out.add(ScFoundWork("arxiv:$id", title, meta, pdfUrl, abs.ifEmpty { null }))
                }
            }
            event = parser.next()
        }
        return out
    }

    /* ------------------------------------------------------------------------------------------
     * Europe PMC (JSON)
     * ---------------------------------------------------------------------------------------- */

    fun searchEuropePmc(query: String): List<ScFoundWork> {
        val cleanQuery = query.replace("(", " ").replace(")", " ").trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val q = "($cleanQuery) AND OPEN_ACCESS:y AND HAS_PDF:y"
        val url = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=" + URLEncoder.encode(q, "UTF-8") +
                "&format=json&resultType=core&pageSize=25"
        return parseEuropePmc(httpGet(url))
    }

    private fun parseEuropePmc(body: String): List<ScFoundWork> {
        val results = JSONObject(body).optJSONObject("resultList")?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<ScFoundWork>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val title = clean(o.sStr("title"))
            if (title.isEmpty()) continue
            val source = o.sStr("source")
            val id = o.sStr("id")
            val pmcid = o.sStr("pmcid")
            val authors = o.sStr("authorString").trimEnd('.').split(", ").take(3).joinToString(", ")
            val year = o.sStr("pubYear")

            var pdf: String? = null
            val list = o.optJSONObject("fullTextUrlList")?.optJSONArray("fullTextUrl")
            if (list != null) {
                for (j in 0 until list.length()) {
                    val u = list.optJSONObject(j) ?: continue
                    val code = u.sStr("availabilityCode")
                    if (u.sStr("documentStyle") == "pdf" && (code == "F" || code == "OA")) {
                        pdf = u.sStr("url").ifEmpty { null }
                        break
                    }
                }
            }
            if (pdf == null && pmcid.isNotEmpty()) pdf = "https://europepmc.org/articles/$pmcid?pdf=render"
            val landing = if (source.isNotEmpty() && id.isNotEmpty()) "https://europepmc.org/article/$source/$id" else null
            val meta = listOf(authors, year, "Europe PMC").filter { it.isNotEmpty() }.joinToString(" · ")
            out.add(ScFoundWork("epmc:$source:$id", title, meta, pdf, landing))
        }
        return out
    }
}
