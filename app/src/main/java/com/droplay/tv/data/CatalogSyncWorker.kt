package com.droplay.tv.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = DroplayRepository(applicationContext)
        val source = repository.savedSource() ?: return@withContext Result.success()
        try {
            if (repository.isRefreshDue(source)) repository.load(source, save = false, force = true)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (Network.isTransient(error)) Result.retry() else Result.failure()
        }
    }
}

object CatalogWorkScheduler {
    fun schedule(context: Context, playlistKey: String) {
        val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("droplay-catalog-sync")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("droplay-sync-$playlistKey", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag("droplay-catalog-sync")
}
