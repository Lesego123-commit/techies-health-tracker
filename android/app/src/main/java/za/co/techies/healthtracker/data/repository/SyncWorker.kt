// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Schedule Tasks with WorkManager. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/workmanager
//    [Accessed 25 September 2026].
//   Google. 2024. Define Work Requests. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work
//    [Accessed 25 September 2026].
//   Google. 2024. Threading in CoroutineWorker. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/workmanager/advanced/coroutineworker
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5: Offline
//    mode with synchronisation).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.repository

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import za.co.techies.healthtracker.TechiesApplication
import java.util.concurrent.TimeUnit

/**
 * Drains the offline queue in the background, so a queued dose is not stranded until someone
 * happens to open the app again.
 *
 * The in-app observer already syncs the moment connectivity returns while Home is on screen.
 * This covers the case that actually strands data: the caregiver logs a dose in a lift with no
 * signal, locks the phone, and does not open the app again for two days. WorkManager survives
 * process death and reboots, which is what makes the promise on the banner true.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? TechiesApplication)?.container
            ?: run {
                // Cannot happen with the manifest as it stands, but retrying forever against a
                // graph that does not exist would be worse than stopping.
                Log.e(TAG, "No application container; abandoning this run")
                return Result.failure()
            }

        val engine = container.syncEngine
        val pending = engine.pendingCount()

        if (pending == 0) {
            Log.d(TAG, "Nothing queued; nothing to do")
            return Result.success()
        }

        Log.i(TAG, "Background sync starting for $pending queued dose log(s)")

        val outcome = engine.sync()

        return when {
            // The request itself did not land. Retry with backoff rather than reporting success,
            // which would drop the work until something else happened to schedule it.
            outcome.failed -> {
                Log.w(TAG, "Background sync failed: ${outcome.message}")
                Result.retry()
            }

            // Some rows are still queued — usually because the server answered for only part of
            // the batch. Another run will pick them up.
            engine.pendingCount() > 0 -> {
                Log.i(TAG, "Batch handled; ${engine.pendingCount()} still queued")
                Result.retry()
            }

            else -> {
                Log.i(
                    TAG,
                    "Background sync complete: ${outcome.settled} settled, " +
                        "${outcome.conflicted} conflict(s), ${outcome.rejected} rejected"
                )
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"

        /** One queue, one worker. A second request would race the first over the same rows. */
        const val WorkName = "techies-offline-sync"

        /**
         * Asks for a sync as soon as the device has a connection.
         *
         * [ExistingWorkPolicy.KEEP] rather than REPLACE: logging five doses offline should not
         * cancel and recreate the request five times, which would reset the backoff each time and
         * hammer the server the moment signal returned.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WorkName)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request)

            Log.d(TAG, "Background sync requested")
        }
    }
}
