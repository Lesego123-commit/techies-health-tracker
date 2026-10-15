// ----------------------------------------------------------------------------------------------
//  References
//   JetBrains. 2024. Kotlin Serialization Guide. [Online]. Available at:
//    https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serialization-guide.md
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.remote.dto

import kotlinx.serialization.Serializable
import za.co.techies.healthtracker.core.network.InstantSerializer
import java.time.Instant

/**
 * Offline synchronisation (Requirement 5, design document section 5.4.10).
 *
 * This is a POE-phase feature on the Android side, but the contract is here now so that the
 * RoomDB entities can be designed against its real shape rather than a guess.
 *
 * The property that makes this safe: **replaying a batch is harmless**. WorkManager will resend a
 * batch whenever it cannot confirm the last attempt landed, and an entry the server already holds
 * comes back as `duplicate` rather than being written twice.
 */
@Serializable
data class SyncBatchRequest(
    /** At most 500 per batch; split a longer queue. */
    val doseLogs: List<SyncDoseLogItem>
)

@Serializable
data class SyncDoseLogItem(
    /**
     * The RoomDB primary key. Echoed back untouched so the client can match each result to the
     * row it came from.
     */
    val localId: String,

    val scheduleId: String,
    val dependantId: String,
    val medicationId: String,

    /** `taken` or `missed`. */
    val status: String,

    /** Conflicts are settled on this timestamp, not on arrival order. */
    @Serializable(with = InstantSerializer::class)
    val loggedAt: Instant,

    /** Optional: the API resolves the slot from the schedule when it is omitted. */
    @Serializable(with = InstantSerializer::class)
    val scheduledAt: Instant? = null,

    /** The row version the device last saw. */
    val rowVersion: Int = 0,

    val notes: String? = null
)

@Serializable
data class SyncBatchResultDto(
    /** Items written or already present. Both are settled from the client's point of view. */
    val syncedCount: Int = 0,

    /** Items where the server held a newer entry for the same slot. */
    val conflictCount: Int = 0,

    /** Items rejected outright. Retrying these will not help. */
    val rejectedCount: Int = 0,

    val results: List<SyncItemResultDto> = emptyList()
)

@Serializable
data class SyncItemResultDto(
    val localId: String,

    /** The server-side id, or null when the item was rejected. */
    val serverId: String? = null,

    /** One of the values in [SyncItemStatus]. */
    val status: String,

    /** The authoritative row version the device should now store. */
    val serverRowVersion: Int? = null,

    /** Populated for conflicts and rejections. */
    val message: String? = null
)

/** What to do with each verdict when reconciling the local queue. */
object SyncItemStatus {
    /** Written on the server. Clear the pending flag. */
    const val SYNCED = "synced"

    /** Already present and identical. Clear the pending flag; nothing more to do. */
    const val DUPLICATE = "duplicate"

    /** The server kept a newer entry. Clear the flag and adopt `serverRowVersion`. */
    const val CONFLICT = "conflict"

    /** Invalid — see `message`. Drop it and log; retrying will not help. */
    const val REJECTED = "rejected"

    /** True when the local row can stop being retried, whatever the outcome. */
    fun isSettled(status: String): Boolean = status != REJECTED
}
