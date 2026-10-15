// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 25 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.2 (Requirement 2:
//    Biometric authentication).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.core.auth.BiometricPolicy

/**
 * Tests for the one decision in biometric unlock that actually protects anything.
 *
 * A fingerprint sensor cannot be exercised in a unit test, but the question of *when* to put the
 * lock screen up can be — and it is the part where a mistake matters. Getting it wrong in one
 * direction leaves a stranger looking at someone's medication schedule; in the other, it locks a
 * caregiver out of their own data on a phone that cannot prompt.
 */
class BiometricPolicyTest {

    @Test
    fun `locks when the preference is on, the user is in, and the device can prompt`() {
        assertTrue(
            BiometricPolicy.shouldLock(
                enabled = true, isSignedIn = true, isUnlocked = false, available = true
            )
        )
    }

    @Test
    fun `does not lock when the caregiver has not asked for it`() {
        assertFalse(
            BiometricPolicy.shouldLock(
                enabled = false, isSignedIn = true, isUnlocked = false, available = true
            )
        )
    }

    @Test
    fun `does not lock before sign-in`() {
        // There is nothing behind the lock yet, and Firebase already guards this point. Prompting
        // here would ask for a fingerprint to reach a sign-in screen.
        assertFalse(
            BiometricPolicy.shouldLock(
                enabled = true, isSignedIn = false, isUnlocked = false, available = true
            )
        )
    }

    @Test
    fun `does not lock again once unlocked for this foreground session`() {
        assertFalse(
            BiometricPolicy.shouldLock(
                enabled = true, isSignedIn = true, isUnlocked = true, available = true
            )
        )
    }

    @Test
    fun `does not lock a device that cannot prompt`() {
        // The preference travels with the account, so it can arrive on a phone with no sensor and
        // no screen lock. Locking there would be an unopenable door with no way through it.
        assertFalse(
            BiometricPolicy.shouldLock(
                enabled = true, isSignedIn = true, isUnlocked = false, available = false
            )
        )
    }

    @Test
    fun `every condition is necessary`() {
        // Guards against a future edit turning an && into an ||, which would still pass the happy
        // path above while unlocking the app for everybody.
        val conditions = listOf(true, false)

        for (enabled in conditions) {
            for (signedIn in conditions) {
                for (unlocked in conditions) {
                    for (available in conditions) {
                        val expected = enabled && signedIn && !unlocked && available
                        assertEquals(
                            "enabled=$enabled signedIn=$signedIn unlocked=$unlocked available=$available",
                            expected,
                            BiometricPolicy.shouldLock(enabled, signedIn, unlocked, available)
                        )
                    }
                }
            }
        }
    }
}
