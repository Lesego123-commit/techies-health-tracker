// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Request Location Permissions. [Online]. Available at:
//    https://developer.android.com/develop/sensors-and-location/location/permissions
//    [Accessed 25 September 2026].
//   Google. 2024. LocationManager. [Online]. Available at:
//    https://developer.android.com/reference/android/location/LocationManager
//    [Accessed 25 September 2026].
//   Google. 2024. LocationManagerCompat. [Online]. Available at:
//    https://developer.android.com/reference/androidx/core/location/LocationManagerCompat
//    [Accessed 25 September 2026].
//   Kotlin Foundation. 2024. suspendCancellableCoroutine. [Online]. Available at:
//    https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.8 (Additional Feature 1 —
//    Pharmacy Finder).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the caregiver is, or why we could not tell.
 *
 * Four outcomes rather than a nullable location, because the screen has a different thing to say
 * about each. "Turn on location services" and "grant the app permission" are different actions in
 * different places, and a single null would leave the user guessing which one they needed.
 */
sealed interface LocationResult {

    data class Available(val latitude: Double, val longitude: Double) : LocationResult

    /** The app has not been granted location access. Ask, or send the user to app settings. */
    data object PermissionMissing : LocationResult

    /** Location services are switched off for the whole device. */
    data object Disabled : LocationResult

    /** Permission and services are both fine, but no fix arrived in time. */
    data object Unavailable : LocationResult
}

/**
 * A position fix that has not yet been chosen between.
 *
 * Kept as a plain data class with no Android types so [LocationPolicy] can be tested on the JVM:
 * picking between two fixes is a decision worth testing, and it does not need a device to make.
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val accuracyMetres: Float
)

/**
 * Chooses between the cached fixes the platform offers.
 *
 * Android keeps a last-known position per provider — GPS, network, sometimes passive — and they
 * disagree. Taking the first one in the list is how an app ends up searching for pharmacies around
 * where the phone was yesterday, so the rules are explicit and tested.
 */
object LocationPolicy {

    /**
     * How old a cached fix may be and still be used, in milliseconds.
     *
     * Five minutes. A pharmacy search has a radius measured in kilometres, so a fix from a few
     * minutes ago gives the same answer as a fresh one while saving the user a wait — but a fix
     * from this morning could easily be in a different suburb.
     */
    const val MaxFixAgeMillis: Long = 5 * 60 * 1000L

    /**
     * The freshest usable fix, or null when every candidate is too old.
     *
     * Freshness wins over accuracy on purpose. A coarse network fix from a minute ago places the
     * user in the right neighbourhood; a precise GPS fix from an hour ago does not, and being
     * precisely wrong is the worse failure here.
     */
    fun freshest(
        fixes: List<Fix>,
        now: Long,
        maxAgeMillis: Long = MaxFixAgeMillis
    ): Fix? = fixes
        .filter { now - it.timeMillis in 0..maxAgeMillis }
        .maxByOrNull { it.timeMillis }
}

/** Reads the device's position. An interface so the ViewModel can be tested without a device. */
interface LocationProvider {

    /** True when the app currently holds coarse or fine location permission. */
    fun hasPermission(): Boolean

    /** The current position, waiting briefly for a fix when no recent one is cached. */
    suspend fun current(): LocationResult
}

/**
 * The platform implementation, built on [LocationManager].
 *
 * Deliberately not Google Play services' fused provider. That would be a better source of fixes,
 * but it is another dependency for one screen, and this app already asks only for *coarse*
 * location — at which point the fused provider's main advantage, a fast and precise fused fix, is
 * not something we are entitled to use anyway.
 */
class AndroidLocationProvider(private val context: Context) : LocationProvider {

    override fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun current(): LocationResult {
        if (!hasPermission()) return LocationResult.PermissionMissing

        val manager = context.getSystemService(LocationManager::class.java)
            ?: return LocationResult.Unavailable

        // Checked separately from permission because the remedy is different: this one is a
        // device-wide switch the app cannot ask for.
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocationResult.Disabled

        cached(manager)?.let { return it }

        return awaitFix(manager)
    }

    /** A recent fix the platform already holds, which costs nothing and usually answers. */
    @SuppressLint("MissingPermission")
    private fun cached(manager: LocationManager): LocationResult.Available? {
        val fixes = runCatching {
            manager.getProviders(true).mapNotNull { provider ->
                manager.getLastKnownLocation(provider)?.let { it to provider }
            }
        }.getOrElse {
            Log.w(TAG, "Could not read cached locations", it)
            return null
        }

        val chosen = LocationPolicy.freshest(
            fixes = fixes.map { (location, _) -> location.toFix() },
            now = System.currentTimeMillis()
        ) ?: return null

        Log.d(TAG, "Using a cached fix from ${System.currentTimeMillis() - chosen.timeMillis}ms ago")
        return LocationResult.Available(chosen.latitude, chosen.longitude)
    }

    /**
     * Asks for one fresh fix, giving up after [FixTimeoutMillis].
     *
     * The listener is removed in every path, including the timeout and cancellation. Leaving one
     * registered keeps the radio awake for as long as the process lives, which is the kind of
     * battery drain a user notices and never attributes to the app that caused it.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(manager: LocationManager): LocationResult {
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER

            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return LocationResult.Unavailable
        }

        val fix = withTimeoutOrNull(FixTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    // Present for the pre-Android-11 interface, which declares them abstract.
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit

                    @Deprecated("Required by LocationListener below API 29", ReplaceWith(""))
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }

                runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }.onFailure {
                    Log.w(TAG, "Could not request a location update", it)
                    if (continuation.isActive) continuation.resume(null)
                }

                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            }
        }

        return fix
            ?.let { LocationResult.Available(it.latitude, it.longitude) }
            ?: LocationResult.Unavailable.also { Log.i(TAG, "No location fix within the timeout") }
    }

    private fun Location.toFix() = Fix(
        latitude = latitude,
        longitude = longitude,
        timeMillis = time,
        accuracyMetres = if (hasAccuracy()) accuracy else Float.MAX_VALUE
    )

    private companion object {
        const val TAG = "LocationProvider"

        /**
         * How long to wait for a fresh fix.
         *
         * Ten seconds: long enough for a network fix indoors, short enough that a user who has
         * wandered somewhere without signal is told so rather than left watching a spinner.
         */
        const val FixTimeoutMillis = 10_000L
    }
}
