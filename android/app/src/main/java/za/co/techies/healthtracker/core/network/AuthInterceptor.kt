// ----------------------------------------------------------------------------------------------
//  References
//   Square. 2024. OkHttp Interceptors. [Online]. Available at:
//    https://square.github.io/okhttp/features/interceptors/ [Accessed 16 September 2026].
//   Google. 2024. Retrieve a Firebase ID Token on Android. [Online]. Available at:
//    https://firebase.google.com/docs/auth/admin/verify-id-tokens#retrieve_id_tokens_on_clients
//    [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.network

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import za.co.techies.healthtracker.core.auth.TokenProvider

/**
 * Attaches the Firebase identity token to every outgoing request.
 *
 * The API validates this token on each call and derives the caller's identity from it, so the app
 * never sends a user id in a request body and could not impersonate anyone if it tried.
 *
 * On a 401 the token is fetched once more with a forced refresh and the request replayed. Firebase
 * tokens last an hour and the SDK normally refreshes ahead of expiry, but a request that was
 * queued while the device was asleep can still arrive with a token that expired in flight. Without
 * this retry that shows up as a random sign-out, which is a miserable bug to reproduce.
 */
class AuthInterceptor(
    private val tokenProvider: TokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Signed document download links carry their own signature and expiry in the query string
        // and are served anonymously, so attaching a bearer token to them is pointless.
        if (original.url.encodedPath.contains(SIGNED_DOWNLOAD_PATH)) {
            return chain.proceed(original)
        }

        // runBlocking is acceptable here and nowhere else: OkHttp interceptors are synchronous by
        // contract and already run on a background dispatcher, so nothing on the main thread waits.
        val token = runBlocking { tokenProvider.currentToken(forceRefresh = false) }

        val response = chain.proceed(original.withBearer(token))

        if (response.code != HTTP_UNAUTHORIZED || token == null) {
            return response
        }

        Log.i(TAG, "Request to ${original.url.encodedPath} returned 401; retrying with a fresh token")

        val refreshed = runBlocking { tokenProvider.currentToken(forceRefresh = true) }

        if (refreshed == null || refreshed == token) {
            // Either there is no session any more, or the same token came back. Replaying it would
            // just produce a second 401, so the original response is returned for the caller to
            // handle as a sign-out.
            return response
        }

        // The first response body must be closed before a second call on the same chain, or the
        // connection leaks.
        response.close()

        return chain.proceed(original.withBearer(refreshed))
    }

    private fun Request.withBearer(token: String?): Request =
        if (token.isNullOrBlank()) {
            this
        } else {
            newBuilder().header("Authorization", "Bearer $token").build()
        }

    private companion object {
        const val TAG = "AuthInterceptor"
        const val HTTP_UNAUTHORIZED = 401
        const val SIGNED_DOWNLOAD_PATH = "/api/documents/download/"
    }
}
