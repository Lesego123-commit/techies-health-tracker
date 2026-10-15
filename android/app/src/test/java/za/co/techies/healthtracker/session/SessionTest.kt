// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 16 September 2026].
//   Square. 2024. MockWebServer. [Online]. Available at:
//    https://github.com/square/okhttp/tree/master/mockwebserver [Accessed 16 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.remote.dto.SettingsDto
import za.co.techies.healthtracker.data.remote.dto.UserDto
import java.time.Instant
import java.time.LocalDate

/**
 * Tests for the app-wide session, specifically the selected-dependant rules.
 *
 * This is worth testing directly because Home, Trends and Refill all read the selection, so a
 * mistake here shows up as three screens quietly displaying the wrong person's medication — the
 * single worst failure this app could have.
 */
class SessionTest {

    private fun dependant(id: String, first: String = "Thandi") = DependantDto(
        dependantId = id,
        firstName = first,
        lastName = "Masike",
        fullName = "$first Masike",
        dateOfBirth = LocalDate.of(2014, 3, 12),
        age = 12,
        relationship = "child"
    )

    private fun user(vararg dependants: DependantDto) = UserDto(
        userId = "user-1",
        displayName = "Lesego Masike",
        email = "lesego@example.com",
        settings = SettingsDto(),
        dependants = dependants.toList(),
        createdAt = Instant.parse("2026-04-15T06:00:00Z")
    )

    @Test
    fun `selects the first dependant on sign-in so Home has something to show`() {
        val session = Session()

        session.onSignedIn(user(dependant("a"), dependant("b", "Sipho")))

        // A caregiver with one dependant — the common case — should never have to choose.
        assertEquals("a", session.current.selectedDependantId)
        assertTrue(session.current.isSignedIn)
        assertTrue(session.current.hasDependants)
    }

    @Test
    fun `leaves the selection empty when there are no dependants`() {
        val session = Session()

        session.onSignedIn(user())

        assertNull(session.current.selectedDependantId)
        assertFalse(session.current.hasDependants)
    }

    @Test
    fun `keeps the current selection when the profile is reloaded`() {
        val session = Session()
        session.onSignedIn(user(dependant("a"), dependant("b", "Sipho")))

        session.selectDependant("b")

        // Login runs on every launch. If it reset the selection each time, switching dependant
        // would silently undo itself whenever the app was backgrounded and resumed.
        session.onSignedIn(user(dependant("a"), dependant("b", "Sipho")))

        assertEquals("b", session.current.selectedDependantId)
    }

    @Test
    fun `falls back when the selected dependant is deleted`() {
        val session = Session()
        session.onSignedIn(user(dependant("a"), dependant("b", "Sipho")))
        session.selectDependant("b")

        session.onDependantsChanged(listOf(dependant("a")))

        // Leaving the selection pointed at a deleted record would have every screen requesting a
        // dependant the API returns 404 for.
        assertEquals("a", session.current.selectedDependantId)
    }

    @Test
    fun `clears the selection when the last dependant is deleted`() {
        val session = Session()
        session.onSignedIn(user(dependant("a")))

        session.onDependantsChanged(emptyList())

        assertNull(session.current.selectedDependantId)
        assertFalse(session.current.hasDependants)
    }

    @Test
    fun `exposes the selected dependant object, not just its id`() {
        val session = Session()
        session.onSignedIn(user(dependant("a"), dependant("b", "Sipho")))
        session.selectDependant("b")

        assertEquals("Sipho", session.current.selectedDependant?.firstName)
    }

    @Test
    fun `sign-out discards everything`() {
        val session = Session()
        session.onSignedIn(user(dependant("a")))

        session.signOut()

        // Nothing from the previous user may survive: the next person to sign in on this handset
        // must not see any trace of their medication data.
        assertFalse(session.current.isSignedIn)
        assertNull(session.current.userId)
        assertNull(session.current.selectedDependantId)
        assertTrue(session.current.dependants.isEmpty())
    }

    @Test
    fun `settings updates replace the stored preferences`() {
        val session = Session()
        session.onSignedIn(user(dependant("a")))

        session.onSettingsChanged(SettingsDto(preferredLanguage = "zu", lowStockThresholdDays = 14))

        assertEquals("zu", session.current.settings.preferredLanguage)
        assertEquals(14, session.current.settings.lowStockThresholdDays)
    }
}
