/*
 * Science Collab: "My literature" screen.
 * Stage 1: search open-access papers via OpenAlex and open their PDF.
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
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.core.utils.openUrlInChromeCustomTab
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
}

private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")

class ScLibraryDialogFragment : DialogFragment() {

    private data class Work(
            val title: String,
            val meta: String,
            val pdfUrl: String?,
            val landingUrl: String?,
    )

    private val works = ArrayList<Work>()
    private var listAdapter: WorkAdapter? = null
    private var statusView: TextView? = null
    private var searchBox: EditText? = null
    private var keyBox: EditText? = null

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

        // API key
        val keyHelp = TextView(ctx).apply {
            text = "Бесплатный ключ OpenAlex: openalex.org/settings/api. Он хранится только на этом телефоне."
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(keyHelp)

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
        root.addView(keyRow)

        // Search
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
        root.addView(searchRow)

        // Status
        val status = TextView(ctx).apply {
            text = "Введите тему и нажмите «Найти». Показываются только открытые статьи."
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(8))
        }
        statusView = status
        root.addView(status)

        // Results
        val adapter = WorkAdapter()
        listAdapter = adapter
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
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
                            showResults(parsed, if (parsed.isEmpty()) "Ничего не найдено." else "Найдено: ${parsed.size}")
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
            result.add(Work(title, meta, pdf, landing))
        }
        return result
    }

    private fun showResults(list: List<Work>, message: String) {
        works.clear()
        works.addAll(list)
        listAdapter?.notifyDataSetChanged()
        statusView?.text = message
    }

    private fun openWork(work: Work) {
        val url = work.pdfUrl ?: work.landingUrl
        if (url.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "У этой работы нет открытой ссылки", Toast.LENGTH_SHORT).show()
            return
        }
        openUrlInChromeCustomTab(requireContext(), null, url)
    }

    private fun pill(ctx: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
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
            (col.getChildAt(0) as TextView).text = work.title
            (col.getChildAt(1) as TextView).text = work.meta + if (work.pdfUrl != null) "  ·  PDF" else ""
            col.setOnClickListener { openWork(work) }
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
                text = "Поиск и чтение открытых статей"
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
