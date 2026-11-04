package app.respiro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Ícones monocromáticos "versão Respiro" de cada rede — releituras satíricas:
 * Instagram com o logo do Respiro como lente, YouTube em pausa, TikTok dormindo,
 * X como botão de fechar, Threads se desenrolando, Reddit de olhos fechados, LinkedIn "out".
 * Desenhados numa grade de 24×24, só traço — ficam nítidos em qualquer tamanho.
 */
object Glyphs {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val p = Path()

    /** Desenha o glifo de [site] dentro do quadrado (x, y, size). */
    fun draw(c: Canvas, site: Site, x: Float, y: Float, size: Float, color: Int) {
        c.save()
        c.translate(x, y)
        c.scale(size / 24f, size / 24f)
        stroke.color = color; stroke.strokeWidth = 1.6f
        fill.color = color
        text.color = color
        when (site.id) {
            "instagram" -> {
                r.set(3f, 3f, 21f, 21f); c.drawRoundRect(r, 5.5f, 5.5f, stroke)
                c.drawCircle(12f, 12f, 4.4f, stroke)       // lente = anel do Respiro
                c.drawCircle(12f, 12f, 1.9f, fill)         // …com o ponto de respiração
                c.drawCircle(17.1f, 6.9f, 0.95f, fill)
            }
            "youtube" -> {
                r.set(2f, 5.5f, 22f, 18.5f); c.drawRoundRect(r, 4.5f, 4.5f, stroke)
                stroke.strokeWidth = 2.1f
                c.drawLine(10f, 9.6f, 10f, 14.4f, stroke)  // pause no lugar do play
                c.drawLine(14f, 9.6f, 14f, 14.4f, stroke)
            }
            "tiktok" -> {
                c.drawLine(12.5f, 4f, 12.5f, 15.6f, stroke)
                p.reset(); p.moveTo(12.5f, 4f); p.cubicTo(12.5f, 6.6f, 14.6f, 8.2f, 17.4f, 8.2f); c.drawPath(p, stroke)
                c.drawCircle(9.6f, 16.2f, 2.9f, stroke)
                stroke.strokeWidth = 1.2f                  // …dormindo
                p.reset(); p.moveTo(17.2f, 13.4f); p.lineTo(20.2f, 13.4f); p.lineTo(17.2f, 17f); p.lineTo(20.2f, 17f); c.drawPath(p, stroke)
                p.reset(); p.moveTo(19.6f, 9.6f); p.lineTo(21.4f, 9.6f); p.lineTo(19.6f, 11.8f); p.lineTo(21.4f, 11.8f); c.drawPath(p, stroke)
            }
            "x" -> {
                c.drawCircle(12f, 12f, 9f, stroke)          // virou botão de fechar
                c.drawLine(8.8f, 8.8f, 15.2f, 15.2f, stroke)
                c.drawLine(15.2f, 8.8f, 8.8f, 15.2f, stroke)
            }
            "threads" -> {                                  // "@" cujo fio se solta no fim
                c.drawCircle(12f, 12f, 3.2f, stroke)
                c.drawLine(15.2f, 8.8f, 15.2f, 13.4f, stroke)
                p.reset(); p.moveTo(15.2f, 13.4f); p.cubicTo(15.2f, 16f, 19.4f, 16.2f, 20.3f, 12.4f); c.drawPath(p, stroke)
                r.set(3.7f, 3.7f, 20.3f, 20.3f)
                p.reset(); p.arcTo(r, 0f, -290f, true)
                p.cubicTo(15.6f, 21.4f, 17.6f, 21f, 18.6f, 22.6f)
                c.drawPath(p, stroke)
            }
            "facebook" -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                p.reset(); p.moveTo(15f, 7.3f); p.lineTo(14f, 7.3f)
                p.cubicTo(12.6f, 7.3f, 12f, 8.1f, 12f, 9.4f); p.lineTo(12f, 21f); c.drawPath(p, stroke)
                c.drawLine(9.8f, 12.3f, 14.8f, 12.3f, stroke)
            }
            "reddit" -> {
                r.set(4.5f, 9f, 19.5f, 19.2f); c.drawOval(r, stroke)
                c.drawLine(12f, 9f, 13.2f, 4.6f, stroke)
                c.drawLine(13.2f, 4.6f, 15f, 4.9f, stroke)
                c.drawCircle(16.6f, 5f, 1.4f, stroke)
                c.drawCircle(4.2f, 10.6f, 1.5f, stroke); c.drawCircle(19.8f, 10.6f, 1.5f, stroke)
                stroke.strokeWidth = 1.4f                  // olhos fechados
                p.reset(); p.moveTo(7.9f, 13.4f); p.quadTo(9.2f, 14.7f, 10.5f, 13.4f); c.drawPath(p, stroke)
                p.reset(); p.moveTo(13.5f, 13.4f); p.quadTo(14.8f, 14.7f, 16.1f, 13.4f); c.drawPath(p, stroke)
                p.reset(); p.moveTo(10f, 16.4f); p.quadTo(12f, 17.4f, 14f, 16.4f); c.drawPath(p, stroke)
            }
            "linkedin" -> {
                r.set(3f, 3f, 21f, 21f); c.drawRoundRect(r, 4.5f, 4.5f, stroke)
                text.typeface = font(700); text.textSize = 7.4f
                c.drawText("out", 12f, 12f - (text.descent() + text.ascent()) / 2, text)  // log out
            }
            else -> {
                r.set(3f, 3f, 21f, 21f); c.drawRoundRect(r, 5.5f, 5.5f, stroke)
                text.typeface = font(600); text.textSize = 10f
                c.drawText(site.name.take(1).uppercase(), 12f, 12f - (text.descent() + text.ascent()) / 2, text)
            }
        }
        c.restore()
    }

    /** Ícone para atalho na tela inicial: fundo preto, glifo branco e o selo do Respiro. */
    fun shortcutBitmap(ctx: Context, site: Site): Bitmap {
        val s = ctx.dp(108)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt())
        val g = s * 0.50f
        draw(c, site, (s - g) / 2, (s - g) / 2, g, 0xFFFFFFFF.toInt())
        // selo: anel + ponto (logo do Respiro) no canto, dentro da zona segura do ícone adaptativo
        val bx = s * 0.67f; val by = s * 0.67f; val br = s * 0.06f
        fill.color = 0xFF000000.toInt(); c.drawCircle(bx, by, br * 1.7f, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = s * 0.012f; c.drawCircle(bx, by, br, stroke)
        fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(bx, by, br * 0.42f, fill)
        return bmp
    }
}

/** Ladrilho da tela Hoje: quadrado arredondado com o glifo da rede. */
class GlyphTile(ctx: Context, private val site: Site) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.cCard }
    private val r = RectF()

    init {
        contentDescription = site.name
        isClickable = true
        foreground = ctx.ripple(18)
    }

    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(context.dp(62), context.dp(62))

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        r.set(0f, 0f, s, s)
        c.drawRoundRect(r, s * 0.29f, s * 0.29f, bg)
        val g = s * 0.56f
        Glyphs.draw(c, site, (s - g) / 2, (s - g) / 2, g, context.cFg)
    }
}
