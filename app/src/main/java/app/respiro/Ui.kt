package app.respiro

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

val Context.cBg get() = getColor(R.color.bg)
val Context.cFg get() = getColor(R.color.fg)
val Context.cMuted get() = getColor(R.color.muted)
val Context.cCard get() = getColor(R.color.card)
val Context.cLine get() = getColor(R.color.line)
val Context.cEdge get() = getColor(R.color.edge)

private val fonts = HashMap<Int, Typeface>()
fun font(weight: Int): Typeface = fonts.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }

// ---------- Texto ----------

fun Context.label(s: CharSequence, size: Float = 16f, weight: Int = 400, color: Int = cFg) = TextView(this).apply {
    text = s
    textSize = size
    typeface = font(weight)
    setTextColor(color)
    if (size >= 22f) letterSpacing = -0.022f
    setLineSpacing(0f, 1.12f)
}

fun Context.caption(s: CharSequence, size: Float = 14f) = label(s, size, 400, cMuted)

fun Context.section(s: String) = label(s.uppercase(), 12f, 600, cMuted).apply { letterSpacing = 0.07f }

// ---------- Fundos ----------

fun Context.rounded(color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dpf(radiusDp.toFloat())
    if (strokeDp > 0) setStroke(max(1, dpf(strokeDp).toInt()), stroke)
}

fun Context.ripple(radiusDp: Int) =
    RippleDrawable(ColorStateList.valueOf(0x30888888), null, rounded(Color.WHITE, radiusDp))

// ---------- Layout ----------

inline fun Context.vbox(padDp: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(padDp); setPadding(p, p, p, p)
    block()
}

inline fun Context.hbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun <T : View> LinearLayout.add(
    v: T, top: Int = 0, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, gravity: Int = -1, start: Int = 0,
): T {
    val lp = LinearLayout.LayoutParams(w, h, weight)
    lp.topMargin = context.dp(top)
    lp.marginStart = context.dp(start)
    if (gravity != -1) lp.gravity = gravity
    addView(v, lp)
    return v
}

fun Context.scroll(inner: View) = ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    isFillViewport = true
    addView(inner)
}

inline fun Context.card(padDp: Int = 18, block: LinearLayout.() -> Unit) = vbox(padDp) {
    background = rounded(cCard, 20)
    block()
}

fun Context.divider() = View(this).apply { setBackgroundColor(cLine) }

fun Context.spacer() = View(this)

// ---------- Botões ----------

enum class Btn { PRIMARY, SECONDARY, TEXT }

fun Context.button(text: String, style: Btn = Btn.PRIMARY, onClick: (TextView) -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    textSize = 16f
    typeface = font(600)
    minHeight = dp(if (style == Btn.TEXT) 44 else 54)
    setPadding(dp(18), dp(8), dp(18), dp(8))
    when (style) {
        Btn.PRIMARY -> { background = rounded(cFg, 16); setTextColor(cBg) }
        Btn.SECONDARY -> { background = rounded(cCard, 16); setTextColor(cFg) }
        Btn.TEXT -> { setTextColor(cMuted); textSize = 15f; typeface = font(500) }
    }
    foreground = ripple(16)
    isClickable = true
    isFocusable = true
    setOnClickListener {
        if (!isEnabled) return@setOnClickListener
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick(this)
    }
}

fun View.enable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.32f
}

/** Botão que só libera depois de [seconds] segundos — atrito deliberado. */
fun TextView.countdown(seconds: Int, base: String) {
    var left = seconds
    enable(false)
    val tick = object : Runnable {
        override fun run() {
            if (left <= 0) { text = base; enable(true); return }
            text = "$base ($left)"
            left--
            postDelayed(this, 1000)
        }
    }
    tick.run()
}

// ---------- Chips ----------

class Chip(ctx: Context, text: String) : TextView(ctx) {
    var on = false
        set(v) { field = v; refresh() }

    init {
        this.text = text
        textSize = 15f
        typeface = font(500)
        gravity = Gravity.CENTER
        minHeight = ctx.dp(40)
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
        isClickable = true
        foreground = ctx.ripple(20)
        refresh()
    }

    private fun refresh() {
        background = if (on) context.rounded(context.cFg, 20) else context.rounded(Color.TRANSPARENT, 20, context.cEdge, 1.2f)
        setTextColor(if (on) context.cBg else context.cFg)
    }
}

/** Grupo de chips; devolve o FlowLayout. [onChange] recebe os índices selecionados. */
fun Context.chips(
    options: List<String>, multi: Boolean = false, initial: Set<Int> = emptySet(), onChange: (Set<Int>) -> Unit,
): FlowLayout {
    val flow = FlowLayout(this)
    val sel = initial.toMutableSet()
    val all = ArrayList<Chip>()
    options.forEachIndexed { i, s ->
        val c = Chip(this, s)
        c.on = i in sel
        c.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (multi) {
                if (!sel.add(i)) sel.remove(i)
            } else {
                sel.clear(); sel.add(i)
            }
            all.forEachIndexed { j, ch -> ch.on = j in sel }
            onChange(sel.toSet())
        }
        all += c
        flow.addView(c)
    }
    return flow
}

class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private val gap = ctx.dp(8)

    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(ws), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
    }
}

// ---------- Controles ----------

fun Context.toggle(on: Boolean, onChange: (Boolean) -> Unit) = Toggle(this, on, onChange)

/** Interruptor no estilo iOS, em preto e branco, com animação. */
class Toggle(ctx: Context, on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = on
        private set
    private var pos = if (on) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        contentDescription = "interruptor"
        setOnClickListener {
            checked = !checked
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            anim?.cancel()
            anim = ValueAnimator.ofFloat(pos, if (checked) 1f else 0f).apply {
                duration = 180
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
            onChange(checked)
        }
    }

    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(context.dp(51), context.dp(31))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        track.color = blend(context.cEdge, context.cFg, pos)
        r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, track)
        val pad = context.dpf(2f)
        val tr = h / 2 - pad
        thumb.color = blend(if (context.isDark()) context.cFg else Color.WHITE, context.cBg, pos)
        c.drawCircle(pad + tr + (w - 2 * pad - 2 * tr) * pos, h / 2, tr, thumb)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt() shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}

fun Context.isDark() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
    android.content.res.Configuration.UI_MODE_NIGHT_YES

fun Context.stepper(value: Int, min: Int, max: Int, step: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
    var v = value
    val tv = label(fmt(v), 15f, 500).apply { gravity = Gravity.CENTER; minWidth = dp(72) }
    fun mk(s: String, d: Int) = label(s, 20f, 400).apply {
        gravity = Gravity.CENTER
        background = rounded(cBg, 14)
        foreground = ripple(14)
        setOnClickListener {
            val n = (v + d).coerceIn(min, max)
            if (n != v) { v = n; tv.text = fmt(v); it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onChange(v) }
        }
    }
    return hbox {
        add(mk("−", -step), w = dp(40), h = dp(36))
        add(tv, w = WRAP)
        add(mk("+", step), w = dp(40), h = dp(36))
    }
}

/** Linha de lista estilo Ajustes: título, subtítulo e um controle à direita. */
fun Context.row(title: String, sub: String? = null, right: View? = null, onClick: (() -> Unit)? = null): LinearLayout = hbox {
    minimumHeight = dp(52)
    setPadding(0, dp(10), 0, dp(10))
    val texts = vbox {
        add(label(title, 16f, 500))
        if (sub != null) add(caption(sub, 13f), top = 2)
    }
    add(texts, w = 0, weight = 1f)
    if (right != null) add(right, w = WRAP, start = 12)
    if (onClick != null) {
        if (right == null) add(label("›", 22f, 300, cMuted), w = WRAP, start = 12)
        foreground = ripple(12)
        setOnClickListener { onClick() }
    }
}

// ---------- Janela ----------

/** Desenha atrás das barras do sistema e aplica os recuos como padding em [root]. */
fun Activity.edgeToEdge(root: View) {
    window.setDecorFitsSystemWindows(false)
    root.setOnApplyWindowInsetsListener { v, ins ->
        val i: Insets = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
        v.setPadding(i.left, i.top, i.right, i.bottom)
        WindowInsets.CONSUMED
    }
}

// ---------- Frase de atrito ----------

const val PHRASE = "escolho gastar mais tempo aqui"

private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!')

fun Activity.confirmPhrase(title: String, onOk: () -> Unit) {
    val input = EditText(this).apply {
        hint = "digite aqui"
        setHintTextColor(cEdge)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
    }
    val box = vbox(22) {
        add(caption("Para continuar, digite exatamente:"))
        add(label("“$PHRASE”", 17f, 600), top = 6)
        add(input, top = 14)
    }
    val d = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setNegativeButton("Desistir", null)
        .setPositiveButton("Continuar") { _, _ -> onOk() }
        .create()
    d.setOnShowListener {
        val b = d.getButton(AlertDialog.BUTTON_POSITIVE)
        b.isEnabled = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { b.isEnabled = norm(s.toString()) == PHRASE }
        })
    }
    d.show()
}

// ---------- Assíncrono ----------

val worker = Executors.newSingleThreadExecutor()

fun <T : Any> Activity.async(work: () -> T, done: (T) -> Unit) {
    worker.execute {
        val r = runCatching(work).getOrNull() ?: return@execute
        runOnUiThread { if (!isDestroyed) done(r) }
    }
}

// ---------- Views desenhadas ----------

/** Círculo que expande (inspire) e contrai (expire) — 4 s cada fase. */
class BreathView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.cFg }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.2f); color = ctx.cEdge
    }
    private var phase = 0f
    var onPhase: ((inhale: Boolean) -> Unit)? = null
    private var inhale = true
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(a: Animator) { inhale = !inhale; onPhase?.invoke(inhale) }
        })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start(); onPhase?.invoke(true) }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f - ring.strokeWidth
        c.drawCircle(cx, cy, maxR, ring)
        c.drawCircle(cx, cy, maxR * (0.28f + 0.72f * phase), fill)
    }
}

class RingView(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(9f); color = ctx.cLine; strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(track).apply { color = ctx.cFg }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val s = track.strokeWidth / 2
        val size = min(width, height).toFloat()
        val l = (width - size) / 2 + s; val t = (height - size) / 2 + s
        r.set(l, t, l + size - 2 * s, t + size - 2 * s)
        c.drawOval(r, track)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) c.drawArc(r, -90f, 360f * f, false, arc)
    }
}

/** Barras verticais: [back] em cinza (ex.: tela total) e [front] em destaque (ex.: redes). */
class BarsView(ctx: Context, private val hDp: Int = 150) : View(ctx) {
    var back = LongArray(0)
    var front = LongArray(0)
    var labels: Array<String> = emptyArray()
    var goal = 0L
    var highlight = -1
    private val pBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.cLine }
    private val pFront = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.cFg }
    private val pGoal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.cMuted; strokeWidth = ctx.dpf(1f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(4f), ctx.dpf(4f)), 0f)
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.cMuted; textSize = ctx.dpf(11f); textAlign = Paint.Align.CENTER; typeface = font(500)
    }
    private val rr = RectF()

    fun set(back: LongArray, front: LongArray, labels: Array<String>, goal: Long = 0, highlight: Int = -1) {
        this.back = back; this.front = front; this.labels = labels; this.goal = goal; this.highlight = highlight
        invalidate()
    }

    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(MeasureSpec.getSize(ws), context.dp(hDp))

    override fun onDraw(c: Canvas) {
        val n = max(back.size, front.size)
        if (n == 0) return
        val labelH = if (labels.isNotEmpty()) context.dpf(20f) else 0f
        val chartH = height - labelH - context.dpf(4f)
        val top = max(max(back.maxOrNull() ?: 0, front.maxOrNull() ?: 0), (goal * 1.15).toLong()).coerceAtLeast(1)
        val slot = width.toFloat() / n
        val bw = slot * if (n > 12) 0.62f else 0.5f
        val rad = min(bw / 2, context.dpf(5f))
        for (i in 0 until n) {
            val x = slot * i + (slot - bw) / 2
            fun bar(v: Long, p: Paint) {
                if (v <= 0) return
                val h = max(context.dpf(3f), chartH * v / top)
                rr.set(x, chartH - h, x + bw, chartH); c.drawRoundRect(rr, rad, rad, p)
            }
            if (i < back.size) bar(back[i], pBack)
            if (i < front.size) bar(front[i], pFront)
            if (i < labels.size && labels[i].isNotEmpty()) {
                pText.color = if (i == highlight) context.cFg else context.cMuted
                c.drawText(labels[i], slot * i + slot / 2, height - context.dpf(4f), pText)
            }
        }
        if (goal > 0) {
            val y = chartH - chartH * goal / top
            c.drawLine(0f, y, width.toFloat(), y, pGoal)
        }
    }
}

/** Barra horizontal fina com fração preenchida. */
class HBar(ctx: Context, var fraction: Float = 0f) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.cLine }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.cFg }
    private val r = RectF()
    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(MeasureSpec.getSize(ws), context.dp(6))
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, track)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0) { r.set(0f, 0f, max(h, width * f), h); c.drawRoundRect(r, h / 2, h / 2, fill) }
    }
}

/** Barra empilhada em tons de cinza (ex.: melhor / igual / pior). */
class StackBar(ctx: Context, private val parts: IntArray) : View(ctx) {
    private val colors = intArrayOf(ctx.cFg, ctx.cMuted, ctx.cEdge)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(MeasureSpec.getSize(ws), context.dp(8))
    override fun onDraw(c: Canvas) {
        val total = parts.sum().toFloat()
        if (total <= 0) return
        var x = 0f
        val gap = context.dpf(2f)
        parts.forEachIndexed { i, v ->
            if (v == 0) return@forEachIndexed
            val w = width * v / total
            p.color = colors[i % colors.size]
            r.set(x, 0f, x + w - gap, height.toFloat()); c.drawRoundRect(r, height / 2f, height / 2f, p)
            x += w
        }
    }
}

/** Overlay de tela cheia, opaco, que engole toques. */
fun Context.sheet(content: View, center: Boolean = true): FrameLayout = FrameLayout(this).apply {
    setBackgroundColor(cBg)
    isClickable = true
    val inner = FrameLayout(this@sheet)
    inner.addView(content, FrameLayout.LayoutParams(MATCH, WRAP, if (center) Gravity.CENTER_VERTICAL else Gravity.TOP))
    addView(scroll(inner), FrameLayout.LayoutParams(MATCH, MATCH))
}

fun View.fadeIn() {
    alpha = 0f; visibility = View.VISIBLE
    animate().alpha(1f).setDuration(350).start()
}

