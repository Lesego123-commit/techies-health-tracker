// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Save Data in a Local Database Using Room. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room [Accessed 25 September 2026].
//   Google. 2024. Defining Data Using Room Entities. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room/defining-data
//    [Accessed 25 September 2026].
//   Google. 2024. Accessing Data Using Room DAOs. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room/accessing-data
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5: Offline
//    mode with synchronisation) and section 5.4.10 (Sync endpoint).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * A dose the caregiver logged while the phone had no usable connection.
 *
 * Offline support is scoped to dose logging rather than the whole app, which the brief permits
 * and which is the honest choice here: logging a dose is the one action that is genuinely
 * time-critical. Adding a dependant or editing a prescription can wait for signal; recording that
 * a tablet was taken at 08:00 cannot, because by the time the phone reconnects the adherence
 * record would already be wrong.
 *
 * The row carries everything `POST /api/sync/batch` needs, so a queued entry never has to be
 * re-derived from other tables that may since have changed.
 */
@Entity(
    tableName = "pending_dose_logs",
    indices = [
        // The queue is always read in one of two ways: "what is still outstanding" and
        // "is this slot already queued". Both are covered here.
        Index(value = ["isPending"]),
        Index(value = ["scheduleId", "scheduledAtMillis"], unique = true)
    ]
)
data class PendingDoseLog(

    /**
     * Generated on the device, sent to the server as `localId`, and echoed back untouched so each
     * result can be matched to the row it came from.
     */
    @PrimaryKey
    val localId: String = UUID.randomUUID().toString(),

    val scheduleId: String,
    val dependantId: String,
    val medicationId: String,

    /** `taken` or `missed`. */
    val status: String,

    /** Epoch milliseconds. Conflicts are settled on this, not on arrival order. */
    val loggedAtMillis: Long,

    /** The slot this belongs to. Sent explicitly so the server never has to guess. */
    val scheduledAtMillis: Long?,

    val notes: String? = null,

    /** False once the server has confirmed it, whatever the verdict. */
    @ColumnInfo(defaultValue = "1")
    val isPending: Boolean = true,

    /**
     * How many times this row has been sent. Used to stop retrying something the server will
     * never accept, so one bad row cannot block the whole queue forever.
     */
    @ColumnInfo(defaultValue = "0")
    val attempts: Int = 0,

    /** The last thing the server said about it, kept for the sync status screen. */
    val lastError: String? = null
) {
    companion object {
        /** After this many failures a row is abandoned rather than retried indefinitely. */
        const val MaxAttempts = 5
    }
}

@Dao
interface PendingDoseLogDao {

    /**
     * Queues a dose. `IGNORE` rather than `REPLACE`: the unique index is on
     * (scheduleId, scheduledAt), so a double tap on the same slot must not overwrite the first
     * entry with a second one — the first is the one that reflects when the tablet was taken.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: PendingDoseLog): Long

    @Query("SELECT * FROM pending_dose_logs WHERE isPending = 1 AND attempts < :maxAttempts ORDER BY loggedAtMillis LIMIT :limit")
    suspend fun nextBatch(limit: Int = 500, maxAttempts: Int = PendingDoseLog.MaxAttempts): List<PendingDoseLog>

    /** Drives the offline banner. A Flow so the count updates without the screen polling. */
    @Query("SELECT COUNT(*) FROM pending_dose_logs WHERE isPending = 1 AND attempts < :maxAttempts")
    fun pendingCount(maxAttempts: Int = PendingDoseLog.MaxAttempts): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_dose_logs WHERE isPending = 1 AND attempts < :maxAttempts")
    suspend fun pendingCountNow(maxAttempts: Int = PendingDoseLog.MaxAttempts): Int

    /** Rows the server will never accept, surfaced so they are not silently lost. */
    @Query("SELECT * FROM pending_dose_logs WHERE isPending = 1 AND attempts >= :maxAttempts")
    suspend fun abandoned(maxAttempts: Int = PendingDoseLog.MaxAttempts): List<PendingDoseLog>

    @Query("UPDATE pending_dose_logs SET isPending = 0, lastError = NULL WHERE localId = :localId")
    suspend fun markSettled(localId: String)

    @Query("UPDATE pending_dose_logs SET attempts = attempts + 1, lastError = :error WHERE localId = :localId")
    suspend fun markFailed(localId: String, error: String?)

    /** Called when a whole batch could not be delivered, so every row is retried together. */
    @Query("UPDATE pending_dose_logs SET attempts = attempts + 1, lastError = :error WHERE localId IN (:localIds)")
    suspend fun markBatchFailed(localIds: List<String>, error: String?)

    @Query("SELECT EXISTS(SELECT 1 FROM pending_dose_logs WHERE scheduleId = :scheduleId AND scheduledAtMillis = :scheduledAtMillis)")
    suspend fun isQueued(scheduleId: String, scheduledAtMillis: Long?): Boolean

    /** Housekeeping: settled rows are kept briefly for the sync screen, then cleared. */
    @Query("DELETE FROM pending_dose_logs WHERE isPending = 0 AND loggedAtMillis < :before")
    suspend fun deleteSettledBefore(before: Long)
}
