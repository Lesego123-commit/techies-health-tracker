// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 20 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 20 September 2026].
//   Google. 2024. Localise your app. [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/localization [Accessed 24 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.auth.AuthErrors
import za.co.techies.healthtracker.core.auth.Credentials
import za.co.techies.healthtracker.core.text.UiText

/**
 * Tests for the checks that run before anything reaches Firebase.
 *
 * These are worth testing precisely because they are the part a user meets when they make a
 * mistake. Getting them wrong does not throw — it just produces a form that either rejects valid
 * details or lets bad ones through to come back as an SDK error nobody can act on.
 *
 * They assert which message was chosen, not what it says. Since the app ships in three languages the
 * wording lives in strings.xml, and a test pinned to the English sentence would be testing the
 * translation rather than the validation.
 */
class CredentialsTest {

    /** The resource a validation result names, or null when the details were usable. */
    private fun resIdOf(result: UiText?): Int? = (result as? UiText.Res)?.resId

    @Test
    fun `accepts ordinary email addresses`() {
        assertTrue(Credentials.isValidEmail("lesego@example.com"))
        assertTrue(Credentials.isValidEmail("first.last@sub.domain.co.za"))
        assertTrue(Credentials.isValidEmail("name+tag@example.org"))
    }

    @Test
    fun `rejects addresses that cannot be delivered to`() {
        assertFalse(Credentials.isValidEmail(""))
        assertFalse(Credentials.isValidEmail("lesego"))
        assertFalse(Credentials.isValidEmail("lesego@"))
        assertFalse(Credentials.isValidEmail("lesego@example"))
        assertFalse(Credentials.isValidEmail("lesego example@test.com"))
    }

    @Test
    fun `ignores surrounding whitespace on the address`() {
        // Phone keyboards add a trailing space more often than anyone expects, and being strict
        // about it would reject an address the user typed correctly.
        assertTrue(Credentials.isValidEmail("  lesego@example.com  "))
    }

    @Test
    fun `passes a complete sign-in`() {
        assertNull(Credentials.validate("lesego@example.com", "secret123", registering = false))
    }

    @Test
    fun `passes a complete registration`() {
        val problem = Credentials.validate(
            email = "lesego@example.com",
            password = "secret123",
            registering = true,
            displayName = "Lesego Masike",
            confirmPassword = "secret123"
        )

        assertNull(problem)
    }

    @Test
    fun `names the first missing field rather than listing every problem`() {
        assertEquals(
            R.string.validate_email_blank,
            resIdOf(Credentials.validate("", "", registering = false))
        )

        assertEquals(
            R.string.validate_password_blank,
            resIdOf(Credentials.validate("lesego@example.com", "", registering = false))
        )
    }

    @Test
    fun `rejects an address that could not be delivered to`() {
        assertEquals(
            R.string.validate_email_invalid,
            resIdOf(Credentials.validate("lesego", "secret123", registering = false))
        )
    }

    @Test
    fun `enforces the minimum password length only when registering`() {
        val tooShort = Credentials.validate(
            "lesego@example.com", "abc", registering = true,
            displayName = "Lesego", confirmPassword = "abc"
        )

        assertEquals(R.string.validate_password_short, resIdOf(tooShort))

        // The minimum travels with the message rather than being written into the sentence, so the
        // number cannot drift away from the constant the check actually uses.
        assertEquals(
            listOf(Credentials.MinimumPasswordLength),
            (tooShort as UiText.Res).args
        )

        // An existing account may predate the rule. Telling someone their own correct password
        // is too short would be nonsense, and would lock them out of their own data.
        assertNull(Credentials.validate("lesego@example.com", "abc", registering = false))
    }

    @Test
    fun `requires a name when creating an account`() {
        assertEquals(
            R.string.validate_name_blank,
            resIdOf(
                Credentials.validate(
                    "lesego@example.com", "secret123", registering = true,
                    displayName = "  ", confirmPassword = "secret123"
                )
            )
        )
    }

    @Test
    fun `catches mistyped password confirmation`() {
        assertEquals(
            R.string.validate_passwords_differ,
            resIdOf(
                Credentials.validate(
                    "lesego@example.com", "secret123", registering = true,
                    displayName = "Lesego", confirmPassword = "secret124"
                )
            )
        )
    }
}

/**
 * Tests for the Firebase error mapping.
 *
 * These exist because of a specific bug: the first version matched the literal string
 * "OPERATION_NOT_ALLOWED", but the SDK's message reads "This operation is not allowed", so the
 * branch never fired and a project misconfiguration reached the user as raw SDK text. Matching on
 * the error code fixes it, and these tests are what stop it coming back.
 *
 * They assert which message the mapping picks rather than its wording, for the same reason as
 * above — and the case that matters most, that the raw SDK text never reaches the user, is now
 * easier to state rather than harder: the result has to be a [UiText.Res], because anything the app
 * words is a resource and only pass-through text is a [UiText.Raw].
 */
class AuthErrorsTest {

    private fun resIdOf(result: UiText): Int? = (result as? UiText.Res)?.resId

    @Test
    fun `names the fix when the sign-in provider is disabled`() {
        val message = AuthErrors.describe(
            code = "ERROR_OPERATION_NOT_ALLOWED",
            message = null,
            registering = false
        )

        assertEquals(R.string.auth_err_email_not_enabled, resIdOf(message))
    }

    @Test
    fun `recognises a disabled provider from the message when no code is supplied`() {
        // Belt and braces: some SDK paths surface the prose without a typed code.
        val message = AuthErrors.describe(
            code = null,
            message = "This operation is not allowed. This may be because the given sign-in "
                + "provider is disabled for this Firebase project.",
            registering = false
        )

        assertEquals(R.string.auth_err_email_not_enabled, resIdOf(message))
    }

    @Test
    fun `never shows the raw SDK text to the user`() {
        val message = AuthErrors.describe(
            code = "ERROR_SOMETHING_NEW",
            message = "com.google.firebase.auth.FirebaseAuthException: internal blah",
            registering = false
        )

        // A resource rather than the exception's own text, which is the whole point: an
        // unrecognised failure still produces a sentence the app wrote, in the chosen language.
        assertTrue("Expected a resource, got $message", message is UiText.Res)
        assertEquals(R.string.auth_err_login_failed, resIdOf(message))
    }

    @Test
    fun `words a failed registration as a failed registration`() {
        val message = AuthErrors.describe("ERROR_SOMETHING_NEW", null, registering = true)

        assertEquals(R.string.auth_err_create_failed, resIdOf(message))
    }

    @Test
    fun `suggests logging in when the address is already registered`() {
        val message = AuthErrors.describe("ERROR_EMAIL_ALREADY_IN_USE", null, registering = true)

        assertEquals(R.string.auth_err_email_in_use, resIdOf(message))
    }

    @Test
    fun `does not reveal whether an address has an account`() {
        val unknown = AuthErrors.describe("ERROR_USER_NOT_FOUND", null, registering = false)
        val wrongPassword = AuthErrors.describe("ERROR_WRONG_PASSWORD", null, registering = false)

        // Wording the two differently would let anyone probe which addresses are registered.
        // Comparing the chosen resource is a stricter check than comparing two sentences: they
        // cannot drift apart in one language while matching in another.
        assertEquals(unknown, wrongPassword)
    }

    @Test
    fun `calls a network failure a network failure`() {
        val message = AuthErrors.describe(null, "A network error has occurred.", registering = false)

        assertEquals(R.string.auth_err_network, resIdOf(message))
    }

    @Test
    fun `carries the minimum length with a weak-password refusal`() {
        val message = AuthErrors.describe("ERROR_WEAK_PASSWORD", null, registering = true)

        assertEquals(R.string.auth_err_weak_password, resIdOf(message))
        assertEquals(
            listOf(Credentials.MinimumPasswordLength),
            (message as UiText.Res).args
        )
    }

    @Test
    fun `treats expected outcomes as not worth logging`() {
        assertFalse(AuthErrors.isUnexpected("ERROR_WRONG_PASSWORD"))
        assertTrue(AuthErrors.isUnexpected("ERROR_SOMETHING_NEW"))
        assertTrue(AuthErrors.isUnexpected(null))
    }
}
