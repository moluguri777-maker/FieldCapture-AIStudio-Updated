package com.example.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.FieldCaptureRepository

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = FieldCaptureRepository(applicationContext)
        return try {
            val outcome = repo.processPendingOutbox()
            if (outcome.failedCount > 0) {
                if (runAttemptCount < 4) Result.retry() else Result.failure()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}
