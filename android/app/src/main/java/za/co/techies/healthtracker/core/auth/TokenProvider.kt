// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Manage Users in Firebase — Android. [Online]. Available at:
//    https://firebase.google.com/docs/auth/android/manage-users [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Supplies the Firebase identity token that authenticates every API call.
 *
 * Declared as an interface so the network layer can be tested with a fake: the interceptor's job
 * is to attach whatever token it is handed, and proving that does not require a live Firebase
 * project.
 */
interface TokenProvider {

    /**
     * The current identity token, or null when nobody is signed in.
     *
     * @param forceRefresh true to bypass the cached token. Used once after a 401, in case the
     *  cached token expired between being read and reaching the server.
     */
    suspend fun currentToken(forceRefresh: Boolean = false): String?

    /** The signed-in user's Firebase UID, or null. */
    fun currentUid(): String?
}

/**
 * The real implementation, backed by the Firebase SDK.
 *
 * The SDK caches the token and refreshes it transparently roughly five minutes before it expires,
 * so asking for it per request is cheap and is what keeps a long-running session working without
 * any refresh logic of our own.
 */
class FirebaseTokenProvider(
    private val firebaseAuth: FirebaseAuth
) : TokenProvider {

    override suspend fun currentToken(forceRefresh: Boolean): String? {
        val user = firebaseAuth.currentUser ?: return null

        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: Exception) {
            // A failure here means the device is offline or the account was disabled. Returning
            // null lets the request proceed unauthenticated and fail with a clean 401, rather
            // than crashing inside an OkHttp interceptor where the stack trace is unhelpful.
            Log.w(TAG, "Could not obtain a Firebase identity token", e)
            null
        }
    }

    override fun currentUid(): String? = firebaseAuth.currentUser?.uid

    private companion object {
        const val TAG = "FirebaseTokenProvider"
    }
}

/** A provider for builds with no Firebase configuration, so the app still runs. */
object UnconfiguredTokenProvider : TokenProvider {
    override suspend fun currentToken(forceRefresh: Boolean): String? = null
    override fun currentUid(): String? = null
}
