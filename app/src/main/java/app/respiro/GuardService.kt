package app.respiro

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Escuta trocas de janela (qual app abriu) e, só nos navegadores conhecidos, lê o campo da barra
 * de endereço. Nenhum outro conteúdo da tela é lido.
 * Custo: uma busca num HashMap por evento; nos navegadores, no máximo uma leitura da URL a cada 800 ms.
 */
class GuardService : AccessibilityService() {
    companion object {
        var instance: GuardService? = null
        @Volatile var interceptVisible = false
        private var lastLaunch = 0L

        /** Navegador → id do campo da barra de endereço. */
        val BROWSERS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
        )
    }

    private val h = Handler(Looper.getMainLooper())
    private val hb = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var ignore = emptySet<String>()
    private var urlCheckPending = false
    private var lastBrowserHost: String? = null

    override fun onServiceConnected() {
        instance = this
        val ime = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        ignore = setOf("com.android.systemui", "android") + ime
    }

    override fun onDestroy() {
        instance = null
        h.removeCallbacksAndMessages(null); hb.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in ignore) return
                if (pkg != current) lastBrowserHost = null
                current = pkg
                if (pkg in BROWSERS) scheduleUrlCheck(pkg) else check(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == current && pkg in BROWSERS) scheduleUrlCheck(pkg)
        }
    }

    private fun check(pkg: String) {
        val site = Sites.byPkg(pkg) ?: return
        if (site.id in Prefs.noIntercept) return
        val t = now()
        if (interceptVisible || t - lastLaunch < 1500) return
        if (Prefs.focusActive()) { launch(site, InterceptActivity.Mode.FOCUS, pkg); return }
        val pass = Prefs.pass(site.id)
        if (pass > t) { arm(site, pass); return }
        launch(site, InterceptActivity.Mode.OPEN, pkg)
    }

    // ---------- Navegadores ----------

    /** Agrupa as mudanças de conteúdo: no máximo uma leitura da URL a cada 800 ms. */
    private fun scheduleUrlCheck(pkg: String) {
        if (urlCheckPending) return
        urlCheckPending = true
        hb.postDelayed({ urlCheckPending = false; if (current == pkg) checkBrowser(pkg) }, 800)
    }

    private fun checkBrowser(pkg: String) {
        val id = BROWSERS[pkg] ?: return
        val node = runCatching { rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }.getOrNull() ?: return
        // Campo em edição: é o que a pessoa está digitando, não a página aberta.
        if (node.isFocused) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || ' ' in text) return
        val url = if ("://" in text) text else "https://$text"
        val host = Uri.parse(url).host ?: return
        val site = Sites.forBrowserHost(host)
        if (site == null || site.id in Prefs.noIntercept) { lastBrowserHost = null; return }
        // Mesmo site ainda na tela (ex.: navegando dentro dele depois de uma pausa): não repete.
        if (host == lastBrowserHost) return
        val t = now()
        if (interceptVisible || t - lastLaunch < 1500) return
        if (!Prefs.focusActive() && Prefs.pass(site.id) > t) return
        lastBrowserHost = host
        // Tira a aba da rede antes da pausa: o "voltar" vale para o navegador, que ainda está na frente.
        performGlobalAction(GLOBAL_ACTION_BACK)
        lastLaunch = t
        h.postDelayed({
            launch(site, if (Prefs.focusActive()) InterceptActivity.Mode.FOCUS else InterceptActivity.Mode.OPEN, pkg, browserUrl = url)
        }, 250)
    }

    /** Agenda a checagem de quando o passe expira; se o app ainda estiver na frente, intercepta. */
    fun arm(site: Site, until: Long, pkg: String? = null) {
        if (pkg != null) current = pkg
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            if (current in site.pkgs && Prefs.pass(site.id) <= now()) launch(site, InterceptActivity.Mode.EXPIRED, current)
        }, until - now() + 500)
    }

    private fun launch(site: Site, mode: InterceptActivity.Mode, pkg: String?, browserUrl: String? = null) {
        lastLaunch = now()
        startActivity(
            Intent(this, InterceptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("site", site.id)
                .putExtra("mode", mode.name)
                .putExtra("pkg", pkg)
                .putExtra("browserUrl", browserUrl),
        )
    }
}
