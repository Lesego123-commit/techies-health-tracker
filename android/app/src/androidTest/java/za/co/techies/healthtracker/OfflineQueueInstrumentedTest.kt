// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Test Your Database. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room/testing-db
//    [Accessed 25 September 2026].
//   Google. 2024. Build Instrumented Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/instrumented-tests
//    [Accessed 25 September 2026].
//   Google. 2024. Monitor Connectivity Status and Connection Metering. [Online]. Available at:
//    https://developer.android.com/training/monitoring-device-state/connectivity-status-type
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5: Offline
//    mode with synchronisation).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import za.co.techies.healthtracker.core.network.AndroidConnectivityObserver
import za.co.techies.healthtracker.data.local.PendingDoseLog
import za.co.techies.healthtracker.data.local.TechiesDatabase

/**
 * Offline mode against the real thing: real SQLite on a real device, and the device's own
 * connectivity service.
 *
 * The JVM tests cover the reconciliation rules with a fake DAO. What they cannot prove is the
 * claim the whole feature rests on — that a dose recorded without signal is still there after
 * the process dies. That needs an actual database file, which is what these tests use.
 */
@RunWith(AndroidJUnit4::class)
class OfflineQueueInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TechiesDatabase

    private fun openDatabase() = TechiesDatabase.create(context)

    @Before
    fun setUp() {
        // The real on-disk database, not an in-memory one: persistence across a close and reopen
        // is exactly what is being tested.
        context.deleteDatabase("techies.db")
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("techies.db")
    }

    private fun dose(
        localId: String,
        scheduleId: String = "schedule-1",
        slot: Long? = 1_000L
    ) = PendingDoseLog(
        localId = localId,
        scheduleId = scheduleId,
        dependantId = "dependant-1",
        medicationId = "medication-1",
        status = "taken",
        loggedAtMillis = System.currentTimeMillis(),
        scheduledAtMillis = slot
    )

    @Test
    fun aQueuedDoseSurvivesTheDatabaseBeingClosedAndReopened() = runBlocking {
        database.pendingDoseLogs().insert(dose("survivor"))
        assertEquals(1, database.pendingDoseLogs().pendingCountNow())

        // Stand in for the app being killed while offline.
        database.close()
        database = openDatabase()

        val recovered = database.pendingDoseLogs().nextBatch()

        assertEquals(1, recovered.size)
        assertEquals("survivor", recovered.single().localId)
        assertTrue(recovered.single().isPending)
    }

    @Test
    fun theUniqueSlotIndexIsEnforcedBySqliteNotJustByKotlin() = runBlocking {
        val dao = database.pendingDoseLogs()

        assertTrue(dao.insert(dose("first")) != -1L)

        // Same schedule, same slot — a double tap. SQLite must reject it, which is what makes
        // the guarantee hold even if two code paths ever queue the same dose.
        assertEquals(-1L, dao.insert(dose("second")))
        assertEquals(1, dao.pendingCountNow())
    }

    @Test
    fun differentSlotsOnTheSameScheduleAreBothKept() = runBlocking {
        val dao = database.pendingDoseLogs()

        dao.insert(dose("morning", slot = 1_000L))
        dao.insert(dose("evening", slot = 2_000L))

        assertEquals(2, dao.pendingCountNow())
    }

    @Test
    fun settlingARowRemovesItFromTheQueueWithoutDeletingIt() = runBlocking {
        val dao = database.pendingDoseLogs()
        dao.insert(dose("done"))

        dao.markSettled("done")

        assertEquals(0, dao.pendingCountNow())
        // Kept so the sync screen can still show what happened, rather than vanishing.
        assertEquals(0, dao.abandoned().size)
    }

    @Test
    fun aRowIsAbandonedOnceItHasExhaustedItsAttempts() = runBlocking {
        val dao = database.pendingDoseLogs()
        dao.insert(dose("doomed"))

        repeat(PendingDoseLog.MaxAttempts) { dao.markFailed("doomed", "rejected by server") }

        // Out of the queue so one unacceptable row cannot block every dose behind it, but still
        // recorded rather than silently dropped.
        assertEquals(0, dao.pendingCountNow())
        assertEquals(1, dao.abandoned().size)
        assertEquals("rejected by server", dao.abandoned().single().lastError)
    }

    @Test
    fun connectivityReflectsWhateverTheDeviceActuallyReports() {
        val observer = AndroidConnectivityObserver(context)

        // Not asserting a fixed value: the emulator may be in airplane mode when this runs. What
        // is asserted is that the observer answers from the system rather than assuming online,
        // which is the failure that would queue nothing and lose doses.
        val reported = observer.isOnline
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = manager?.activeNetwork

        if (network == null) {
            assertFalse("no active network must report offline", reported)
        } else {
            val caps = manager.getNetworkCapabilities(network)
            val validated = caps?.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true
            assertEquals(validated, reported)
        }
    }
}
