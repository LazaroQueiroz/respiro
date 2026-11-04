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
    private val h = Handler(Looper.getMainLooper())
    private lateinit var site: Site
    private var mode = "open"
    private var pkg: String? = null
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
        if (!decided && ::site.isInitialized) log("left")
        setup(i)
    }

    override fun onResume() { super.onResume(); GuardService.interceptVisible = true }
    override fun onPause() { super.onPause(); GuardService.interceptVisible = false }

    override fun onStop() {
        super.onStop()
        // Saiu sem decidir (Home, Recentes, tela apagada): conta como não abrir.
        if (!decided && !isFinishing) {
            decided = true
            log(if (mode == "expired") "expired_exit" else "left")
            finish()
        }
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave(if (mode == "expired") "expired_exit" else "dismiss")

    private fun setup(i: Intent) {
        h.removeCallbacksAndMessages(null)
        site = Sites.byId(i.getStringExtra("site") ?: "") ?: run { finish(); return }
        mode = i.getStringExtra("mode") ?: "open"
        pkg = i.getStringExtra("pkg")
        preview = i.getBooleanExtra("preview", false)
        if (mode == "open" && Prefs.focusActive()) mode = "focus"
        intention = null
        decided = false
        overLimit = false

        val opens = Db.i.countOpens(site.id, dayStart())
        delay = when (mode) {
            "expired" -> 3
            "focus" -> 0
            else -> if (Prefs.restNow()) 30 else min(30, 5 + opens * 2)
        }

        val col = vbox(28) { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(label(site.name, 34f, 700).apply { gravity = Gravity.CENTER })
        val sub = caption(
            when (mode) {
                "focus" -> "Modo foco"
                "expired" -> "Seu tempo acabou"
                else -> "${opens + 1}ª vez hoje" + if (Prefs.restNow()) " · horário de descanso" else ""
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
        if (mode != "focus") async({
            val d = Usage.day(this, dayStart())
            siteMs(site, d, Db.i.webMs(dayStart(), nextDay(dayStart())))
        }) { used ->
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
            "focus" -> focusChoices(c)
            "expired" -> expiredChoices(c)
            else -> openChoices(c)
        }
    }

    private fun openChoices(c: LinearLayout) {
        if (Prefs.planIf.isNotBlank() && Prefs.planThen.isNotBlank()) {
            c.add(card {
                add(section("Seu plano"))
                add(label("Se ${Prefs.planIf}, então ${Prefs.planThen}.", 16f, 500), top = 6)
            }, top = 0)
        }
        c.add(label("O que você veio fazer?", 20f, 600), top = 24)
        val hint = caption("").apply { visibility = View.GONE }
        val web = button("Usar a versão web", Btn.SECONDARY) { go("web") }
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

        c.add(button("Não abrir agora") { leave("dismiss") }, top = 28)
        if (site.web) c.add(web, top = 10)

        appRow.add(caption("ou abrir o app por").apply { gravity = Gravity.CENTER }, top = 18)
        val mins = listOf(5, 10, 15)
        appRow.add(chips(mins.map { "$it min" }) { sel ->
            if (!appRow.isEnabled) return@chips
            val m = mins[sel.first()]
            if (overLimit) confirmPhrase("Você passou do limite de hoje") { allow(m, "app") } else allow(m, "app")
        }.apply { setPadding(0, 0, 0, 0) }, top = 8, w = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        c.add(appRow)
    }

    private fun expiredChoices(c: LinearLayout) {
        c.add(label("Valeu a pena?", 20f, 600).apply { gravity = Gravity.CENTER })
        var mood: Int? = null
        c.add(chips(listOf("Melhor", "Igual", "Pior")) { mood = 3 - it.first() }, top = 12, w = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        fun saveMood() { if (!preview) mood?.let { Db.i.logSession(site.id, "app", 0, it, null, 0) } }
        c.add(button("Sair") { saveMood(); leave("expired_exit") }, top = 28)
        c.add(button("Mais 5 minutos", Btn.SECONDARY) {
            saveMood()
            if (overLimit) confirmPhrase("Você passou do limite de hoje") { allow(5, "expired_more") } else allow(5, "expired_more")
        }, top = 10)
    }

    private fun focusChoices(c: LinearLayout) {
        val left = Prefs.focusUntil - now()
        c.add(label("Faltam ${fmt(left + MIN - 1)}.", 22f, 600).apply { gravity = Gravity.CENTER })
        c.add(caption("${site.name} pode esperar. O que você estava fazendo antes?").apply { gravity = Gravity.CENTER }, top = 6)
        c.add(button("Voltar") { leave("blocked") }, top = 28)
        c.add(button("Encerrar o foco", Btn.TEXT) {
            confirmPhrase("Encerrar o foco?") {
                Prefs.focusUntil = 0
                log("focus_end")
                setup(intent.putExtra("mode", "open"))
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
                .putExtra("intention", intention),
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
