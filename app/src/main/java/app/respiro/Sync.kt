package app.respiro

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sincroniza os ajustes com a extensão do navegador sem servidor próprio, usando um tópico do ntfy.
 *
 * - Pareamento: código `RESPIRO1:<tópico>:<chave>` (tópico aleatório + chave AES-256), colado no outro lado.
 * - Tudo que vai para o ntfy é cifrado (AES-GCM, IV aleatório de 12 bytes, base64 de IV‖cifra‖tag).
 *   O ntfy só vê texto opaco. O formato é o mesmo da extensão (extension/sync.js).
 * - Mensagens: {t:"state", ts, from, …ajustes} ou {t:"req", from} (pede o estado completo).
 * - Vence o estado com `ts` mais novo. Publicação com atraso de 3 s após a última mudança.
 * - Recebe por polling (ao abrir o app), sem conexão permanente.
 */
object Sync {
    const val PREFIX = "RESPIRO1:"
    private val ME = "app"
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var applying = false

    /** Chaves do SharedPreferences que fazem parte do estado sincronizado. */
    private val KEYS = setOf("goal", "gray", "shorts", "feed", "cue", "rest", "restStart", "restEnd", "focus", "planIf", "planThen", "hidden", "noint", "custom")

    private val sp get() = Prefs.sp
    val paired get() = topic.isNotEmpty() && key.isNotEmpty()
    private val topic get() = sp.getString("sync_topic", "")!!
    private val key get() = sp.getString("sync_key", "")!!
    var server: String
        get() = sp.getString("sync_server", "https://ntfy.sh")!!
        set(v) { sp.edit().putString("sync_server", v.trimEnd('/')).apply() }
    var lastSync: Long
        get() = sp.getLong("sync_last", 0)
        private set(v) { sp.edit().putLong("sync_last", v).apply() }
    private var localTs: Long
        get() = sp.getLong("sync_ts", 0)
        set(v) { sp.edit().putLong("sync_ts", v).apply() }
    /** Último erro de rede (null = última tentativa deu certo). */
    @Volatile var lastError: String? = null
        private set
    private var since: String
        get() = sp.getString("sync_since", "all")!!
        set(v) { sp.edit().putString("sync_since", v).apply() }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (applying || k == null) return@OnSharedPreferenceChangeListener
        if (k in KEYS || k.startsWith("limit_")) { localTs = now(); schedulePublish() }
    }

    fun init(c: android.content.Context) {
        sp.registerOnSharedPreferenceChangeListener(listener)
        if (paired) SyncJob.schedule(c)
    }

    // ---------- Pareamento ----------

    fun newCode(): String {
        val r = SecureRandom()
        val t = ByteArray(16).also(r::nextBytes).joinToString("") { "%02x".format(it) }
        val k = ByteArray(32).also(r::nextBytes)
        return "$PREFIX$t:${b64url(k)}"
    }

    fun code() = if (paired) "$PREFIX$topic:$key" else ""

    /**
     * Aplica um código de pareamento; devolve false se o formato estiver errado.
     * Quem gera o código fica com os ajustes valendo (ts = agora); quem cola adota os do outro lado (ts = 0).
     */
    fun pair(c: android.content.Context, code: String, generated: Boolean = false): Boolean {
        val p = code.trim().removePrefix(PREFIX).split(":")
        if (!code.trim().startsWith(PREFIX) || p.size != 2 || !Regex("[a-f0-9]{32}").matches(p[0])) return false
        if (runCatching { unb64url(p[1]).size }.getOrNull() != 32) return false
        sp.edit().putString("sync_topic", p[0]).putString("sync_key", p[1]).putString("sync_since", "all").remove("sync_last").apply()
        localTs = if (generated) now() else 0
        SyncJob.schedule(c)
        thread { publish(JSONObject().put("t", "req").put("from", ME)); publish(state()); pollNow() }
        return true
    }

    fun unpair(c: android.content.Context) {
        sp.edit().remove("sync_topic").remove("sync_key").remove("sync_since").remove("sync_last").apply()
        lastError = null
        SyncJob.cancel(c)
    }

    // ---------- Estado ----------

    private fun state(): JSONObject {
        val limits = JSONObject()
        Sites.all().filter { it.web }.forEach { limits.put(it.id, Prefs.limit(it.id)) }
        return JSONObject()
            .put("t", "state").put("v", 1).put("ts", localTs).put("from", ME)
            .put("goal", Prefs.goalMin).put("gray", Prefs.grayscale).put("shorts", Prefs.hideShorts)
            .put("feed", Prefs.hideFeed).put("cue", Prefs.scrollCue)
            .put("rest", Prefs.restOn).put("restStart", Prefs.restStart).put("restEnd", Prefs.restEnd)
            .put("focusUntil", Prefs.focusUntil).put("planIf", Prefs.planIf).put("planThen", Prefs.planThen)
            .put("limits", limits)
            .put("hidden", JSONArray(Prefs.hidden.toList())).put("noint", JSONArray(Prefs.noIntercept.toList()))
            .put("custom", JSONArray(Prefs.customJson))
    }

    private fun apply(o: JSONObject) {
        val ts = o.optLong("ts")
        if (ts <= localTs) return
        applying = true
        try {
            fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            if (o.has("goal")) Prefs.goalMin = o.getInt("goal")
            if (o.has("gray")) Prefs.grayscale = o.getBoolean("gray")
            if (o.has("shorts")) Prefs.hideShorts = o.getBoolean("shorts")
            if (o.has("feed")) Prefs.hideFeed = o.getBoolean("feed")
            if (o.has("cue")) Prefs.scrollCue = o.getInt("cue")
            if (o.has("rest")) Prefs.restOn = o.getBoolean("rest")
            if (o.has("restStart")) Prefs.restStart = o.getInt("restStart")
            if (o.has("restEnd")) Prefs.restEnd = o.getInt("restEnd")
            if (o.has("focusUntil")) Prefs.focusUntil = o.getLong("focusUntil")
            if (o.has("planIf")) Prefs.planIf = o.getString("planIf")
            if (o.has("planThen")) Prefs.planThen = o.getString("planThen")
            o.optJSONObject("limits")?.let { l -> l.keys().forEach { Prefs.setLimit(it, l.getInt(it)) } }
            strs("hidden")?.let { Prefs.hidden = it }
            strs("noint")?.let { Prefs.noIntercept = it + Prefs.noIntercept.filter { id -> id.startsWith("pkg_") } }
            o.optJSONArray("custom")?.let { Prefs.customJson = it.toString(); Sites.reload() }
            localTs = ts
        } finally { applying = false }
    }

    // ---------- Rede ----------

    private val publishTask = Runnable { thread { publish(state()) } }
    private fun schedulePublish() {
        if (!paired) return
        h.removeCallbacks(publishTask)
        h.postDelayed(publishTask, 3000)
    }

    /** Busca as mensagens novas. Chamado ao abrir o app e na pausa; [done] roda na thread principal se algo mudou. */
    fun poll(done: () -> Unit = {}) {
        if (!paired) return
        thread { pollNow(done) }
    }

    /** "Sincronizar agora": publica o estado, busca e devolve o erro (ou null) na thread principal. */
    fun syncNow(done: (String?) -> Unit) {
        if (!paired) return
        thread {
            publish(state())
            pollNow()
            h.post { done(lastError) }
        }
    }

    /** Busca as mensagens novas, na thread atual. Devolve false se a rede falhou. */
    fun pollNow(done: () -> Unit = {}): Boolean {
        var latest: JSONObject? = null
        var askedState = false
        // O ntfy.sh guarda as mensagens por ~12 h: sem contato há 6 h, pede o estado do outro lado.
        if (now() - lastSync > 6 * HOUR) publish(JSONObject().put("t", "req").put("from", ME))
        val body = http("GET", "$server/$topic/json?poll=1&since=$since") ?: return false
        body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val ev = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            if (ev.optString("event") != "message") return@forEach
            since = ev.getString("id")
            val msg = decrypt(ev.optString("message")) ?: return@forEach
            if (msg.optString("from") == ME) return@forEach
            when (msg.optString("t")) {
                "state" -> if (msg.optLong("ts") > (latest?.optLong("ts") ?: 0)) latest = msg
                "req" -> askedState = true
            }
        }
        lastSync = now()
        if (askedState) publish(state())
        // Aplica na thread principal: assim o listener roda já com `applying` ligado e não republica.
        latest?.let { m -> h.post { if (m.optLong("ts") > localTs) { apply(m); done() } } }
        return true
    }

    private fun publish(o: JSONObject) {
        if (!paired) return
        http("POST", "$server/$topic", encrypt(o.toString()))
    }

    private fun http(method: String, url: String, body: String? = null): String? = runCatching<String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code !in 200..299) error("ntfy respondeu $code")
        lastError = null
        text
    }.onFailure { lastError = it.message ?: it.javaClass.simpleName }.getOrNull()

    private fun thread(block: () -> Unit) = Thread { runCatching(block) }.start()

    // ---------- Criptografia ----------

    private fun cipher(mode: Int, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(unb64url(key), "AES"), GCMParameterSpec(128, iv))
    }

    private fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plain.toByteArray())
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): JSONObject? = runCatching {
        val all = Base64.decode(s, Base64.DEFAULT)
        val pt = cipher(Cipher.DECRYPT_MODE, all.copyOfRange(0, 12)).doFinal(all, 12, all.size - 12)
        JSONObject(String(pt))
    }.getOrNull()

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64url(s: String) = Base64.decode(s, Base64.URL_SAFE)
}
