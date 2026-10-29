/*
 * Science Collab: "Grants" screen. Cards, region chips, search; live US search via Grants.gov.
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.core.utils.openUrlInChromeCustomTab

private fun grantsColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
    return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
}

class ScGrantsDialogFragment : DialogFragment() {

    private var region = ScGrants.REGION_ALL
    private var cards: List<ScGrantCard> = emptyList()
    private var cardAdapter: CardAdapter? = null
    private var searchBox: EditText? = null
    private var statusView: TextView? = null
    private val chipViews = LinkedHashMap<String, TextView>()
    private var loading = false

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawable(ColorDrawable(grantsColor(requireContext(), android.R.attr.colorBackground, Color.WHITE)))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val bg = grantsColor(ctx, android.R.attr.colorBackground, Color.WHITE)
        val primary = grantsColor(ctx, android.R.attr.textColorPrimary, Color.BLACK)
        val secondary = grantsColor(ctx, android.R.attr.textColorSecondary, Color.GRAY)

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
            text = scTr("Гранты", "Grants")
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(ctx, scTr("Закрыть", "Close"), false) { dismiss() })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Region chips
        val chipsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chipDefs = listOf(
                ScGrants.REGION_ALL to scTr("Все", "All"),
                ScGrants.REGION_UZ to scTr("Узбекистан", "Uzbekistan"),
                ScGrants.REGION_KZ to scTr("Казахстан", "Kazakhstan"),
                ScGrants.REGION_INT to scTr("Международные", "International"),
                ScGrants.REGION_US to scTr("США · live", "USA · live"),
        )
        for ((key, label) in chipDefs) {
            val chip = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isClickable = true
                setOnClickListener { selectRegion(key) }
            }
            styleChip(chip, key == region)
            chipViews[key] = chip
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            chipsRow.addView(chip, lp)
        }
        val chipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }
        val chipsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chipsLp.topMargin = dp(12)
        root.addView(chipsScroll, chipsLp)

        // Search
        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val search = EditText(ctx).apply {
            hint = scTr("Тема, ключевое слово или страна", "Topic, keyword or country")
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
                onSearch()
                true
            } else {
                false
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(pill(ctx, scTr("Найти", "Search"), true) { onSearch() })
        root.addView(searchRow)

        // Status
        val status = TextView(ctx).apply {
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(10))
        }
        statusView = status
        root.addView(status)

        // Cards
        val adapterLocal = CardAdapter()
        cardAdapter = adapterLocal
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapterLocal
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshCatalog()
        return root
    }

    private fun selectRegion(key: String) {
        region = key
        for ((k, chip) in chipViews) styleChip(chip, k == key)
        onSearch()
    }

    private fun onSearch() {
        if (region == ScGrants.REGION_US) runUsSearch() else refreshCatalog()
    }

    private fun refreshCatalog() {
        cards = ScGrants.catalog(region, searchBox?.text?.toString().orEmpty())
        cardAdapter?.notifyDataSetChanged()
        statusView?.text = if (cards.isEmpty()) {
            scTr("Ничего не найдено.", "Nothing found.")
        } else {
            scTr(
                    "Справочник программ. Актуальные сроки смотрите по ссылке в карточке.",
                    "A directory of programmes. Check the link in each card for current deadlines."
            )
        }
    }

    private fun runUsSearch() {
        if (loading) return
        loading = true
        val keyword = searchBox?.text?.toString()?.trim().orEmpty()
        statusView?.text = scTr("Ищу в Grants.gov…", "Searching Grants.gov…")
        Thread {
            val result: List<ScGrantCard>? = try {
                ScGrants.searchUs(keyword)
            } catch (e: Exception) {
                null
            }
            activity?.runOnUiThread {
                loading = false
                if (!isAdded || region != ScGrants.REGION_US) return@runOnUiThread
                if (result == null) {
                    cards = emptyList()
                    cardAdapter?.notifyDataSetChanged()
                    statusView?.text = scTr(
                            "Не удалось загрузить. Проверьте соединение.",
                            "Could not load. Check your connection."
                    )
                } else {
                    cards = result
                    cardAdapter?.notifyDataSetChanged()
                    statusView?.text = if (result.isEmpty()) {
                        scTr("Ничего не найдено.", "Nothing found.")
                    } else {
                        scTr(
                                "Grants.gov (США): ${result.size}. Многие конкурсы только для организаций США, условия смотрите в карточке. " +
                                        "Данные Grants.gov, не одобрено HHS.",
                                "Grants.gov (USA): ${result.size}. Many calls are for US organizations only, see the card for eligibility. " +
                                        "Data from Grants.gov, not endorsed by HHS."
                        )
                    }
                }
            }
        }.start()
    }

    private fun openCard(c: ScGrantCard) {
        val url = c.url
        if (url.isNullOrEmpty()) return
        openUrlInChromeCustomTab(requireContext(), null, url)
    }

    private fun styleChip(tv: TextView, selected: Boolean) {
        val d = tv.resources.displayMetrics.density
        tv.setTextColor(if (selected) Color.WHITE else ACCENT)
        tv.background = GradientDrawable().apply {
            cornerRadius = 20 * d
            if (selected) {
                setColor(ACCENT)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * d).toInt(), ACCENT)
            }
        }
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

    private fun tagChip(ctx: Context, label: String): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(Color.parseColor("#1F4338CA"))
            }
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val primary = grantsColor(ctx, android.R.attr.textColorPrimary, Color.BLACK)
            val secondary = grantsColor(ctx, android.R.attr.textColorSecondary, Color.GRAY)

            val card = LinearLayout(ctx).apply {
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

            card.addView(TextView(ctx).apply {
                setTextColor(primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(ctx).apply {
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(TextView(ctx).apply {
                setTextColor(primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(8), 0, 0)
            })
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            })
            card.addView(TextView(ctx).apply {
                text = scTr("Открыть →", "Open →")
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, 0)
            })
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = cards[position]
            val card = holder.itemView as LinearLayout
            val d = card.resources.displayMetrics.density

            (card.getChildAt(0) as TextView).text = c.title
            val sub = card.getChildAt(1) as TextView
            sub.text = c.subtitle
            sub.visibility = if (c.subtitle.isEmpty()) View.GONE else View.VISIBLE
            val body = card.getChildAt(2) as TextView
            body.text = c.body
            body.visibility = if (c.body.isEmpty()) View.GONE else View.VISIBLE

            val tagsRow = card.getChildAt(3) as LinearLayout
            tagsRow.removeAllViews()
            for (t in c.tags) {
                val tagLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tagLp.marginEnd = (6 * d).toInt()
                tagsRow.addView(tagChip(card.context, t), tagLp)
            }
            tagsRow.visibility = if (c.tags.isEmpty()) View.GONE else View.VISIBLE

            card.getChildAt(4).visibility = if (c.url == null) View.GONE else View.VISIBLE
            card.setOnClickListener { openCard(c) }
        }
    }

    companion object {
        private val ACCENT = Color.parseColor("#4338CA")

        /** Adds the "Grants" card to the Science tools dialog (below "My literature"). */
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
                setImageResource(R.drawable.ic_sc_nav_projects)
                setColorFilter(ACCENT)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = scTr("Гранты", "Grants")
                setTextColor(grantsColor(ctx, android.R.attr.textColorPrimary, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(ctx).apply {
                text = scTr("Конкурсы и программы финансирования", "Funding calls and programmes")
                setTextColor(grantsColor(ctx, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(14)
            row.addView(col, lp)

            root.addView(row, minOf(2, root.childCount))
        }
    }
}
