/*
 * Science Collab: project boards (kanban). Stored on the phone; can be synced through a chat room.
 */

package im.vector.app.features.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ScCard(
        val id: String,
        var title: String,
        var note: String,
        var assignee: String,
        var due: String,
        var updatedAt: Long = 0L,
        var pos: Double = 0.0,
)

class ScColumn(
        val id: String,
        var title: String,
        val cards: MutableList<ScCard>,
        var titleAt: Long = 0L,
)

class ScBoard(val id: String, var title: String, var updatedAt: Long, val columns: MutableList<ScColumn>) {

    /** Chat room the board is synced with (empty = only on this phone). Not part of the shared snapshot. */
    var roomId: String = ""
    var titleAt: Long = 0L
    var structAt: Long = 0L

    /** Deleted cards and columns: id -> time of deletion. */
    val tombstones: MutableMap<String, Long> = LinkedHashMap()

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

    /* ------------------------------------------------------------------------------------------
     * JSON
     * ---------------------------------------------------------------------------------------- */

    fun toJson(b: ScBoard, forSync: Boolean): JSONObject {
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
                                .put("updatedAt", card.updatedAt)
                                .put("pos", card.pos)
                )
            }
            colArr.put(
                    JSONObject()
                            .put("id", c.id)
                            .put("title", c.title)
                            .put("titleAt", c.titleAt)
                            .put("cards", cardArr)
            )
        }
        val tomb = JSONObject()
        for ((k, v) in b.tombstones) tomb.put(k, v)
        val o = JSONObject()
                .put("id", b.id)
                .put("title", b.title)
                .put("titleAt", b.titleAt)
                .put("structAt", b.structAt)
                .put("updatedAt", b.updatedAt)
                .put("tomb", tomb)
                .put("columns", colArr)
        if (!forSync) o.put("roomId", b.roomId)
        return o
    }

    fun toJsonString(b: ScBoard, forSync: Boolean): String = toJson(b, forSync).toString()

    fun fromJson(b: JSONObject): ScBoard {
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
                                        id = o.optString("id").ifEmpty { newId() },
                                        title = o.optString("title"),
                                        note = o.optString("note"),
                                        assignee = o.optString("assignee"),
                                        due = o.optString("due"),
                                        updatedAt = o.optLong("updatedAt", 0L),
                                        pos = if (o.has("pos")) o.optDouble("pos") else k.toDouble(),
                                )
                        )
                    }
                }
                columns.add(
                        ScColumn(
                                id = c.optString("id").ifEmpty { newId() },
                                title = c.optString("title"),
                                cards = cards,
                                titleAt = c.optLong("titleAt", 0L),
                        )
                )
            }
        }
        val board = ScBoard(b.optString("id").ifEmpty { newId() }, b.optString("title"), b.optLong("updatedAt", 0L), columns)
        board.titleAt = b.optLong("titleAt", 0L)
        board.structAt = b.optLong("structAt", 0L)
        board.roomId = b.optString("roomId")
        val tomb = b.optJSONObject("tomb")
        if (tomb != null) {
            val keys = tomb.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                board.tombstones[key] = tomb.optLong(key, 0L)
            }
        }
        return board
    }

    /** Parses a board received from a chat. Returns null when the text is not a valid board. */
    fun fromJsonString(s: String): ScBoard? {
        return try {
            val o = JSONObject(s)
            if (o.optString("id").isEmpty()) null else fromJson(o)
        } catch (e: Exception) {
            null
        }
    }

    /* ------------------------------------------------------------------------------------------
     * Storage
     * ---------------------------------------------------------------------------------------- */

    @Synchronized
    fun load(ctx: Context): MutableList<ScBoard> {
        val f = file(ctx)
        val out = ArrayList<ScBoard>()
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                out.add(fromJson(b))
            }
        } catch (e: Exception) {
            // a damaged file is treated as empty
        }
        return out
    }

    @Synchronized
    fun save(ctx: Context, boards: List<ScBoard>) {
        val arr = JSONArray()
        for (b in boards) arr.put(toJson(b, false))
        file(ctx).writeText(arr.toString())
    }

    /* ------------------------------------------------------------------------------------------
     * Merge of two copies of the same board (idempotent, order independent)
     * ---------------------------------------------------------------------------------------- */

    private class Pick(val card: ScCard, val colId: String)

    /** Merges [remote] into [local] in place. Returns true when [local] changed. */
    fun mergeChanged(local: ScBoard, remote: ScBoard): Boolean {
        val before = toJsonString(local, false)
        merge(local, remote)
        return before != toJsonString(local, false)
    }

    fun merge(local: ScBoard, remote: ScBoard) {
        if (remote.id != local.id) return

        if (remote.titleAt > local.titleAt) {
            local.title = remote.title
            local.titleAt = remote.titleAt
        }
        for ((id, ts) in remote.tombstones) {
            val current = local.tombstones[id]
            if (current == null || ts > current) local.tombstones[id] = ts
        }

        val localCols = LinkedHashMap<String, ScColumn>()
        for (c in local.columns) localCols[c.id] = c
        val remoteCols = LinkedHashMap<String, ScColumn>()
        for (c in remote.columns) remoteCols[c.id] = c

        // Column order: the copy with the newer structure wins, columns known only to the other copy are added before "done".
        val remoteIsNewer = remote.structAt > local.structAt
        val base = if (remoteIsNewer) remote.columns else local.columns
        val other = if (remoteIsNewer) local.columns else remote.columns
        val order = ArrayList<String>()
        for (c in base) {
            if (!local.tombstones.containsKey(c.id) && !order.contains(c.id)) order.add(c.id)
        }
        val extra = ArrayList<String>()
        for (c in other) {
            if (!local.tombstones.containsKey(c.id) && !order.contains(c.id) && !extra.contains(c.id)) extra.add(c.id)
        }
        if (extra.isNotEmpty()) {
            val at = if (order.size >= 2) order.size - 1 else order.size
            order.addAll(at, extra)
        }
        if (order.isEmpty()) return

        // Cards: the newer version of every card wins (ties keep the local one).
        val best = LinkedHashMap<String, Pick>()
        for (col in local.columns) {
            for (card in col.cards) best[card.id] = Pick(card, col.id)
        }
        for (col in remote.columns) {
            for (card in col.cards) {
                val current = best[card.id]
                if (current == null || card.updatedAt > current.card.updatedAt) best[card.id] = Pick(card, col.id)
            }
        }

        val byColumn = HashMap<String, MutableList<ScCard>>()
        for ((id, pick) in best) {
            val tomb = local.tombstones[id]
            if (tomb != null && tomb >= pick.card.updatedAt) continue
            val colId = if (order.contains(pick.colId)) pick.colId else order[0]
            val copy = ScCard(
                    pick.card.id,
                    pick.card.title,
                    pick.card.note,
                    pick.card.assignee,
                    pick.card.due,
                    pick.card.updatedAt,
                    pick.card.pos,
            )
            byColumn.getOrPut(colId) { ArrayList() }.add(copy)
        }

        local.columns.clear()
        for (id in order) {
            val lc = localCols[id]
            val rc = remoteCols[id]
            val src: ScColumn = when {
                lc != null && rc != null -> if (rc.titleAt > lc.titleAt) rc else lc
                lc != null -> lc
                rc != null -> rc
                else -> continue
            }
            val cards = byColumn[id] ?: ArrayList<ScCard>()
            cards.sortWith(compareBy<ScCard>({ it.pos }, { it.updatedAt }))
            local.columns.add(ScColumn(id, src.title, cards, src.titleAt))
        }
        local.structAt = maxOf(local.structAt, remote.structAt)
        local.updatedAt = maxOf(local.updatedAt, remote.updatedAt)
    }

    /* ------------------------------------------------------------------------------------------
     * Templates
     * ---------------------------------------------------------------------------------------- */

    fun create(kind: String, title: String): ScBoard {
        val now = System.currentTimeMillis()
        val columns = ArrayList<ScColumn>()

        fun col(name: String, vararg tasks: String): ScColumn {
            val cards = ArrayList<ScCard>()
            for ((i, t) in tasks.withIndex()) cards.add(ScCard(newId(), t, "", "", "", now, i.toDouble()))
            return ScColumn(newId(), name, cards, now)
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
        val board = ScBoard(newId(), title, now, columns)
        board.titleAt = now
        board.structAt = now
        return board
    }
}
