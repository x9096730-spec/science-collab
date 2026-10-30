/*
 * Science Collab: "Projects and tasks" - kanban boards for grants, papers and teams.
 * Boards are stored on the phone (private app storage). Sharing as text uses the system share sheet.
 */

package im.vector.app.features.home

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun boardColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
    return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
}

class ScBoardsDialogFragment : DialogFragment() {

    private var boards: MutableList<ScBoard> = ArrayList()
    private var current: ScBoard? = null
    private var root: LinearLayout? = null
    private var columnsAdapter: ColumnsAdapter? = null
    private var progressView: TextView? = null
    private var titleView: TextView? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun primary(): Int = boardColor(requireContext(), android.R.attr.textColorPrimary, Color.BLACK)

    private fun secondary(): Int = boardColor(requireContext(), android.R.attr.textColorSecondary, Color.GRAY)

    private fun bgColor(): Int = boardColor(requireContext(), android.R.attr.colorBackground, Color.WHITE)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(bgColor()))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        boards = ScBoards.load(ctx)
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        root = r
        showList()
        return r
    }

    private fun touch(board: ScBoard) {
        board.updatedAt = System.currentTimeMillis()
        ScBoards.save(requireContext(), boards)
    }

    /* ------------------------------------------------------------------------------------------
     * List of boards
     * ---------------------------------------------------------------------------------------- */

    private fun showList() {
        val r = root ?: return
        val c = requireContext()
        current = null
        columnsAdapter = null
        progressView = null
        titleView = null
        r.removeAllViews()

        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(c).apply {
            text = scTr("Проекты и задачи", "Projects and tasks")
            setTextColor(primary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(c, scTr("Закрыть", "Close"), false) { dismiss() })
        r.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val newLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        newLp.topMargin = dp(12)
        r.addView(pill(c, scTr("＋ Новая доска", "＋ New board"), true) { newBoard() }, newLp)

        r.addView(TextView(c).apply {
            text = scTr(
                    "Создайте доску для гранта, статьи или любого проекта: задачи, сроки и исполнители в одном месте.",
                    "Create a board for a grant, a paper or any project: tasks, deadlines and assignees in one place."
            )
            setTextColor(secondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16), 0, dp(8))
            visibility = if (boards.isEmpty()) View.VISIBLE else View.GONE
        })

        val list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c)
            this.adapter = BoardsAdapter()
        }
        val listLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        listLp.topMargin = dp(8)
        r.addView(list, listLp)
    }

    private fun newBoard() {
        askText(
                scTr("Название проекта", "Project name"),
                "",
                scTr("Например: грант Erasmus+", "For example: Erasmus+ grant")
        ) { name ->
            val options = arrayOf(
                    scTr("Пустая доска", "Blank board"),
                    scTr("Заявка на грант", "Grant application"),
                    scTr("Научная статья", "Research paper"),
            )
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(scTr("Шаблон", "Template"))
                    .setItems(options) { _, which ->
                        val kind = when (which) {
                            1 -> ScBoards.TEMPLATE_GRANT
                            2 -> ScBoards.TEMPLATE_PAPER
                            else -> ScBoards.TEMPLATE_BASIC
                        }
                        val board = ScBoards.create(kind, name)
                        boards.add(0, board)
                        ScBoards.save(requireContext(), boards)
                        showBoard(board)
                    }
                    .show()
        }
    }

    private fun boardMenu(board: ScBoard, inBoard: Boolean) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(scTr("Переименовать", "Rename"))
        actions.add {
            askText(scTr("Название проекта", "Project name"), board.title, "") { name ->
                board.title = name
                touch(board)
                titleView?.text = name
                if (!inBoard) showList()
            }
        }
        if (inBoard) {
            labels.add(scTr("Добавить колонку", "Add column"))
            actions.add { addColumn(board) }
            labels.add(scTr("Поделиться (текстом)", "Share (as text)"))
            actions.add { shareBoard(board) }
        }
        labels.add(scTr("Удалить доску", "Delete board"))
        actions.add { confirmDeleteBoard(board) }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(board.title)
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .show()
    }

    private fun confirmDeleteBoard(board: ScBoard) {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(scTr("Удалить доску?", "Delete the board?"))
                .setMessage(board.title)
                .setPositiveButton(scTr("Удалить", "Delete")) { _, _ ->
                    boards.remove(board)
                    ScBoards.save(requireContext(), boards)
                    showList()
                }
                .setNegativeButton(scTr("Отмена", "Cancel"), null)
                .show()
    }

    private fun shareBoard(board: ScBoard) {
        val sb = StringBuilder()
        sb.append("📋 ").append(board.title).append('\n')
        for (col in board.columns) {
            sb.append('\n').append(col.title).append(" (").append(col.cards.size).append(")\n")
            for (card in col.cards) {
                sb.append("• ").append(card.title)
                val extra = listOf(card.assignee, card.due).filter { it.isNotEmpty() }
                if (extra.isNotEmpty()) sb.append(" — ").append(extra.joinToString(", "))
                sb.append('\n')
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, scTr("Поделиться доской", "Share the board")))
    }

    /* ------------------------------------------------------------------------------------------
     * Board
     * ---------------------------------------------------------------------------------------- */

    private fun showBoard(board: ScBoard) {
        val r = root ?: return
        val c = requireContext()
        current = board
        r.removeAllViews()

        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(pill(c, scTr("← Назад", "← Back"), false) { showList() })
        val title = TextView(c).apply {
            text = board.title
            setTextColor(primary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
        }
        titleView = title
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(c, "⋯", false) { boardMenu(board, true) })
        r.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val progress = TextView(c).apply {
            setTextColor(secondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, dp(10))
        }
        progressView = progress
        r.addView(progress)
        updateProgress()

        val adapterLocal = ColumnsAdapter()
        columnsAdapter = adapterLocal
        val columnsList = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapterLocal
        }
        r.addView(columnsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun updateProgress() {
        val board = current ?: return
        val total = board.totalCards()
        val done = board.doneCards()
        progressView?.text = if (total == 0) {
            scTr("Задач пока нет. Нажмите «＋ Добавить задачу» в любой колонке.", "No tasks yet. Tap “＋ Add task” in any column.")
        } else {
            val percent = done * 100 / total
            scTr("Выполнено $done из $total · $percent%", "Done $done of $total · $percent%")
        }
    }

    private fun addColumn(board: ScBoard) {
        askText(scTr("Название колонки", "Column name"), "", "") { name ->
            val index = if (board.columns.size >= 2) board.columns.size - 1 else board.columns.size
            board.columns.add(index, ScColumn(ScBoards.newId(), name, ArrayList()))
            touch(board)
            columnsAdapter?.notifyDataSetChanged()
            updateProgress()
        }
    }

    private fun columnMenu(index: Int) {
        val board = current ?: return
        val column = board.columns.getOrNull(index) ?: return
        val options = arrayOf(scTr("Переименовать", "Rename"), scTr("Удалить колонку", "Delete column"))
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(column.title)
                .setItems(options) { _, which ->
                    if (which == 0) {
                        askText(scTr("Название колонки", "Column name"), column.title, "") { name ->
                            column.title = name
                            touch(board)
                            columnsAdapter?.notifyItemChanged(index)
                        }
                    } else {
                        confirmDeleteColumn(board, column)
                    }
                }
                .show()
    }

    private fun confirmDeleteColumn(board: ScBoard, column: ScColumn) {
        val warning = if (column.cards.isEmpty()) {
            ""
        } else {
            scTr("Задачи в этой колонке тоже будут удалены.", "Tasks in this column will be deleted too.")
        }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(scTr("Удалить колонку?", "Delete the column?"))
                .setMessage(column.title + (if (warning.isEmpty()) "" else "\n\n$warning"))
                .setPositiveButton(scTr("Удалить", "Delete")) { _, _ ->
                    board.columns.remove(column)
                    touch(board)
                    columnsAdapter?.notifyDataSetChanged()
                    updateProgress()
                }
                .setNegativeButton(scTr("Отмена", "Cancel"), null)
                .show()
    }

    private fun moveCard(from: Int, card: ScCard, to: Int) {
        val board = current ?: return
        if (to < 0 || to >= board.columns.size) return
        board.columns[from].cards.remove(card)
        board.columns[to].cards.add(0, card)
        touch(board)
        columnsAdapter?.notifyItemChanged(from)
        columnsAdapter?.notifyItemChanged(to)
        updateProgress()
    }

    private fun editCard(colIndex: Int, card: ScCard?) {
        val board = current ?: return
        val c = requireContext()
        val form = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val titleBox = EditText(c).apply {
            hint = scTr("Название задачи", "Task title")
            setText(card?.title.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val noteBox = EditText(c).apply {
            hint = scTr("Описание", "Description")
            setText(card?.note.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        val whoBox = EditText(c).apply {
            hint = scTr("Исполнитель", "Assignee")
            setText(card?.assignee.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        var due = card?.due.orEmpty()
        val dueView = TextView(c).apply {
            text = dueLabel(due)
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(6))
            isClickable = true
        }
        dueView.setOnClickListener {
            pickDate(due) { picked ->
                due = picked
                dueView.text = dueLabel(picked)
            }
        }
        val clearView = TextView(c).apply {
            text = scTr("Очистить срок", "Clear due date")
            setTextColor(secondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(8))
            isClickable = true
        }
        clearView.setOnClickListener {
            due = ""
            dueView.text = dueLabel("")
        }
        form.addView(titleBox)
        form.addView(noteBox)
        form.addView(whoBox)
        form.addView(dueView)
        form.addView(clearView)

        val builder = MaterialAlertDialogBuilder(c)
                .setTitle(if (card == null) scTr("Новая задача", "New task") else scTr("Задача", "Task"))
                .setView(form)
                .setPositiveButton(scTr("Сохранить", "Save")) { _, _ ->
                    val t = titleBox.text.toString().trim()
                    if (t.isNotEmpty()) {
                        val note = noteBox.text.toString().trim()
                        val who = whoBox.text.toString().trim()
                        if (card == null) {
                            board.columns[colIndex].cards.add(ScCard(ScBoards.newId(), t, note, who, due))
                        } else {
                            card.title = t
                            card.note = note
                            card.assignee = who
                            card.due = due
                        }
                        touch(board)
                        columnsAdapter?.notifyItemChanged(colIndex)
                        updateProgress()
                    }
                }
                .setNegativeButton(scTr("Отмена", "Cancel"), null)
        if (card != null) {
            builder.setNeutralButton(scTr("Удалить", "Delete")) { _, _ ->
                board.columns[colIndex].cards.remove(card)
                touch(board)
                columnsAdapter?.notifyItemChanged(colIndex)
                updateProgress()
            }
        }
        builder.show()
    }

    /* ------------------------------------------------------------------------------------------
     * Small helpers
     * ---------------------------------------------------------------------------------------- */

    private fun askText(title: String, initial: String, hint: String, onOk: (String) -> Unit) {
        val c = requireContext()
        val box = EditText(c).apply {
            setText(initial)
            this.hint = hint
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        val frame = FrameLayout(c).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(box)
        }
        MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setView(frame)
                .setPositiveButton(scTr("ОК", "OK")) { _, _ ->
                    val value = box.text.toString().trim()
                    if (value.isNotEmpty()) onOk(value)
                }
                .setNegativeButton(scTr("Отмена", "Cancel"), null)
                .show()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun dueLabel(due: String): String = if (due.isEmpty()) {
        scTr("Срок: не задан (нажмите, чтобы выбрать)", "Due date: not set (tap to choose)")
    } else {
        scTr("Срок: $due", "Due: $due")
    }

    private fun pickDate(currentDue: String, onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        val p = currentDue.split("-")
        if (p.size == 3) {
            cal.set(
                    p[0].toIntOrNull() ?: cal.get(Calendar.YEAR),
                    (p[1].toIntOrNull() ?: 1) - 1,
                    p[2].toIntOrNull() ?: 1
            )
        }
        DatePickerDialog(
                requireContext(),
                { _, y, m, day -> onPicked(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day)) },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pill(c: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val d = c.resources.displayMetrics.density
        return TextView(c).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                if (filled) {
                    setColor(ACCENT)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke((1.5f * d).toInt(), ACCENT)
                }
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun chip(c: Context): TextView = TextView(c).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        setTextColor(ACCENT)
    }

    private fun tint(tv: TextView, red: Boolean) {
        val d = tv.resources.displayMetrics.density
        tv.setTextColor(if (red) OVERDUE else ACCENT)
        tv.background = GradientDrawable().apply {
            cornerRadius = 10 * d
            setColor(if (red) Color.parseColor("#1FDC2626") else Color.parseColor("#1F4338CA"))
        }
    }

    private fun arrow(c: Context, label: String): TextView = TextView(c).apply {
        text = label
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isClickable = true
    }

    /* ------------------------------------------------------------------------------------------
     * Adapters
     * ---------------------------------------------------------------------------------------- */

    private inner class BoardsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = boards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(Color.parseColor("#12808080"))
                    setStroke(dp(1), Color.parseColor("#26808080"))
                }
                isClickable = true
            }
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            card.layoutParams = lp
            card.addView(TextView(c).apply {
                setTextColor(primary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(c).apply {
                setTextColor(secondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            })
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val board = boards[position]
            val card = holder.itemView as LinearLayout
            (card.getChildAt(0) as TextView).text = board.title
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(board.updatedAt))
            val total = board.totalCards()
            val done = board.doneCards()
            (card.getChildAt(1) as TextView).text = scTr(
                    "Задач: $total · выполнено: $done · $date",
                    "Tasks: $total · done: $done · $date"
            )
            card.setOnClickListener { showBoard(board) }
            card.setOnLongClickListener {
                boardMenu(board, false)
                true
            }
        }
    }

    private inner class ColumnsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = current?.columns?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            val width = (c.resources.displayMetrics.widthPixels * 0.86f).toInt()
            val column = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(Color.parseColor("#0F808080"))
                    setStroke(dp(1), Color.parseColor("#1F808080"))
                }
            }
            val lp = RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.marginEnd = dp(12)
            column.layoutParams = lp

            val header = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, 0, dp(8))
            }
            header.addView(TextView(c).apply {
                setTextColor(primary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(c).apply {
                setTextColor(secondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8), 0, dp(8), 0)
            })
            header.addView(TextView(c).apply {
                text = "⋯"
                setTextColor(secondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(10), dp(2), dp(6), dp(2))
                isClickable = true
            })
            column.addView(header)

            val cardsList = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c) }
            column.addView(cardsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            column.addView(TextView(c).apply {
                text = scTr("＋ Добавить задачу", "＋ Add task")
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(10), dp(6), dp(8))
                isClickable = true
            })
            return object : RecyclerView.ViewHolder(column) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val board = current ?: return
            val column = board.columns[position]
            val view = holder.itemView as LinearLayout
            val header = view.getChildAt(0) as LinearLayout
            (header.getChildAt(0) as TextView).text = column.title
            (header.getChildAt(1) as TextView).text = column.cards.size.toString()
            header.getChildAt(2).setOnClickListener { columnMenu(position) }
            val cardsList = view.getChildAt(1) as RecyclerView
            cardsList.adapter = CardsAdapter(position)
            view.getChildAt(2).setOnClickListener { editCard(position, null) }
        }
    }

    private inner class CardsAdapter(private val colIndex: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private fun cards(): List<ScCard> = current?.columns?.getOrNull(colIndex)?.cards ?: emptyList()

        override fun getItemCount(): Int = cards().size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(bgColor())
                    setStroke(dp(1), Color.parseColor("#26808080"))
                }
                isClickable = true
            }
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            card.layoutParams = lp

            card.addView(TextView(c).apply {
                setTextColor(primary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(c).apply {
                setTextColor(secondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
            val meta = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            meta.addView(chip(c))
            val whoLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            whoLp.marginStart = dp(6)
            meta.addView(chip(c), whoLp)
            card.addView(meta)

            val moves = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            moves.addView(arrow(c, "←"))
            moves.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            moves.addView(arrow(c, "→"))
            card.addView(moves)
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val board = current ?: return
            val item = board.columns[colIndex].cards[position]
            val view = holder.itemView as LinearLayout

            (view.getChildAt(0) as TextView).text = item.title
            val note = view.getChildAt(1) as TextView
            note.text = item.note
            note.visibility = if (item.note.isEmpty()) View.GONE else View.VISIBLE

            val meta = view.getChildAt(2) as LinearLayout
            val dueChip = meta.getChildAt(0) as TextView
            val whoChip = meta.getChildAt(1) as TextView
            val overdue = item.due.isNotEmpty() && item.due < today() && colIndex < board.columns.size - 1
            if (item.due.isEmpty()) {
                dueChip.visibility = View.GONE
            } else {
                dueChip.visibility = View.VISIBLE
                dueChip.text = "📅 " + item.due
                tint(dueChip, overdue)
            }
            if (item.assignee.isEmpty()) {
                whoChip.visibility = View.GONE
            } else {
                whoChip.visibility = View.VISIBLE
                whoChip.text = "👤 " + item.assignee
                tint(whoChip, false)
            }
            meta.visibility = if (item.due.isEmpty() && item.assignee.isEmpty()) View.GONE else View.VISIBLE

            val moves = view.getChildAt(3) as LinearLayout
            val left = moves.getChildAt(0)
            val right = moves.getChildAt(2)
            left.visibility = if (colIndex > 0) View.VISIBLE else View.INVISIBLE
            right.visibility = if (colIndex < board.columns.size - 1) View.VISIBLE else View.INVISIBLE
            left.setOnClickListener { moveCard(colIndex, item, colIndex - 1) }
            right.setOnClickListener { moveCard(colIndex, item, colIndex + 1) }

            view.setOnClickListener { editCard(colIndex, item) }
        }
    }

    companion object {
        private val ACCENT = Color.parseColor("#4338CA")
        private val OVERDUE = Color.parseColor("#DC2626")

        /** Adds the "Projects and tasks" card to the Science tools dialog (below "Grants"). */
        fun attachEntry(content: View, onClick: () -> Unit) {
            val root = content as? LinearLayout ?: return
            val ctx = root.context
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(64)
                isClickable = true
                isFocusable = true
                val out = TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    setBackgroundResource(out.resourceId)
                }
                setOnClickListener { onClick() }
            }
            val icon = ImageView(ctx).apply {
                setBackgroundResource(R.drawable.bg_sc_tool_icon)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.ic_sc_board)
                setColorFilter(ACCENT)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = scTr("Проекты и задачи", "Projects and tasks")
                setTextColor(boardColor(ctx, android.R.attr.textColorPrimary, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(ctx).apply {
                text = scTr("Канбан-доска для грантов, статей и команд", "Kanban board for grants, papers and teams")
                setTextColor(boardColor(ctx, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(14)
            row.addView(col, lp)

            root.addView(row, minOf(3, root.childCount))
        }
    }
}
