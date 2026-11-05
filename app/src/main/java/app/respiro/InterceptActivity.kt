package app.respiro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min

/**
 * A pausa antes de abrir um app nativo. Técnicas aplicadas:
 *  - atraso com respiração guiada que cresce a cada abertura no dia (one sec, PNAS 2023);
 *  - opção explícita de desistir — o componente mais eficaz no estudo;
 *  - pergunta de intenção (separa uso com propósito de impulso);
 *  - lembra o plano se–então que você mesmo escreveu;
 *  - passe com tempo limitado em vez de liberar de vez;
 *  - acima do limite diário, exige digitar uma frase.
 */
class InterceptActivity : Activity() {
    /** OPEN: abriu o app · EXPIRED: o passe de minutos acabou · FOCUS: modo foco ativo. */
    enum class Mode { OPEN, EXPIRED, FOCUS }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var site: Site
    private var mode = Mode.OPEN
    private var pkg: String? = null
    /** Veio de um navegador: a rede só abre na versão filtrada, dentro do Respiro. */
    private var browserUrl: String? = null
    private var intention: String? = null
    private var delay = 0
    private var decided = false
    private var overLimit = false
    private var preview = false

    private fun log(outcome: String) { if (!preview) Db.i.logOpen(site.id, outcome, intention, delay) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setup(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (!decided && ::site.isInitialized) log(Outcome.LEFT)
        setup(i)
    }

    override fun onResume() { super.onResume(); GuardService.interceptVisible = true }
    override fun onPause() { super.onPause(); GuardService.interceptVisible = false }

    override fun onStop() {
        super.onStop()
        // Saiu sem decidir (Home, Recentes, tela apagada): conta como não abrir.
        if (!decided && !isFinishing) {
            decided = true
            log(if (mode == Mode.EXPIRED) Outcome.EXPIRED_EXIT else Outcome.LEFT)
            finish()
        }
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave(if (mode == Mode.EXPIRED) Outcome.EXPIRED_EXIT else Outcome.DISMISS)

    private fun setup(i: Intent) {
        h.removeCallbacksAndMessages(null)
        site = Sites.byId(i.getStringExtra("site") ?: "") ?: run { finish(); return }
        mode = runCatching { Mode.valueOf(i.getStringExtra("mode")!!) }.getOrDefault(Mode.OPEN)
        pkg = i.getStringExtra("pkg")
        browserUrl = i.getStringExtra("browserUrl")
        preview = i.getBooleanExtra("preview", false)
        if (mode == Mode.OPEN && Prefs.focusActive()) mode = Mode.FOCUS
        intention = null
        decided = false
        overLimit = false

        // Atrito crescente: 5 s na 1ª abertura do dia, +2 s a cada nova, até 30 s.
        val opens = Db.i.countOpens(site.id, dayStart())
        delay = when (mode) {
            Mode.EXPIRED -> 3
            Mode.FOCUS -> 0
            Mode.OPEN -> if (Prefs.restNow()) 30 else min(30, 5 + opens * 2)
        }

        val col = vbox(28) { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(label(site.name, 34f, 700).apply { gravity = Gravity.CENTER })
        val sub = caption(
            when (mode) {
                Mode.FOCUS -> "Modo foco"
                Mode.EXPIRED -> "Seu tempo acabou"
                Mode.OPEN -> "${opens + 1}ª vez hoje" + (if (browserUrl != null) " · no navegador" else "") +
                    if (Prefs.restNow()) " · horário de descanso" else ""
            },
        ).apply { gravity = Gravity.CENTER }
        col.add(sub, top = 4)

        val breath = BreathView(this)
        val breathBox = FrameLayout(this)
        breathBox.addView(breath, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
        val breathLabel = label("inspire", 17f, 500, cMuted).apply { gravity = Gravity.CENTER }
        val counter = caption("").apply { gravity = Gravity.CENTER }
        breath.onPhase = { inhale -> breathLabel.text = if (inhale) "inspire" else "expire" }

        val choices = vbox().apply { visibility = View.GONE }

        if (delay > 0) {
            col.add(breathBox, top = 36, h = dp(200))
            col.add(breathLabel, top = 14)
            col.add(counter, top = 2)
        }
        col.add(choices, top = 28)

        val root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        root.addView(sheet(col))
        edgeToEdge(root)
        setContentView(root)

        // Uso de hoje → limite.
        if (mode != Mode.FOCUS) async({ usageOn(this, dayStart()).of(site) }) { used ->
            val lim = Prefs.limit(site.id) * MIN
            overLimit = used >= lim
            sub.text = sub.text.toString() + if (overLimit) " · ${fmt(used)} hoje, limite ${fmt(lim)}" else " · ${fmt(used)} de ${fmt(lim)}"
        }

        val reveal = Runnable {
            if (delay > 0) {
                (breathBox.layoutParams as LinearLayout.LayoutParams).height = dp(110)
                breath.layoutParams = FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER)
                breathBox.requestLayout()
                counter.visibility = View.GONE
            }
            buildChoices(choices)
            choices.fadeIn()
        }
        if (delay > 0) {
            var left = delay
            val tick = object : Runnable {
                override fun run() {
                    if (left <= 0) { reveal.run(); return }
                    counter.text = "$left"
                    left--
                    h.postDelayed(this, 1000)
                }
            }
            tick.run()
        } else reveal.run()
    }

    private fun buildChoices(c: LinearLayout) {
        c.removeAllViews()
        when (mode) {
            Mode.FOCUS -> focusChoices(c)
            Mode.EXPIRED -> expiredChoices(c)
            Mode.OPEN -> if (browserUrl != null) browserChoices(c) else openChoices(c)
        }
    }

    private fun openChoices(c: LinearLayout) {
        plan(c)
        c.add(label("O que você veio fazer?", 20f, 600), top = 24)
        val hint = caption("").apply { visibility = View.GONE }
        val web = button("Usar a versão web", Btn.SECONDARY) { go(Outcome.WEB) }
        val appRow = vbox()
        val gated = listOf<View>(web, appRow)
        gated.forEach { it.enable(false) }

        c.add(chips(INTENTIONS) { sel ->
            intention = INTENTIONS[sel.first()]
            gated.forEach { it.enable(true) }
            if (intention in IMPULSIVE) {
                hint.text = "Percebido. Que tal 2 minutos sem tela? Beba água, alongue, olhe pela janela."
                hint.fadeIn()
            } else hint.visibility = View.GONE
        }, top = 12)
        c.add(hint, top = 12)

        c.add(button("Não abrir agora") { leave(Outcome.DISMISS) }, top = 28)
        if (site.web) c.add(web, top = 10)

        appRow.add(caption("ou abrir o app por").apply { gravity = Gravity.CENTER }, top = 18)
        val mins = listOf(5, 10, 15)
        appRow.add(chips(mins.map { "$it min" }) { sel ->
            if (!appRow.isEnabled) return@chips
            val m = mins[sel.first()]
            if (overLimit) confirmPhrase("Você passou do limite de hoje") { allow(m, Outcome.APP) } else allow(m, Outcome.APP)
        }.apply { setPadding(0, 0, 0, 0) }, top = 8, w = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        c.add(appRow)
    }

    /** Aberto num navegador: a opção principal é a versão filtrada no Respiro; não há passe no navegador. */
    private fun browserChoices(c: LinearLayout) {
        plan(c)
        c.add(label("O que você veio fazer?", 20f, 600), top = 24)
        val hint = caption("").apply { visibility = View.GONE }
        val open = button("Abrir no Respiro") {
            if (overLimit) confirmPhrase("Você passou do limite de hoje") { go(Outcome.BROWSER_WEB) } else go(Outcome.BROWSER_WEB)
        }
        open.enable(false)
        c.add(chips(INTENTIONS) { sel ->
            intention = INTENTIONS[sel.first()]
            open.enable(true)
            if (intention in IMPULSIVE) {
                hint.text = "Percebido. Que tal 2 minutos sem tela? Beba água, alongue, olhe pela janela."
                hint.fadeIn()
            } else hint.visibility = View.GONE
        }, top = 12)
        c.add(hint, top = 12)
        c.add(open, top = 28)
        c.add(caption("Sem feed infinito e sem vídeos curtos.").apply { gravity = Gravity.CENTER }, top = 6)
        c.add(button("Não abrir agora", Btn.SECONDARY) { leave(Outcome.DISMISS) }, top = 14)
    }

    private fun plan(c: LinearLayout) {
        if (Prefs.planIf.isNotBlank() && Prefs.planThen.isNotBlank()) {
            c.add(card {
                add(section("Seu plano"))
                add(label("Se ${Prefs.planIf}, então ${Prefs.planThen}.", 16f, 500), top = 6)
            }, top = 0)
        }
    }

    private fun expiredChoices(c: LinearLayout) {
        c.add(label("Valeu a pena?", 20f, 600).apply { gravity = Gravity.CENTER })
        var mood: Int? = null
        c.add(chips(listOf("Melhor", "Igual", "Pior")) { mood = 3 - it.first() }, top = 12, w = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        fun saveMood() { if (!preview) mood?.let { Db.i.logSession(site.id, "app", 0, it, null, 0) } }
        c.add(button("Sair") { saveMood(); leave(Outcome.EXPIRED_EXIT) }, top = 28)
        c.add(button("Mais 5 minutos", Btn.SECONDARY) {
            saveMood()
            if (overLimit) confirmPhrase("Você passou do limite de hoje") { allow(5, Outcome.EXPIRED_MORE) } else allow(5, Outcome.EXPIRED_MORE)
        }, top = 10)
    }

    private fun focusChoices(c: LinearLayout) {
        val left = Prefs.focusUntil - now()
        c.add(label("Faltam ${fmt(left + MIN - 1)}.", 22f, 600).apply { gravity = Gravity.CENTER })
        c.add(caption("${site.name} pode esperar. O que você estava fazendo antes?").apply { gravity = Gravity.CENTER }, top = 6)
        c.add(button("Voltar") { leave(Outcome.BLOCKED) }, top = 28)
        c.add(button("Encerrar o foco", Btn.TEXT) {
            confirmPhrase("Encerrar o foco?") {
                Prefs.focusUntil = 0
                log(Outcome.FOCUS_END)
                setup(intent.putExtra("mode", Mode.OPEN.name))
            }
        }, top = 6)
    }

    private fun allow(minutes: Int, outcome: String) {
        decided = true
        if (preview) { finish(); return }
        val until = now() + minutes * MIN
        Prefs.setPass(site.id, until)
        log(outcome)
        GuardService.instance?.arm(site, until, pkg)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun go(where: String) {
        decided = true
        log(where)
        startActivity(
            Intent(this, WebActivity::class.java)
                .setData(Uri.parse("respiro://site/${site.id}"))
                .putExtra("intention", intention)
                .apply { browserUrl?.let { putExtra("url", it) } },
        )
        finish()
    }

    private fun leave(outcome: String) {
        if (!decided) log(outcome)
        decided = true
        if (preview) { finish(); return }
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
