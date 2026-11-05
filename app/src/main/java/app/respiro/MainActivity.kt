package app.respiro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()
    var tab = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = vbox { setBackgroundColor(cBg) }
        content = FrameLayout(this)
        root.add(content, h = 0, weight = 1f)
        root.add(divider(), h = 1)
        val bar = hbox()
        listOf("Hoje", "Painel", "Ajustes").forEachIndexed { i, s ->
            val t = label(s, 13f, 600).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { show(i) }
            }
            tabs += t
            bar.add(t, w = 0, weight = 1f)
        }
        root.add(bar)
        edgeToEdge(root)
        setContentView(root)
        tab = b?.getInt("tab") ?: 0
        Reminders.schedule(this)
        publishShortcuts()
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putInt("tab", tab) }

    override fun onResume() {
        super.onResume()
        show(tab)
        Sync.poll { show(tab) }
    }

    fun show(i: Int) {
        tab = i
        tabs.forEachIndexed { j, t -> t.setTextColor(if (j == i) cFg else cMuted) }
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> todayTab()
                1 -> statsTab()
                else -> settingsTab()
            },
        )
    }

    fun refresh() = show(tab)

    // ---------- Permissões ----------

    fun notifOk() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun askNotif() = requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

    fun openUsageSettings() = runCatching {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:$packageName")))
    }.onFailure { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

    fun openA11ySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Redes cujos links ainda não abrem no Respiro (domínio não selecionado ou de outro app). */
    fun linksMissing(): List<String> = runCatching {
        val st = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            .getDomainVerificationUserState(packageName) ?: return emptyList()
        st.hostToStateMap.filterValues { it == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE }
            .keys.mapNotNull { Sites.forHost(it)?.name }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun openAppInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) = refresh()

    /** Cartão com o que falta configurar; null se tudo ok. */
    fun setupCard(): View? {
        val usage = Usage.granted(this)
        val a11y = a11yOn()
        val notif = notifOk()
        if (usage && a11y && notif) return null
        return card {
            add(label("Configuração", 20f, 700))
            add(caption("Três permissões e o Respiro passa a funcionar sozinho."), top = 4)
            if (!usage) {
                add(row("Acesso ao uso", "Para medir o tempo de tela e montar o Painel.", button("Ativar", Btn.PRIMARY) { openUsageSettings() }.small()), top = 10)
            }
            if (!a11y) {
                add(row("Pausa ao abrir apps", "Serviço de acessibilidade: só detecta qual app abriu, não lê a tela.", button("Ativar", Btn.PRIMARY) { openA11ySettings() }.small()), top = 6)
                add(caption("Em Acessibilidade → Apps instalados → Respiro → ativar. Se estiver bloqueado: Info do app → ⋮ → “Permitir configurações restritas” e tente de novo.", 12f), top = 2)
                add(button("Abrir Info do app", Btn.TEXT) { openAppInfo() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0) })
            }
            if (!notif) {
                add(row("Notificações", "Só para o lembrete da reflexão diária.", button("Ativar", Btn.PRIMARY) { askNotif() }.small()), top = 6)
            }
        }
    }

    // ---------- Atalhos ----------

    private fun shortcut(s: Site) = ShortcutInfo.Builder(this, "site_${s.id}")
        .setShortLabel(s.name)
        .setIcon(Icon.createWithAdaptiveBitmap(Glyphs.shortcutBitmap(this, s)))
        .setIntent(Intent(this, WebActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("respiro://site/${s.id}")))
        .build()

    fun pinShortcut(s: Site) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) { Toast.makeText(this, "Seu launcher não aceita atalhos fixados.", Toast.LENGTH_LONG).show(); return }
        sm.requestPinShortcut(shortcut(s), null)
    }

    private fun publishShortcuts() = runCatching {
        getSystemService(ShortcutManager::class.java).dynamicShortcuts = Sites.webSites().take(4).map { shortcut(it) }
    }

    fun openSite(s: Site) = startActivity(Intent(this, WebActivity::class.java).setData(Uri.parse("respiro://site/${s.id}")))
}

fun TextView.small() = apply {
    minHeight = context.dp(36); textSize = 14f
    setPadding(context.dp(14), 0, context.dp(14), 0)
}

// =====================================================================
// Hoje
// =====================================================================

fun MainActivity.todayTab(): View {
    val col = vbox(24) { gravity = Gravity.CENTER_HORIZONTAL }
    col.add(caption(dateLabel().replaceFirstChar { it.uppercase() }).apply { gravity = Gravity.CENTER }, top = 8)
    setupCard()?.let { col.add(it, top = 20) }

    // Destaque: foco em andamento ou anel do dia
    val big = label("—", 44f, 700).apply { gravity = Gravity.CENTER }
    val small = caption("").apply { gravity = Gravity.CENTER }
    val ring = RingView(this)
    val hero = FrameLayout(this).apply {
        addView(ring, FrameLayout.LayoutParams(dp(232), dp(232), Gravity.CENTER))
        addView(vbox {
            gravity = Gravity.CENTER
            add(big, w = WRAP); add(small, w = WRAP, top = 2)
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }
    col.add(hero, top = 28, h = dp(240))
    val sub = caption("", 13f).apply { gravity = Gravity.CENTER }
    col.add(sub, top = 14)

    if (Prefs.focusActive()) {
        ring.fraction = 1f
        small.text = "modo foco"
        val tick = object : Runnable {
            override fun run() {
                val l = Prefs.focusUntil - now()
                if (l <= 0) { refresh(); return }
                big.text = fmtClock(l); big.postDelayed(this, 1000)
            }
        }
        big.post(tick)
    } else small.text = "de ${fmt(Prefs.goalMin * MIN)} em redes"

    // Grade de redes: só ícones (nome no toque longo / acessibilidade) e minutos de hoje
    val sites = Sites.webSites()
    val minutes = HashMap<String, TextView>()
    val grid = vbox()
    sites.chunked(4).forEach { rowSites ->
        val row = hbox { gravity = Gravity.TOP }
        rowSites.forEach { s ->
            val tile = GlyphTile(this, s).apply {
                setOnClickListener { openSite(s) }
                setOnLongClickListener { pinShortcut(s); true }
            }
            val m = caption("", 12f).apply { gravity = Gravity.CENTER }
            minutes[s.id] = m
            row.add(vbox {
                gravity = Gravity.CENTER_HORIZONTAL
                add(tile, w = WRAP)
                add(m, top = 6, w = WRAP)
            }, w = 0, weight = 1f)
        }
        repeat(4 - rowSites.size) { row.add(spacer(), w = 0, weight = 1f) }
        grid.add(row, top = if (grid.childCount == 0) 0 else 18)
    }
    col.add(grid, top = 40)

    // Ações
    col.add(hbox {
        if (Prefs.focusActive()) {
            add(button("Encerrar foco", Btn.SECONDARY) {
                confirmPhrase("Encerrar o foco?") { Prefs.focusUntil = 0; refresh() }
            }, w = 0, weight = 1f)
        } else {
            add(button("Foco", Btn.SECONDARY) { focusDialog() }, w = 0, weight = 1f)
        }
        if (!Db.i.hasReflection(dayStart())) {
            add(button("Reflexão", Btn.SECONDARY) {
                startActivity(Intent(this@todayTab, ReflectionActivity::class.java))
            }, w = 0, weight = 1f, start = 10)
        }
    }, top = 40)

    if (Prefs.planIf.isNotBlank() && Prefs.planThen.isNotBlank()) {
        col.add(caption("Se ${Prefs.planIf}, então ${Prefs.planThen}.", 13f).apply {
            gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.ITALIC)
        }, top = 22)
    }
    col.add(spacer(), h = dp(12))

    async({ usageOn(this, dayStart()) to Db.i.outcomes(dayStart()) }) { (d, opens) ->
        val social = d.social
        val goal = Prefs.goalMin * MIN
        if (!Prefs.focusActive()) {
            ring.fraction = social.toFloat() / goal
            big.text = fmt(social)
            if (social > goal) small.text = "${fmt(social - goal)} além da meta"
        }
        val skipped = opens.gaveUp()
        val parts = ArrayList<String>()
        if (Usage.granted(this)) parts += "tela ${fmt(d.total)}"
        if (d.remote >= MIN) parts += "computador ${fmt(d.remote)}"
        if (d.pickups > 0) parts += "${d.pickups} desbloqueios"
        if (skipped > 0) parts += "$skipped evitadas"
        sub.text = parts.joinToString("  ·  ")
        minutes.forEach { (id, tv) -> tv.text = d.perSite[id]?.takeIf { it >= MIN }?.let { fmt(it) } ?: "—" }
    }
    return scroll(col)
}

private fun MainActivity.focusDialog() {
    val opts = listOf(25, 50, 90)
    android.app.AlertDialog.Builder(this)
        .setTitle("Modo foco")
        .setItems(opts.map { "$it minutos" }.toTypedArray()) { _, i ->
            Prefs.focusUntil = now() + opts[i] * MIN
            refresh()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

// =====================================================================
// Painel
// =====================================================================

private class Stats(
    /** 14 dias, do mais antigo (0) a hoje (13). */
    val days: List<DayUsage>,
    val outcomes: Map<String, Int>,
    val intentions: Map<String, Int>,
    val moods: Map<String, IntArray>,
    val hours: IntArray,
    val ratings: List<Int>,
    val pulls: Map<String, Int>,
)

fun MainActivity.statsTab(): View {
    val col = vbox(20)
    col.add(caption("Últimos 7 dias"))
    col.add(label("Painel", 34f, 700), top = 2)
    val body = vbox()
    col.add(body, top = 8)
    body.add(caption("Calculando…"), top = 20)

    async({
        val days = (13 downTo 0).map { usageOn(this, dayStart(-it)) }
        val since = dayStart(-6)
        Stats(
            days, Db.i.outcomes(since), Db.i.intentions(since), Db.i.moods(dayStart(-29)),
            Db.i.hours(dayStart(-13)), Db.i.ratings(since), Db.i.pulls(since),
        )
    }) { st -> body.removeAllViews(); fillStats(body, st) }
    return scroll(col)
}

/** Média que pode ficar abaixo de 1 min (ex.: poucos minutos na semana). */
private fun fmtAvg(ms: Long) = if (ms in 1 until MIN) "menos de 1 min" else fmt(ms)

private fun MainActivity.fillStats(body: LinearLayout, st: Stats) {
    val goal = Prefs.goalMin * MIN
    val last7 = 7..13
    val total7 = last7.sumOf { st.days[it].total }
    val social = LongArray(14) { st.days[it].social }
    val social7 = last7.sumOf { social[it] }
    val socialPrev = (0..6).sumOf { social[it] }
    val hasPrev = (0..6).all { st.days[it].total > 0 } // só compara com semana completa

    // --- Insights
    val insights = ArrayList<String>()
    if (hasPrev && socialPrev > 0) {
        val pct = ((social7 - socialPrev) * 100 / socialPrev).toInt()
        insights += if (pct <= 0) "Redes ${pct}% em relação à semana anterior. Continue." else "Redes +$pct% em relação à semana anterior."
    }
    val peak = st.hours.indices.maxByOrNull { st.hours[it] } ?: -1
    if (peak >= 0 && st.hours[peak] >= 3) {
        val night = peak >= 22 || peak < 6
        insights += "Seus impulsos se concentram entre ${peak}h e ${(peak + 1) % 24}h." +
            if (night && !Prefs.restOn) " Ative o horário de descanso em Ajustes." else ""
    }
    st.intentions.maxByOrNull { it.value }?.let { (k, v) ->
        if (v >= 3) insights += "“$k” foi o motivo nº 1 para abrir redes." + if (k in IMPULSIVE) " Impulso, não necessidade." else ""
    }
    st.moods.filter { it.value.sum() >= 3 }.maxByOrNull { it.value[2].toFloat() / it.value.sum() }?.let { (id, a) ->
        val pct = a[2] * 100 / a.sum()
        if (pct >= 40) insights += "Você sai pior do ${Sites.byId(id)?.name ?: id} em $pct% das vezes."
    }
    val streak = run {
        var n = 0
        for (i in 12 downTo 0) { if (st.days[i].total == 0L || social[i] > goal) break; n++ }
        if (social[13] <= goal) n + 1 else n
    }
    if (streak >= 2) insights += "$streak dias seguidos dentro da meta."
    if (insights.isNotEmpty()) {
        body.add(card { insights.forEachIndexed { i, s -> add(label(s, 16f, 500), top = if (i == 0) 0 else 10) } }, top = 12)
    }

    // --- Semana
    body.add(section("Tempo de tela"), top = 28)
    body.add(card {
        add(hbox {
            add(vbox {
                add(caption("Média diária"))
                add(label(fmt(total7 / 7), 28f, 700))
            }, w = 0, weight = 1f)
            add(vbox {
                add(caption("Redes / dia"))
                add(label(fmt(social7 / 7), 28f, 700))
            }, w = 0, weight = 1f)
        })
        val bars = BarsView(this@fillStats)
        bars.set(
            LongArray(7) { st.days[7 + it].total }, LongArray(7) { social[7 + it] },
            Array(7) { dayLetter(st.days[7 + it].start) }, goal, highlight = 6,
        )
        add(bars, top = 18)
        add(caption("Cinza: tela total · Preto: redes · Tracejado: meta de ${fmt(goal)}", 12f), top = 10)
        val pick = last7.sumOf { st.days[it].pickups }
        if (pick > 0) add(caption("${pick / 7} desbloqueios por dia, em média.", 13f), top = 4)
        val remote7 = last7.sumOf { st.days[it].remote }
        if (remote7 >= MIN) add(caption(
            "Redes por dia: celular ${fmtAvg((social7 - remote7) / 7)} · computador ${fmtAvg(remote7 / 7)}. A tela total é só a do celular.", 13f,
        ), top = 4)
    }, top = 10)

    // --- Por rede
    val per = Sites.all().map { s -> s.id to last7.sumOf { st.days[it].of(s) } }
        .filter { it.second / 7 >= MIN }.sortedByDescending { it.second }
    if (per.isNotEmpty()) {
        body.add(section("Por rede · média diária"), top = 28)
        val max = per.first().second
        body.add(card {
            per.forEachIndexed { i, (id, ms) ->
                val s = Sites.byId(id) ?: return@forEachIndexed
                val lim = Prefs.limit(id) * MIN
                add(hbox {
                    add(label(s.name, 15f, 500), w = 0, weight = 1f)
                    add(label(fmt(ms / 7), 15f, 500, if (ms / 7 > lim) cFg else cMuted), w = WRAP)
                }, top = if (i == 0) 0 else 14)
                add(HBar(this@fillStats, ms.toFloat() / max), top = 6)
                val pc = last7.sumOf { st.days[it].remotePer[id] ?: 0L }
                if (pc >= MIN) add(caption("celular ${fmtAvg((ms - pc) / 7)} · computador ${fmtAvg(pc / 7)}", 12f), top = 4)
            }
        }, top = 10)
    }

    // --- Pausas
    val o = st.outcomes
    val tries = o.attempts()
    if (tries > 0) {
        val gaveUp = o.gaveUp()
        body.add(section("Pausas"), top = 28)
        body.add(card {
            fun tile(n: String, l: String) = vbox { add(label(n, 28f, 700)); add(caption(l, 13f)) }
            add(hbox {
                add(tile("$tries", "tentativas de abrir"), w = 0, weight = 1f)
                add(tile("${gaveUp * 100 / tries}%", "desistências"), w = 0, weight = 1f)
            })
            add(hbox {
                add(tile("${(o[Outcome.WEB] ?: 0) + (o[Outcome.BROWSER_WEB] ?: 0)}", "foram para a web"), w = 0, weight = 1f)
                add(tile("${o[Outcome.APP] ?: 0}", "abriram o app"), w = 0, weight = 1f)
            }, top = 16)
            add(caption("No estudo do one sec, só a pausa fez as pessoas desistirem de 36% das aberturas.", 12f), top = 14)
        }, top = 10)
    }

    // --- Intenções
    if (st.intentions.isNotEmpty()) {
        body.add(section("Por que você abre"), top = 28)
        val tot = st.intentions.values.sum()
        val imp = st.intentions.filterKeys { it in IMPULSIVE }.values.sum()
        val maxI = st.intentions.values.max()
        body.add(card {
            add(label("${imp * 100 / tot}% impulso · ${100 - imp * 100 / tot}% propósito", 17f, 600))
            st.intentions.toList().sortedByDescending { it.second }.forEach { (k, v) ->
                add(hbox {
                    add(label(k, 14f, 500), w = 0, weight = 1f)
                    add(caption("$v", 14f), w = WRAP)
                }, top = 12)
                add(HBar(this@fillStats, v.toFloat() / maxI), top = 5)
            }
        }, top = 10)
    }

    // --- Humor
    val moods = st.moods.filter { it.value.sum() > 0 }
    if (moods.isNotEmpty()) {
        body.add(section("Como você sai · 30 dias"), top = 28)
        body.add(card {
            moods.toList().sortedByDescending { it.second.sum() }.forEachIndexed { i, (id, a) ->
                val n = a.sum()
                add(hbox {
                    add(label(Sites.byId(id)?.name ?: id, 15f, 500), w = 0, weight = 1f)
                    add(caption("${a[0] * 100 / n}% melhor · ${a[2] * 100 / n}% pior", 13f), w = WRAP)
                }, top = if (i == 0) 0 else 14)
                add(StackBar(this@fillStats, a), top = 6)
            }
            add(caption("Preto: melhor · Cinza: igual · Claro: pior", 12f), top = 12)
        }, top = 10)
    }

    // --- Horários
    if (st.hours.sum() > 0) {
        body.add(section("Horários de impulso · 14 dias"), top = 28)
        body.add(card {
            val b = BarsView(this@fillStats, 100)
            b.set(LongArray(0), LongArray(24) { st.hours[it].toLong() }, Array(24) { if (it % 6 == 0) "${it}h" else "" })
            add(b)
        }, top = 10)
    }

    // --- Reflexões
    if (st.ratings.isNotEmpty() || st.pulls.isNotEmpty()) {
        body.add(section("Reflexões · 7 dias"), top = 28)
        body.add(card {
            if (st.ratings.isNotEmpty()) {
                val avg = st.ratings.average()
                add(label("Nota média: ${"%.1f".format(PT, avg)} de 5", 17f, 600))
            }
            st.pulls.toList().sortedByDescending { it.second }.take(3).takeIf { it.isNotEmpty() }?.let { top ->
                add(caption("O que mais puxou: " + top.joinToString(", ") { it.first.lowercase() }), top = 6)
            }
        }, top = 10)
    }

    if (!Usage.granted(this)) {
        body.add(caption("Ative o acesso ao uso na aba Hoje para ver o tempo de tela.", 13f), top = 20)
    }
}

// =====================================================================
// Ajustes
// =====================================================================

fun MainActivity.settingsTab(): View {
    val col = vbox(20)
    col.add(label("Ajustes", 34f, 700), top = 18)

    fun group(title: String, block: LinearLayout.() -> Unit) {
        col.add(section(title), top = 28)
        col.add(card(padDp = 0) { setPadding(dp(18), dp(4), dp(18), dp(4)); block() }, top = 10)
    }

    group("Permissões") {
        add(row("Acesso ao uso", if (Usage.granted(this@settingsTab)) "Ativo" else "Desativado") { openUsageSettings() })
        add(divider(), h = 1)
        add(row("Pausa ao abrir apps e sites", if (a11yOn()) "Ativo · inclui as redes abertas no navegador" else "Desativado") { openA11ySettings() })
        add(divider(), h = 1)
        add(row("Notificações", if (notifOk()) "Ativo" else "Desativado") { askNotif() })
        add(divider(), h = 1)
        val missing = linksMissing()
        add(row(
            "Abrir links no Respiro",
            if (missing.isEmpty()) "Ativo para todas as redes"
            else "Ainda abrem fora: ${missing.joinToString(", ")}. Toque e adicione os links. Se algum não puder ser marcado, " +
                "outro app é dono dele (ex.: Facebook App Manager, X): em Configurações → Apps → esse app → Abrir por padrão, desligue.",
        ) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName")))
            }.onFailure { openAppInfo() }
        })
    }

    group("Metas") {
        add(row("Meta diária em redes", "Soma de todos os apps e sites abaixo.",
            stepper(Prefs.goalMin, 15, 300, 15, { fmt(it * MIN) }) { Prefs.goalMin = it }))
    }

    group("Apps e sites") {
        Sites.all().forEachIndexed { i, s ->
            if (i > 0) add(divider(), h = 1)
            val tags = ArrayList<String>()
            tags += "limite ${Prefs.limit(s.id)} min"
            if (s.pkgs.isNotEmpty()) tags += when {
                !Sites.installed(s) -> "app não instalado"
                s.id in Prefs.noIntercept -> "sem pausa"
                a11yOn() -> "pausa ativa"
                else -> "pausa desligada"
            }
            if (s.web && s.id in Prefs.hidden) tags += "oculto"
            add(row(s.name, tags.joinToString(" · ")) { siteDialog(s) })
        }
        add(divider(), h = 1)
        add(row("Adicionar site", "Qualquer endereço, aberto com sessão e tempo.") { addSiteDialog() })
        add(divider(), h = 1)
        add(row("Outros apps para pausar", "Jogos, Kwai, Pinterest…") { extraAppsDialog() })
    }

    group("Navegação web") {
        add(row("Tons de cinza", "Cor torna o feed mais recompensador.", toggle(Prefs.grayscale) { Prefs.grayscale = it }))
        add(divider(), h = 1)
        add(row("Esconder Reels e Shorts", "Shorts abrem como vídeo normal.", toggle(Prefs.hideShorts) { Prefs.hideShorts = it }))
        add(divider(), h = 1)
        add(row("Esconder o feed inicial", "Mostra busca e atalhos no lugar.", toggle(Prefs.hideFeed) { Prefs.hideFeed = it }))
        add(divider(), h = 1)
        add(row("Aviso de rolagem", "A cada N telas roladas.",
            stepper(Prefs.scrollCue, 0, 100, 10, { if (it == 0) "nunca" else "$it telas" }) { Prefs.scrollCue = it }))
    }

    group("Reflexão diária") {
        add(row("Lembrete", null, toggle(Prefs.reflectOn) { Prefs.reflectOn = it; Reminders.schedule(this@settingsTab) }))
        add(divider(), h = 1)
        add(row("Horário", fmtHm(Prefs.reflectAt)) {
            pickTime(Prefs.reflectAt) { Prefs.reflectAt = it; Reminders.schedule(this@settingsTab); refresh() }
        })
        add(divider(), h = 1)
        add(row("Responder agora") { startActivity(Intent(this@settingsTab, ReflectionActivity::class.java)) })
    }

    group("Extensão do navegador") {
        if (Sync.paired) {
            val last = Sync.lastSync
            val status = Sync.lastError?.let { "Erro: $it" }
                ?: if (last > 0) "Última sincronização: ${java.text.SimpleDateFormat("d/M HH:mm", PT).format(last)}" else "Aguardando a primeira sincronização"
            add(row("Conectada", "$status. Busca a cada 15 min e ao abrir o app."))
            add(divider(), h = 1)
            add(row("Sincronizar agora") {
                Sync.syncNow { err ->
                    android.widget.Toast.makeText(this@settingsTab, err?.let { "Falhou: $it" } ?: "Sincronizado", android.widget.Toast.LENGTH_SHORT).show()
                    refresh()
                }
            })
            add(divider(), h = 1)
            add(row("Código de conexão", "Cole na extensão para conectar outro navegador.") { shareText(Sync.code()) })
            add(divider(), h = 1)
            add(row("Desconectar") { Sync.unpair(this@settingsTab); refresh() })
        } else {
            add(row("Conectar extensão", "Ajustes e sites iguais no celular e no computador, via ntfy.sh, cifrados.") { syncDialog() })
        }
    }

    group("Testar") {
        add(row("Ver a tela de pausa", "Prévia com o YouTube — nada é registrado.") {
            startActivity(
                Intent(this@settingsTab, InterceptActivity::class.java)
                    .putExtra("site", "youtube").putExtra("mode", InterceptActivity.Mode.OPEN.name).putExtra("preview", true),
            )
        })
    }

    group("Horário de descanso") {
        add(row("Ativar", "Pausa máxima (30 s) e lembrete para dormir.", toggle(Prefs.restOn) { Prefs.restOn = it }))
        add(divider(), h = 1)
        add(row("Início", fmtHm(Prefs.restStart)) { pickTime(Prefs.restStart) { Prefs.restStart = it; refresh() } })
        add(divider(), h = 1)
        add(row("Fim", fmtHm(Prefs.restEnd)) { pickTime(Prefs.restEnd) { Prefs.restEnd = it; refresh() } })
    }

    col.add(section("Por que funciona"), top = 28)
    col.add(card {
        listOf(
            "Pausa antes de abrir" to "No estudo do app one sec (PNAS, 2023), uma pausa curta fez as pessoas desistirem de 36% das aberturas, e as tentativas caíram 37% em 6 semanas. Dar a opção explícita de desistir foi o mais eficaz.",
            "Atrito crescente" to "Cada abertura no dia deixa a próxima pausa mais longa. Passar do limite exige digitar uma frase: decidir vira um ato consciente.",
            "Feed coberto" to "Feeds infinitos não têm ponto de parada. Remover o feed reduz a navegação passiva sem tirar mensagens e buscas.",
            "Sinais de parada" to "Avisos de rolagem e tempo de sessão criam o fim que o app não tem.",
            "Tons de cinza" to "Estudos com universitários associaram o modo cinza a menos tempo de tela e menos ansiedade.",
            "Planos se–então" to "Decidir antes o que fazer quando a vontade vier ajuda a agir fora do piloto automático.",
            "Medir como você sai" to "Perguntar se valeu a pena separa o uso que ajuda do que só ocupa tempo.",
        ).forEachIndexed { i, (t, d) ->
            add(label(t, 15f, 600), top = if (i == 0) 0 else 14)
            add(caption(d, 13f), top = 3)
        }
    }, top = 10)
    col.add(caption("Respiro ${packageManager.getPackageInfo(packageName, 0).versionName} · sem anúncios, sem contas. Só os ajustes saem do celular, cifrados, se você conectar a extensão.", 12f).apply { gravity = Gravity.CENTER }, top = 24)
    return scroll(col)
}

private fun MainActivity.pickTime(min: Int, done: (Int) -> Unit) {
    android.app.TimePickerDialog(this, { _, h, m -> done(h * 60 + m) }, min / 60, min % 60, true).show()
}

private fun MainActivity.siteDialog(s: Site) {
    val box = vbox(22)
    box.add(row("Limite diário", null, stepper(Prefs.limit(s.id), 5, 180, 5, { "$it min" }) { Prefs.setLimit(s.id, it) }))
    if (s.pkgs.isNotEmpty()) {
        box.add(row("Pausa ao abrir o app", null, toggle(s.id !in Prefs.noIntercept) {
            Prefs.noIntercept = if (it) Prefs.noIntercept - s.id else Prefs.noIntercept + s.id
        }))
    }
    if (s.web) {
        box.add(row("Mostrar em Hoje", null, toggle(s.id !in Prefs.hidden) {
            Prefs.hidden = if (it) Prefs.hidden - s.id else Prefs.hidden + s.id
        }))
        box.add(button("Pôr atalho na tela inicial", Btn.SECONDARY) { pinShortcut(s) }, top = 12)
    }
    val d = android.app.AlertDialog.Builder(this).setTitle(s.name).setView(box)
        .setPositiveButton("Ok", null)
    if (s.custom || s.id.startsWith("pkg_")) d.setNeutralButton("Remover") { _, _ ->
        if (s.custom) Sites.removeCustom(s.id) else { Prefs.extraApps = Prefs.extraApps - s.pkgs.first(); Sites.reload() }
        refresh()
    }
    d.setOnDismissListener { refresh() }.show()
}

private fun MainActivity.addSiteDialog() {
    val name = android.widget.EditText(this).apply { hint = "Nome (ex.: Pinterest)"; isSingleLine = true }
    val url = android.widget.EditText(this).apply {
        hint = "Endereço (ex.: pinterest.com)"; isSingleLine = true
        inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val box = vbox(22) { add(name); add(url, top = 8) }
    android.app.AlertDialog.Builder(this).setTitle("Adicionar site").setView(box)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Adicionar") { _, _ ->
            var u = url.text.toString().trim()
            if (u.isEmpty()) return@setPositiveButton
            if (!u.startsWith("http")) u = "https://$u"
            val host = Uri.parse(u).host ?: return@setPositiveButton
            val n = name.text.toString().trim().ifEmpty { host.removePrefix("www.") }
            Sites.addCustom(n, u)
            refresh()
        }.show()
}

private fun MainActivity.extraAppsDialog() {
    val pm = packageManager
    val builtinPkgs = Sites.builtin.flatMap { it.pkgs }.toSet()
    val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .filter { it.first != packageName && it.first !in builtinPkgs }
        .sortedBy { it.second.lowercase() }
    val sel = Prefs.extraApps.toMutableSet()
    val checked = BooleanArray(apps.size) { apps[it].first in sel }
    android.app.AlertDialog.Builder(this).setTitle("Outros apps para pausar")
        .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
            if (on) sel += apps[i].first else sel -= apps[i].first
        }
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Salvar") { _, _ -> Prefs.extraApps = sel; Sites.reload(); refresh() }
        .show()
}

private fun MainActivity.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Código de conexão"))
}

/** Conectar à extensão: gerar um código aqui (e colar na extensão) ou colar o código gerado na extensão. */
private fun MainActivity.syncDialog() {
    val input = android.widget.EditText(this).apply {
        hint = "RESPIRO1:…"; isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    val box = vbox(22) {
        add(caption("Os ajustes passam pelo ntfy.sh, cifrados com uma chave que só os seus aparelhos têm."))
        add(caption("Já tem um código da extensão? Cole aqui:"), top = 12)
        add(input, top = 6)
    }
    android.app.AlertDialog.Builder(this).setTitle("Conectar extensão").setView(box)
        .setNegativeButton("Cancelar", null)
        .setNeutralButton("Gerar código") { _, _ ->
            Sync.pair(this, Sync.newCode(), generated = true)
            shareText(Sync.code())
            refresh()
        }
        .setPositiveButton("Conectar") { _, _ ->
            if (!Sync.pair(this, input.text.toString())) android.widget.Toast.makeText(this, "Código inválido", android.widget.Toast.LENGTH_SHORT).show()
            refresh()
        }.show()
}
