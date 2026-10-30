/*
 * Science Collab: project boards (kanban). Stored on the phone in private app storage.
 */

package im.vector.app.features.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ScCard(val id: String, var title: String, var note: String, var assignee: String, var due: String)

class ScColumn(val id: String, var title: String, val cards: MutableList<ScCard>)

class ScBoard(val id: String, var title: String, var updatedAt: Long, val columns: MutableList<ScColumn>) {

    fun totalCards(): Int {
        var n = 0
        for (c in columns) n += c.cards.size
        return n
    }

    /** The last column means "done". */
    fun doneCards(): Int = if (columns.size >= 2) columns.last().cards.size else 0
}

object ScBoards {

    const val TEMPLATE_BASIC = "basic"
    const val TEMPLATE_GRANT = "grant"
    const val TEMPLATE_PAPER = "paper"

    fun newId(): String = UUID.randomUUID().toString()

    private fun file(ctx: Context): File {
        val dir = File(ctx.filesDir, "sc_boards")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "boards.json")
    }

    @Synchronized
    fun load(ctx: Context): MutableList<ScBoard> {
        val f = file(ctx)
        val out = ArrayList<ScBoard>()
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val columns = ArrayList<ScColumn>()
                val colArr = b.optJSONArray("columns")
                if (colArr != null) {
                    for (j in 0 until colArr.length()) {
                        val c = colArr.optJSONObject(j) ?: continue
                        val cards = ArrayList<ScCard>()
                        val cardArr = c.optJSONArray("cards")
                        if (cardArr != null) {
                            for (k in 0 until cardArr.length()) {
                                val o = cardArr.optJSONObject(k) ?: continue
                                cards.add(
                                        ScCard(
                                                o.optString("id").ifEmpty { newId() },
                                                o.optString("title"),
                                                o.optString("note"),
                                                o.optString("assignee"),
                                                o.optString("due"),
                                        )
                                )
                            }
                        }
                        columns.add(ScColumn(c.optString("id").ifEmpty { newId() }, c.optString("title"), cards))
                    }
                }
                out.add(ScBoard(b.optString("id").ifEmpty { newId() }, b.optString("title"), b.optLong("updatedAt"), columns))
            }
        } catch (e: Exception) {
            // a damaged file is treated as empty
        }
        return out
    }

    @Synchronized
    fun save(ctx: Context, boards: List<ScBoard>) {
        val arr = JSONArray()
        for (b in boards) {
            val colArr = JSONArray()
            for (c in b.columns) {
                val cardArr = JSONArray()
                for (card in c.cards) {
                    cardArr.put(
                            JSONObject()
                                    .put("id", card.id)
                                    .put("title", card.title)
                                    .put("note", card.note)
                                    .put("assignee", card.assignee)
                                    .put("due", card.due)
                    )
                }
                colArr.put(JSONObject().put("id", c.id).put("title", c.title).put("cards", cardArr))
            }
            arr.put(
                    JSONObject()
                            .put("id", b.id)
                            .put("title", b.title)
                            .put("updatedAt", b.updatedAt)
                            .put("columns", colArr)
            )
        }
        file(ctx).writeText(arr.toString())
    }

    fun create(kind: String, title: String): ScBoard {
        val columns = ArrayList<ScColumn>()

        fun col(name: String, vararg tasks: String): ScColumn {
            val cards = ArrayList<ScCard>()
            for (t in tasks) cards.add(ScCard(newId(), t, "", "", ""))
            return ScColumn(newId(), name, cards)
        }

        when (kind) {
            TEMPLATE_GRANT -> {
                columns.add(
                        col(
                                scTr("Идея и условия", "Idea and rules"),
                                scTr("Прочитать условия конкурса", "Read the call rules"),
                                scTr(
                                        "Проверить, кто может подавать (страна, организация)",
                                        "Check who can apply (country, organization)"
                                ),
                        )
                )
                columns.add(col(scTr("Партнёры", "Partners"), scTr("Найти партнёров и коллег", "Find partners and colleagues")))
                columns.add(
                        col(
                                scTr("Черновик заявки", "Application draft"),
                                scTr("Описать идею на одной странице", "Describe the idea on one page"),
                                scTr("Написать черновик заявки", "Write the application draft"),
                        )
                )
                columns.add(
                        col(
                                scTr("Бюджет и документы", "Budget and documents"),
                                scTr("Составить бюджет", "Prepare the budget"),
                                scTr("Собрать документы и подписи", "Collect documents and signatures"),
                        )
                )
                columns.add(
                        col(
                                scTr("Подача", "Submission"),
                                scTr("Проверить заявку по чек-листу конкурса", "Check the application against the call checklist"),
                                scTr("Подать заявку до дедлайна", "Submit before the deadline"),
                        )
                )
                columns.add(col(scTr("Готово", "Done")))
            }
            TEMPLATE_PAPER -> {
                columns.add(
                        col(
                                scTr("Идея", "Idea"),
                                scTr("Сформулировать вопрос и гипотезу", "Formulate the question and hypothesis"),
                        )
                )
                columns.add(
                        col(
                                scTr("Литература", "Literature"),
                                scTr("Собрать и прочитать источники", "Collect and read the sources"),
                        )
                )
                columns.add(col(scTr("Данные и анализ", "Data and analysis"), scTr("Провести анализ", "Run the analysis")))
                columns.add(col(scTr("Текст", "Writing"), scTr("Написать черновик", "Write the draft")))
                columns.add(
                        col(
                                scTr("Правки и подача", "Revision and submission"),
                                scTr("Выбрать журнал и подать статью", "Choose a journal and submit the paper"),
                        )
                )
                columns.add(col(scTr("Готово", "Done")))
            }
            else -> {
                columns.add(col(scTr("К выполнению", "To do")))
                columns.add(col(scTr("В работе", "In progress")))
                columns.add(col(scTr("Готово", "Done")))
            }
        }
        return ScBoard(newId(), title, System.currentTimeMillis(), columns)
    }
}
