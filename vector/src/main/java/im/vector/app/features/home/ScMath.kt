/*
 * Science Collab: formulas in chat.
 * Draws $...$ and $$...$$ (a LaTeX subset) inside message text. No third-party libraries.
 */

package im.vector.app.features.home

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Finds $formula$ fragments in a text and replaces their look with drawn formulas. */
object ScMath {

    private const val MAX_LEN = 400

    /** The original characters stay in the text, so copy/paste keeps the LaTeX source. */
    fun render(text: CharSequence): CharSequence {
        if (text.indexOf('$') < 0) return text
        val sb = SpannableStringBuilder(text)
        var i = 0
        var changed = false
        while (i < sb.length) {
            if (sb[i] != '$' || (i > 0 && sb[i - 1] == '\\')) {
                i++
                continue
            }
            val display = i + 1 < sb.length && sb[i + 1] == '$'
            val open = if (display) 2 else 1
            val close = findClose(sb, i + open, display)
            if (close < 0) {
                i += open
                continue
            }
            val latex = sb.subSequence(i + open, close).toString()
            val badSpacing = !display && (latex.first().isWhitespace() || latex.last().isWhitespace())
            if (latex.isBlank() || latex.length > MAX_LEN || badSpacing) {
                i += open
                continue
            }
            val end = close + open
            sb.setSpan(ScMathSpan(latex), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            changed = true
            i = end
        }
        return if (changed) sb else text
    }

    private fun findClose(sb: CharSequence, from: Int, display: Boolean): Int {
        var j = from
        while (j < sb.length) {
            val c = sb[j]
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == '$') {
                if (display) {
                    if (j + 1 < sb.length && sb[j + 1] == '$') return j
                } else if (j + 1 >= sb.length || !sb[j + 1].isDigit()) {
                    return j
                }
            }
            if (!display && c == '\n') return -1
            j++
        }
        return -1
    }
}

/** Draws one formula in place of its source characters, using the text size and color of the TextView. */
class ScMathSpan(private val latex: String) : ReplacementSpan() {

    private var box: ScMathBox? = null
    private var boxSize = -1f

    private fun ensure(paint: Paint): ScMathBox {
        val size = paint.textSize
        val cached = box
        if (cached != null && boxSize == size) return cached
        val built = try {
            ScMathEngine.build(latex, size)
        } catch (e: Exception) {
            ScMathEngine.plain(latex, size)
        }
        box = built
        boxSize = size
        return built
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val b = ensure(paint)
        if (fm != null) {
            val asc = ceil(b.ascent + 2f).toInt()
            val desc = ceil(b.descent + 2f).toInt()
            fm.ascent = min(fm.ascent, -asc)
            fm.top = min(fm.top, -asc)
            fm.descent = max(fm.descent, desc)
            fm.bottom = max(fm.bottom, desc)
        }
        return ceil(b.width + 4f).toInt()
    }

    override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
    ) {
        ensure(paint).draw(canvas, x + 2f, y.toFloat(), paint.color)
    }
}

/* ---------------------------------------------------------------------------------------------
 * Boxes (layout + drawing)
 * ------------------------------------------------------------------------------------------- */

internal abstract class ScMathBox {
    var width = 0f
    var ascent = 0f
    var descent = 0f
    abstract fun draw(c: Canvas, x: Float, y: Float, color: Int)
}

private class ScmTextBox(private val s: String, private val paint: Paint, size: Float) : ScMathBox() {
    init {
        width = paint.measureText(s)
        ascent = size * 0.74f
        descent = size * 0.22f
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        paint.color = color
        c.drawText(s, x, y, paint)
    }
}

private class ScmRowBox(private val kids: List<ScMathBox>) : ScMathBox() {
    init {
        var w = 0f
        var a = 0f
        var d = 0f
        for (k in kids) {
            w += k.width
            a = max(a, k.ascent)
            d = max(d, k.descent)
        }
        width = w
        ascent = a
        descent = d
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        var cx = x
        for (k in kids) {
            k.draw(c, cx, y, color)
            cx += k.width
        }
    }
}

private class ScmSpaceBox(w: Float) : ScMathBox() {
    init {
        width = w
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        // nothing to draw
    }
}

private class ScmPadBox(private val inner: ScMathBox, private val left: Float, right: Float) : ScMathBox() {
    init {
        width = left + inner.width + right
        ascent = inner.ascent
        descent = inner.descent
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        inner.draw(c, x + left, y, color)
    }
}

private class ScmShiftBox(private val inner: ScMathBox, private val dy: Float) : ScMathBox() {
    init {
        width = inner.width
        ascent = inner.ascent - dy
        descent = inner.descent + dy
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        inner.draw(c, x, y + dy, color)
    }
}

private class ScmScriptBox(
        private val base: ScMathBox,
        private val sup: ScMathBox?,
        private val sub: ScMathBox?,
        private val supShift: Float,
        private val subShift: Float,
        private val gap: Float,
) : ScMathBox() {
    init {
        val sw = max(sup?.width ?: 0f, sub?.width ?: 0f)
        width = base.width + gap + sw
        ascent = max(base.ascent, if (sup != null) supShift + sup.ascent else 0f)
        descent = max(base.descent, if (sub != null) subShift + sub.descent else 0f)
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        base.draw(c, x, y, color)
        val sx = x + base.width + gap
        sup?.draw(c, sx, y - supShift, color)
        sub?.draw(c, sx, y + subShift, color)
    }
}

private class ScmFracBox(private val num: ScMathBox, private val den: ScMathBox, size: Float) : ScMathBox() {
    private val axis = size * 0.27f
    private val gap = size * 0.14f
    private val pad = size * 0.1f
    private val thick = size * 0.055f
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        width = max(num.width, den.width) + 2f * pad
        ascent = axis + gap + num.descent + num.ascent
        descent = den.ascent + den.descent + gap - axis
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        rule.color = color
        val ry = y - axis
        c.drawRect(x + pad * 0.5f, ry - thick / 2f, x + width - pad * 0.5f, ry + thick / 2f, rule)
        num.draw(c, x + (width - num.width) / 2f, ry - gap - num.descent, color)
        den.draw(c, x + (width - den.width) / 2f, ry + gap + den.ascent, color)
    }
}

private class ScmSqrtBox(
        private val inner: ScMathBox,
        private val idx: ScMathBox?,
        private val size: Float,
) : ScMathBox() {
    private val pad = size * 0.1f
    private val signW = size * 0.55f
    private val ix = if (idx != null) max(0f, idx.width - signW * 0.4f) else 0f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = size * 0.06f
    }

    init {
        width = ix + signW + inner.width + pad
        ascent = inner.ascent + pad * 1.8f
        descent = inner.descent
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        stroke.color = color
        val topY = y - inner.ascent - pad * 1.2f
        val botY = y + inner.descent
        val midY = botY - (botY - topY) * 0.42f
        val left = x + ix
        val path = Path()
        path.moveTo(left, midY)
        path.lineTo(left + signW * 0.3f, botY)
        path.lineTo(left + signW, topY)
        path.lineTo(x + width, topY)
        c.drawPath(path, stroke)
        inner.draw(c, left + signW, y, color)
        idx?.draw(c, x, midY - size * 0.05f, color)
    }
}

private class ScmLimitsBox(
        private val sym: ScMathBox,
        private val upper: ScMathBox?,
        private val lower: ScMathBox?,
        private val gap: Float,
) : ScMathBox() {
    init {
        width = max(sym.width, max(upper?.width ?: 0f, lower?.width ?: 0f))
        ascent = sym.ascent + (if (upper != null) gap + upper.ascent + upper.descent else 0f)
        descent = sym.descent + (if (lower != null) gap + lower.ascent + lower.descent else 0f)
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        sym.draw(c, x + (width - sym.width) / 2f, y, color)
        if (upper != null) {
            upper.draw(c, x + (width - upper.width) / 2f, y - sym.ascent - gap - upper.descent, color)
        }
        if (lower != null) {
            lower.draw(c, x + (width - lower.width) / 2f, y + sym.descent + gap + lower.ascent, color)
        }
    }
}

private class ScmAccentBox(
        private val body: ScMathBox,
        private val kind: String,
        private val size: Float,
) : ScMathBox() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = size * 0.055f
        strokeCap = Paint.Cap.ROUND
    }

    init {
        width = body.width
        ascent = body.ascent + size * 0.22f
        descent = body.descent
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        body.draw(c, x, y, color)
        stroke.color = color
        val yy = y - body.ascent - size * 0.1f
        val cx = x + width / 2f
        val hw = min(width / 2f, size * 0.3f)
        val h = size * 0.14f
        when (kind) {
            "bar", "overline" -> c.drawLine(x, yy, x + width, yy, stroke)
            "vec", "overrightarrow" -> {
                val tip = x + width * 0.9f
                c.drawLine(x + width * 0.1f, yy, tip, yy, stroke)
                c.drawLine(tip, yy, tip - size * 0.12f, yy - size * 0.08f, stroke)
                c.drawLine(tip, yy, tip - size * 0.12f, yy + size * 0.08f, stroke)
            }
            "hat", "widehat" -> {
                c.drawLine(cx - hw, yy + h, cx, yy, stroke)
                c.drawLine(cx, yy, cx + hw, yy + h, stroke)
            }
            "tilde", "widetilde" -> {
                val path = Path()
                path.moveTo(cx - hw, yy + h * 0.5f)
                path.quadTo(cx - hw * 0.5f, yy - h * 0.5f, cx, yy + h * 0.5f)
                path.quadTo(cx + hw * 0.5f, yy + h * 1.5f, cx + hw, yy + h * 0.5f)
                c.drawPath(path, stroke)
            }
            "dot" -> c.drawCircle(cx, yy, size * 0.03f, stroke)
            "ddot" -> {
                c.drawCircle(cx - size * 0.08f, yy, size * 0.03f, stroke)
                c.drawCircle(cx + size * 0.08f, yy, size * 0.03f, stroke)
            }
        }
    }
}

private class ScmDelimBox(
        private val ch: String,
        private val paint: Paint,
        contentAsc: Float,
        contentDesc: Float,
        private val size: Float,
) : ScMathBox() {
    private val stretch = max(1f, (contentAsc + contentDesc) / size)

    init {
        width = paint.measureText(ch)
        ascent = max(contentAsc, size * 0.74f)
        descent = max(contentDesc, size * 0.22f)
    }

    override fun draw(c: Canvas, x: Float, y: Float, color: Int) {
        paint.color = color
        val centerY = y - (ascent - descent) / 2f
        c.save()
        c.translate(x, centerY)
        c.scale(1f, stretch)
        c.drawText(ch, 0f, size * 0.26f, paint)
        c.restore()
    }
}

/* ---------------------------------------------------------------------------------------------
 * Layout
 * ------------------------------------------------------------------------------------------- */

internal object ScMathEngine {

    fun build(latex: String, size: Float): ScMathBox = layout(ScmParser(latex).parseAll(), size, false)

    fun plain(text: String, size: Float): ScMathBox = ScmTextBox(text, paintFor(size, false, false), size)

    private fun paintFor(size: Float, italic: Boolean, bold: Boolean): Paint {
        val style = when {
            italic && bold -> Typeface.BOLD_ITALIC
            italic -> Typeface.ITALIC
            bold -> Typeface.BOLD
            else -> Typeface.NORMAL
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.SERIF, style)
        }
    }

    private fun layout(n: ScmNode, size: Float, bold: Boolean): ScMathBox = when (n) {
        is ScmText -> textNode(n, size, bold)
        is ScmGroup -> ScmRowBox(n.items.map { layout(it, size, bold) })
        is ScmBold -> layout(n.body, size, true)
        is ScmSpace -> ScmSpaceBox(n.em * size)
        is ScmScript -> script(n, size, bold)
        is ScmFrac -> ScmFracBox(layout(n.num, size * 0.85f, bold), layout(n.den, size * 0.85f, bold), size)
        is ScmSqrt -> ScmSqrtBox(layout(n.body, size, bold), n.index?.let { layout(it, size * 0.55f, bold) }, size)
        is ScmBig -> big(n, size, bold)
        is ScmAccent -> ScmAccentBox(layout(n.body, size, bold), n.kind, size)
        is ScmDelim -> delim(n, size, bold)
    }

    private fun textNode(n: ScmText, size: Float, bold: Boolean): ScMathBox {
        val tb = ScmTextBox(n.s, paintFor(size, n.italic, bold), size)
        if (n.padEm == 0f && n.trailEm == 0f) return tb
        return ScmPadBox(tb, n.padEm * size, (n.padEm + n.trailEm) * size)
    }

    private fun script(n: ScmScript, size: Float, bold: Boolean): ScMathBox {
        val base = layout(n.base, size, bold)
        val ss = size * 0.7f
        val sup = n.sup?.let { layout(it, ss, bold) }
        val sub = n.sub?.let { layout(it, ss, bold) }
        val supShift = size * 0.42f + max(0f, base.ascent - size * 0.74f)
        val subShift = size * 0.2f + max(0f, base.descent - size * 0.22f)
        return ScmScriptBox(base, sup, sub, supShift, subShift, size * 0.04f)
    }

    private fun big(n: ScmBig, size: Float, bold: Boolean): ScMathBox {
        val isWord = n.sym.length > 1
        val symSize = if (isWord) size else size * 1.45f
        val sym = ScmTextBox(n.sym, paintFor(symSize, false, false), symSize)
        val ss = size * 0.7f
        val up = n.sup?.let { layout(it, ss, bold) }
        val low = n.sub?.let { layout(it, ss, bold) }
        val shifted = ScmShiftBox(sym, if (isWord) 0f else size * 0.22f)
        val core: ScMathBox = if (n.above) {
            ScmLimitsBox(shifted, up, low, size * 0.08f)
        } else {
            ScmScriptBox(shifted, up, low, shifted.ascent * 0.85f, shifted.descent * 0.85f, size * 0.03f)
        }
        return ScmPadBox(core, 0f, size * 0.12f)
    }

    private fun delim(n: ScmDelim, size: Float, bold: Boolean): ScMathBox {
        val body = layout(n.body, size, bold)
        val kids = ArrayList<ScMathBox>()
        if (n.left.isNotEmpty()) {
            kids.add(ScmDelimBox(n.left, paintFor(size, false, false), body.ascent, body.descent, size))
        }
        kids.add(body)
        if (n.right.isNotEmpty()) {
            kids.add(ScmDelimBox(n.right, paintFor(size, false, false), body.ascent, body.descent, size))
        }
        return ScmRowBox(kids)
    }
}

/* ---------------------------------------------------------------------------------------------
 * Parser (LaTeX subset)
 * ------------------------------------------------------------------------------------------- */

private sealed class ScmNode

private class ScmText(
        val s: String,
        val italic: Boolean,
        val padEm: Float = 0f,
        val trailEm: Float = 0f,
) : ScmNode()

private class ScmGroup(val items: List<ScmNode>) : ScmNode()
private class ScmBold(val body: ScmNode) : ScmNode()
private class ScmSpace(val em: Float) : ScmNode()
private class ScmScript(val base: ScmNode, val sup: ScmNode?, val sub: ScmNode?) : ScmNode()
private class ScmFrac(val num: ScmNode, val den: ScmNode) : ScmNode()
private class ScmSqrt(val body: ScmNode, val index: ScmNode?) : ScmNode()
private class ScmBig(val sym: String, val above: Boolean, val sup: ScmNode?, val sub: ScmNode?) : ScmNode()
private class ScmAccent(val body: ScmNode, val kind: String) : ScmNode()
private class ScmDelim(val left: String, val body: ScmNode, val right: String) : ScmNode()

private val SCM_GREEK_LOWER = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "varrho" to "ϱ",
        "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
)

private val SCM_GREEK_UPPER = mapOf(
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)

private val SCM_SYMBOLS = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔",
        "mapsto" to "↦", "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "setminus" to "∖",
        "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨",
        "circ" to "∘", "degree" to "°", "prime" to "′", "langle" to "⟨", "rangle" to "⟩",
        "perp" to "⊥", "parallel" to "∥", "angle" to "∠", "otimes" to "⊗", "oplus" to "⊕",
        "dagger" to "†", "star" to "⋆", "bullet" to "•", "ast" to "∗",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮",
)

private val SCM_RELATIONS = setOf(
        "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv", "sim", "simeq", "propto", "ll", "gg",
        "to", "rightarrow", "leftarrow", "leftrightarrow", "Rightarrow", "Leftarrow", "Leftrightarrow",
        "implies", "iff", "mapsto", "in", "notin", "subset", "subseteq", "supset", "perp", "parallel",
)

private val SCM_BINARY = setOf(
        "cdot", "times", "div", "pm", "mp", "cup", "cap", "setminus", "land", "lor", "wedge", "vee",
        "circ", "otimes", "oplus", "ast",
)

private val SCM_FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "arcsin", "arccos", "arctan",
        "log", "ln", "lg", "exp", "det", "dim", "ker", "arg", "deg", "gcd", "Pr", "mod",
)

private val SCM_BLACKBOARD = mapOf("R" to "ℝ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "C" to "ℂ", "H" to "ℍ")

private class ScmParser(private val s: String) {

    private var i = 0

    fun parseAll(): ScmNode = ScmGroup(parseSeq(stopAtBrace = false, stopAtRight = false))

    private fun parseSeq(stopAtBrace: Boolean, stopAtRight: Boolean): List<ScmNode> {
        val out = ArrayList<ScmNode>()
        while (i < s.length) {
            val c = s[i]
            if (c == '}') {
                if (stopAtBrace) return out
                i++
                continue
            }
            if (stopAtRight && s.startsWith("\\right", i)) return out
            var atom = parseAtom() ?: continue
            if (out.isEmpty() && atom is ScmText && atom.padEm > 0f) {
                atom = ScmText(atom.s, atom.italic, 0f, atom.trailEm)
            }
            out.add(withScripts(atom))
        }
        return out
    }

    private fun skipSpaces() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun withScripts(base: ScmNode): ScmNode {
        var sup: ScmNode? = null
        var sub: ScmNode? = null
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            val c = s[i]
            if (c == '^') {
                i++
                sup = parseArg()
            } else if (c == '_') {
                i++
                sub = parseArg()
            } else if (c == '\'') {
                i++
                sup = ScmText("′", false)
            } else {
                break
            }
        }
        return if (sup == null && sub == null) base else ScmScript(base, sup, sub)
    }

    private fun parseArg(): ScmNode {
        skipSpaces()
        if (i >= s.length) return ScmGroup(emptyList())
        val c = s[i]
        if (c.isDigit()) {
            i++
            return ScmText(c.toString(), false)
        }
        return parseAtom() ?: ScmGroup(emptyList())
    }

    private fun parseAtom(): ScmNode? {
        if (i >= s.length) return null
        val c = s[i]
        if (c.isWhitespace()) {
            i++
            return null
        }
        if (c == '{') {
            i++
            val seq = parseSeq(stopAtBrace = true, stopAtRight = false)
            if (i < s.length && s[i] == '}') i++
            return ScmGroup(seq)
        }
        if (c == '}' || c == '^' || c == '_' || c == '&') {
            i++
            return null
        }
        if (c == '\\') return parseCommand()
        if (c.isDigit()) {
            val start = i
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()))) i++
            return ScmText(s.substring(start, i), false)
        }
        if (c.isLetter()) {
            i++
            val cyrillic = c.code in 0x0400..0x04FF
            return ScmText(c.toString(), !cyrillic)
        }
        i++
        return when (c) {
            '+' -> ScmText("+", false, 0.2f)
            '-' -> ScmText("−", false, 0.2f)
            '=' -> ScmText("=", false, 0.28f)
            '<' -> ScmText("<", false, 0.28f)
            '>' -> ScmText(">", false, 0.28f)
            '*' -> ScmText("∗", false)
            ',', ';' -> ScmText(c.toString(), false, 0f, 0.15f)
            '~' -> ScmSpace(0.3f)
            else -> ScmText(c.toString(), false)
        }
    }

    private fun parseCommand(): ScmNode? {
        i++
        if (i >= s.length) return null
        val first = s[i]
        if (!first.isLetter()) {
            i++
            return when (first) {
                '{', '}', '%', '$', '#', '&', '_' -> ScmText(first.toString(), false)
                ',' -> ScmSpace(0.17f)
                ';', ':' -> ScmSpace(0.25f)
                ' ', '\\' -> ScmSpace(0.3f)
                '|' -> ScmText("‖", false)
                else -> null
            }
        }
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        return commandNode(s.substring(start, i))
    }

    private fun commandNode(name: String): ScmNode? {
        return when (name) {
            "frac", "dfrac", "tfrac" -> {
                val a = parseArg()
                val b = parseArg()
                ScmFrac(a, b)
            }
            "sqrt" -> {
                val idx = parseOptionalIndex()
                ScmSqrt(parseArg(), idx)
            }
            "sum" -> bigOp("∑", true)
            "prod" -> bigOp("∏", true)
            "coprod" -> bigOp("∐", true)
            "int" -> bigOp("∫", false)
            "iint" -> bigOp("∬", false)
            "oint" -> bigOp("∮", false)
            "lim", "max", "min", "sup", "inf" -> bigOp(name, true)
            "vec", "hat", "bar", "overline", "dot", "ddot", "tilde", "widehat", "widetilde", "overrightarrow" ->
                ScmAccent(parseArg(), name)
            "text", "mathrm", "textrm", "operatorname", "mbox" -> ScmText(readRawGroup(), false)
            "mathit" -> ScmText(readRawGroup(), true)
            "mathbb" -> ScmText(readRawGroup().map { SCM_BLACKBOARD[it.toString()] ?: it.toString() }.joinToString(""), false)
            "mathbf", "boldsymbol", "bm" -> ScmBold(parseArg())
            "mathcal", "mathscr", "mathfrak" -> parseArg()
            "left" -> delimited()
            "right" -> {
                readDelim()
                null
            }
            "begin", "end" -> {
                readRawGroup()
                null
            }
            "quad" -> ScmSpace(1f)
            "qquad" -> ScmSpace(2f)
            "displaystyle", "textstyle", "limits", "nolimits" -> null
            in SCM_FUNCTIONS -> ScmText(name, false, 0f, 0.17f)
            else -> lookupSymbol(name)
        }
    }

    private fun lookupSymbol(name: String): ScmNode {
        SCM_GREEK_LOWER[name]?.let { return ScmText(it, true) }
        SCM_GREEK_UPPER[name]?.let { return ScmText(it, false) }
        val sym = SCM_SYMBOLS[name]
        if (sym != null) {
            val pad = if (name in SCM_RELATIONS) 0.28f else if (name in SCM_BINARY) 0.2f else 0f
            return ScmText(sym, false, pad)
        }
        return ScmText("\\" + name, false)
    }

    private fun bigOp(sym: String, above: Boolean): ScmNode {
        var sup: ScmNode? = null
        var sub: ScmNode? = null
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            val c = s[i]
            if (c == '^') {
                i++
                sup = parseArg()
            } else if (c == '_') {
                i++
                sub = parseArg()
            } else {
                break
            }
        }
        return ScmBig(sym, above, sup, sub)
    }

    private fun parseOptionalIndex(): ScmNode? {
        skipSpaces()
        if (i < s.length && s[i] == '[') {
            val end = s.indexOf(']', i)
            if (end > i) {
                val inner = s.substring(i + 1, end)
                i = end + 1
                return ScmParser(inner).parseAll()
            }
        }
        return null
    }

    private fun readRawGroup(): String {
        skipSpaces()
        if (i >= s.length) return ""
        if (s[i] != '{') {
            val c = s[i]
            i++
            return c.toString()
        }
        i++
        var depth = 1
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            i++
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) break
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun delimited(): ScmNode {
        val left = readDelim()
        val body = ScmGroup(parseSeq(stopAtBrace = false, stopAtRight = true))
        var right = ""
        if (s.startsWith("\\right", i)) {
            i += 6
            right = readDelim()
        }
        return ScmDelim(left, body, right)
    }

    private fun readDelim(): String {
        skipSpaces()
        if (i >= s.length) return ""
        val c = s[i]
        if (c == '\\') {
            i++
            if (i >= s.length) return ""
            val d = s[i]
            if (d.isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetter()) i++
                return when (s.substring(start, i)) {
                    "langle" -> "⟨"
                    "rangle" -> "⟩"
                    "lvert", "rvert" -> "|"
                    "lVert", "rVert" -> "‖"
                    "lfloor" -> "⌊"
                    "rfloor" -> "⌋"
                    "lceil" -> "⌈"
                    "rceil" -> "⌉"
                    else -> ""
                }
            }
            i++
            return when (d) {
                '{' -> "{"
                '}' -> "}"
                '|' -> "‖"
                else -> ""
            }
        }
        i++
        return if (c == '.') "" else c.toString()
    }
}
