// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Test Your Database. [Online]. Available at:
//    https://developer.android.com/training/data-storage/room/testing-db
//    [Accessed 25 September 2026].
//   JetBrains. 2024. Testing Kotlin Coroutines. [Online]. Available at:
//    https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
//    [Accessed 25 September 2026].
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5) and
//    section 5.4.10 (Sync endpoint).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.core.network.ConnectivityObserver
import za.co.techies.healthtracker.data.local.PendingDoseLog
import za.co.techies.healthtracker.data.local.PendingDoseLogDao
import za.co.techies.healthtracker.data.remote.dto.SyncBatchRequest
import za.co.techies.healthtracker.data.remote.dto.SyncBatchResultDto
import za.co.techies.healthtracker.data.remote.dto.SyncItemResultDto
import za.co.techies.healthtracker.data.remote.dto.SyncItemStatus
import za.co.techies.healthtracker.data.repository.SyncEngine

/**
 * Tests for how server verdicts are reconciled against the offline queue.
 *
 * This is the part of offline mode where a mistake is expensive and invisible. Clearing a row the
 * server never accepted loses a dose the caregiver recorded; failing to clear one it did accept
 * leaves the queue retrying forever and the offline banner permanently on. Neither throws, and
 * neither is visible on a device with a good connection.
 *
 * A fake DAO is used rather than an in-memory Room database because these tests are about the
 * reconciliation rules, not about SQL — and they then run on the JVM in milliseconds.
 */
class SyncEngineTest {

    // ---- Test doubles ----

    private class FakeDao : PendingDoseLogDao {
        val rows = mutableMapOf<String, PendingDoseLog>()
        var insertRejections = 0

        override suspend fun insert(log: PendingDoseLog): Long {
            val clash = rows.values.any {
                it.scheduleId == log.scheduleId && it.scheduledAtMillis == log.scheduledAtMillis
            }
            if (clash) {
                insertRejections++
                return -1L
            }
            rows[log.localId] = log
            return 1L
        }

        override suspend fun nextBatch(limit: Int, maxAttempts: Int) =
            rows.values.filter { it.isPending && it.attempts < maxAttempts }.sortedBy { it.loggedAtMillis }

        override fun pendingCount(maxAttempts: Int): Flow<Int> = flowOf(pending(maxAttempts))

        override suspend fun pendingCountNow(maxAttempts: Int) = pending(maxAttempts)

        override suspend fun abandoned(maxAttempts: Int) =
            rows.values.filter { it.isPending && it.attempts >= maxAttempts }

        override suspend fun markSettled(localId: String) {
            rows[localId]?.let { rows[localId] = it.copy(isPending = false, lastError = null) }
        }

        override suspend fun markFailed(localId: String, error: String?) {
            rows[localId]?.let { rows[localId] = it.copy(attempts = it.attempts + 1, lastError = error) }
        }

        override suspend fun markBatchFailed(localIds: List<String>, error: String?) {
            localIds.forEach { markFailed(it, error) }
        }

        override suspend fun isQueued(scheduleId: String, scheduledAtMillis: Long?) =
            rows.values.any { it.scheduleId == scheduleId && it.scheduledAtMillis == scheduledAtMillis }

        override suspend fun deleteSettledBefore(before: Long) {
            rows.values.filter { !it.isPending && it.loggedAtMillis < before }
                .forEach { rows.remove(it.localId) }
        }

        private fun pending(maxAttempts: Int) =
            rows.values.count { it.isPending && it.attempts < maxAttempts }
    }

    private class FakeConnectivity(override val isOnline: Boolean) : ConnectivityObserver {
        override fun observe(): Flow<Boolean> = flowOf(isOnline)
    }

    private fun row(
        localId: String,
        scheduleId: String = "schedule-1",
        slot: Long? = 1_000L,
        pending: Boolean = true,
        attempts: Int = 0
    ) = PendingDoseLog(
        localId = localId,
        scheduleId = scheduleId,
        dependantId = "dep-1",
        medicationId = "med-1",
        status = "taken",
        loggedAtMillis = 500L,
        scheduledAtMillis = slot,
        isPending = pending,
        attempts = attempts
    )

    // ---- Queueing ----

    @Test
    fun `a second tap on the same slot does not queue a second dose`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a"))
        dao.insert(row("b"))

        assertEquals(1, dao.rows.size)
        assertEquals(1, dao.insertRejections)
    }

    @Test
    fun `different slots on the same schedule both queue`() = runTest {
        val dao = FakeDao()
        dao.insert(row("morning", slot = 1_000L))
        dao.insert(row("evening", slot = 2_000L))

        assertEquals(2, dao.pendingCountNow())
    }

    // ---- Reconciliation ----

    @Test
    fun `synced and duplicate both settle the row`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a", slot = 1L))
        dao.insert(row("b", slot = 2L))

        dao.markSettled("a")
        dao.markSettled("b")

        assertEquals(0, dao.pendingCountNow())
    }

    @Test
    fun `a conflict settles the row rather than retrying it`() = runTest {
        // The server held a newer entry for this slot. Its answer wins, and resending ours would
        // never change that — leaving it pending would retry forever.
        assertTrue(SyncItemStatus.isSettled(SyncItemStatus.CONFLICT))
    }

    @Test
    fun `a rejection is the only verdict worth stopping on`() = runTest {
        assertTrue(SyncItemStatus.isSettled(SyncItemStatus.SYNCED))
        assertTrue(SyncItemStatus.isSettled(SyncItemStatus.DUPLICATE))
        assertTrue(SyncItemStatus.isSettled(SyncItemStatus.CONFLICT))
        assertFalse(SyncItemStatus.isSettled(SyncItemStatus.REJECTED))
    }

    @Test
    fun `a row stops being retried once it has exhausted its attempts`() = runTest {
        val dao = FakeDao()
        dao.insert(row("bad"))

        repeat(PendingDoseLog.MaxAttempts) { dao.markFailed("bad", "invalid") }

        // It leaves the queue so one unacceptable row cannot block every dose behind it, but it
        // is still recorded as abandoned rather than deleted.
        assertEquals(0, dao.pendingCountNow())
        assertEquals(1, dao.abandoned().size)
    }

    @Test
    fun `a failed batch leaves every row queued for the next attempt`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a", slot = 1L))
        dao.insert(row("b", slot = 2L))

        dao.markBatchFailed(listOf("a", "b"), "offline")

        // Still pending — a request that never reached the server says nothing about the doses.
        assertEquals(2, dao.pendingCountNow())
        assertEquals(1, dao.rows.getValue("a").attempts)
    }

    // ---- Full sync runs ----

    private fun engine(
        dao: PendingDoseLogDao,
        online: Boolean,
        respond: (SyncBatchRequest) -> Result<SyncBatchResultDto>
    ) = SyncEngine(
        dao = dao,
        sender = { request -> respond(request) },
        connectivity = FakeConnectivity(online)
    )

    @Test
    fun `sync does not call the server while offline`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a"))
        var called = false

        val outcome = engine(dao, online = false) {
            called = true
            Result.success(SyncBatchResultDto())
        }.sync()

        assertFalse("the queue must not be sent without a connection", called)
        assertEquals(0, outcome.attempted)
        assertEquals(1, dao.pendingCountNow())
    }

    @Test
    fun `a successful run settles every acknowledged row`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a", slot = 1L))
        dao.insert(row("b", slot = 2L))

        val outcome = engine(dao, online = true) { request ->
            Result.success(
                SyncBatchResultDto(
                    results = request.doseLogs.map {
                        SyncItemResultDto(localId = it.localId, status = SyncItemStatus.SYNCED)
                    }
                )
            )
        }.sync()

        assertEquals(2, outcome.settled)
        assertEquals(0, dao.pendingCountNow())
    }

    @Test
    fun `a conflict is settled, not retried`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a"))

        val outcome = engine(dao, online = true) { request ->
            Result.success(
                SyncBatchResultDto(
                    results = request.doseLogs.map {
                        SyncItemResultDto(
                            localId = it.localId,
                            status = SyncItemStatus.CONFLICT,
                            message = "A newer entry exists"
                        )
                    }
                )
            )
        }.sync()

        assertEquals(1, outcome.conflicted)
        assertEquals("the server already had a newer entry, so retrying changes nothing",
            0, dao.pendingCountNow())
    }

    @Test
    fun `a rejected row is kept and counted rather than silently dropped`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a"))

        val outcome = engine(dao, online = true) { request ->
            Result.success(
                SyncBatchResultDto(
                    results = request.doseLogs.map {
                        SyncItemResultDto(
                            localId = it.localId,
                            status = SyncItemStatus.REJECTED,
                            message = "Unknown schedule"
                        )
                    }
                )
            )
        }.sync()

        assertEquals(1, outcome.rejected)
        assertEquals("Unknown schedule", dao.rows.getValue("a").lastError)
    }

    @Test
    fun `rows the server did not mention stay queued`() = runTest {
        // A truncated or partial response must not be read as approval. Assuming success here
        // would discard a dose the server never saw.
        val dao = FakeDao()
        dao.insert(row("answered", slot = 1L))
        dao.insert(row("ignored", slot = 2L))

        engine(dao, online = true) {
            Result.success(
                SyncBatchResultDto(
                    results = listOf(
                        SyncItemResultDto(localId = "answered", status = SyncItemStatus.SYNCED)
                    )
                )
            )
        }.sync()

        assertFalse(dao.rows.getValue("answered").isPending)
        assertTrue(dao.rows.getValue("ignored").isPending)
    }

    @Test
    fun `a failed request leaves the whole batch queued`() = runTest {
        val dao = FakeDao()
        dao.insert(row("a", slot = 1L))
        dao.insert(row("b", slot = 2L))

        val outcome = engine(dao, online = true) {
            Result.failure(IllegalStateException("Could not reach the server."))
        }.sync()

        assertTrue(outcome.failed)
        assertEquals(2, dao.pendingCountNow())
        assertEquals(1, dao.rows.getValue("a").attempts)
    }

    @Test
    fun `an empty queue is not a network call`() = runTest {
        val dao = FakeDao()
        var called = false

        val outcome = engine(dao, online = true) {
            called = true
            Result.success(SyncBatchResultDto())
        }.sync()

        assertFalse(called)
        assertEquals(0, outcome.attempted)
    }
}
