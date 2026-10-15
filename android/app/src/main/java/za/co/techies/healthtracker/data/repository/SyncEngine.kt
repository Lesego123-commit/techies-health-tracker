// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Schedule Tasks with WorkManager. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/workmanager
//    [Accessed 25 September 2026].
//   Google. 2024. Define Work Requests. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5) and
//    section 5.4.10 (Sync endpoint, last-write-wins conflict resolution).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.repository

import android.util.Log
import za.co.techies.healthtracker.core.network.ConnectivityObserver
import za.co.techies.healthtracker.data.local.PendingDoseLog
import za.co.techies.healthtracker.data.local.PendingDoseLogDao
import za.co.techies.healthtracker.data.remote.dto.SyncBatchRequest
import za.co.techies.healthtracker.data.remote.dto.SyncBatchResultDto
import za.co.techies.healthtracker.data.remote.dto.SyncDoseLogItem
import za.co.techies.healthtracker.data.remote.dto.SyncItemStatus
import java.time.Instant

/**
 * The one network call the engine makes.
 *
 * A function rather than the whole repository: the engine needs exactly this, and depending on
 * `DoseRepository` would drag a 20-method Retrofit interface into every test of the
 * reconciliation rules.
 */
fun interface SyncSender {
    suspend fun send(request: SyncBatchRequest): Result<SyncBatchResultDto>
}

/** What a sync run did, so the UI can say something specific rather than "done". */
data class SyncOutcome(
    val attempted: Int = 0,
    val settled: Int = 0,
    val conflicted: Int = 0,
    val rejected: Int = 0,
    val failed: Boolean = false,
    val message: String? = null
) {
    val changedAnything: Boolean get() = settled > 0 || conflicted > 0 || rejected > 0
}

/**
 * Drains the offline queue into `POST /api/sync/batch` and reconciles what comes back.
 *
 * The endpoint is idempotent by contract — an entry the server already holds returns `duplicate`
 * rather than being written twice — which is what makes retrying safe. That matters because the
 * one case this must never get wrong is a batch that reached the server but whose response was
 * lost: resending is correct, and would double-count the dose on a naive server.
 *
 * Reconciliation is per item, not per batch. A single rejected row must not hold up the rest,
 * and a conflict is not a failure: it means the server already had a newer entry for that slot,
 * so the local row has done its job and can be settled.
 */
class SyncEngine(
    private val dao: PendingDoseLogDao,
    private val sender: SyncSender,
    private val connectivity: ConnectivityObserver
) {

    suspend fun pendingCount(): Int = dao.pendingCountNow()

    /**
     * Queues a dose locally. Returns the row, which the caller can send immediately when online.
     *
     * Writing locally first is deliberate even when there is signal: if the request fails
     * halfway, the dose is already durable, and the queue will carry it. The alternative — try
     * the network, write locally only on failure — loses the dose if the process dies mid-request.
     */
    suspend fun enqueue(
        scheduleId: String,
        dependantId: String,
        medicationId: String,
        status: String,
        loggedAt: Instant,
        scheduledAt: Instant?,
        notes: String? = null
    ): PendingDoseLog {
        val row = PendingDoseLog(
            scheduleId = scheduleId,
            dependantId = dependantId,
            medicationId = medicationId,
            status = status,
            loggedAtMillis = loggedAt.toEpochMilli(),
            scheduledAtMillis = scheduledAt?.toEpochMilli(),
            notes = notes
        )

        val inserted = dao.insert(row)

        if (inserted == -1L) {
            // The unique index rejected it: this slot is already queued. A second tap on the same
            // dose is a double tap, not a second dose.
            Log.d(TAG, "Slot already queued for schedule $scheduleId; ignoring duplicate")
        } else {
            Log.i(TAG, "Queued dose ${row.localId} (${row.status}) for offline sync")
        }

        return row
    }

    /** Marks a queued row done, for when the live request beat the queue to it. */
    suspend fun settle(localId: String) = dao.markSettled(localId)

    /** Drains the queue. Safe to call repeatedly and from more than one trigger. */
    suspend fun sync(): SyncOutcome {
        if (!connectivity.isOnline) {
            Log.d(TAG, "Sync skipped: no validated network")
            return SyncOutcome(message = "Waiting for a connection.")
        }

        val batch = dao.nextBatch()

        if (batch.isEmpty()) {
            return SyncOutcome()
        }

        Log.i(TAG, "Syncing ${batch.size} queued dose log(s)")

        val request = SyncBatchRequest(
            doseLogs = batch.map { row ->
                SyncDoseLogItem(
                    localId = row.localId,
                    scheduleId = row.scheduleId,
                    dependantId = row.dependantId,
                    medicationId = row.medicationId,
                    status = row.status,
                    loggedAt = Instant.ofEpochMilli(row.loggedAtMillis),
                    scheduledAt = row.scheduledAtMillis?.let(Instant::ofEpochMilli),
                    notes = row.notes
                )
            }
        )

        return sender.send(request).fold(
            onSuccess = { result ->
                var settled = 0
                var conflicted = 0
                var rejected = 0

                for (item in result.results) {
                    when (item.status) {
                        SyncItemStatus.SYNCED, SyncItemStatus.DUPLICATE -> {
                            dao.markSettled(item.localId)
                            settled++
                        }

                        SyncItemStatus.CONFLICT -> {
                            // The server held a newer entry for this slot. Its answer wins, and
                            // the local row is done — retrying would not change the outcome.
                            dao.markSettled(item.localId)
                            conflicted++
                            Log.i(TAG, "Conflict on ${item.localId}: ${item.message}")
                        }

                        SyncItemStatus.REJECTED -> {
                            dao.markFailed(item.localId, item.message)
                            rejected++
                            Log.w(TAG, "Rejected ${item.localId}: ${item.message}")
                        }

                        else -> {
                            dao.markFailed(item.localId, "Unrecognised status '${item.status}'")
                            rejected++
                        }
                    }
                }

                // Anything the server did not mention is left pending and retried next time,
                // rather than assumed successful.
                val answered = result.results.map { it.localId }.toSet()
                val unanswered = batch.filterNot { it.localId in answered }

                if (unanswered.isNotEmpty()) {
                    Log.w(TAG, "${unanswered.size} item(s) had no result; leaving them queued")
                    dao.markBatchFailed(unanswered.map { it.localId }, "No result returned")
                }

                SyncOutcome(
                    attempted = batch.size,
                    settled = settled,
                    conflicted = conflicted,
                    rejected = rejected
                )
            },
            onFailure = { error ->
                // The whole request failed — offline again, or the server is down. Every row is
                // counted as one attempt so a permanently unacceptable queue cannot spin forever.
                Log.w(TAG, "Sync failed", error)
                dao.markBatchFailed(batch.map { it.localId }, error.message)

                SyncOutcome(
                    attempted = batch.size,
                    failed = true,
                    message = error.message ?: "Could not reach the server."
                )
            }
        )
    }

    private companion object {
        const val TAG = "SyncEngine"
    }
}
