// ----------------------------------------------------------------------------------------------
//  References
//   Square. 2024. Retrofit — A Type-safe HTTP Client. [Online]. Available at:
//    https://square.github.io/retrofit/ [Accessed 16 September 2026].
//   Square. 2024. OkHttp — Calls, Connections and Timeouts. [Online]. Available at:
//    https://square.github.io/okhttp/ [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import za.co.techies.healthtracker.core.auth.TokenProvider
import za.co.techies.healthtracker.data.remote.TechiesApi
import java.util.concurrent.TimeUnit

/**
 * Builds the OkHttp client and Retrofit instance.
 *
 * Kept free of Android framework types so the whole network stack can be constructed in a unit
 * test against MockWebServer, which is how `AuthInterceptorTest` and the repository tests run
 * without a device.
 */
object NetworkFactory {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * Timeouts are deliberately generous on read. The API is hosted on an Azure B1 instance,
     * which cold-starts after a period of inactivity, and a caregiver on a weak signal is exactly
     * the user this app is for. A connect timeout that is too aggressive turns a slow morning
     * into a stream of spurious "no connection" errors.
     */
    private const val CONNECT_TIMEOUT_SECONDS = 20L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    fun createOkHttpClient(
        tokenProvider: TokenProvider,
        enableLogging: Boolean
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Retries an idempotent request when a pooled connection turns out to be stale, which
            // is common on mobile as the handset moves between towers.
            .retryOnConnectionFailure(true)
            .addInterceptor(AuthInterceptor(tokenProvider))

        if (enableLogging) {
            // BODY level only in debug builds. Responses here contain medical information, and
            // logging it on a release build would write a dependant's medication list into logcat
            // where any app with log access could read it.
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }

        return builder.build()
    }

    fun createRetrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ApiCall.json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()

    fun createApi(baseUrl: String, client: OkHttpClient): TechiesApi =
        createRetrofit(baseUrl, client).create(TechiesApi::class.java)
}
