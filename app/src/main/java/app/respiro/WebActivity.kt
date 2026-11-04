package app.respiro

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versão web de uma rede, com:
 *  - no lugar do feed infinito, uma página local leve com busca e atalhos (assets/home.html);
 *  - filtros injetados em cada página (assets/filter.js);
 *  - Reels/Shorts escondidos e abas bloqueadas (Shorts viram vídeo normal);
 *  - sessão com duração escolhida antes de entrar + barra de tempo;
 *  - "stopping cue" depois de N telas roladas;
 *  - pergunta de humor ao sair;
 *  - tons de cinza opcionais.
 */
class WebActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var site: Site
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var timeTv: TextView
    private lateinit var bar: HBar
    private var overlay: View? = null
    private var cue: View? = null
    private var fullscreen: View? = null
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private var intention: String? = null
    private var budget = 10 * MIN
    private var used = 0L
    private var running = false
    private var resumedAt = 0L
    private var stoppedAt = 0L
    private var logged = true
    private var screens = 0
    private var nextCue = 0
    private var injectedThisLoad = false
    private var limitUsed = 0L
    private var reelToast = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val u = used + (now() - resumedAt)
            val left = budget - u
            timeTv.text = fmtClock(left)
            bar.fraction = u.toFloat() / budget; bar.invalidate()
            if (left <= 0) timeUp() else h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.data?.lastPathSegment ?: intent.getStringExtra("site") ?: ""
        site = Sites.byId(id) ?: run { finish(); return }
        intention = intent.getStringExtra("intention")
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(site.name))
        buildUi()
        if (Prefs.focusActive()) { showFocusBlock(); return }
        open(linkOrHome(intent))
        showStart()
    }

    private fun linkOrHome(i: Intent): String =
        i.getStringExtra("url")?.takeIf { u -> Uri.parse(u).host?.let { site.allows(it) } == true } ?: site.url

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        // Reaberto pelo atalho ou pela pausa: se não há sessão ativa, recomeça pela escolha.
        setIntent(i)
        if (Prefs.focusActive()) { showFocusBlock(); return }
        if (i.hasExtra("url")) open(linkOrHome(i))
        if (!running) {
            endSession(null)
            intention = i.getStringExtra("intention")
            showStart()
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val col = vbox()
        val top = hbox {
            setPadding(dp(18), dp(6), dp(6), dp(6))
            add(label(site.name, 15f, 600), w = 0, weight = 1f)
            timeTv = label("", 15f, 500, cMuted).apply { typeface = font(500) }
            add(timeTv, w = WRAP)
            add(button("Sair", Btn.TEXT) { exitFlow() }.apply { setTextColor(cFg) }, w = WRAP, start = 4)
        }
        col.add(top)
        bar = HBar(this)
        col.add(bar, h = dp(3))
        web = WebView(this)
        col.add(web, h = 0, weight = 1f)
        root.addView(col)
        edgeToEdge(root)
        setContentView(root)
        setupWeb()
    }

    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            // Alguns sites (login do Google, Instagram) recusam WebViews identificadas.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/\\d+\\.\\d+"), "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        if (Prefs.grayscale) {
            val p = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            web.setLayerType(View.LAYER_TYPE_HARDWARE, p)
        }
        web.addJavascriptInterface(Bridge(), "RespiroBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!r.isForMainFrame) return false
                return handleUrl(r.url)
            }
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { injectedThisLoad = false }
            override fun onPageCommitVisible(v: WebView, url: String?) = inject()
            override fun onPageFinished(v: WebView, url: String?) = inject()
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                endSession(null); finishAndRemoveTask(); return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (p >= 40 && !injectedThisLoad) { injectedThisLoad = true; inject() }
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { startActivityForResult(params.createIntent(), 7); true } catch (e: Exception) { fileCb = null; false }
            }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                fullscreen = view; fullscreenCb = cb
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                window.insetsController?.apply {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            override fun onHideCustomView() = exitFullscreen()
        }
    }

    private fun exitFullscreen() {
        fullscreen?.let { root.removeView(it) }
        fullscreen = null
        fullscreenCb?.onCustomViewHidden(); fullscreenCb = null
        window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    /** true = o WebView não deve navegar. Bloqueia deep links que abririam o app nativo. */
    private fun handleUrl(u: Uri): Boolean {
        return when (u.scheme) {
            "http", "https" -> {
                val host = u.host ?: return true
                when {
                    !site.allows(host) -> { openOutside(u); true }
                    isFeed(u) -> { loadHome(); true }
                    else -> false
                }
            }
            "mailto", "tel" -> { openOutside(u); true }
            else -> true // intent://, instagram://, vnd.youtube:… → ignorados
        }
    }

    private fun openOutside(u: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    // ---------- Injeção ----------

    private val script: String by lazy {
        val hide = listOfNotNull(
            site.short.takeIf { Prefs.hideShorts && it.isNotEmpty() },
            site.feedCss.takeIf { Prefs.hideFeed && it.isNotEmpty() },
        ).joinToString(",")
        val cfg = JSONObject()
            .put("hide", hide)
            .put("feed", if (Prefs.hideFeed) site.feedRe ?: "" else "")
            .put("block", if (Prefs.hideShorts) site.blockRe ?: "" else "")
            .put("yt", Prefs.hideShorts && site.id == "youtube")
            .put("reel", if (Prefs.hideShorts) site.reelRe ?: "" else "")
            .put("home", site.url)
            .put("name", site.name)
            .put("search", site.search ?: "")
            .put("links", JSONArray().apply { site.links.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        asset("filter.js").replace("__CFG__", cfg.toString())
    }

    private fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }

    private fun inject() { web.evaluateJavascript(script, null) }

    // ---------- Início leve ----------

    /** Feed liberado manualmente nesta sessão ("Mostrar o feed mesmo assim"). */
    private var feedForced = false

    private fun isFeed(u: Uri): Boolean {
        if (!Prefs.hideFeed || feedForced) return false
        val re = site.feedRe ?: return false
        val h = (u.host ?: "").removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        return h == site.domain && Regex(re).containsMatchIn(u.path?.ifEmpty { "/" } ?: "/")
    }

    private fun open(url: String) { if (isFeed(Uri.parse(url))) loadHome() else web.loadUrl(url) }

    /**
     * No lugar do feed, uma página local com busca e atalhos. O site real (e seus ~200 MB
     * de JavaScript, imagens e vídeos) só carrega quando você vai a algum lugar.
     */
    private fun loadHome() {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val links = site.links.joinToString("") { (l, u) -> "<a href=\"${esc(u)}\">${esc(l)}</a>" }
        val search = site.search?.let {
            "<form id=s><input name=q type=search placeholder=\"Buscar\" autocomplete=off enterkeyhint=search></form>" +
                "<script>document.getElementById('s').onsubmit=function(e){e.preventDefault();var v=this.q.value.trim();" +
                "if(v)location.href=${JSONObject.quote(it)}.replace('%s',encodeURIComponent(v));};</script>"
        } ?: ""
        val html = asset("home.html").replace("__NAME__", esc(site.name)).replace("__SEARCH__", search).replace("__LINKS__", links)
        web.loadDataWithBaseURL(site.url, html, "text/html", "utf-8", site.url)
    }

    inner class Bridge {
        @JavascriptInterface fun scr(n: Int) { h.post { onScreens(n) } }
        @JavascriptInterface fun feed() { h.post { askShowFeed() } }
        /** O site navegou até o feed: troca pela página leve, a não ser que o feed tenha sido liberado. */
        @JavascriptInterface fun home() { h.post { if (Prefs.hideFeed && !feedForced) loadHome() } }
        @JavascriptInterface fun reel() {
            h.post { if (!reelToast) { reelToast = true; Toast.makeText(this@WebActivity, "Só este vídeo — deslizar para os próximos está bloqueado", Toast.LENGTH_LONG).show() } }
        }
        @JavascriptInterface fun blocked() {
            h.post { Toast.makeText(this@WebActivity, "Vídeos curtos bloqueados pelo Respiro", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------- Sessão ----------

    private fun showOverlay(content: View, center: Boolean = true) {
        overlay?.let { root.removeView(it) }
        val s = sheet(content, center)
        overlay = s
        root.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        s.fadeIn()
    }

    private fun hideOverlay() { overlay?.let { root.removeView(it) }; overlay = null }

    private fun showStart() {
        val col = vbox(28)
        col.add(label(site.name, 34f, 700))
        val info = caption(if (Prefs.restNow()) "Horário de descanso. Que tal deixar para amanhã?" else "Defina antes de entrar.")
        col.add(info, top = 4)

        val enter = button("Entrar") { }
        var minutes = 10
        if (intention == null) {
            col.add(label("Por que você está aqui?", 18f, 600), top = 30)
            col.add(chips(INTENTIONS) { intention = INTENTIONS[it.first()]; enter.enable(true) }, top = 12)
            enter.enable(false)
        }
        val opts = listOf(5, 10, 15, 30)
        col.add(label("Por quanto tempo?", 18f, 600), top = 26)
        col.add(chips(opts.map { "$it min" }, initial = setOf(1)) { minutes = opts[it.first()] }, top = 12)

        enter.setOnClickListener {
            if (!enter.isEnabled) return@setOnClickListener
            val lim = Prefs.limit(site.id) * MIN
            if (limitUsed >= lim) confirmPhrase("Você passou do limite de hoje") { start(minutes) } else start(minutes)
        }
        col.add(enter, top = 34)
        col.add(button("Agora não", Btn.TEXT) { finishAndRemoveTask() }, top = 6)
        showOverlay(col)

        async({ usageOn(this, dayStart()).of(site) }) { u ->
            limitUsed = u
            val lim = Prefs.limit(site.id) * MIN
            val extra = if (u >= lim) "Você já usou ${fmt(u)} hoje — o limite é ${fmt(lim)}." else "Hoje: ${fmt(u)} de ${fmt(lim)}."
            info.text = if (Prefs.restNow()) "${info.text}\n$extra" else extra
        }
    }

    /** Começa uma sessão nova de [minutes] minutos. */
    private fun start(minutes: Int) {
        budget = minutes * MIN
        used = 0; screens = 0
        nextCue = Prefs.scrollCue
        logged = false
        resume()
    }

    /** Dá mais [minutes] minutos à sessão atual. */
    private fun extend(minutes: Int) {
        budget += minutes * MIN
        resume()
    }

    private fun resume() {
        running = true
        resumedAt = now()
        hideOverlay()
        web.resumeTimers()
        web.onResume()
        h.removeCallbacks(tick); h.post(tick)
    }

    /** Para o relógio e congela a página (sem JavaScript nem vídeo rodando por trás da tela). */
    private fun freeze() {
        stopClock()
        web.onPause()
        web.pauseTimers()
    }

    private fun stopClock() {
        if (running) { used += now() - resumedAt; running = false }
        h.removeCallbacks(tick)
    }

    private fun timeUp() {
        freeze()
        val col = vbox(28) { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(BreathView(this), w = dp(120), h = dp(120))
        col.add(label("Seu tempo acabou.", 28f, 700).apply { gravity = Gravity.CENTER }, top = 28)
        col.add(caption("Você passou ${fmt(used + 30_000)} no ${site.name}.").apply { gravity = Gravity.CENTER }, top = 6)
        col.add(button("Sair") { showMood() }, top = 32)
        val more = button("Mais 5 minutos", Btn.SECONDARY) {
            val lim = Prefs.limit(site.id) * MIN
            if (limitUsed + used >= lim) confirmPhrase("Você passou do limite de hoje") { extend(5) } else extend(5)
        }
        col.add(more, top = 10)
        more.countdown(10, "Mais 5 minutos")
        showOverlay(col)
    }

    private fun showMood() {
        freeze()
        val col = vbox(28)
        col.add(label("Como você sai daqui?", 28f, 700))
        col.add(caption("Isso mostra no Painel quais redes valem a pena para você."), top = 6)
        listOf("Melhor" to 3, "Igual" to 2, "Pior" to 1).forEachIndexed { i, (s, m) ->
            col.add(button(s, Btn.SECONDARY) { endSession(m); finishAndRemoveTask() }, top = if (i == 0) 28 else 10)
        }
        col.add(button("Pular", Btn.TEXT) { endSession(null); finishAndRemoveTask() }, top = 8)
        showOverlay(col)
    }

    private fun exitFlow() {
        stopClock()
        if (logged || used < 20_000) { endSession(null); finishAndRemoveTask() } else showMood()
    }

    private fun endSession(mood: Int?) {
        stopClock()
        if (logged) return
        logged = true
        if (used > 0) Db.i.logSession(site.id, "web", used, mood, intention, screens)
    }

    private fun showFocusBlock() {
        val col = vbox(28) { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(label("Modo foco", 34f, 700).apply { gravity = Gravity.CENTER })
        col.add(caption("Faltam ${fmt(Prefs.focusUntil - now() + MIN - 1)}. ${site.name} pode esperar.").apply { gravity = Gravity.CENTER }, top = 6)
        col.add(button("Voltar") { finishAndRemoveTask() }, top = 32)
        showOverlay(col)
    }

    // ---------- Rolagem ----------

    private fun onScreens(n: Int) {
        if (!running) return
        screens += n
        val cueEvery = Prefs.scrollCue
        if (cueEvery <= 0 || screens < nextCue || cue != null) return
        nextCue = screens + cueEvery
        val c = card {
            add(label("Você já rolou ~$screens telas.", 17f, 600))
            add(caption("Ainda está procurando algo específico?"), top = 4)
            add(hbox {
                add(button("Sair", Btn.PRIMARY) { dismissCue(); exitFlow() }, w = 0, weight = 1f)
                add(button("Continuar", Btn.SECONDARY) { dismissCue() }.apply { background = rounded(cBg, 16) }, w = 0, weight = 1f, start = 10)
            }, top = 14)
        }
        cue = c
        root.addView(c, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(14)) })
        c.elevation = dpf(8f)
        c.fadeIn()
    }

    private fun dismissCue() { cue?.let { root.removeView(it) }; cue = null }

    private fun askShowFeed() {
        val col = vbox(28)
        col.add(label("Mostrar o feed?", 28f, 700))
        col.add(caption("Feeds infinitos são desenhados para não ter fim. Se veio por algo específico, a busca chega mais rápido."), top = 8)
        val show = button("Mostrar feed") {
            hideOverlay()
            feedForced = true
            web.loadUrl(site.url)
        }
        col.add(show, top = 30)
        col.add(button("Voltar", Btn.SECONDARY) { hideOverlay() }, top = 10)
        show.countdown(8, "Mostrar feed")
        showOverlay(col)
    }

    // ---------- Ciclo de vida ----------

    override fun onPause() {
        super.onPause()
        if (running) { used += now() - resumedAt; resumedAt = now() }
        h.removeCallbacks(tick)
        web.onPause()
        web.pauseTimers() // onPause() não pausa JavaScript; sem isso o Instagram gasta ~15% de CPU no fundo
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        if (running) { resumedAt = now(); h.post(tick) }
        web.resumeTimers()
        if (overlay == null) web.onResume()
    }

    private val closeAway = Runnable { endSession(null); finishAndRemoveTask() }

    override fun onStart() { super.onStart(); h.removeCallbacks(closeAway) }

    override fun onStop() {
        super.onStop()
        stoppedAt = now()
        if (fileCb == null) h.postDelayed(closeAway, 3 * MIN)
    }

    override fun onRestart() {
        super.onRestart()
        // Voltou depois de um tempo fora: é uma nova sessão, com nova escolha.
        if (Prefs.focusActive()) { endSession(null); showFocusBlock(); return }
        if (!logged && now() - stoppedAt > 3 * MIN) { endSession(null); intention = null; showStart() }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            endSession(null)
            web.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 7) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen != null -> exitFullscreen()
            cue != null -> dismissCue()
            overlay != null && running -> hideOverlay()
            overlay != null -> finishAndRemoveTask()
            atHome() -> exitFlow()
            else -> smartBack()
        }
    }

    /** Na página inicial do site (onde fica o "Feed oculto"), voltar = sair da rede. */
    private fun atHome(): Boolean {
        val path = Uri.parse(web.url ?: return true).path?.ifEmpty { "/" } ?: "/"
        val re = site.feedRe
        return if (re != null) Regex(re).containsMatchIn(path) else path.trimEnd('/') == (Uri.parse(site.url).path ?: "").trimEnd('/')
    }

    private val authPage = Regex("/(accounts/(login|onetap|emailsignup)|challenge|login|i/flow/|checkpoint)")

    /** Volta para a última página diferente da atual, pulando login/redirecionamentos (que reabririam a mesma página). */
    private fun smartBack() {
        val list = web.copyBackForwardList()
        val cur = list.currentIndex
        fun key(u: String?) = Uri.parse(u ?: "").let { (it.host ?: "") + (it.path ?: "").trimEnd('/') }
        val here = key(list.currentItem?.url)
        for (i in cur - 1 downTo 0) {
            val u = list.getItemAtIndex(i)?.url ?: continue
            if (key(u) == here || authPage.containsMatchIn(Uri.parse(u).path ?: "")) continue
            web.goBackOrForward(i - cur)
            return
        }
        exitFlow()
    }
}
