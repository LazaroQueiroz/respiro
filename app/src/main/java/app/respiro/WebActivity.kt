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
 *  - feed infinito coberto por um painel de atalhos (busca, mensagens…);
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
        JS.replace("__CFG__", cfg.toString())
    }

    private fun inject() { web.evaluateJavascript(if (feedForced) "window.__rspFeedOk=1;$script" else script, null) }

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
        val html = HOME_HTML.replace("__NAME__", esc(site.name)).replace("__SEARCH__", search).replace("__LINKS__", links)
        web.loadDataWithBaseURL(site.url, html, "text/html", "utf-8", site.url)
    }

    inner class Bridge {
        @JavascriptInterface fun scr(n: Int) { h.post { onScreens(n) } }
        @JavascriptInterface fun feed() { h.post { askShowFeed() } }
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

        async({
            Usage.day(this, dayStart()).let { siteMs(site, it, Db.i.webMs(dayStart(), nextDay(dayStart()))) }
        }) { u ->
            limitUsed = u
            val lim = Prefs.limit(site.id) * MIN
            val extra = if (u >= lim) "Você já usou ${fmt(u)} hoje — o limite é ${fmt(lim)}." else "Hoje: ${fmt(u)} de ${fmt(lim)}."
            info.text = if (Prefs.restNow()) "${info.text}\n$extra" else extra
        }
    }

    private fun start(minutes: Int) {
        budget = minutes * MIN
        used = 0; screens = 0
        nextCue = Prefs.scrollCue
        logged = false
        running = true
        resumedAt = now()
        hideOverlay()
        web.resumeTimers()
        web.onResume()
        h.removeCallbacks(tick); h.post(tick)
    }

    private fun extend(minutes: Int) {
        budget += minutes * MIN
        running = true
        resumedAt = now()
        hideOverlay()
        web.resumeTimers()
        web.onResume()
        h.removeCallbacks(tick); h.post(tick)
    }

    private fun stopClock() {
        if (running) { used += now() - resumedAt; running = false }
        h.removeCallbacks(tick)
    }

    private fun timeUp() {
        stopClock()
        web.onPause()
        web.pauseTimers()
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
        stopClock()
        web.onPause()
        web.pauseTimers()
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
            web.evaluateJavascript("document.querySelector('meta[name=rsp-home]')?1:(window.__rsp&&window.__rsp.show(),0)") {
                if (it == "1") web.loadUrl(site.url)
            }
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

private val JS = """
(function(){
if(window.__rsp){window.__rsp.u();return;}
var C=__CFG__,B=window.RespiroBridge,feedOk=!!window.__rspFeedOk,acc=0,pos=new WeakMap();
var fRe=C.feed?new RegExp(C.feed):null,bRe=C.block?new RegExp(C.block):null,rRe=C.reel?new RegExp(C.reel):null,rId=null,rUrl='',ty=0;
function css(){if(!C.hide)return;
 if(!document.getElementById('__rsp_css')){var s=document.createElement('style');s.id='__rsp_css';s.textContent=C.hide+'{display:none!important}';(document.head||document.documentElement).appendChild(s);}
 try{var n=document.querySelectorAll(C.hide);for(var i=0;i<n.length;i++)n[i].style.setProperty('display','none','important');}catch(e){}}
function el(t,st,tx){var e=document.createElement(t);if(st)e.style.cssText=st;if(tx)e.textContent=tx;return e;}
function under(on){if(!document.body)return;var k=document.body.children;
 for(var i=0;i<k.length;i++){if(k[i].id==='__rsp_ov')continue;
  if(on)k[i].style.setProperty('content-visibility','hidden','important');else k[i].style.removeProperty('content-visibility');}
 if(on){var v=document.querySelectorAll('video');for(var j=0;j<v.length;j++)if(!v[j].paused)v[j].pause();}}
function ov(on){var o=document.getElementById('__rsp_ov');
 under(on);
 if(!on){if(o)o.remove();return;}
 if(o||!document.body)return;
 var dk=matchMedia('(prefers-color-scheme: dark)').matches,bg=dk?'#000':'#fff',fg=dk?'#fff':'#000',mu='#86868b',cd=dk?'#161617':'#f5f5f7';
 o=el('div','position:fixed;inset:0;z-index:2147483647;background:'+bg+';color:'+fg+';display:flex;flex-direction:column;justify-content:center;padding:32px 24px;box-sizing:border-box;font-family:system-ui,-apple-system,Roboto,sans-serif;overflow:auto;-webkit-font-smoothing:antialiased');
 o.id='__rsp_ov';
 o.appendChild(el('div','font-size:12px;font-weight:600;letter-spacing:.07em;text-transform:uppercase;color:'+mu,C.name));
 o.appendChild(el('div','font-size:32px;font-weight:700;letter-spacing:-.02em;margin-top:6px','Feed oculto'));
 o.appendChild(el('div','font-size:16px;color:'+mu+';margin-top:6px;line-height:1.4','Vá direto ao que você veio fazer.'));
 if(C.search){var f=el('form','margin:28px 0 0');var i=el('input','width:100%;box-sizing:border-box;font:inherit;font-size:17px;padding:15px 16px;border-radius:16px;border:0;outline:0;background:'+cd+';color:'+fg);
  i.type='search';i.placeholder='Buscar';f.appendChild(i);
  f.addEventListener('submit',function(ev){ev.preventDefault();var v=i.value.trim();if(v)location.href=C.search.replace('%s',encodeURIComponent(v));});o.appendChild(f);}
 var l=el('div','margin-top:12px;display:flex;flex-direction:column;gap:10px');
 C.links.forEach(function(k){var a=el('a','display:block;padding:16px;border-radius:16px;background:'+cd+';color:'+fg+';text-decoration:none;font-size:17px;font-weight:500',k[0]);a.href=k[1];l.appendChild(a);});
 o.appendChild(l);
 var s=el('div','margin-top:32px;color:'+mu+';font-size:14px;text-align:center;text-decoration:underline;cursor:pointer','Mostrar o feed mesmo assim');
 s.addEventListener('click',function(e){e.preventDefault();if(B)B.feed();});
 o.appendChild(s);document.body.appendChild(o);}
function lock(on){var L=document.querySelectorAll('[data-rsp-lock]'),i;
 if(!on){for(i=0;i<L.length;i++){L[i].style.removeProperty('overflow-y');L[i].removeAttribute('data-rsp-lock');}return;}
 var v=document.querySelectorAll('video');
 for(i=0;i<v.length;i++){var e=v[i].parentElement;
  while(e&&e!==document.body){var cs=getComputedStyle(e);
   if(/(auto|scroll)/.test(cs.overflowY)&&e.scrollHeight>e.clientHeight+10){e.style.setProperty('overflow-y','hidden','important');e.setAttribute('data-rsp-lock','1');}
   e=e.parentElement;}}}
var NAG=/^(use the app|usar o app|abrir o app|abrir app|open app|open in app|abrir no app|abrir no aplicativo|get the app|baixar o app|open tiktok|abrir o tiktok|switch to the app)$/i;
function nag(){var b=document.querySelectorAll('button,a[role=button],a[href*="app"]');
 for(var i=0;i<b.length;i++){if(!NAG.test((b[i].textContent||'').trim()))continue;
  var e=b[i];for(var k=0;k<8&&e&&e!==document.body;k++){var ps=getComputedStyle(e).position;
   if(ps==='fixed'||ps==='sticky'){e.style.setProperty('display','none','important');break;}e=e.parentElement;}}}
function login(){return !!document.querySelector('input[type=password],input[name=pass],input[autocomplete=current-password]');}
var HOME=!!document.querySelector('meta[name=rsp-home]');
function u(){if(HOME)return;css();nag();var p=location.pathname;
 if(C.yt){var m=p.match(/^\/shorts\/([\w-]+)/);if(m){location.replace('/watch?v='+m[1]);return;}}
 if(bRe&&bRe.test(p)){if(B)B.blocked();location.replace(C.home);return;}
 if(rRe){var r=p.match(rRe);
  if(!r)rId=null;
  else if(!rId){rId=r[1];rUrl=location.href;if(B)B.reel();}
  else if(r[1]!==rId){location.replace(rUrl);return;}
}
 if(C.reel)lock(!!rId||bigVid());
 ov(!!(fRe&&!feedOk&&fRe.test(p)&&!login()));}
window.__rsp={u:u,show:function(){feedOk=true;ov(false);}};
document.addEventListener('scroll',function(e){var t=e.target&&e.target.nodeType===1?e.target:document.scrollingElement;if(!t)return;
 var y=t.scrollTop,q=pos.get(t);if(q!==undefined)acc+=Math.abs(y-q);pos.set(t,y);
 var hh=innerHeight||600;if(acc>=hh*3){var n=Math.floor(acc/hh);acc-=n*hh;if(B)B.scr(n);}},{capture:true,passive:true});
['pushState','replaceState'].forEach(function(k){var o=history[k];history[k]=function(){var x=o.apply(this,arguments);setTimeout(u,0);return x;};});
window.addEventListener('popstate',function(){setTimeout(u,0);});
function bigVid(){var v=document.querySelectorAll('video'),H=innerHeight;
 for(var i=0;i<v.length;i++){var r=v[i].getBoundingClientRect();if(r.height>H*0.45&&r.top<H*0.5&&r.bottom>H*0.5)return true;}return false;}
function exempt(t){if(!t||!t.closest)return false;if(t.closest('textarea,input'))return true;
 var d=t.closest('[role=dialog]');return !!(d&&!d.querySelector('video'));}
function active(t){return !!C.reel&&(!!rId||bigVid())&&!exempt(t);}
var moved=false,act=false,lastSwipe=0;
['touchstart','pointerdown'].forEach(function(k){window.addEventListener(k,function(e){var q=e.touches?e.touches[0]:e;
 if(k==='pointerdown'&&e.pointerType==='touch'&&act)return;ty=q.clientY;moved=false;act=active(e.target);
 if(act&&B&&!window.__rspT){window.__rspT=1;B.reel();}},{capture:true,passive:true});});
['touchmove','pointermove'].forEach(function(k){window.addEventListener(k,function(e){if(!act)return;
 var q=e.touches?e.touches[0]:e;if(Math.abs(q.clientY-ty)>8){moved=true;lastSwipe=Date.now();}
 if(e.cancelable)e.preventDefault();e.stopImmediatePropagation();},{capture:true,passive:false});});
['touchend','pointerup'].forEach(function(k){window.addEventListener(k,function(e){if(act&&moved){if(e.cancelable)e.preventDefault();e.stopImmediatePropagation();}},{capture:true,passive:false});});
// Deslizar para cima no visualizador abre a resposta (teclado): desfaz se o foco veio de um deslize, não de um toque.
window.addEventListener('focusin',function(e){var t=e.target;
 if(Date.now()-lastSwipe<900&&t&&(t.isContentEditable||/^(TEXTAREA|INPUT)$/.test(t.tagName))){t.blur();}},true);
window.addEventListener('wheel',function(e){if(active(e.target)){if(e.cancelable)e.preventDefault();e.stopImmediatePropagation();}},{capture:true,passive:false});
var pend=false;function sched(){if(pend)return;pend=true;setTimeout(function(){pend=false;if(!document.hidden)u();},700);}
new MutationObserver(sched).observe(document.documentElement,{childList:true,subtree:true});
setInterval(function(){if(!document.hidden)u();},4000);u();
})();
"""

private val HOME_HTML = """<!doctype html><html lang=pt><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><meta name=rsp-home content=1>
<style>
:root{--bg:#fff;--fg:#000;--mu:#86868b;--cd:#f5f5f7;color-scheme:light dark}
@media (prefers-color-scheme:dark){:root{--bg:#000;--fg:#fff;--cd:#161617}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;min-height:100%;background:var(--bg);color:var(--fg);font-family:system-ui,-apple-system,Roboto,sans-serif;-webkit-font-smoothing:antialiased}
main{min-height:100vh;display:flex;flex-direction:column;justify-content:center;padding:32px 24px}
.k{font-size:12px;font-weight:600;letter-spacing:.07em;text-transform:uppercase;color:var(--mu)}
h1{font-size:32px;font-weight:700;letter-spacing:-.02em;margin:6px 0 0}
p{font-size:16px;color:var(--mu);margin:6px 0 0;line-height:1.4}
form{margin-top:28px}
input{width:100%;font:inherit;font-size:17px;padding:15px 16px;border-radius:16px;border:0;outline:0;background:var(--cd);color:var(--fg)}
nav{margin-top:12px;display:flex;flex-direction:column;gap:10px}
nav a{display:block;padding:16px;border-radius:16px;background:var(--cd);color:var(--fg);text-decoration:none;font-size:17px;font-weight:500}
nav a:active{opacity:.6}
button{margin-top:32px;background:none;border:0;color:var(--mu);font:inherit;font-size:14px;text-decoration:underline}
</style></head><body><main>
<div class=k>__NAME__</div><h1>Feed oculto</h1><p>Vá direto ao que você veio fazer.</p>
__SEARCH__<nav>__LINKS__</nav>
<button onclick="window.RespiroBridge&&RespiroBridge.feed()">Mostrar o feed mesmo assim</button>
</main></body></html>"""
