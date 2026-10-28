/*
 * Science Collab: "My literature" screen.
 * Stage 2: search open-access papers via OpenAlex, save PDFs inside the app, read them in the app.
 */

package im.vector.app.features.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.utils.openUrlInChromeCustomTab
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
    return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
}

private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")

class ScLibraryDialogFragment : DialogFragment() {

    private data class Work(
            val id: String,
            val title: String,
            val meta: String,
            val pdfUrl: String?,
            val landingUrl: String?,
    )

    private val works = ArrayList<Work>()
    private var saved: List<ScSavedPaper> = emptyList()
    private var downloading = false
    private var searchAdapter: WorkAdapter? = null
    private var savedAdapter: SavedAdapter? = null
    private var statusView: TextView? = null
    private var savedEmptyView: TextView? = null
    private var searchBox: EditText? = null
    private var keyBox: EditText? = null
    private var searchPane: View? = null
    private var savedPane: View? = null
    private var tabSearch: TextView? = null
    private var tabSaved: TextView? = null

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawable(ColorDrawable(themeColor(requireContext(), android.R.attr.colorBackground, Color.WHITE)))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val bg = themeColor(ctx, android.R.attr.colorBackground, Color.WHITE)
        val primary = themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK)
        val secondary = themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        // Top bar
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "Моя литература"
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(ctx, "Закрыть", false) { dismiss() })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Tabs
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val tabSearchView = pill(ctx, "Поиск", true) { showSavedTab(false) }
        val tabSavedView = pill(ctx, "Сохранённые", false) { showSavedTab(true) }
        tabSearch = tabSearchView
        tabSaved = tabSavedView
        tabs.addView(tabSearchView)
        val tabGap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tabGap.marginStart = dp(8)
        tabs.addView(tabSavedView, tabGap)
        root.addView(tabs)

        // Search pane
        val searchLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        searchPane = searchLayout

        val keyHelp = TextView(ctx).apply {
            text = "Бесплатный ключ OpenAlex: openalex.org/settings/api. Он хранится только на этом телефоне."
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(4))
        }
        searchLayout.addView(keyHelp)

        val keyRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val key = EditText(ctx).apply {
            hint = "Ключ OpenAlex"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs().getString(KEY_API, "") ?: "")
            setTextColor(primary)
            setHintTextColor(secondary)
            textSize = 14f
        }
        keyBox = key
        keyRow.addView(key, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(pill(ctx, "Сохранить", false) {
            prefs().edit().putString(KEY_API, key.text.toString().trim()).apply()
            Toast.makeText(requireContext(), "Ключ сохранён", Toast.LENGTH_SHORT).show()
        })
        searchLayout.addView(keyRow)

        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val search = EditText(ctx).apply {
            hint = "Тема, автор или название"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(primary)
            setHintTextColor(secondary)
            textSize = 16f
        }
        searchBox = search
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(pill(ctx, "Найти", true) { runSearch() })
        searchLayout.addView(searchRow)

        val status = TextView(ctx).apply {
            text = "Введите тему и нажмите «Найти». Показываются только открытые статьи."
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(8))
        }
        statusView = status
        searchLayout.addView(status)

        val workAdapter = WorkAdapter()
        searchAdapter = workAdapter
        val resultList = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = workAdapter
        }
        searchLayout.addView(resultList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(searchLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Saved pane
        val savedLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        savedPane = savedLayout
        val empty = TextView(ctx).apply {
            text = "Здесь появятся статьи, которые вы откроете из поиска. " +
                    "Файлы хранятся внутри приложения и не попадают в галерею и загрузки телефона."
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16), 0, dp(8))
        }
        savedEmptyView = empty
        savedLayout.addView(empty)
        val savedListAdapter = SavedAdapter()
        savedAdapter = savedListAdapter
        val savedList = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = savedListAdapter
        }
        savedLayout.addView(savedList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(savedLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshSaved()
        return root
    }

    private fun showSavedTab(show: Boolean) {
        searchPane?.visibility = if (show) View.GONE else View.VISIBLE
        savedPane?.visibility = if (show) View.VISIBLE else View.GONE
        tabSearch?.let { stylePill(it, !show) }
        tabSaved?.let { stylePill(it, show) }
    }

    private fun refreshSaved() {
        saved = ScLibraryStore.list(requireContext())
        tabSaved?.text = if (saved.isEmpty()) "Сохранённые" else "Сохранённые (${saved.size})"
        savedEmptyView?.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
        savedAdapter?.notifyDataSetChanged()
        searchAdapter?.notifyDataSetChanged()
    }

    private fun runSearch() {
        val query = searchBox?.text?.toString()?.trim().orEmpty()
        val key = keyBox?.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            statusView?.text = "Сначала вставьте ключ OpenAlex и нажмите «Сохранить»."
            return
        }
        if (query.isEmpty()) {
            statusView?.text = "Введите тему, автора или название."
            return
        }
        prefs().edit().putString(KEY_API, key).apply()
        statusView?.text = "Поиск…"
        Thread {
            try {
                val url = "https://api.openalex.org/works?search=" + URLEncoder.encode(query, "UTF-8") +
                        "&filter=open_access.is_oa:true&per-page=25" +
                        "&select=id,title,publication_year,authorships,best_oa_location,doi" +
                        "&api_key=" + URLEncoder.encode(key, "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code in 200..299) {
                    val parsed = parseWorks(body)
                    activity?.runOnUiThread {
                        if (isAdded) {
                            showResults(
                                    parsed,
                                    if (parsed.isEmpty()) {
                                        "Ничего не найдено."
                                    } else {
                                        "Найдено: ${parsed.size}. Нажмите на статью, чтобы сохранить её в приложении и читать. " +
                                                "Долгое нажатие откроет статью в браузере."
                                    }
                            )
                        }
                    }
                } else {
                    val message = when (code) {
                        401, 403 -> "Ключ не принят. Проверьте ключ."
                        409, 429 -> "Нужен действующий ключ или исчерпан дневной лимит."
                        else -> "Ошибка сервера (код $code)."
                    }
                    activity?.runOnUiThread {
                        if (isAdded) showResults(emptyList(), message)
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (isAdded) showResults(emptyList(), "Нет соединения или ошибка запроса.")
                }
            }
        }.start()
    }

    private fun parseWorks(body: String): List<Work> {
        val result = ArrayList<Work>()
        val root = JSONObject(body)
        val arr: JSONArray = root.optJSONArray("results") ?: return result
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.str("title").ifEmpty { "Без названия" }
            val names = ArrayList<String>()
            val authors = o.optJSONArray("authorships")
            if (authors != null) {
                for (j in 0 until minOf(authors.length(), 3)) {
                    val name = authors.optJSONObject(j)?.optJSONObject("author")?.str("display_name").orEmpty()
                    if (name.isNotEmpty()) names.add(name)
                }
            }
            val year = if (o.isNull("publication_year")) "" else o.optInt("publication_year").toString()
            val meta = listOf(names.joinToString(", "), year).filter { it.isNotEmpty() }.joinToString(" · ")
            val loc = o.optJSONObject("best_oa_location")
            val pdf = loc?.str("pdf_url")?.ifEmpty { null }
            val landing = loc?.str("landing_page_url")?.ifEmpty { null } ?: o.str("doi").ifEmpty { null }
            val id = o.str("id").ifEmpty { pdf ?: (title + year) }
            result.add(Work(id, title, meta, pdf, landing))
        }
        return result
    }

    private fun showResults(list: List<Work>, message: String) {
        works.clear()
        works.addAll(list)
        searchAdapter?.notifyDataSetChanged()
        statusView?.text = message
    }

    private fun onWorkClicked(work: Work) {
        val existing = saved.firstOrNull { it.id == work.id }
        if (existing != null) {
            openReader(existing)
            return
        }
        val pdf = work.pdfUrl
        if (pdf.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Прямого PDF нет, открываю страницу статьи", Toast.LENGTH_SHORT).show()
            openInBrowser(work)
            return
        }
        downloadAndOpen(work, pdf)
    }

    private fun downloadAndOpen(work: Work, pdfUrl: String) {
        if (downloading) {
            Toast.makeText(requireContext(), "Дождитесь окончания загрузки", Toast.LENGTH_SHORT).show()
            return
        }
        downloading = true
        statusView?.text = "Скачиваю: ${work.title}"
        val appCtx = requireContext().applicationContext
        Thread {
            val error = ScLibraryStore.download(appCtx, work.id, pdfUrl, work.title, work.meta)
            activity?.runOnUiThread {
                downloading = false
                if (!isAdded) return@runOnUiThread
                if (error == null) {
                    refreshSaved()
                    statusView?.text = "Сохранено в приложении."
                    saved.firstOrNull { it.id == work.id }?.let { openReader(it) }
                } else {
                    statusView?.text = "$error Долгое нажатие откроет статью в браузере."
                }
            }
        }.start()
    }

    private fun openReader(paper: ScSavedPaper) {
        val file = ScLibraryStore.fileOf(requireContext(), paper)
        if (!file.exists()) {
            ScLibraryStore.remove(requireContext(), paper)
            refreshSaved()
            Toast.makeText(requireContext(), "Файл не найден, скачайте статью заново", Toast.LENGTH_SHORT).show()
            return
        }
        ScPdfReaderFragment.newInstance(file.absolutePath, paper.title).show(parentFragmentManager, "ScPdfReader")
    }

    private fun openInBrowser(work: Work) {
        val url = work.pdfUrl ?: work.landingUrl
        if (url.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "У этой работы нет открытой ссылки", Toast.LENGTH_SHORT).show()
            return
        }
        openUrlInChromeCustomTab(requireContext(), null, url)
    }

    private fun confirmDelete(paper: ScSavedPaper) {
        val appCtx = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
                .setTitle("Удалить из библиотеки?")
                .setMessage(paper.title)
                .setPositiveButton("Удалить") { _, _ ->
                    ScLibraryStore.remove(appCtx, paper)
                    if (isAdded) refreshSaved()
                }
                .setNegativeButton("Отмена", null)
                .show()
    }

    private fun stylePill(tv: TextView, filled: Boolean) {
        val d = tv.resources.displayMetrics.density
        tv.setTextColor(if (filled) Color.WHITE else ACCENT)
        tv.background = GradientDrawable().apply {
            cornerRadius = 14 * d
            if (filled) {
                setColor(ACCENT)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * d).toInt(), ACCENT)
            }
        }
    }

    private fun pill(ctx: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val d = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
        stylePill(tv, filled)
        return tv
    }

    private inner class WorkAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = works.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
                isClickable = true
            }
            val t = TextView(ctx).apply {
                setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val m = TextView(ctx).apply {
                setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            col.addView(t)
            col.addView(m)
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val work = works[position]
            val col = holder.itemView as LinearLayout
            val isSaved = saved.any { it.id == work.id }
            val tag = when {
                isSaved -> "  ·  ✓ сохранено"
                work.pdfUrl != null -> "  ·  PDF"
                else -> ""
            }
            (col.getChildAt(0) as TextView).text = work.title
            (col.getChildAt(1) as TextView).text = work.meta + tag
            col.setOnClickListener { onWorkClicked(work) }
            col.setOnLongClickListener {
                openInBrowser(work)
                true
            }
        }
    }

    private inner class SavedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = saved.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
                isClickable = true
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val t = TextView(ctx).apply {
                setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val m = TextView(ctx).apply {
                setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            col.addView(t)
            col.addView(m)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val del = TextView(ctx).apply {
                text = "✕"
                setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding((16 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
                isClickable = true
            }
            row.addView(del)
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val paper = saved[position]
            val row = holder.itemView as LinearLayout
            val col = row.getChildAt(0) as LinearLayout
            (col.getChildAt(0) as TextView).text = paper.title
            (col.getChildAt(1) as TextView).text = paper.meta
            row.setOnClickListener { openReader(paper) }
            row.getChildAt(1).setOnClickListener { confirmDelete(paper) }
        }
    }

    companion object {
        private const val PREFS = "sc_library"
        private const val KEY_API = "openalex_api_key"
        private val ACCENT = Color.parseColor("#4338CA")

        /** Adds the "My literature" card to the Science tools dialog. */
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
                setImageResource(R.drawable.ic_sc_nav_library)
                setColorFilter(ACCENT)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(ctx).apply {
                text = "Моя литература"
                setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val desc = TextView(ctx).apply {
                text = "Поиск, сохранение и чтение статей"
                setTextColor(themeColor(ctx, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            col.addView(name)
            col.addView(desc)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(14)
            row.addView(col, lp)

            root.addView(row, 1)
        }
    }
}
