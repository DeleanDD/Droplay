package com.droplay.tv.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = DroplayRepository(applicationContext)
        val source = repository.savedSource() ?: return Result.success()
        if (!repository.isRefreshDue(source)) return Result.success()
        return runCatching { repository.load(source, save = false, force = true); Result.success() }
            .getOrElse { error -> if (Network.isTransient(error)) Result.retry() else Result.failure() }
    }
}

object CatalogWorkScheduler {
    fun schedule(context: Context, playlistKey: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("droplay-catalog-sync")
        val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("droplay-catalog-sync")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("droplay-sync-$playlistKey", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag("droplay-catalog-sync")
}
