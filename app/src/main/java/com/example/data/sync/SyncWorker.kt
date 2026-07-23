package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.repository.AppRepository
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "WorkManager periodic sync starting...")
        val repository = AppRepository(applicationContext)
        val tvId = repository.tvIdFlow.firstOrNull() ?: return Result.success()

        val success = repository.syncData(tvId)
        return if (success) {
            Log.d("SyncWorker", "Periodic sync completed successfully.")
            Result.success()
        } else {
            Log.w("SyncWorker", "Periodic sync failed. Sending simple heartbeat as backup.")
            repository.updateHeartbeat(tvId)
            Result.retry()
        }
    }
}

object SyncScheduler {
    private const val SYNC_WORK_NAME = "vision_central_sync_work"

    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Note: Android WorkManager mandates a minimum periodic interval of 15 minutes.
        // For shorter 5-minute heartbeats, our active Player ViewModel runs an in-memory loop.
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
        Log.d("SyncScheduler", "Periodic WorkManager sync task scheduled successfully.")
    }
}
