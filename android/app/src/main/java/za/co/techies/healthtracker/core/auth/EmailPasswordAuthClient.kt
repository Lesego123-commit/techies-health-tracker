// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Authenticate with Firebase using Password-Based Accounts on Android. [Online].
//    Available at: https://firebase.google.com/docs/auth/android/password-auth
//    [Accessed 20 September 2026].
//   Google. 2024. Manage Users in Firebase — Android. [Online]. Available at:
//    https://firebase.google.com/docs/auth/android/manage-users [Accessed 20 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.1 (Requirement 1:
//    Registration and login).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.tasks.await
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.uiText

/**
 * Email and password registration and sign-in (Requirement 1).
 *
 * Google SSO covers most people, but it is not a complete answer: a caregiver without a Google
 * account, or using a device where adding one is not an option, would otherwise have no way in at
 * all. This gives the Sign In screen a second route that depends on nothing but an email address.
 *
 * Both routes end in the same place — a Firebase session — so everything downstream is unchanged.
 * The API validates the Firebase token without caring which provider minted it, and
 * `POST /api/auth/login` provisions the profile either way.
 */
class EmailPasswordAuthClient(
    private val firebaseAuth: FirebaseAuth?
) {

    val isConfigured: Boolean get() = firebaseAuth != null

    /** Signs an existing account in. */
    suspend fun signIn(email: String, password: String): SignInResult {
        val auth = firebaseAuth ?: return SignInResult.NotConfigured

        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()

            val uid = result.user?.uid
                ?: return SignInResult.Failure(uiText(R.string.auth_err_no_user))

            Log.i(TAG, "Signed in with email as $uid")
            SignInResult.Success(uid)
        } catch (e: Exception) {
            SignInResult.Failure(describe(e, registering = false), e)
        }
    }

    /**
     * Creates an account and signs into it.
     *
     * The display name is written to the Firebase profile and then the token is force-refreshed.
     * That second step matters: the ID token is minted at sign-in, so without it the `name` claim
     * would still be missing when the API reads the token, and the new profile would fall back to
     * the local part of the email address on the Home screen greeting.
     */
    suspend fun register(email: String, password: String, displayName: String): SignInResult {
        val auth = firebaseAuth ?: return SignInResult.NotConfigured

        return try {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()

            val user = result.user
                ?: return SignInResult.Failure(uiText(R.string.auth_err_no_user_after_create))

            val name = displayName.trim()

            if (name.isNotEmpty()) {
                user.updateProfile(userProfileChangeRequest { this.displayName = name }).await()
                user.getIdToken(true).await()
            }

            Log.i(TAG, "Registered and signed in as ${user.uid}")
            SignInResult.Success(user.uid)
        } catch (e: Exception) {
            SignInResult.Failure(describe(e, registering = true), e)
        }
    }

    /**
     * Sends a password reset email. Reported the same way whether or not the address exists,
     * because confirming which addresses have accounts would let anyone probe the user list.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val auth = firebaseAuth ?: return Result.failure(IllegalStateException("Not configured."))

        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Log.i(TAG, "Password reset email requested")
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidUserException) {
            Log.d(TAG, "Reset requested for an address with no account", e)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Could not send the password reset email", e)
            Result.failure(e)
        }
    }

    /** Hands the exception's code and message to the pure mapping in [AuthErrors]. */
    private fun describe(error: Throwable, registering: Boolean): UiText {
        val code = (error as? FirebaseAuthException)?.errorCode

        if (AuthErrors.isUnexpected(code)) {
            Log.e(TAG, "Email authentication failed", error)
        }

        return AuthErrors.describe(code, error.message, registering)
    }

    private companion object {
        const val TAG = "EmailPasswordAuth"
    }
}

/**
 * Field-level checks that run before anything is sent to Firebase.
 *
 * Kept as pure functions so they can be unit tested without a Firebase project, and so the same
 * rules apply to the register and sign-in paths rather than being retyped in the ViewModel.
 */
object Credentials {

    /** Firebase's own floor. Rejecting it here means a clear message instead of a round trip. */
    const val MinimumPasswordLength = 6

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())

    /** Returns the first problem with the details supplied, or null when they are usable. */
    fun validate(
        email: String,
        password: String,
        registering: Boolean,
        displayName: String = "",
        confirmPassword: String = ""
    ): UiText? = when {
        email.isBlank() -> uiText(R.string.validate_email_blank)
        !isValidEmail(email) -> uiText(R.string.validate_email_invalid)
        password.isEmpty() -> uiText(R.string.validate_password_blank)

        // Only enforced when creating an account. An existing account may predate the rule, and
        // telling someone their correct password is too short would be nonsense.
        registering && password.length < MinimumPasswordLength ->
            uiText(R.string.validate_password_short, MinimumPasswordLength)

        registering && displayName.isBlank() -> uiText(R.string.validate_name_blank)
        registering && confirmPassword != password -> uiText(R.string.validate_passwords_differ)
        else -> null
    }
}

/**
 * Turns a Firebase authentication error into something a caregiver can act on.
 *
 * Separated from the client, and keyed on the error *code* rather than the message, for two
 * reasons. Codes are stable where the prose is not — an earlier version of this matched on the
 * string "OPERATION_NOT_ALLOWED" and silently never fired, because the SDK actually says "This
 * operation is not allowed", so a project misconfiguration surfaced to the user as raw SDK text.
 * And a pure function can be tested without a Firebase project, which is what stops that
 * happening again.
 */
object AuthErrors {

    /** Codes that represent an ordinary, expected outcome rather than something worth logging. */
    private val EXPECTED = setOf(
        "ERROR_INVALID_EMAIL",
        "ERROR_WRONG_PASSWORD",
        "ERROR_USER_NOT_FOUND",
        "ERROR_INVALID_CREDENTIAL",
        "ERROR_EMAIL_ALREADY_IN_USE",
        "ERROR_WEAK_PASSWORD",
        "ERROR_USER_DISABLED",
        "ERROR_TOO_MANY_REQUESTS",
        "ERROR_OPERATION_NOT_ALLOWED"
    )

    fun isUnexpected(code: String?): Boolean = code == null || code !in EXPECTED

    fun describe(code: String?, message: String?, registering: Boolean): UiText = when {
        code == "ERROR_OPERATION_NOT_ALLOWED" ||
            message?.contains("operation is not allowed", ignoreCase = true) == true ->
            uiText(R.string.auth_err_email_not_enabled)

        code == "ERROR_EMAIL_ALREADY_IN_USE" -> uiText(R.string.auth_err_email_in_use)

        code == "ERROR_WEAK_PASSWORD" ->
            uiText(R.string.auth_err_weak_password, Credentials.MinimumPasswordLength)

        code == "ERROR_INVALID_EMAIL" -> uiText(R.string.validate_email_invalid)

        code == "ERROR_USER_DISABLED" -> uiText(R.string.auth_err_disabled)

        code == "ERROR_TOO_MANY_REQUESTS" -> uiText(R.string.auth_err_too_many)

        // Firebase reports an unknown address and a wrong password identically once email
        // enumeration protection is on, so the wording must not distinguish them either.
        code == "ERROR_USER_NOT_FOUND" && registering -> uiText(R.string.validate_email_invalid)

        code == "ERROR_USER_NOT_FOUND" ||
            code == "ERROR_WRONG_PASSWORD" ||
            code == "ERROR_INVALID_CREDENTIAL" -> uiText(R.string.auth_err_bad_credentials)

        // A network failure is the common case here, and it is worth naming rather than showing
        // whatever the SDK happened to say.
        message?.contains("network", ignoreCase = true) == true ->
            uiText(R.string.auth_err_network)

        registering -> uiText(R.string.auth_err_create_failed)
        else -> uiText(R.string.auth_err_login_failed)
    }
}
