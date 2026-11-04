package app.respiro

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Process
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
        Sites.app = this
    }
}

// ---------- Tempo ----------

const val MIN = 60_000L
const val HOUR = 60 * MIN

fun now() = System.currentTimeMillis()

fun dayStart(offset: Int = 0, from: Long = now()): Long = Calendar.getInstance().apply {
    timeInMillis = from
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, offset)
}.timeInMillis

fun nextDay(start: Long) = dayStart(1, start)

fun minuteOfDay(t: Long = now()) = Calendar.getInstance().run { timeInMillis = t; get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE) }

fun fmt(ms: Long): String {
    val m = (ms / MIN).toInt()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}min"
    }
}

fun fmtClock(ms: Long): String {
    val s = (max(0, ms) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtHm(minOfDay: Int) = "%02d:%02d".format(minOfDay / 60, minOfDay % 60)

val PT = Locale("pt", "BR")
private val DAY_LETTERS = arrayOf("D", "S", "T", "Q", "Q", "S", "S")
fun dayLetter(t: Long) = Calendar.getInstance().run { timeInMillis = t; DAY_LETTERS[get(Calendar.DAY_OF_WEEK) - 1] }
fun dateLabel(t: Long = now()): String = SimpleDateFormat("EEEE, d 'de' MMMM", PT).format(t)
fun isSunday() = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

val INTENTIONS = listOf("Responder alguém", "Postar algo", "Buscar algo", "Trabalho/estudo", "Tédio", "Hábito", "Ansiedade/fuga")
val IMPULSIVE = setOf("Tédio", "Hábito", "Ansiedade/fuga")

// ---------- Preferências ----------

object Prefs {
    lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("respiro", Context.MODE_PRIVATE) }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = sp.edit().putInt(k, value).apply()
    }
    private fun long(k: String, d: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = sp.edit().putLong(k, value).apply()
    }
    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = sp.edit().putBoolean(k, value).apply()
    }
    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = sp.edit().putString(k, value).apply()
    }
    private fun set(k: String) = object : ReadWriteProperty<Any?, Set<String>> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Set<String> = sp.getStringSet(k, emptySet())!!.toSet()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<String>) = sp.edit().putStringSet(k, value).apply()
    }

    var goalMin by int("goal", 60)
    var grayscale by bool("gray", true)
    var hideShorts by bool("shorts", true)
    var hideFeed by bool("feed", true)
    var scrollCue by int("cue", 30)
    var reflectOn by bool("reflect", true)
    var reflectAt by int("reflectAt", 21 * 60 + 30)
    var restOn by bool("rest", false)
    var restStart by int("restStart", 23 * 60)
    var restEnd by int("restEnd", 7 * 60)
    var focusUntil by long("focus", 0)
    var planIf by str("planIf", "")
    var planThen by str("planThen", "")
    var hidden by set("hidden")
    var noIntercept by set("noint")
    var extraApps by set("extra")
    var customJson by str("custom", "[]")
    var lastWeekly by long("weekly", 0)

    fun limit(id: String) = sp.getInt("limit_$id", 20)
    fun setLimit(id: String, v: Int) = sp.edit().putInt("limit_$id", v).apply()
    fun pass(id: String) = sp.getLong("pass_$id", 0)
    fun setPass(id: String, until: Long) = sp.edit().putLong("pass_$id", until).apply()

    fun focusActive() = focusUntil > now()
    fun restNow(): Boolean {
        if (!restOn) return false
        val m = minuteOfDay()
        return if (restStart <= restEnd) m in restStart until restEnd else m >= restStart || m < restEnd
    }
}

// ---------- Sites ----------

class Site(
    val id: String,
    val name: String,
    val url: String,
    val pkgs: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    /** Seletores CSS de vídeos curtos (Reels/Shorts) a esconder. */
    val short: String = "",
    /** Seletores extras escondidos junto com o feed (ex.: recomendados). */
    val feedCss: String = "",
    /** Caminhos onde o feed infinito é coberto. */
    val feedRe: String? = null,
    /** Caminhos bloqueados de vez (abas de vídeos curtos). */
    val blockRe: String? = null,
    val search: String? = null,
    /** Visualizador de um vídeo curto: permite o que foi aberto, impede deslizar para os próximos. */
    val reelRe: String? = null,
    val links: List<Pair<String, String>> = emptyList(),
    val web: Boolean = true,
    val custom: Boolean = false,
) {
    fun allows(host: String) = hosts.any { host == it || host.endsWith(".$it") }
    val domain get() = Uri.parse(url).host.orEmpty().removePrefix("www.").removePrefix("m.")
}

object Sites {
    lateinit var app: Context
    private var cache: List<Site>? = null
    private var byPkg: Map<String, Site> = emptyMap()

    val builtin = listOf(
        Site(
            "instagram", "Instagram", "https://www.instagram.com/",
            listOf("com.instagram.android", "com.instagram.lite"),
            listOf("instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net"),
            short = "a[href=\"/reels/\"],a[href^=\"/reels/\"],a[href=\"/explore/\"]",
            feedRe = "^/$",
            blockRe = "^/reels/?$|^/explore/?$",
            reelRe = "^/(?:[\\w.]+/)?reels?/([\\w-]+)",
            links = listOf("Mensagens" to "/direct/inbox/", "Pesquisar" to "/explore/search/", "Notificações" to "/notifications/"),
        ),
        Site(
            "youtube", "YouTube", "https://m.youtube.com/",
            listOf("com.google.android.youtube"),
            listOf("youtube.com", "youtu.be", "google.com", "gstatic.com", "googleusercontent.com", "ggpht.com", "ytimg.com"),
            short = "ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2," +
                "ytm-rich-section-renderer:has(a[href^=\"/shorts\"]),ytm-pivot-bar-item-renderer:has(.pivot-shorts)," +
                "ytm-pivot-bar-item-renderer:has(a[href^=\"/shorts\"]),a[href^=\"/shorts\"]",
            feedCss = "ytm-item-section-renderer[section-identifier=\"related-items\"]",
            feedRe = "^/$",
            search = "https://m.youtube.com/results?search_query=%s",
            links = listOf("Inscrições" to "/feed/subscriptions", "Você" to "/feed/you"),
        ),
        Site(
            "tiktok", "TikTok", "https://www.tiktok.com/",
            listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
            listOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "ttwstatic.com"),
            feedRe = "^/(foryou|explore|following|friends)?/?$",
            reelRe = "^/@[\\w.-]+/video/(\\d+)",
            search = "https://www.tiktok.com/search?q=%s",
            links = listOf("Mensagens" to "/messages", "Perfil" to "/profile"),
        ),
        Site(
            "x", "X", "https://x.com/home",
            listOf("com.twitter.android"),
            listOf("x.com", "twitter.com", "twimg.com", "t.co"),
            feedRe = "^/(home|explore)/?$",
            search = "https://x.com/search?q=%s",
            links = listOf("Mensagens" to "/messages", "Notificações" to "/notifications"),
        ),
        Site(
            "threads", "Threads", "https://www.threads.com/",
            listOf("com.instagram.barcelona"),
            listOf("threads.com", "threads.net", "instagram.com", "cdninstagram.com", "fbcdn.net"),
            feedRe = "^/$",
            search = "https://www.threads.com/search?q=%s",
            links = listOf("Atividade" to "/activity"),
        ),
        Site(
            "facebook", "Facebook", "https://m.facebook.com/",
            listOf("com.facebook.katana", "com.facebook.lite"),
            listOf("facebook.com", "fbcdn.net", "fb.com"),
            short = "a[href*=\"/reel\"],a[href^=\"/watch\"]",
            feedRe = "^/(home\\.php)?/?$",
            blockRe = "^/(reel|reels|watch)/?$",
            reelRe = "^/reels?/(\\d+)",
            search = "https://m.facebook.com/search/top/?q=%s",
            links = listOf("Notificações" to "/notifications.php", "Grupos" to "/groups/", "Marketplace" to "/marketplace/"),
        ),
        Site(
            "reddit", "Reddit", "https://www.reddit.com/",
            listOf("com.reddit.frontpage"),
            listOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"),
            feedRe = "^/(best|hot|new|top|rising|r/popular|r/all)?/?$",
            search = "https://www.reddit.com/search/?q=%s",
            links = listOf("Notificações" to "/notifications", "Chat" to "https://chat.reddit.com/"),
        ),
        Site(
            "linkedin", "LinkedIn", "https://www.linkedin.com/feed/",
            listOf("com.linkedin.android"),
            listOf("linkedin.com", "licdn.com"),
            feedRe = "^/feed/?$",
            search = "https://www.linkedin.com/search/results/all/?keywords=%s",
            links = listOf("Mensagens" to "/messaging/", "Vagas" to "/jobs/", "Notificações" to "/notifications/", "Minha rede" to "/mynetwork/"),
        ),
    )

    fun all(): List<Site> {
        cache?.let { return it }
        val l = builtin + custom() + extras()
        cache = l
        byPkg = l.flatMap { s -> s.pkgs.map { it to s } }.toMap()
        return l
    }

    fun reload() { cache = null }
    fun byId(id: String) = all().find { it.id == id }

    /** Site dono de um link: primeiro pelo domínio principal, depois pelos domínios auxiliares. */
    fun forHost(host: String): Site? {
        val h = host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        val web = all().filter { it.web }
        return web.firstOrNull { h == it.domain || h.endsWith(".${it.domain}") } ?: web.firstOrNull { it.allows(host) }
    }
    fun byPkg(p: String): Site? { all(); return byPkg[p] }
    fun webSites() = all().filter { it.web && it.id !in Prefs.hidden }

    private fun custom(): List<Site> = runCatching {
        val a = JSONArray(Prefs.customJson)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val url = o.getString("url")
            val host = Uri.parse(url).host.orEmpty().removePrefix("www.").removePrefix("m.")
            Site(o.getString("id"), o.getString("name"), url, hosts = listOf(host), custom = true)
        }
    }.getOrDefault(emptyList())

    fun addCustom(name: String, url: String) {
        val a = JSONArray(Prefs.customJson)
        a.put(JSONObject().put("id", "c_${now()}").put("name", name).put("url", url))
        Prefs.customJson = a.toString(); reload()
    }

    fun removeCustom(id: String) {
        val a = JSONArray(Prefs.customJson)
        val b = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("id") != id) b.put(a.getJSONObject(i))
        Prefs.customJson = b.toString(); reload()
    }

    private fun extras(): List<Site> {
        val pm = app.packageManager
        return Prefs.extraApps.map { p ->
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
            Site("pkg_$p", name, "", listOf(p), web = false)
        }.sortedBy { it.name.lowercase() }
    }

    fun installed(s: Site) = s.pkgs.any { app.packageManager.getLaunchIntentForPackage(it) != null }
}

// ---------- Banco ----------

class Db(c: Context) : SQLiteOpenHelper(c, "respiro.db", null, 1) {
    companion object {
        lateinit var i: Db
        fun init(c: Context) { i = Db(c.applicationContext) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table opens(id integer primary key, ts integer, site text, outcome text, intention text, delay integer)")
        db.execSQL("create index opens_ts on opens(ts)")
        db.execSQL("create table sessions(id integer primary key, ts integer, site text, kind text, ms integer, mood integer, intention text, screens integer)")
        db.execSQL("create index sessions_ts on sessions(ts)")
        db.execSQL("create table reflections(day integer primary key, rating integer, pulls text, plan_if text, plan_then text)")
        db.execSQL("create table usage_cache(day integer, pkg text, ms integer, primary key(day, pkg))")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun logOpen(site: String, outcome: String, intention: String?, delay: Int) {
        writableDatabase.insert("opens", null, ContentValues().apply {
            put("ts", now()); put("site", site); put("outcome", outcome); put("intention", intention); put("delay", delay)
        })
    }

    fun countOpens(site: String, since: Long): Int = DatabaseUtils.longForQuery(
        readableDatabase, "select count(*) from opens where site=? and ts>=? and outcome not like 'expired%'",
        arrayOf(site, since.toString()),
    ).toInt()

    fun logSession(site: String, kind: String, ms: Long, mood: Int?, intention: String?, screens: Int) {
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("ts", now()); put("site", site); put("kind", kind); put("ms", ms)
            if (mood != null) put("mood", mood) else putNull("mood")
            put("intention", intention); put("screens", screens)
        })
    }

    /** Tempo gasto nas versões web dentro do Respiro, por site. */
    fun webMs(from: Long, to: Long): Map<String, Long> {
        val m = HashMap<String, Long>()
        readableDatabase.rawQuery(
            "select site, sum(ms) from sessions where kind='web' and ts>=? and ts<? group by site",
            arrayOf(from.toString(), to.toString()),
        ).use { while (it.moveToNext()) m[it.getString(0)] = it.getLong(1) }
        return m
    }

    fun outcomes(since: Long): Map<String, Int> {
        val m = HashMap<String, Int>()
        readableDatabase.rawQuery("select outcome, count(*) from opens where ts>=? group by outcome", arrayOf(since.toString()))
            .use { while (it.moveToNext()) m[it.getString(0)] = it.getInt(1) }
        return m
    }

    fun intentions(since: Long): Map<String, Int> {
        val m = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "select intention, count(*) from (select intention from opens where ts>=?1 and intention is not null " +
                "union all select intention from sessions where kind='web' and ts>=?1 and intention is not null) group by intention",
            arrayOf(since.toString()),
        ).use { while (it.moveToNext()) m[it.getString(0)] = it.getInt(1) }
        return m
    }

    /** Por site: [melhor, igual, pior]. */
    fun moods(since: Long): Map<String, IntArray> {
        val m = HashMap<String, IntArray>()
        readableDatabase.rawQuery("select site, mood, count(*) from sessions where ts>=? and mood is not null group by site, mood", arrayOf(since.toString()))
            .use {
                while (it.moveToNext()) {
                    val a = m.getOrPut(it.getString(0)) { IntArray(3) }
                    when (it.getInt(1)) { 3 -> a[0] += it.getInt(2); 2 -> a[1] += it.getInt(2); 1 -> a[2] += it.getInt(2) }
                }
            }
        return m
    }

    fun hours(since: Long): IntArray {
        val h = IntArray(24)
        val cal = Calendar.getInstance()
        readableDatabase.rawQuery("select ts from opens where ts>=? and outcome not like 'expired%'", arrayOf(since.toString())).use {
            while (it.moveToNext()) { cal.timeInMillis = it.getLong(0); h[cal.get(Calendar.HOUR_OF_DAY)]++ }
        }
        return h
    }

    fun saveReflection(day: Long, rating: Int, pulls: String, planIf: String, planThen: String) {
        writableDatabase.insertWithOnConflict("reflections", null, ContentValues().apply {
            put("day", day); put("rating", rating); put("pulls", pulls); put("plan_if", planIf); put("plan_then", planThen)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun hasReflection(day: Long) =
        DatabaseUtils.longForQuery(readableDatabase, "select count(*) from reflections where day=?", arrayOf(day.toString())) > 0

    fun ratings(since: Long): List<Int> {
        val l = ArrayList<Int>()
        readableDatabase.rawQuery("select rating from reflections where day>=?", arrayOf(since.toString()))
            .use { while (it.moveToNext()) l += it.getInt(0) }
        return l
    }

    fun pulls(since: Long): Map<String, Int> {
        val m = HashMap<String, Int>()
        readableDatabase.rawQuery("select pulls from reflections where day>=?", arrayOf(since.toString())).use {
            while (it.moveToNext()) it.getString(0).orEmpty().split('|').filter { s -> s.isNotBlank() }
                .forEach { p -> m[p] = (m[p] ?: 0) + 1 }
        }
        return m
    }

    fun cacheGet(day: Long): Map<String, Long>? {
        val m = HashMap<String, Long>()
        readableDatabase.rawQuery("select pkg, ms from usage_cache where day=?", arrayOf(day.toString()))
            .use { while (it.moveToNext()) m[it.getString(0)] = it.getLong(1) }
        return m.ifEmpty { null }
    }

    fun cachePut(day: Long, m: Map<String, Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            m.forEach { (k, v) ->
                db.insertWithOnConflict("usage_cache", null, ContentValues().apply {
                    put("day", day); put("pkg", k); put("ms", v)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

// ---------- Uso (UsageStats) ----------

object Usage {
    class Day(val start: Long, val per: Map<String, Long>, val total: Long, val pickups: Int) {
        fun native(s: Site) = s.pkgs.sumOf { per[it] ?: 0L }
    }

    fun granted(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Dias passados ficam em cache no SQLite; o dia de hoje é sempre recalculado. */
    fun day(c: Context, start: Long): Day {
        val end = nextDay(start)
        val past = end <= now()
        if (past) Db.i.cacheGet(start)?.let { m ->
            return Day(start, m.filterKeys { !it.startsWith("~") }, m["~total"] ?: 0, (m["~pickups"] ?: 0).toInt())
        }
        val d = compute(c, start, minOf(end, now()))
        if (past && d.total > 0) {
            Db.i.cachePut(start, d.per.filterValues { it >= 30_000 } + mapOf("~total" to d.total, "~pickups" to d.pickups.toLong()))
        }
        return d
    }

    private fun homes(c: Context): Set<String> =
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()

    private val skip = setOf("com.android.systemui", "android")

    fun compute(c: Context, start: Long, end: Long): Day {
        if (!granted(c)) return Day(start, emptyMap(), 0, 0)
        val usm = c.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(start - 3 * HOUR, end)
        val e = UsageEvents.Event()
        val openAt = HashMap<String, Long>()
        val active = HashMap<String, HashSet<String>>()
        val per = HashMap<String, Long>()
        var pickups = 0
        fun close(p: String, t: Long) {
            val s = openAt.remove(p) ?: return
            active.remove(p)
            val a = max(s, start)
            if (t > a) per[p] = (per[p] ?: 0L) + (t - a)
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            val p = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    active.getOrPut(p) { HashSet() }.add(e.className.orEmpty())
                    if (p !in openAt) openAt[p] = t
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val set = active[p]
                    set?.remove(e.className.orEmpty())
                    if (set == null || set.isEmpty()) close(p, t)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    openAt.keys.toList().forEach { close(it, t) }
                UsageEvents.Event.KEYGUARD_HIDDEN -> if (t >= start) pickups++
            }
        }
        openAt.keys.toList().forEach { close(it, end) }
        val h = homes(c)
        val total = per.filterKeys { it !in h && it !in skip }.values.sum()
        return Day(start, per, total, pickups)
    }
}

/** Tempo de hoje num site: app nativo + versão web no Respiro. */
fun siteMs(s: Site, day: Usage.Day, web: Map<String, Long>) = day.native(s) + (web[s.id] ?: 0L)

fun Context.a11yOn(): Boolean {
    val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
}
