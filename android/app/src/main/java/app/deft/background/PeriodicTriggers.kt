package app.deft.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.data.AppRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * PARITY D2: the `periodic:N` trigger. One unique WorkManager periodic work per app, named
 * after its webAppId ([workName]), exists exactly while the app declares `background` with a
 * `periodic:N` trigger and holds the grant. [sync] is called when the app is installed or its
 * consent written, when its manifest changes, when the `background` grant changes and at every
 * process start; uninstall calls [cancel]. The worker re-checks and cancels itself if the app
 * no longer qualifies, so a missed hook costs at most one empty wakeup.
 */
object PeriodicTriggers {
    private const val TAG = "PeriodicTriggers"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w(TAG, "periodic sync failed", t) },
    )

    fun workName(webAppId: String): String = "background.periodic:$webAppId"

    /** Schedules the app's work every [minutes], or cancels it when null. */
    fun apply(context: Context, webAppId: String, minutes: Int?) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (minutes == null) {
            work.cancelUniqueWork(workName(webAppId))
            return
        }
        val request = PeriodicWorkRequestBuilder<PeriodicTriggerWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(PeriodicTriggerWorker.KEY_WEB_APP_ID to webAppId))
            .build()
        // UPDATE keeps the current schedule when nothing changed and takes a new interval.
        work.enqueueUniquePeriodicWork(workName(webAppId), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context, webAppId: String) {
        runCatching { apply(context, webAppId, null) }.onFailure { Log.w(TAG, "cancel failed", it) }
    }

    suspend fun sync(context: Context, repository: AppRepository, webAppId: String) {
        val app = repository.getApp(webAppId)
        val minutes = app?.let { BackgroundTriggers.periodicMinutes(BackgroundWake.candidate(repository, it)) }
        apply(context, webAppId, minutes)
    }

    suspend fun syncAll(context: Context, repository: AppRepository) {
        for (app in repository.allApps()) {
            runCatching { sync(context, repository, app.id) }.onFailure { Log.w(TAG, "sync failed", it) }
        }
    }

    /** After a workspace manifest is written (agent or editor): sync the app of [slug], off the caller's thread. */
    fun syncSlugSoon(context: Context, slug: String) {
        scope.launch {
            val repository = DeftApp.instance.repository
            val app = repository.bySlug(slug) ?: return@launch
            sync(context, repository, app.id)
        }
    }
}

/** PARITY D2: one `periodic` trigger for [KEY_WEB_APP_ID]. */
class PeriodicTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val webAppId = inputData.getString(KEY_WEB_APP_ID) ?: return Result.success()
        val repository = DeftApp.instance.repository
        val app = repository.getApp(webAppId)
        val wanted = app != null &&
            BackgroundTriggers.wants(BackgroundWake.candidate(repository, app), RunnerQueue.PERIODIC)
        if (!wanted) {
            PeriodicTriggers.cancel(applicationContext, webAppId)
            return Result.success()
        }
        BridgeService.backgroundTrigger(applicationContext, webAppId, RunnerQueue.PERIODIC, "{}")
        return Result.success()
    }

    companion object {
        const val KEY_WEB_APP_ID = "webAppId"
    }
}
