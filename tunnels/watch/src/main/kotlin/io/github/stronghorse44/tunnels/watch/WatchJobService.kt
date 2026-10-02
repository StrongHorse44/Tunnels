package io.github.stronghorse44.tunnels.watch

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs one background check when the system starts the job. The work happens in [WatchRunner]. */
class WatchJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val failed = try {
                WatchRunner.run(applicationContext, notify = true)
                false
            } catch (e: Exception) {
                Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
                true
            }
            // A failed check retries with the system's backoff; a finished one waits for the next period.
            jobFinished(params, failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true // stopped by the system (constraints, time limit): run again later
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TunnelsWatch"
    }
}
