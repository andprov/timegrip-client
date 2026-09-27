package ru.timegrip.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.timegrip.app.TimeGripApplication
import java.util.concurrent.TimeUnit

/**
 * Delivers changes queued while offline once the network is back, even with
 * the app closed. It only uploads: downloads happen while the app is on
 * screen, so a closed app never polls the server.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TimeGripApplication).container
        if (!container.sessionStore.state.value.isSignedIn) return Result.success()
        // Back on screen meanwhile: the app sends the changes itself.
        if (container.syncManager.isVisible()) return Result.success()
        return if (container.syncManager.pushNow()) {
            Result.success()
        } else if (container.syncManager.status.value.problem is SyncProblem.SessionExpired) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val ONE_TIME = "timegrip-sync"
        /** The periodic sync of earlier versions; cancelled wherever it is still scheduled. */
        private const val PERIODIC = "timegrip-sync-periodic"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: a waiting request already covers everything queued so far.
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
