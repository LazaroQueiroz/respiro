package app.respiro

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Calendar

/**
 * Check-in noturno: nota do dia, o que puxou, e um plano se–então para amanhã
 * (que aparece na tela de pausa). Aos domingos, inclui o resumo da semana e ajuste da meta.
 */
class ReflectionActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        getSystemService(NotificationManager::class.java).cancel(1)

        var rating = -1
        val pulls = mutableSetOf<String>()
        val col = vbox(24)
        col.add(caption(dateLabel().replaceFirstChar { it.uppercase() }), top = 12)
        col.add(label("Reflexão", 34f, 700), top = 2)
        val summary = caption("")
        col.add(summary, top = 6)

        // Semana
        val weekly = isSunday() || now() - Prefs.lastWeekly > 7 * 24 * HOUR
        val weekBox = card { }
        if (weekly) col.add(weekBox, top = 20)

        col.add(label("Como foi sua relação com o celular hoje?", 18f, 600), top = 28)
        val scale = listOf("Péssima", "Ruim", "Ok", "Boa", "Ótima")
        val save = button("Salvar") { }
        save.enable(false)
        col.add(chips(scale) { rating = it.first() + 1; save.enable(true) }, top = 12)

        val pullOpts = listOf("Tédio", "Ansiedade", "Cansaço", "Notificações", "Hábito", "Solidão", "Medo de perder algo", "Trabalho/estudo", "Nada")
        col.add(label("O que mais te puxou para a tela?", 18f, 600), top = 28)
        col.add(chips(pullOpts, multi = true) { s -> pulls.clear(); s.forEach { pulls += pullOpts[it] } }, top = 12)

        col.add(label("Plano para amanhã", 18f, 600), top = 28)
        col.add(caption("Decida agora o que fazer quando a vontade vier. Ele aparece na tela de pausa."), top = 4)
        val ifT = EditText(this).apply { hint = "Se eu… (ex.: sentir tédio no ônibus)"; setText(Prefs.planIf); isSingleLine = true }
        val thenT = EditText(this).apply { hint = "então eu vou… (ex.: ouvir um podcast)"; setText(Prefs.planThen); isSingleLine = true }
        col.add(ifT, top = 10)
        col.add(chips(listOf("sentir tédio", "acordar", "deitar para dormir", "travar no estudo")) {
            ifT.setText(listOf("sentir tédio", "acordar", "deitar para dormir", "travar no estudo")[it.first()])
        }, top = 6)
        col.add(thenT, top = 14)
        val thens = listOf("beber água e alongar", "ler 2 páginas", "mandar mensagem para alguém", "deixar o celular em outro cômodo")
        col.add(chips(thens) { thenT.setText(thens[it.first()]) }, top = 6)

        save.setOnClickListener {
            if (!save.isEnabled) return@setOnClickListener
            val pi = ifT.text.toString().trim().removePrefix("Se ").removePrefix("se ").removePrefix("eu ")
            val pt = thenT.text.toString().trim().removePrefix("então ").removePrefix("eu vou ")
            val fullIf = if (pi.isEmpty()) "" else "eu $pi"
            val fullThen = if (pt.isEmpty()) "" else "eu vou $pt"
            Db.i.saveReflection(dayStart(), rating, pulls.joinToString("|"), fullIf, fullThen)
            Prefs.planIf = fullIf
            Prefs.planThen = fullThen
            if (weekly) Prefs.lastWeekly = now()
            Toast.makeText(this, "Até amanhã.", Toast.LENGTH_SHORT).show()
            finish()
        }
        col.add(save, top = 32)
        col.add(button("Agora não", Btn.TEXT) { finish() }, top = 6)

        val root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        root.addView(scroll(col))
        edgeToEdge(root)
        setContentView(root)

        async({
            val today = Usage.day(this, dayStart())
            val web = Db.i.webMs(dayStart(), nextDay(dayStart()))
            val social = Sites.all().sumOf { siteMs(it, today, web) }
            val o = Db.i.outcomes(dayStart())
            var w = 0L; var wp = 0L; var prevDays = 0
            if (weekly) {
                for (i in 0..13) {
                    val d = Usage.day(this, dayStart(-i))
                    val wb = Db.i.webMs(d.start, nextDay(d.start))
                    val s = Sites.all().sumOf { siteMs(it, d, wb) }
                    if (i < 7) w += s else { wp += s; if (d.total > 0) prevDays++ }
                }
                if (prevDays < 7) wp = 0 // semana anterior incompleta: não compara
            }
            arrayOf(today.total, social, (o["dismiss"] ?: 0).toLong() + (o["left"] ?: 0), w, wp)
        }) { r ->
            val parts = ArrayList<String>()
            if (r[0] > 0) parts += "Tela ${fmt(r[0])}"
            parts += "redes ${fmt(r[1])}"
            if (r[2] > 0) parts += "${r[2]} aberturas evitadas"
            summary.text = "Hoje: " + parts.joinToString(" · ") + "."
            if (weekly) {
                weekBox.add(section("Sua semana"))
                weekBox.add(label("${fmt(r[3] / 7)} por dia em redes", 22f, 700), top = 6)
                if (r[4] > 0) {
                    val pct = ((r[3] - r[4]) * 100 / r[4]).toInt()
                    weekBox.add(caption(if (pct <= 0) "${pct}% vs. semana anterior." else "+$pct% vs. semana anterior."), top = 2)
                }
                weekBox.add(row("Meta diária", "Ajuste para a próxima semana.",
                    stepper(Prefs.goalMin, 15, 300, 15, { fmt(it * MIN) }) { Prefs.goalMin = it }), top = 8)
            }
        }
    }
}

class Reminders : BroadcastReceiver() {
    companion object {
        private const val ACTION = "app.respiro.REFLECT"

        fun schedule(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                c, 1, Intent(c, Reminders::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.cancel(pi)
            if (!Prefs.reflectOn) return
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, Prefs.reflectAt / 60); set(Calendar.MINUTE, Prefs.reflectAt % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now()) add(Calendar.DAY_OF_YEAR, 1)
            }
            // Janela inexata: não precisa de permissão de alarme exato e economiza bateria.
            am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * MIN, pi)
        }
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION && Prefs.reflectOn && !Db.i.hasReflection(dayStart())) notify(c)
        schedule(c)
    }

    private fun notify(c: Context) {
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reflect", "Reflexão diária", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 2, Intent(c, ReflectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            1,
            Notification.Builder(c, "reflect")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Reflexão do dia")
                .setContentText("30 segundos para fechar o dia com intenção.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
