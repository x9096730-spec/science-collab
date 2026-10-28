/*
 * Science Collab: in-app PDF reader (Android PdfRenderer, no third-party libraries).
 */

package im.vector.app.features.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

private fun readerColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
    return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
}

class ScPdfReaderFragment : DialogFragment() {

    private val lock = Any()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var closed = false
    private val executor = Executors.newSingleThreadExecutor()
    private val ratios = ArrayList<Float>()
    private var zoomIndex = 0
    private var zoomGeneration = 0
    private var baseWidth = 0
    private var pageWidthPx = 0
    private var listView: RecyclerView? = null
    private var counterView: TextView? = null
    private var pageAdapter: PageAdapter? = null
    private var filePath = ""

    private val cache = object : LruCache<Int, Bitmap>(cacheKb()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun cacheKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(READER_BG))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val bg = readerColor(ctx, android.R.attr.colorBackground, Color.WHITE)
        val primary = readerColor(ctx, android.R.attr.textColorPrimary, Color.BLACK)
        val secondary = readerColor(ctx, android.R.attr.textColorSecondary, Color.GRAY)

        filePath = arguments?.getString(ARG_PATH).orEmpty()
        val title = arguments?.getString(ARG_TITLE).orEmpty()
        baseWidth = ctx.resources.displayMetrics.widthPixels
        pageWidthPx = baseWidth

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(READER_BG)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        topRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        closeLp.marginStart = dp(12)
        topRow.addView(pill(ctx, "Закрыть") { dismiss() }, closeLp)
        header.addView(topRow)

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val counter = TextView(ctx).apply {
            text = "Открываю…"
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        counterView = counter
        controls.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(pill(ctx, "−") { changeZoom(-1) })
        val plusLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plusLp.marginStart = dp(8)
        controls.addView(pill(ctx, "+") { changeZoom(1) }, plusLp)
        header.addView(controls)

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val adapter = PageAdapter()
        pageAdapter = adapter
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateCounter()
                }
            })
        }
        listView = list

        val hsv = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        hsv.addView(list, FrameLayout.LayoutParams(pageWidthPx, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        loadDocument()
        return root
    }

    private fun loadDocument() {
        val path = filePath
        executor.execute {
            var failed = false
            val found = ArrayList<Float>()
            synchronized(lock) {
                if (closed) return@execute
                try {
                    val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptor = pfd
                    val r = PdfRenderer(pfd)
                    renderer = r
                    for (i in 0 until r.pageCount) {
                        val p = r.openPage(i)
                        try {
                            found.add(if (p.width > 0) p.height.toFloat() / p.width.toFloat() else 1.4f)
                        } finally {
                            p.close()
                        }
                    }
                } catch (e: Exception) {
                    failed = true
                }
            }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (failed || found.isEmpty()) {
                    Toast.makeText(requireContext(), "Не удалось открыть PDF", Toast.LENGTH_LONG).show()
                    dismiss()
                    return@runOnUiThread
                }
                ratios.addAll(found)
                pageAdapter?.notifyDataSetChanged()
                val savedPos = requireContext().getSharedPreferences(PREFS_POS, Context.MODE_PRIVATE)
                        .getInt(File(path).name, 0)
                if (savedPos in 1 until ratios.size) listView?.scrollToPosition(savedPos)
                updateCounter()
            }
        }
    }

    private fun requestRender(page: Int, widthPx: Int, onDone: (Bitmap) -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            var result: Bitmap? = null
            synchronized(lock) {
                val r = renderer
                if (!closed && r != null) {
                    try {
                        val p = r.openPage(page)
                        try {
                            val h = (widthPx * p.height.toFloat() / p.width.toFloat()).toInt().coerceAtLeast(1)
                            val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                            b.eraseColor(Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result = b
                        } finally {
                            p.close()
                        }
                    } catch (e: Exception) {
                        result = null
                    }
                }
            }
            val done = result
            if (done != null) onDone(done)
        }
    }

    private fun updateCounter() {
        val lm = listView?.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first >= 0 && ratios.isNotEmpty()) {
            val percent = (ZOOMS[zoomIndex] * 100).toInt()
            counterView?.text = "Стр. ${first + 1} из ${ratios.size} · $percent%"
        }
    }

    private fun changeZoom(delta: Int) {
        val next = (zoomIndex + delta).coerceIn(0, ZOOMS.size - 1)
        if (next == zoomIndex || ratios.isEmpty()) return
        val lm = listView?.layoutManager as? LinearLayoutManager
        val keep = lm?.findFirstVisibleItemPosition() ?: 0
        zoomIndex = next
        pageWidthPx = (baseWidth * ZOOMS[next]).toInt()
        zoomGeneration++
        cache.evictAll()
        listView?.let { lv ->
            val lp = lv.layoutParams
            lp.width = pageWidthPx
            lv.layoutParams = lp
        }
        pageAdapter?.notifyDataSetChanged()
        listView?.scrollToPosition(keep.coerceAtLeast(0))
        updateCounter()
    }

    override fun onDestroyView() {
        val lm = listView?.layoutManager as? LinearLayoutManager
        val pos = lm?.findFirstVisibleItemPosition() ?: -1
        if (pos >= 0 && filePath.isNotEmpty()) {
            context?.getSharedPreferences(PREFS_POS, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putInt(File(filePath).name, pos)
                    ?.apply()
        }
        executor.shutdownNow()
        synchronized(lock) {
            closed = true
            renderer?.close()
            renderer = null
            descriptor?.close()
            descriptor = null
        }
        cache.evictAll()
        listView = null
        counterView = null
        pageAdapter = null
        super.onDestroyView()
    }

    private fun pill(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minWidth = (48 * d).toInt()
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * d).toInt(), ACCENT)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = ratios.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val img = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            return object : RecyclerView.ViewHolder(img) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val img = holder.itemView as ImageView
            val d = img.resources.displayMetrics.density
            val lp = RecyclerView.LayoutParams(pageWidthPx, (pageWidthPx * ratios[position]).toInt().coerceAtLeast(1))
            lp.bottomMargin = (8 * d).toInt()
            img.layoutParams = lp
            img.tag = position
            val cached = cache.get(position)
            if (cached != null) {
                img.setImageBitmap(cached)
                return
            }
            img.setImageDrawable(null)
            val generation = zoomGeneration
            requestRender(position, pageWidthPx) { bmp ->
                img.post {
                    if (generation == zoomGeneration && img.tag == position) {
                        cache.put(position, bmp)
                        img.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_PATH = "path"
        private const val ARG_TITLE = "title"
        private const val PREFS_POS = "sc_library_pos"
        private val READER_BG = Color.parseColor("#D9D9D9")
        private val ACCENT = Color.parseColor("#4338CA")
        private val ZOOMS = floatArrayOf(1f, 1.5f, 2f)

        fun newInstance(path: String, title: String): ScPdfReaderFragment =
                ScPdfReaderFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_PATH, path)
                        putString(ARG_TITLE, title)
                    }
                }
    }
}
