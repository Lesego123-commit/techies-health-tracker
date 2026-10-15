// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Set Up a Firebase Cloud Messaging Client App on Android — Retrieve the current
//    registration token. [Online]. Available at:
//    https://firebase.google.com/docs/cloud-messaging/android/client [Accessed 25 September 2026].
//   JetBrains. 2024. Coroutines — await() for Play Services Tasks. [Online]. Available at:
//    https://github.com/Kotlin/kotlinx.coroutines/tree/master/integration/kotlinx-coroutines-play-services
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.6 (Requirement 6:
//    Real-time notifications).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Supplies the device's Firebase Cloud Messaging registration token.
 *
 * An interface so sign-in can be tested without Firebase, and so a build with no
 * `google-services.json` degrades to "no token" rather than crashing on a missing FirebaseApp.
 */
interface PushTokenProvider {
    suspend fun currentToken(): String?
}

class FirebasePushTokenProvider : PushTokenProvider {

    override suspend fun currentToken(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: Exception) {
        // Play Services missing, no network on first run, or Firebase not configured. None of
        // these should block sign-in: push is an enhancement, and the in-app bell still works.
        Log.w(TAG, "Could not obtain an FCM token; continuing without push", e)
        null
    }

    private companion object {
        const val TAG = "PushTokenProvider"
    }
}

/** Used when Firebase is not configured at all, so nothing has to null-check the provider. */
object NoPushTokenProvider : PushTokenProvider {
    override suspend fun currentToken(): String? = null
}
