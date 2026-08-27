package de.x0bubbuff.needlebub.updates

import android.app.job.JobParameters
import android.app.job.JobService
import de.x0bubbuff.needlebub.NeedleBubApplication

class PackUpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as NeedleBubApplication
        app.packUpdates.checkNow { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
