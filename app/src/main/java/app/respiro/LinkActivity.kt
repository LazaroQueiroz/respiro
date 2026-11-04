package app.respiro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Recebe links das redes (ex.: vindos do WhatsApp) e abre na versão filtrada,
 * em vez do app nativo ou do navegador sem filtros.
 */
class LinkActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val u = intent.data
        val site = u?.host?.let { Sites.forHost(it) }
        if (u != null && site != null) {
            startActivity(
                Intent(this, WebActivity::class.java)
                    .setData(Uri.parse("respiro://site/${site.id}"))
                    .putExtra("url", u.toString()),
            )
        }
        finish()
    }
}
