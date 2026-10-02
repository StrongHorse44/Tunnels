package io.github.stronghorse44.tunnels.watch

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import java.util.concurrent.TimeUnit

/** The periodic check as one JobScheduler job: no wake locks, no alarms, deferred while the battery is low. */
object WatchScheduler {
    const val JOB_ID = WatchPolicy.JOB_ID

    /** Schedules (or replaces) the job per [settings], or cancels it when checks are off. */
    fun apply(context: Context, settings: WatchSettings) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!settings.enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        val period = TimeUnit.HOURS.toMillis(settings.intervalHours.toLong())
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, WatchJobService::class.java))
            .setPeriodic(period, period / 4)
            .setPersisted(true)
            .setRequiresBatteryNotLow(true)
            .build()
        scheduler.schedule(job)
    }

    /** The pending job, or null when none is scheduled. */
    fun pending(context: Context): JobInfo? = context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID)

    /** Re-arms the job when the settings say on but the system has none (a force stop, a cleared job store). */
    suspend fun ensure(context: Context) {
        val settings = WatchSettings.decode(TunnelsRuntime.get(context).store.setting(WatchSettings.KEY))
        if (settings.enabled && pending(context) == null) apply(context, settings)
    }
}
