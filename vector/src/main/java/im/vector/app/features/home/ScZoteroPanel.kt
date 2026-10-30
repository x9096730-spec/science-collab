/*
 * Science Collab: Zotero tab of "My library" (connect, browse, search, read PDFs, add papers).
 */

package im.vector.app.features.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.utils.openUrlInChromeCustomTab
import java.text.NumberFormat

internal class ScZoteroPanel(
        private val fragment: DialogFragment,
        private val openReader: (ScSavedPaper) -> Unit,
        private val onSaved: () -> Unit,
) {

    private var root: LinearLayout? = null
    private var creds: ScZoteroCreds? = null
    private var libraries: List<ScZLibrary> = emptyList()
    private var collections: List<ScZCollection> = emptyList()
    private var libIndex = 0
    private var collectionKey: String? = null
    private val items = ArrayList<ScZItem>()
    private var total = 0L
    private var loading = false
    private var busy = false
    private var generation = 0
    private var lastQuery = ""
    private var started = false
    private var itemAdapter: ItemAdapter? = null
    private var statusView: TextView? = null
    private var searchBox: EditText? = null
    private var libraryHolder: FrameLayout? = null
    private var collectionHolder: FrameLayout? = null

    private fun ctx(): Context = fragment.requireContext()

    private fun post(block: () -> Unit) {
        fragment.activity?.runOnUiThread {
            if (fragment.isAdded) block()
        }
    }

    private fun color(c: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!c.theme.resolveAttribute(attr, tv, true)) return fallback
        return if (tv.resourceId != 0) ContextCompat.getColor(c, tv.resourceId) else tv.data
    }

    private fun noConnection(): String = scTr("Нет соединения или ошибка запроса.", "No connection or request error.")

    /* ------------------------------------------------------------------------------------------
     * Entry points
     * ---------------------------------------------------------------------------------------- */

    fun build(context: Context): View {
        val r = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root = r
        creds = ScZotero.creds(context)
        if (creds == null) showConnect() else showLibrary()
        return r
    }

    /** Called when the tab becomes visible: loads the library the first time. */
    fun onShown() {
        if (creds != null && !started) {
            started = true
            loadLibraries()
        }
    }

    /** "Add to Zotero" from search results. */
    fun addToZotero(work: ScFoundWork, onNeedConnect: () -> Unit) {
        val c = ScZotero.creds(ctx())
        if (c == null) {
            Toast.makeText(
                    ctx(),
                    scTr("Сначала подключите Zotero на вкладке Zotero.", "Connect Zotero on the Zotero tab first."),
                    Toast.LENGTH_LONG
            ).show()
            onNeedConnect()
            return
        }
        if (!c.canWrite) {
            Toast.makeText(
                    ctx(),
                    scTr(
                            "У ключа нет права записи. Создайте ключ с разрешением на запись.",
                            "The key has no write permission. Create a key that allows writing."
                    ),
                    Toast.LENGTH_LONG
            ).show()
            return
        }
        Thread {
            val libs = ScZotero.libraries(c)
            post { chooseLibrary(c, libs, work) }
        }.start()
    }

    private fun chooseLibrary(c: ScZoteroCreds, libs: List<ScZLibrary>, work: ScFoundWork) {
        if (libs.size == 1) {
            chooseCollection(c, libs[0], work)
            return
        }
        MaterialAlertDialogBuilder(ctx())
                .setTitle(scTr("В какую библиотеку?", "Which library?"))
                .setItems(libs.map { it.name }.toTypedArray()) { _, which -> chooseCollection(c, libs[which], work) }
                .show()
    }

    private fun chooseCollection(c: ScZoteroCreds, lib: ScZLibrary, work: ScFoundWork) {
        Thread {
            val cols = try {
                ScZotero.collections(c, lib.prefix)
            } catch (e: Exception) {
                emptyList<ScZCollection>()
            }
            post {
                val labels = ArrayList<String>()
                labels.add(scTr("Без коллекции", "No collection"))
                for (col in cols) labels.add(col.name)
                MaterialAlertDialogBuilder(ctx())
                        .setTitle(scTr("В какую коллекцию?", "Which collection?"))
                        .setItems(labels.toTypedArray()) { _, which ->
                            doAdd(c, lib, if (which == 0) null else cols[which - 1].key, work)
                        }
                        .show()
            }
        }.start()
    }

    private fun doAdd(c: ScZoteroCreds, lib: ScZLibrary, collectionKey: String?, work: ScFoundWork) {
        val url = work.landingUrl ?: work.pdfUrl ?: ""
        Thread {
            var error: String? = null
            try {
                ScZotero.addItem(c, lib.prefix, collectionKey, work.title, work.authors, work.year, work.doi, url, work.publication)
            } catch (e: ScZoteroException) {
                error = e.message
            } catch (e: Exception) {
                error = noConnection()
            }
            val err = error
            post {
                Toast.makeText(
                        ctx(),
                        err ?: scTr("Добавлено в Zotero ✓", "Added to Zotero ✓"),
                        Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /* ------------------------------------------------------------------------------------------
     * Connect screen
     * ---------------------------------------------------------------------------------------- */

    private fun showConnect() {
        val r = root ?: return
        val c = ctx()
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = color(c, android.R.attr.textColorPrimary, Color.BLACK)
        val secondary = color(c, android.R.attr.textColorSecondary, Color.GRAY)

        r.removeAllViews()
        libraryHolder = null
        collectionHolder = null
        searchBox = null
        itemAdapter = null
        statusView = null

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.parseColor("#12808080"))
                setStroke(dp(1), Color.parseColor("#26808080"))
            }
        }
        card.addView(TextView(c).apply {
            text = scTr("Подключите Zotero", "Connect Zotero")
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(c).apply {
            text = scTr(
                    "1. Нажмите «Создать ключ» и войдите в свой аккаунт Zotero.\n" +
                            "2. Разрешите доступ к библиотеке и запись (library access, write access) и нажмите Save Key.\n" +
                            "3. Скопируйте ключ и вставьте ниже.",
                    "1. Tap “Create key” and sign in to your Zotero account.\n" +
                            "2. Allow library access and write access, then tap Save Key.\n" +
                            "3. Copy the key and paste it below."
            )
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(10), 0, dp(10))
        })
        val createLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        card.addView(pill(c, scTr("Создать ключ на zotero.org", "Create key on zotero.org"), false) {
            openUrlInChromeCustomTab(c, null, "https://www.zotero.org/settings/keys/new")
        }, createLp)

        val keyField = EditText(c).apply {
            hint = scTr("Ключ Zotero", "Zotero key")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(primary)
            setHintTextColor(secondary)
            textSize = 15f
        }
        val keyLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        keyLp.topMargin = dp(12)
        card.addView(keyField, keyLp)

        val status = TextView(c).apply {
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(6))
            text = scTr("Ключ хранится только на этом телефоне.", "The key is stored only on this phone.")
        }
        val connectLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        connectLp.topMargin = dp(8)
        card.addView(pill(c, scTr("Подключить", "Connect"), true) {
            connect(keyField.text.toString().trim(), status)
        }, connectLp)
        card.addView(status)

        val scroll = ScrollView(c).apply { addView(card) }
        val scrollLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        scrollLp.topMargin = dp(12)
        r.addView(scroll, scrollLp)
    }

    private fun connect(key: String, status: TextView) {
        if (key.isEmpty()) {
            status.text = scTr("Вставьте ключ.", "Paste the key.")
            return
        }
        if (busy) return
        busy = true
        status.text = scTr("Проверяю ключ…", "Checking the key…")
        val appCtx = ctx().applicationContext
        Thread {
            var result: ScZoteroCreds? = null
            var error: String? = null
            try {
                val verified = ScZotero.verify(key)
                ScZotero.save(appCtx, verified)
                result = verified
            } catch (e: ScZoteroException) {
                error = e.message
            } catch (e: Exception) {
                error = noConnection()
            }
            val ok = result
            val err = error
            post {
                busy = false
                if (ok == null) {
                    status.text = err ?: noConnection()
                } else {
                    creds = ok
                    started = true
                    showLibrary()
                    loadLibraries()
                }
            }
        }.start()
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(ctx())
                .setTitle(scTr("Отключить Zotero?", "Disconnect Zotero?"))
                .setMessage(
                        scTr(
                                "Ключ будет удалён с этого телефона. Ваша библиотека в Zotero не изменится.",
                                "The key will be removed from this phone. Your Zotero library will not change."
                        )
                )
                .setPositiveButton(scTr("Отключить", "Disconnect")) { _, _ ->
                    ScZotero.disconnect(ctx().applicationContext)
                    creds = null
                    started = false
                    libraries = emptyList()
                    collections = emptyList()
                    items.clear()
                    generation++
                    showConnect()
                }
                .setNegativeButton(scTr("Отмена", "Cancel"), null)
                .show()
    }

    /* ------------------------------------------------------------------------------------------
     * Library screen
     * ---------------------------------------------------------------------------------------- */

    private fun showLibrary() {
        val r = root ?: return
        val c = ctx()
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = color(c, android.R.attr.textColorPrimary, Color.BLACK)
        val secondary = color(c, android.R.attr.textColorSecondary, Color.GRAY)

        r.removeAllViews()
        items.clear()
        total = 0L
        generation++

        // Header
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        header.addView(TextView(c).apply {
            val name = creds?.username.orEmpty()
            text = if (name.isEmpty()) "Zotero" else "Zotero · $name"
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(c).apply {
            text = scTr("Выйти", "Disconnect")
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            setOnClickListener { confirmDisconnect() }
        })
        r.addView(header)

        // Chips
        val libHolder = FrameLayout(c)
        libraryHolder = libHolder
        r.addView(libHolder)
        val colHolder = FrameLayout(c)
        collectionHolder = colHolder
        val colLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        colLp.topMargin = dp(6)
        r.addView(colHolder, colLp)

        // Search
        val searchRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val search = EditText(c).apply {
            hint = scTr("Поиск в библиотеке", "Search your library")
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
                loadItems(true)
                true
            } else {
                false
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(pill(c, scTr("Найти", "Search"), true) { loadItems(true) })
        r.addView(searchRow)

        val status = TextView(c).apply {
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(8))
        }
        statusView = status
        r.addView(status)

        val adapter = ItemAdapter()
        itemAdapter = adapter
        val list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c)
            this.adapter = adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (dy > 0 && !loading && items.size < total && lm.findLastVisibleItemPosition() >= items.size - 5) {
                        loadItems(false)
                    }
                }
            })
        }
        r.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        renderChips()
    }

    private fun renderChips() {
        libraryHolder?.let { h ->
            h.removeAllViews()
            if (libraries.size > 1) {
                h.addView(chipScroll(libraries.map { it.name }, libIndex) { i -> selectLibrary(i) })
                h.visibility = View.VISIBLE
            } else {
                h.visibility = View.GONE
            }
        }
        collectionHolder?.let { h ->
            h.removeAllViews()
            val labels = ArrayList<String>()
            labels.add(scTr("Все материалы", "All items"))
            for (col in collections) labels.add(if (col.count > 0) "${col.name} (${col.count})" else col.name)
            val selected = if (collectionKey == null) 0 else collections.indexOfFirst { it.key == collectionKey } + 1
            h.addView(chipScroll(labels, selected) { i -> selectCollection(if (i == 0) null else collections[i - 1].key) })
        }
    }

    private fun chipScroll(labels: List<String>, selected: Int, onClick: (Int) -> Unit): View {
        val c = ctx()
        val d = c.resources.displayMetrics.density
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, label) in labels.withIndex()) {
            val chip = TextView(c).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                isClickable = true
                setOnClickListener { onClick(i) }
            }
            stylePill(chip, i == selected, 20f)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8 * d).toInt()
            row.addView(chip, lp)
        }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /* ------------------------------------------------------------------------------------------
     * Loading
     * ---------------------------------------------------------------------------------------- */

    private fun loadLibraries() {
        val c = creds ?: return
        statusView?.text = scTr("Загружаю библиотеку…", "Loading your library…")
        Thread {
            val libs = ScZotero.libraries(c)
            post {
                libraries = libs
                libIndex = 0
                renderChips()
                loadCollections()
                loadItems(true)
            }
        }.start()
    }

    private fun loadCollections() {
        val c = creds ?: return
        val prefix = libraries.getOrNull(libIndex)?.prefix ?: return
        Thread {
            val cols = try {
                ScZotero.collections(c, prefix)
            } catch (e: Exception) {
                emptyList<ScZCollection>()
            }
            post {
                if (libraries.getOrNull(libIndex)?.prefix == prefix) {
                    collections = cols
                    renderChips()
                }
            }
        }.start()
    }

    private fun selectLibrary(i: Int) {
        if (i == libIndex) return
        libIndex = i
        collectionKey = null
        collections = emptyList()
        renderChips()
        loadCollections()
        loadItems(true)
    }

    private fun selectCollection(key: String?) {
        if (key == collectionKey) return
        collectionKey = key
        renderChips()
        loadItems(true)
    }

    private fun loadItems(reset: Boolean) {
        val c = creds ?: return
        val prefix = libraries.getOrNull(libIndex)?.prefix ?: return
        if (reset) {
            generation++
            items.clear()
            total = 0L
            loading = false
            lastQuery = searchBox?.text?.toString()?.trim().orEmpty()
            itemAdapter?.notifyDataSetChanged()
        }
        if (loading) return
        loading = true
        val gen = generation
        val start = items.size
        val query = lastQuery
        val coll = collectionKey
        statusView?.text = if (reset) scTr("Загружаю…", "Loading…") else scTr("Загружаю ещё…", "Loading more…")
        Thread {
            var page: ScZPage? = null
            var error: String? = null
            try {
                page = ScZotero.items(c, prefix, coll, query, start)
            } catch (e: ScZoteroException) {
                error = e.message
            } catch (e: Exception) {
                error = noConnection()
            }
            val result = page
            val err = error
            post {
                loading = false
                if (gen != generation) return@post
                if (result == null) {
                    statusView?.text = err ?: noConnection()
                    return@post
                }
                val before = items.size
                items.addAll(result.items)
                total = result.total
                if (reset) {
                    itemAdapter?.notifyDataSetChanged()
                } else {
                    itemAdapter?.notifyItemRangeInserted(before, result.items.size)
                }
                statusView?.text = if (items.isEmpty()) {
                    scTr("Здесь пока ничего нет.", "Nothing here yet.")
                } else {
                    val totalText = if (total > items.size) NumberFormat.getInstance().format(total) else items.size.toString()
                    scTr(
                            "Показано ${items.size} из $totalText. Нажмите на запись, чтобы читать PDF. Долгое нажатие: меню.",
                            "Showing ${items.size} of $totalText. Tap an item to read its PDF. Long-press for the menu."
                    )
                }
            }
        }.start()
    }

    /* ------------------------------------------------------------------------------------------
     * Item actions
     * ---------------------------------------------------------------------------------------- */

    private fun linkOf(item: ScZItem): String = if (item.doi.isNotEmpty()) "https://doi.org/" + item.doi else item.url

    private fun readPdf(item: ScZItem) {
        val c = creds ?: return
        val prefix = libraries.getOrNull(libIndex)?.prefix ?: return
        val appCtx = ctx().applicationContext
        val id = "zotero:$prefix:${item.key}"
        val existing = ScLibraryStore.list(appCtx).firstOrNull { it.id == id }
        if (existing != null && ScLibraryStore.fileOf(appCtx, existing).exists()) {
            openReader(existing)
            return
        }
        if (busy) return
        busy = true
        statusView?.text = scTr("Ищу PDF в Zotero…", "Looking for the PDF in Zotero…")
        Thread {
            var paper: ScSavedPaper? = null
            var error: String? = null
            var noPdf = false
            try {
                val attachment = ScZotero.findPdf(c, prefix, item)
                if (attachment == null) {
                    noPdf = true
                } else {
                    paper = ScZotero.savePdf(appCtx, c, prefix, item, attachment)
                }
            } catch (e: ScZoteroException) {
                error = e.message
            } catch (e: Exception) {
                error = noConnection()
            }
            val saved = paper
            val err = error
            val missing = noPdf
            post {
                busy = false
                when {
                    saved != null -> {
                        onSaved()
                        statusView?.text = scTr("Сохранено в приложении.", "Saved in the app.")
                        openReader(saved)
                    }
                    missing -> openLinkInstead(item)
                    else -> statusView?.text = err ?: noConnection()
                }
            }
        }.start()
    }

    private fun openLinkInstead(item: ScZItem) {
        val link = linkOf(item)
        if (link.isEmpty()) {
            statusView?.text = scTr("У этой записи нет PDF и ссылки.", "This item has no PDF and no link.")
            return
        }
        Toast.makeText(
                ctx(),
                scTr("PDF в Zotero нет, открываю ссылку.", "No PDF in Zotero, opening the link."),
                Toast.LENGTH_SHORT
        ).show()
        openUrlInChromeCustomTab(ctx(), null, link)
    }

    private fun showItemMenu(item: ScZItem) {
        val options = arrayOf(
                scTr("Читать PDF", "Read PDF"),
                scTr("Открыть ссылку / DOI", "Open link / DOI"),
                scTr("Копировать DOI или ссылку", "Copy DOI or link"),
        )
        MaterialAlertDialogBuilder(ctx())
                .setTitle(item.title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> readPdf(item)
                        1 -> {
                            val link = linkOf(item)
                            if (link.isEmpty()) {
                                Toast.makeText(ctx(), scTr("Ссылки нет.", "No link."), Toast.LENGTH_SHORT).show()
                            } else {
                                openUrlInChromeCustomTab(ctx(), null, link)
                            }
                        }
                        else -> {
                            val text = linkOf(item)
                            if (text.isEmpty()) {
                                Toast.makeText(ctx(), scTr("Ссылки нет.", "No link."), Toast.LENGTH_SHORT).show()
                            } else {
                                val clipboard = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
                                Toast.makeText(ctx(), scTr("Скопировано.", "Copied."), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .show()
    }

    /* ------------------------------------------------------------------------------------------
     * Views
     * ---------------------------------------------------------------------------------------- */

    private fun stylePill(tv: TextView, filled: Boolean, radiusDp: Float) {
        val d = tv.resources.displayMetrics.density
        tv.setTextColor(if (filled) Color.WHITE else ACCENT)
        tv.background = GradientDrawable().apply {
            cornerRadius = radiusDp * d
            if (filled) {
                setColor(ACCENT)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * d).toInt(), ACCENT)
            }
        }
    }

    private fun pill(c: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val d = c.resources.displayMetrics.density
        val tv = TextView(c).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
        stylePill(tv, filled, 14f)
        return tv
    }

    private inner class ItemAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            val col = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
                isClickable = true
            }
            col.addView(TextView(c).apply {
                setTextColor(color(c, android.R.attr.textColorPrimary, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(c).apply {
                setTextColor(color(c, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            col.addView(TextView(c).apply {
                setTextColor(color(c, android.R.attr.textColorSecondary, Color.GRAY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            })
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val col = holder.itemView as LinearLayout
            (col.getChildAt(0) as TextView).text = ScMath.render(item.title)
            val sub = col.getChildAt(1) as TextView
            sub.text = listOf(item.authors, item.year).filter { it.isNotEmpty() }.joinToString(" · ")
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            val pub = col.getChildAt(2) as TextView
            pub.text = item.publication
            pub.visibility = if (item.publication.isEmpty()) View.GONE else View.VISIBLE
            col.setOnClickListener { readPdf(item) }
            col.setOnLongClickListener {
                showItemMenu(item)
                true
            }
        }
    }

    companion object {
        private val ACCENT = Color.parseColor("#4338CA")
    }
}
