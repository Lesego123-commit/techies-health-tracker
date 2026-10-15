// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Show a Biometric Authentication Dialog. [Online]. Available at:
//    https://developer.android.com/identity/sign-in/biometric-auth [Accessed 25 September 2026].
//   Google. 2024. androidx.biometric.BiometricManager. [Online]. Available at:
//    https://developer.android.com/reference/androidx/biometric/BiometricManager
//    [Accessed 25 September 2026].
//   Google. 2024. androidx.biometric.BiometricPrompt. [Online]. Available at:
//    https://developer.android.com/reference/androidx/biometric/BiometricPrompt
//    [Accessed 25 September 2026].
//   JetBrains. 2024. Coroutines — suspendCancellableCoroutine. [Online]. Available at:
//    https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.2 (Requirement 2:
//    Biometric authentication).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.auth

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * What this device can currently do, which is not the same question as "does it have a sensor".
 *
 * The three failure cases are genuinely different to the person holding the phone, and collapsing
 * them into one "unavailable" would produce a setting that silently does nothing: a phone with no
 * sensor can never offer this, a phone with a sensor and no enrolled fingerprint needs a trip to
 * Settings, and a temporarily blocked sensor just needs a minute.
 */
enum class BiometricAvailability {
    /** Ready to prompt. */
    Available,

    /** No fingerprint or face sensor, and no device PIN either. The toggle should be hidden. */
    NoHardware,

    /** Hardware is present but nothing is enrolled. The user can fix this in system settings. */
    NotEnrolled,

    /** Present but unusable right now — a security update pending, or too many failed attempts. */
    TemporarilyUnavailable
}

/** The outcome of a prompt, so callers never handle raw BiometricPrompt error codes. */
sealed interface BiometricResult {
    data object Success : BiometricResult

    /** The user dismissed the prompt or pressed back. Not an error. */
    data object Cancelled : BiometricResult

    /** Locked out after repeated failures, or otherwise refused. Carries something showable. */
    data class Failed(val message: String) : BiometricResult

    data class Unavailable(val availability: BiometricAvailability) : BiometricResult
}

/**
 * Biometric unlock (Requirement 2).
 *
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is deliberate. Requiring BIOMETRIC_STRONG would lock out
 * perfectly ordinary phones whose face unlock is classed as weak, and allowing the device PIN as
 * a fallback means someone whose finger will not read — wet hands, a plaster — can still reach
 * their own medication schedule. This gates access to an already-authenticated session rather
 * than protecting a cryptographic key, so a weak class plus device credential is the right trade.
 */
class BiometricGate(private val context: Context) {

    private val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricAvailability.Available

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricAvailability.NotEnrolled

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricAvailability.NoHardware

            else ->
                BiometricAvailability.TemporarilyUnavailable
        }

    val isAvailable: Boolean get() = availability() == BiometricAvailability.Available

    /**
     * Shows the system prompt and suspends until the user resolves it.
     *
     * The continuation is resumed exactly once from each callback. BiometricPrompt can call
     * `onAuthenticationFailed` repeatedly — once per unrecognised finger — and resuming there
     * would crash the second time, so that callback only logs; the prompt itself stays up until
     * the user succeeds, cancels, or is locked out.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String
    ): BiometricResult {
        val availability = availability()

        if (availability != BiometricAvailability.Available) {
            Log.i(TAG, "Prompt skipped; availability is $availability")
            return BiometricResult.Unavailable(availability)
        }

        return suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(TAG, "Unlocked")
                    if (continuation.isActive) continuation.resume(BiometricResult.Success)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (!continuation.isActive) return

                    val outcome = when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> {
                            Log.d(TAG, "Prompt cancelled by the user")
                            BiometricResult.Cancelled
                        }

                        else -> {
                            Log.w(TAG, "Prompt failed ($code): $message")
                            BiometricResult.Failed(BiometricMessages.forError(code, message.toString()))
                        }
                    }

                    continuation.resume(outcome)
                }

                override fun onAuthenticationFailed() {
                    // One unrecognised finger. The prompt stays up, so there is nothing to resume.
                    Log.d(TAG, "Biometric not recognised; prompt remains open")
                }
            }

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                callback
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(allowed)
                .build()

            prompt.authenticate(info)

            // Navigating away while the prompt is up would otherwise leave it orphaned on screen.
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        const val TAG = "BiometricGate"
    }
}

/**
 * Wording for the error codes worth distinguishing, kept separate from the Android classes so it
 * can be unit tested without a device.
 */
object BiometricMessages {

    fun forError(code: Int, fallback: String): String = when (code) {
        BiometricPrompt.ERROR_LOCKOUT ->
            "Too many attempts. Wait 30 seconds, or use your device PIN."

        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
            "Biometric unlock is locked. Use your device PIN to continue."

        BiometricPrompt.ERROR_NO_BIOMETRICS ->
            "No fingerprint or face is set up on this device."

        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE ->
            "The biometric sensor is not available right now."

        // Anything else is rare and device-specific; the system's own wording beats a guess.
        else -> fallback.ifBlank { "Could not verify it is you. Please try again." }
    }
}

/**
 * When the lock screen should stand in front of the app.
 *
 * A pure function on purpose. This is the decision that actually matters for security, and it is
 * the one thing here that can be tested without a fingerprint sensor: getting it wrong either
 * leaves medication data open on a found phone, or locks out a user who never asked for this.
 */
object BiometricPolicy {

    /**
     * @param enabled the caregiver's stored preference, which travels with their account
     * @param isSignedIn there is nothing to protect before sign-in; Firebase guards that
     * @param isUnlocked already satisfied for this foreground session
     * @param available false when the device cannot prompt at all, in which case locking the user
     *  out of their own data would be worse than not locking at all
     */
    fun shouldLock(
        enabled: Boolean,
        isSignedIn: Boolean,
        isUnlocked: Boolean,
        available: Boolean
    ): Boolean = enabled && isSignedIn && !isUnlocked && available
}
