// ----------------------------------------------------------------------------------------------
//  References
//   Square. 2024. Retrofit — A Type-safe HTTP Client. [Online]. Available at:
//    https://square.github.io/retrofit/ [Accessed 16 September 2026].
//   Square. 2024. OkHttp — Calls, Connections and Timeouts. [Online]. Available at:
//    https://square.github.io/okhttp/ [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.network

import androidx.annotation.StringRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import za.co.techies.healthtracker.R

/**
 * The error body the API returns for every failure.
 *
 * The backend deliberately uses one envelope for validation failures, business-rule violations,
 * authorisation refusals and unexpected errors alike, so the client has a single shape to parse
 * rather than a different guess per endpoint. See the API's ExceptionHandlingMiddleware.
 */
@Serializable
data class ApiErrorEnvelope(
    /** Machine-readable code. Branch on this, not on the message text. */
    @SerialName("error") val error: String = "server_error",

    /** Human-readable and safe to show the user; it never contains internal detail. */
    @SerialName("message") val message: String = "Something went wrong.",

    @SerialName("status") val status: Int = 0,

    /** Correlates with the server log. Worth including in a bug report. */
    @SerialName("traceId") val traceId: String? = null,

    /**
     * Present only on model-binding failures, keyed by field name. This is what the form screens
     * use to highlight the offending inputs.
     */
    @SerialName("errors") val errors: Map<String, List<String>>? = null
)

/** The error codes the API can return. */
object ApiErrorCodes {
    const val VALIDATION_FAILED = "validation_failed"
    const val INVALID_REQUEST = "invalid_request"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val CONCURRENCY_CONFLICT = "concurrency_conflict"
    const val UPSTREAM_UNAVAILABLE = "upstream_unavailable"
    const val SERVER_ERROR = "server_error"

    /** Raised by the client, not the server: the request never reached the API. */
    const val NETWORK_UNAVAILABLE = "network_unavailable"

    /** Raised by the client when no valid Firebase token could be obtained. */
    const val UNAUTHENTICATED = "unauthenticated"
}

/**
 * A failed API call, carrying the parsed envelope where the server supplied one.
 *
 * Repositories translate every failure — HTTP error, dropped connection, unparseable body — into
 * one of these, so a caller never has to handle three different exception families to show one
 * error message.
 */
class ApiException(
    val code: String,
    val httpStatus: Int,
    override val message: String,
    val fieldErrors: Map<String, List<String>> = emptyMap(),
    val traceId: String? = null,

    /**
     * Set only when the client wrote this message itself, so a screen can show it in the chosen
     * language instead of the English in [message].
     *
     * Null means the API supplied the wording, and that is what gets shown: the server localises
     * its own messages from the same stored preference, so second-guessing it on the device would
     * mean keeping a copy of every message the API can produce, forever in step with it.
     *
     * [message] stays English either way. It is what reaches the log and the stack trace, and a
     * crash report that changes language with the user\'s preference is harder to read, not easier.
     */
    @StringRes val messageRes: Int? = null,

    cause: Throwable? = null
) : Exception(message, cause) {

    /** True when retrying unchanged could plausibly succeed. */
    val isRetryable: Boolean
        get() = code == ApiErrorCodes.NETWORK_UNAVAILABLE ||
            code == ApiErrorCodes.UPSTREAM_UNAVAILABLE ||
            httpStatus >= 500

    /** True when the user needs to sign in again. */
    val requiresReauthentication: Boolean
        get() = httpStatus == 401 || code == ApiErrorCodes.UNAUTHENTICATED

    companion object {
        fun network(cause: Throwable) = ApiException(
            code = ApiErrorCodes.NETWORK_UNAVAILABLE,
            httpStatus = 0,
            message = "No connection to the server. Check your network and try again.",
            messageRes = R.string.api_err_network,
            cause = cause
        )

        fun unauthenticated() = ApiException(
            code = ApiErrorCodes.UNAUTHENTICATED,
            httpStatus = 401,
            message = "You are not signed in. Please sign in and try again.",
            messageRes = R.string.api_err_unauthenticated
        )
    }
}
