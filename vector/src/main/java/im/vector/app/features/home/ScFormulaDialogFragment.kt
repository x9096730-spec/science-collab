/*
 * Science Collab: formula editor with live preview and a palette of symbols.
 * Returns the LaTeX code through a fragment result (RESULT_KEY / RESULT_LATEX).
 */

package im.vector.app.features.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import kotlin.math.max
import kotlin.math.min

private fun formulaColor(ctx: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
    return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
}

class ScFormulaDialogFragment : DialogFragment() {

    private class Chip(val label: String, val text: String, val back: Int = 0)

    private var input: EditText? = null
    private var preview: TextView? = null
    private var primaryColor = Color.BLACK
    private var secondaryColor = Color.GRAY

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
            setBackgroundDrawable(ColorDrawable(formulaColor(requireContext(), android.R.attr.colorBackground, Color.WHITE)))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val bg = formulaColor(ctx, android.R.attr.colorBackground, Color.WHITE)
        primaryColor = formulaColor(ctx, android.R.attr.textColorPrimary, Color.BLACK)
        secondaryColor = formulaColor(ctx, android.R.attr.textColorSecondary, Color.GRAY)

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
            text = "Формула"
            setTextColor(primaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(ctx, "Отмена", false) { dismiss() })
        val insertLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        insertLp.marginStart = dp(8)
        bar.addView(pill(ctx, "Вставить", true) { submit() }, insertLp)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Live preview
        val prev = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            minHeight = dp(90)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.parseColor("#144338CA"))
            }
        }
        preview = prev
        val prevLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        prevLp.topMargin = dp(12)
        root.addView(prev, prevLp)

        // LaTeX code field
        val field = EditText(ctx).apply {
            hint = "Код формулы, например \\frac{a}{b}"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            setTextColor(primaryColor)
            setHintTextColor(secondaryColor)
            textSize = 16f
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // no-op
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePreview()
            }

            override fun afterTextChanged(s: Editable?) {
                // no-op
            }
        })
        input = field
        val fieldLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fieldLp.topMargin = dp(8)
        root.addView(field, fieldLp)

        // Palette
        for (chips in listOf(STRUCTURE, GREEK, SYMBOLS)) {
            val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rowLp.topMargin = dp(8)
            root.addView(chipRow(ctx, chips), rowLp)
        }

        field.requestFocus()
        updatePreview()
        return root
    }

    private fun updatePreview() {
        val view = preview ?: return
        val latex = input?.text?.toString().orEmpty().replace('\n', ' ').trim()
        if (latex.isEmpty()) {
            view.setTextColor(secondaryColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            view.text = "Здесь появится формула"
        } else {
            view.setTextColor(primaryColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            view.text = ScMath.render("\$" + latex + "\$")
        }
    }

    private fun insert(chip: Chip) {
        val e = input ?: return
        val from = min(max(0, e.selectionStart), max(0, e.selectionEnd))
        val to = max(max(0, e.selectionStart), max(0, e.selectionEnd))
        e.text.replace(from, to, chip.text)
        e.setSelection(from + chip.text.length - chip.back)
    }

    private fun submit() {
        val latex = input?.text?.toString().orEmpty().replace('\n', ' ').trim()
        if (latex.isEmpty()) {
            dismiss()
            return
        }
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply { putString(RESULT_LATEX, latex) })
        dismiss()
    }

    override fun onDestroyView() {
        input = null
        preview = null
        super.onDestroyView()
    }

    private fun chipRow(ctx: Context, chips: List<Chip>): View {
        val d = ctx.resources.displayMetrics.density
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (chip in chips) {
            val tv = TextView(ctx).apply {
                text = chip.label
                gravity = Gravity.CENTER
                setTextColor(primaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                minWidth = (44 * d).toInt()
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 10 * d
                    setColor(Color.parseColor("#1F808080"))
                }
                isClickable = true
                setOnClickListener { insert(chip) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * d).toInt()
            inner.addView(tv, lp)
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner)
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

    companion object {
        const val RESULT_KEY = "sc_formula_result"
        const val RESULT_LATEX = "latex"
        private val ACCENT = Color.parseColor("#4338CA")

        /** Wraps the code in dollar signs, as the chat renderer expects. */
        fun wrap(latex: String): String = "\$" + latex + "\$"

        private val STRUCTURE = listOf(
                Chip("a/b", "\\frac{}{}", 3),
                Chip("√", "\\sqrt{}", 1),
                Chip("xⁿ", "^{}", 1),
                Chip("xₙ", "_{}", 1),
                Chip("∑", "\\sum_{}^{}", 4),
                Chip("∫", "\\int_{}^{}", 4),
                Chip("lim", "\\lim_{}", 1),
                Chip("( )", "\\left(\\right)", 7),
                Chip("vec", "\\vec{}", 1),
                Chip("hat", "\\hat{}", 1),
                Chip("bar", "\\bar{}", 1),
                Chip("txt", "\\text{}", 1),
                Chip("∂", "\\partial "),
                Chip("∇", "\\nabla "),
                Chip("∞", "\\infty "),
                Chip("ħ", "\\hbar "),
        )

        private val GREEK = listOf(
                Chip("α", "\\alpha "), Chip("β", "\\beta "), Chip("γ", "\\gamma "), Chip("δ", "\\delta "),
                Chip("ε", "\\varepsilon "), Chip("θ", "\\theta "), Chip("λ", "\\lambda "), Chip("μ", "\\mu "),
                Chip("π", "\\pi "), Chip("ρ", "\\rho "), Chip("σ", "\\sigma "), Chip("τ", "\\tau "),
                Chip("φ", "\\varphi "), Chip("ψ", "\\psi "), Chip("ω", "\\omega "),
                Chip("Δ", "\\Delta "), Chip("Σ", "\\Sigma "), Chip("Ω", "\\Omega "),
        )

        private val SYMBOLS = listOf(
                Chip("±", "\\pm "), Chip("×", "\\times "), Chip("·", "\\cdot "),
                Chip("≤", "\\leq "), Chip("≥", "\\geq "), Chip("≠", "\\neq "), Chip("≈", "\\approx "),
                Chip("∝", "\\propto "), Chip("→", "\\to "), Chip("⇒", "\\Rightarrow "),
                Chip("∈", "\\in "), Chip("∀", "\\forall "), Chip("∃", "\\exists "),
                Chip("sin", "\\sin "), Chip("cos", "\\cos "), Chip("ln", "\\ln "),
        )
    }
}
