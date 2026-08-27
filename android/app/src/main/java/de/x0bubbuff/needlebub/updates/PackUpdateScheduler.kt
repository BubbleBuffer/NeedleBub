package de.x0bubbuff.needlebub.updates

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object PackUpdateScheduler {
    private const val PERIODIC_JOB_ID = 0x4e42
    private const val PERIOD_MS = 24L * 60L * 60L * 1_000L

    fun sync(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) {
            scheduler.cancel(PERIODIC_JOB_ID)
            return
        }
        val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, PackUpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            .build()
        scheduler.schedule(job)
    }
}
