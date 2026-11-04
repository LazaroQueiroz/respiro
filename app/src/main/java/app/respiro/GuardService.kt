package app.respiro

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Só escuta TYPE_WINDOW_STATE_CHANGED (troca de app/tela) e nunca lê conteúdo.
 * Custo: uma busca num HashMap por troca de janela.
 */
class GuardService : AccessibilityService() {
    companion object {
        var instance: GuardService? = null
        @Volatile var interceptVisible = false
        private var lastLaunch = 0L
    }

    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var ignore = emptySet<String>()

    override fun onServiceConnected() {
        instance = this
        val ime = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        ignore = setOf("com.android.systemui", "android") + ime
    }

    override fun onDestroy() { instance = null; h.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignore) return
        current = pkg
        check(pkg)
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

    /** Agenda a checagem de quando o passe expira; se o app ainda estiver na frente, intercepta. */
    fun arm(site: Site, until: Long, pkg: String? = null) {
        if (pkg != null) current = pkg
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            if (current in site.pkgs && Prefs.pass(site.id) <= now()) launch(site, InterceptActivity.Mode.EXPIRED, current)
        }, until - now() + 500)
    }

    private fun launch(site: Site, mode: InterceptActivity.Mode, pkg: String?) {
        lastLaunch = now()
        startActivity(
            Intent(this, InterceptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("site", site.id)
                .putExtra("mode", mode.name)
                .putExtra("pkg", pkg),
        )
    }
}
