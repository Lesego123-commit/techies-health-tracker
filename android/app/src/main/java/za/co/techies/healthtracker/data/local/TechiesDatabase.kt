// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Save Data in a Local Database Using Room. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room [Accessed 25 September 2026].
//   Google. 2024. Migrate Your Room Database. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room/migrating-db-versions
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5: Offline
//    mode with synchronisation).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The on-device database.
 *
 * Deliberately small. It is a durable outbox for actions taken without a connection, not a mirror
 * of the server — mirroring would mean reimplementing adherence, refill forecasting and schedule
 * expansion on the client, and the whole point of the API doing that work is that there is one
 * answer rather than two that can disagree.
 *
 * `exportSchema` is on so the generated schema JSON is committed. Without it a future migration
 * cannot be tested against the version it is migrating from, which is exactly when a migration
 * bug destroys someone's queued doses.
 */
@Database(
    entities = [PendingDoseLog::class],
    version = 1,
    exportSchema = true
)
abstract class TechiesDatabase : RoomDatabase() {

    abstract fun pendingDoseLogs(): PendingDoseLogDao

    companion object {
        private const val NAME = "techies.db"

        fun create(context: Context): TechiesDatabase =
            Room.databaseBuilder(context.applicationContext, TechiesDatabase::class.java, NAME)
                // No fallbackToDestructiveMigration. Dropping the table on a version bump would
                // silently discard doses the caregiver has already recorded but not yet synced,
                // which is the one thing this database exists to prevent. A missing migration
                // should fail loudly in testing instead.
                .build()
    }
}
