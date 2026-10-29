/*
 * Science Collab: grants directory (Uzbekistan, Kazakhstan, international) and live US search via Grants.gov.
 * Texts are chosen by the phone language: Russian, otherwise English.
 */

package im.vector.app.features.home

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal fun scTr(ru: String, en: String): String = if (Locale.getDefault().language == "ru") ru else en

private fun JSONObject.scStr(name: String): String = if (isNull(name)) "" else optString(name, "")

data class ScGrantCard(
        val title: String,
        val subtitle: String,
        val body: String,
        val tags: List<String>,
        val url: String?,
)

object ScGrants {

    const val REGION_ALL = "all"
    const val REGION_UZ = "uz"
    const val REGION_KZ = "kz"
    const val REGION_INT = "int"
    const val REGION_US = "us"

    private class Entry(val regions: Set<String>, val card: ScGrantCard)

    fun catalog(region: String, query: String): List<ScGrantCard> {
        val q = query.trim().lowercase(Locale.ROOT)
        return entries()
                .filter { e ->
                    val inRegion = region == REGION_ALL || region in e.regions
                    val text = (e.card.title + " " + e.card.subtitle + " " + e.card.body).lowercase(Locale.ROOT)
                    inRegion && (q.isEmpty() || text.contains(q))
                }
                .map { it.card }
    }

    private fun gov(path: String): String = "https://gov.uz/" + scTr("ru", "en") + "/innovation/" + path

    private fun entries(): List<Entry> {
        val uz = scTr("Узбекистан", "Uzbekistan")
        val kz = scTr("Казахстан", "Kazakhstan")
        val intl = scTr("Международный", "International")
        val ca = scTr("Центральная Азия", "Central Asia")
        val us = scTr("США", "USA")
        val agency = scTr(
                "Агентство инновационного развития при Министерстве высшего образования, науки и инноваций",
                "Innovation Development Agency under the Ministry of Higher Education, Science and Innovation"
        )
        return listOf(
                Entry(
                        setOf(REGION_UZ),
                        ScGrantCard(
                                title = scTr("Государственные научные программы Узбекистана", "Uzbekistan state scientific programs"),
                                subtitle = agency,
                                body = scTr(
                                        "Конкурсы фундаментальных, прикладных и инновационных проектов. Заявки подаются в электронном виде " +
                                                "через платформу loyiha.ilmiy.uz, нужен идентификатор ScienceID. Даты и условия указаны на странице каждого конкурса.",
                                        "Competitions for fundamental, applied and innovative projects. Applications are submitted online " +
                                                "through the loyiha.ilmiy.uz platform and require a ScienceID. Dates and terms are listed on each competition page."
                                ),
                                tags = listOf(uz),
                                url = gov("contest"),
                        )
                ),
                Entry(
                        setOf(REGION_UZ),
                        ScGrantCard(
                                title = scTr("Совместное финансирование прикладных исследований", "Co-funding of applied research projects"),
                                subtitle = agency,
                                body = scTr(
                                        "Конкурс прикладных проектов с софинансированием в рамках государственных программ. Срок проекта до 3 лет, " +
                                                "один проект на руководителя. Последний известный раунд: 8 мая – 8 июня 2026, платформа loyiha.ilmiy.uz.",
                                        "Applied research competition with co-financing under state programmes. Projects run up to 3 years, " +
                                                "one project per supervisor. Latest known round: 8 May – 8 June 2026, loyiha.ilmiy.uz platform."
                                ),
                                tags = listOf(uz),
                                url = gov("contest/view/163982"),
                        )
                ),
                Entry(
                        setOf(REGION_UZ),
                        ScGrantCard(
                                title = scTr("Коммерциализация научных разработок", "Commercialization of research results"),
                                subtitle = agency,
                                body = scTr(
                                        "Конкурс на подготовку разработок к коммерциализации: возмещаются расходы на опытные и пилотные испытания. " +
                                                "В 2026 году заявки принимали с 21 мая по 21 июля через платформу tijorat.ilmiy.uz.",
                                        "Competition to prepare research results for commercialization, reimbursing the cost of experimental and pilot testing. " +
                                                "In 2026 applications were accepted from 21 May to 21 July via the tijorat.ilmiy.uz platform."
                                ),
                                tags = listOf(uz),
                                url = gov("contest/view/168111"),
                        )
                ),
                Entry(
                        setOf(REGION_UZ),
                        ScGrantCard(
                                title = scTr("«Будущий учёный» (Каракалпакстан)", "“Future Scientist” (Karakalpakstan)"),
                                subtitle = scTr("Конкурс научных идей талантливой молодёжи", "Scientific ideas competition for talented young people"),
                                body = scTr(
                                        "Участвовать могут граждане Узбекистана. На средства победителей нужно создать модель или прототип. " +
                                                "В 2026 году заявки принимали с 5 июня по 4 июля через платформу startup.ilmiy.uz.",
                                        "Open to citizens of Uzbekistan. Winners must build a model or prototype with the funds. " +
                                                "In 2026 applications were accepted from 5 June to 4 July via the startup.ilmiy.uz platform."
                                ),
                                tags = listOf(uz),
                                url = gov("contest/view/174769"),
                        )
                ),
                Entry(
                        setOf(REGION_KZ),
                        ScGrantCard(
                                title = scTr("Грантовое финансирование научных проектов (Казахстан)", "Research grant funding (Kazakhstan)"),
                                subtitle = scTr(
                                        "Комитет науки Министерства науки и высшего образования РК",
                                        "Science Committee, Ministry of Science and Higher Education of Kazakhstan"
                                ),
                                body = scTr(
                                        "Конкурсы на 2026–2028 годы: научные проекты, молодые учёные «Жас Ғалым» (до 30 млн тенге на проект, срок до 36 месяцев) " +
                                                "и коммерциализация. Заявки подаются через систему is.ncste.kz с электронной подписью. " +
                                                "Осенью 2025 года заявки на научные проекты принимали с 6 октября по 6 ноября. Условия участия проверяйте в документации.",
                                        "Calls for 2026–2028: research projects, young scientists “Zhas Galym” (up to 30 million tenge per project, up to 36 months) " +
                                                "and commercialization. Applications go through the is.ncste.kz system with a digital signature. " +
                                                "In autumn 2025 research project applications were accepted from 6 October to 6 November. Check eligibility in the call documents."
                                ),
                                tags = listOf(kz),
                                url = "https://www.ncste.kz/ru/competition",
                        )
                ),
                Entry(
                        setOf(REGION_INT, REGION_UZ, REGION_KZ),
                        ScGrantCard(
                                title = "Horizon Europe",
                                subtitle = scTr("Европейская комиссия", "European Commission"),
                                body = scTr(
                                        "Крупнейшая программа ЕС для исследований. По заявлению представителей ЕС на конференции в феврале 2026 года, " +
                                                "исследователи из Узбекистана и Центральной Азии могут участвовать в проектах и получать финансирование. " +
                                                "Актуальные конкурсы ищите на портале Funding & Tenders. В Узбекистане есть национальный контактный координатор.",
                                        "The EU's main research programme. According to EU representatives at a February 2026 conference, " +
                                                "researchers from Uzbekistan and Central Asia can take part in projects and receive funding. " +
                                                "Find current calls on the Funding & Tenders portal. Uzbekistan has a national contact coordinator."
                                ),
                                tags = listOf(intl, ca),
                                url = "https://ec.europa.eu/info/funding-tenders/opportunities/portal/screen/home",
                        )
                ),
                Entry(
                        setOf(REGION_INT, REGION_UZ, REGION_KZ),
                        ScGrantCard(
                                title = "Erasmus+",
                                subtitle = scTr("Программа ЕС по образованию и обучению", "EU programme for education and training"),
                                body = scTr(
                                        "Центральная Азия входит в регионы-партнёры: Афганистан, Казахстан, Кыргызстан, Таджикистан, Туркменистан, Узбекистан. " +
                                                "Проекты по развитию вузов, кредитная мобильность, совместные магистерские программы Erasmus Mundus, Жан Монне. " +
                                                "В Узбекистане работает национальный офис Erasmus+ (erasmus.uz).",
                                        "Central Asia is a partner region: Afghanistan, Kazakhstan, Kyrgyzstan, Tajikistan, Turkmenistan, Uzbekistan. " +
                                                "Capacity building for universities, credit mobility, Erasmus Mundus joint masters, Jean Monnet. " +
                                                "Uzbekistan has a national Erasmus+ office (erasmus.uz)."
                                ),
                                tags = listOf(intl, ca),
                                url = "https://erasmus-plus.ec.europa.eu/",
                        )
                ),
                Entry(
                        setOf(REGION_INT),
                        ScGrantCard(
                                title = scTr("Гранты США (Grants.gov)", "US grants (Grants.gov)"),
                                subtitle = scTr("Федеральные конкурсы США", "US federal funding opportunities"),
                                body = scTr(
                                        "Живой поиск по федеральным конкурсам США (NIH, NSF и другие). Многие рассчитаны только на организации США. " +
                                                "Откройте вкладку «США · live» вверху.",
                                        "Live search of US federal funding opportunities (NIH, NSF and others). Many are only for US organizations. " +
                                                "Open the “USA · live” chip above."
                                ),
                                tags = listOf(intl, us),
                                url = "https://www.grants.gov/",
                        )
                ),
        )
    }

    /** Live search in Grants.gov (official public API). Throws on network or parse errors. */
    fun searchUs(keyword: String): List<ScGrantCard> {
        val payload = JSONObject()
                .put("rows", 25)
                .put("keyword", keyword)
                .put("oppStatuses", "forecasted|posted")
        val conn = URL("https://api.grants.gov/v1/api/search2").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseUs(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseUs(body: String): List<ScGrantCard> {
        val root = JSONObject(body)
        if (root.optInt("errorcode", -1) != 0) throw IllegalStateException(root.scStr("msg"))
        val hits = root.optJSONObject("data")?.optJSONArray("oppHits") ?: return emptyList()
        val us = scTr("США", "USA")
        val out = ArrayList<ScGrantCard>()
        for (i in 0 until hits.length()) {
            val o = hits.optJSONObject(i) ?: continue
            val id = o.scStr("id")
            val number = o.scStr("number")
            val title = o.scStr("title").ifEmpty { number }
            val agency = o.scStr("agencyName").ifEmpty { o.scStr("agencyCode") }
            val close = o.scStr("closeDate")
            val statusText = when (val status = o.scStr("oppStatus")) {
                "posted" -> scTr("Открыт", "Open")
                "forecasted" -> scTr("Анонс", "Forecast")
                else -> status
            }
            val closeText = if (close.isEmpty()) {
                scTr("срок не указан", "no deadline listed")
            } else {
                scTr("до ", "until ") + isoDate(close)
            }
            val body2 = listOf(statusText, closeText, number).filter { it.isNotEmpty() }.joinToString(" · ")
            out.add(
                    ScGrantCard(
                            title = title,
                            subtitle = agency,
                            body = body2,
                            tags = listOf(us, statusText).filter { it.isNotEmpty() },
                            url = if (id.isEmpty()) null else "https://www.grants.gov/search-results-detail/$id",
                    )
            )
        }
        return out
    }

    private fun isoDate(s: String): String {
        val p = s.split("/")
        return if (p.size == 3) p[2] + "-" + p[0] + "-" + p[1] else s
    }
}
