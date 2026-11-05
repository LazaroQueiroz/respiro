package app.respiro

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Busca as mudanças da extensão a cada 15 min (o menor intervalo que o Android permite), só com
 * internet. Uma requisição curta por vez; o sistema agrupa com outras tarefas para poupar bateria.
 */
class SyncJob : JobService() {
    companion object {
        private const val ID = 4201

        /** Nunca derruba o app: se o sistema recusar, só fica sem a busca periódica. */
        fun schedule(c: Context) = runCatching {
            val js = c.getSystemService(JobScheduler::class.java) ?: return@runCatching
            if (js.getPendingJob(ID) != null) return@runCatching
            js.schedule(
                JobInfo.Builder(ID, ComponentName(c, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * MIN)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun cancel(c: Context) { c.getSystemService(JobScheduler::class.java)?.cancel(ID) }
    }

    override fun onStartJob(p: JobParameters): Boolean {
        if (!Sync.paired) return false
        Thread {
            val ok = runCatching { Sync.pollNow() }.getOrDefault(false)
            jobFinished(p, !ok)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters) = true
}
