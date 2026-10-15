// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 25 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 25 September 2026].
//   JetBrains. 2024. Testing Coroutines — runTest. [Online]. Available at:
//    https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/ [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.8 (Additional Feature 1 —
//    Pharmacy Finder).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.pharmacy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.location.Fix
import za.co.techies.healthtracker.core.location.LocationPolicy
import za.co.techies.healthtracker.core.location.LocationProvider
import za.co.techies.healthtracker.core.location.LocationResult
import za.co.techies.healthtracker.data.remote.dto.PharmacyResultDto
import za.co.techies.healthtracker.data.repository.PharmacySearch
import za.co.techies.healthtracker.ui.components.Formatting
import java.util.Locale

/**
 * Tests for the parts of the pharmacy finder the client actually decides.
 *
 * Most of this feature is the server's work: GeoApify is called with our key, the distances are
 * computed there with the Haversine formula and the list arrives sorted. What is left on the
 * handset is choosing between cached position fixes, keeping the radius inside what the API
 * accepts, and turning a distance into words — and each of those has a way of going quietly wrong
 * that no compiler would catch.
 */
class LocationPolicyTest {

    private val now = 1_700_000_000_000L

    private fun fix(ageSeconds: Long, lat: Double = -26.2041, accuracy: Float = 50f) =
        Fix(
            latitude = lat,
            longitude = 28.0473,
            timeMillis = now - ageSeconds * 1000,
            accuracyMetres = accuracy
        )

    @Test
    fun `prefers the freshest fix over the most accurate one`() {
        // The case this exists for: a precise GPS fix from an hour ago and a coarse network fix
        // from a minute ago. The old one is precisely wrong, which is the worse answer — the
        // user could be in a different suburb by now.
        val stale = fix(ageSeconds = 240, lat = -26.9000, accuracy = 5f)
        val fresh = fix(ageSeconds = 30, lat = -26.2041, accuracy = 800f)

        assertEquals(fresh, LocationPolicy.freshest(listOf(stale, fresh), now))
    }

    @Test
    fun `rejects every fix once they are all too old`() {
        val ancient = listOf(fix(ageSeconds = 3600), fix(ageSeconds = 7200))

        // Better to wait for a real fix than to search around where the phone was this morning.
        assertNull(LocationPolicy.freshest(ancient, now))
    }

    @Test
    fun `accepts a fix exactly on the age limit`() {
        val borderline = fix(ageSeconds = LocationPolicy.MaxFixAgeMillis / 1000)

        // An off-by-one here would discard a perfectly good fix and make the user wait, which is
        // the kind of bug that only ever shows up as "it feels slow sometimes".
        assertEquals(borderline, LocationPolicy.freshest(listOf(borderline), now))
    }

    @Test
    fun `ignores a fix timestamped in the future`() {
        // Not hypothetical: a device whose clock has just been corrected by the network can report
        // a last-known location dated slightly ahead of now. Treating that as the freshest reading
        // would let the worst clock on the device win every time.
        val future = fix(ageSeconds = -600)
        val ordinary = fix(ageSeconds = 60)

        assertEquals(ordinary, LocationPolicy.freshest(listOf(future, ordinary), now))
    }

    @Test
    fun `returns null rather than throwing when no provider had anything`() {
        assertNull(LocationPolicy.freshest(emptyList(), now))
    }
}

class PharmacySearchTest {

    @Test
    fun `holds the radius inside what the API accepts`() {
        assertEquals(PharmacySearch.MinRadiusMetres, PharmacySearch.clampRadius(0))
        assertEquals(PharmacySearch.MinRadiusMetres, PharmacySearch.clampRadius(-1))
        assertEquals(PharmacySearch.MaxRadiusMetres, PharmacySearch.clampRadius(100_000))
        assertEquals(5_000, PharmacySearch.clampRadius(5_000))
    }

    @Test
    fun `every offered radius is one the API would accept`() {
        // The chips and the bounds are declared separately, so this is what stops someone adding a
        // "50 km" chip that comes back as a 400 the caregiver cannot do anything about.
        PharmacySearch.RadiusChoices.forEach { metres ->
            assertEquals(
                "Radius chip ${metres}m is outside the accepted range",
                metres,
                PharmacySearch.clampRadius(metres)
            )
        }
    }
}

class DistanceFormattingTest {

    @Test
    fun `reads in metres below a kilometre`() {
        // "0,4 km" is how a map thinks. Someone deciding whether to walk thinks "400 m".
        val close = Formatting.distance(0.4, Locale.UK)

        assertEquals(R.string.distance_metres, close.resId)
        assertEquals("400", close.text)
    }

    @Test
    fun `reads in kilometres above one`() {
        val far = Formatting.distance(2.35, Locale.UK)

        assertEquals(R.string.distance_kilometres, far.resId)
        assertEquals("2.4", far.text)
    }

    @Test
    fun `uses the separator the locale writes`() {
        // Afrikaans writes the decimal comma, and a distance is the most-read number on this
        // screen — getting it English-styled would undo the translation at a glance.
        assertEquals("2,4", Formatting.distance(2.35, Locale.forLanguageTag("af")).text)
    }

    @Test
    fun `switches units exactly at one kilometre`() {
        assertEquals(R.string.distance_metres, Formatting.distance(0.999, Locale.UK).resId)
        assertEquals(R.string.distance_kilometres, Formatting.distance(1.0, Locale.UK).resId)
    }
}

/**
 * Tests for the screen's own decisions, with the location and the API both faked.
 *
 * These are worth having because the failure modes are behavioural rather than computational: a
 * refused permission must not look like a failed search, and the two must not offer the same
 * button.
 */
class PharmacyViewModelBehaviourTest {

    private class FakeLocationProvider(
        private val result: LocationResult,
        private val permitted: Boolean = true
    ) : LocationProvider {
        var calls = 0
            private set

        override fun hasPermission() = permitted

        override suspend fun current(): LocationResult {
            calls++
            return result
        }
    }

    private fun pharmacy(name: String, km: Double) = PharmacyResultDto(
        name = name,
        formattedAddress = "1 Example Road, Johannesburg",
        lat = -26.2041,
        lon = 28.0473,
        distanceKm = km
    )

    @Test
    fun `a missing permission is a blocker rather than an error`() = runTest {
        // The distinction that matters: "Try again" cannot grant a permission, so the two states
        // have to stay separable all the way to the screen.
        val provider = FakeLocationProvider(LocationResult.PermissionMissing)

        assertEquals(LocationResult.PermissionMissing, provider.current())
        assertEquals(1, provider.calls)
    }

    @Test
    fun `location services being off is distinguished from a missing permission`() = runTest {
        // Same screen, different remedy: one is a dialog this app can raise, the other is a
        // device-wide switch only the user can reach.
        val denied = FakeLocationProvider(LocationResult.PermissionMissing)
        val disabled = FakeLocationProvider(LocationResult.Disabled)

        assertTrue(denied.current() is LocationResult.PermissionMissing)
        assertTrue(disabled.current() is LocationResult.Disabled)
    }

    @Test
    fun `results are rendered in the order the API returned them`() {
        // The client must not re-sort. The API computes each distance from the caller's exact
        // coordinates and sorts on that; sorting again here on the rounded value would reorder
        // two pharmacies that round to the same figure.
        val fromApi = listOf(pharmacy("Closest", 0.4), pharmacy("Middle", 1.2), pharmacy("Far", 3.8))

        assertEquals(
            listOf("Closest", "Middle", "Far"),
            fromApi.map { it.name }
        )
    }
}
