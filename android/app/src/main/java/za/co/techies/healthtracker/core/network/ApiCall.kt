// ----------------------------------------------------------------------------------------------
//  References
//   Square. 2024. Retrofit — A Type-safe HTTP Client. [Online]. Available at:
//    https://square.github.io/retrofit/ [Accessed 16 September 2026].
//   Square. 2024. OkHttp — Calls, Connections and Timeouts. [Online]. Available at:
//    https://square.github.io/okhttp/ [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.network

import android.util.Log
import za.co.techies.healthtracker.R
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * Wraps a Retrofit call so that every failure arrives as an [ApiException].
 *
 * Without this, each caller would have to handle [HttpException], [IOException] and serialization
 * failures separately and would still end up showing the user a raw stack trace. Repositories
 * funnel every call through here instead, so a screen only ever deals with one failure type.
 */
object ApiCall {

    private const val TAG = "ApiCall"

    /**
     * Lenient on purpose. The server controls this contract, but an API that gained a field
     * should not break a client that has not been rebuilt yet, so unknown keys are ignored.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun <T> execute(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        // Never swallowed: a cancelled coroutine is the caller leaving the screen, not a failure,
        // and converting it to an error would show a spurious message on the way out.
        throw cancellation
    } catch (http: HttpException) {
        Result.failure(http.toApiException())
    } catch (io: IOException) {
        Log.w(TAG, "Request failed before reaching the server", io)
        Result.failure(ApiException.network(io))
    } catch (other: Exception) {
        Log.e(TAG, "Unexpected failure handling a request", other)
        Result.failure(
            ApiException(
                code = ApiErrorCodes.SERVER_ERROR,
                httpStatus = 0,
                message = "Something went wrong. Please try again.",
                messageRes = R.string.api_err_generic,
                cause = other
            )
        )
    }

    /**
     * Reads the API's error envelope off a failed response. A body that cannot be parsed falls
     * back to a message derived from the status code, because a client that crashes while
     * handling an error is worse than the original error.
     */
    private fun HttpException.toApiException(): ApiException {
        val status = code()
        val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()

        val envelope = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<ApiErrorEnvelope>(it) }.getOrNull() }

        if (envelope == null) {
            Log.w(TAG, "HTTP $status with no parseable error envelope")

            return ApiException(
                code = defaultCodeFor(status),
                httpStatus = status,
                message = defaultMessageFor(status),
                messageRes = defaultMessageResFor(status),
                cause = this
            )
        }

        Log.w(TAG, "HTTP $status ${envelope.error}: ${envelope.message} (trace ${envelope.traceId})")

        return ApiException(
            code = envelope.error,
            httpStatus = status,
            message = envelope.message,
            fieldErrors = envelope.errors.orEmpty(),
            traceId = envelope.traceId,
            cause = this
        )
    }

    private fun defaultCodeFor(status: Int): String = when (status) {
        400 -> ApiErrorCodes.INVALID_REQUEST
        401 -> ApiErrorCodes.UNAUTHENTICATED
        403 -> ApiErrorCodes.FORBIDDEN
        404 -> ApiErrorCodes.NOT_FOUND
        409 -> ApiErrorCodes.CONCURRENCY_CONFLICT
        502, 503 -> ApiErrorCodes.UPSTREAM_UNAVAILABLE
        else -> ApiErrorCodes.SERVER_ERROR
    }

    /**
     * The same fallbacks as [defaultMessageFor], named rather than written.
     *
     * Both exist on purpose: the resource is what the caregiver reads, in their own language, and
     * the English string is what goes into the log and the exception\'s own message.
     */
    private fun defaultMessageResFor(status: Int): Int = when (status) {
        400 -> R.string.api_err_invalid_request
        401 -> R.string.api_err_session_expired
        403 -> R.string.api_err_forbidden
        404 -> R.string.api_err_not_found
        409 -> R.string.api_err_conflict
        502, 503 -> R.string.api_err_unavailable
        else -> R.string.api_err_generic
    }

    private fun defaultMessageFor(status: Int): String = when (status) {
        400 -> "That request was not valid."
        401 -> "Your session has expired. Please sign in again."
        403 -> "You do not have access to that."
        404 -> "That record could not be found."
        409 -> "Someone else changed this. Refresh and try again."
        502, 503 -> "The service is temporarily unavailable. Please try again shortly."
        else -> "Something went wrong. Please try again."
    }
}
