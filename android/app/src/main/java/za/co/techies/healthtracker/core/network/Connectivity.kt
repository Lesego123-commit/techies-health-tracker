// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Monitor Connectivity Status and Connection Metering. [Online]. Available at:
//    https://developer.android.com/training/monitoring-device-state/connectivity-status-type
//    [Accessed 25 September 2026].
//   Google. 2024. ConnectivityManager.NetworkCallback. [Online]. Available at:
//    https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
//    [Accessed 25 September 2026].
//   JetBrains. 2024. Coroutines — callbackFlow. [Online]. Available at:
//    https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/callback-flow.html
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device currently has a network that can actually reach the internet.
 *
 * `NET_CAPABILITY_VALIDATED` is required alongside `INTERNET`. Without it, a phone sitting on a
 * captive-portal hotspot or a router with no upstream reports itself as connected, the app
 * decides it is online, and every dose log fails on a timeout instead of being queued — which is
 * the worst of both worlds, because the user is then told nothing was saved when it could have
 * been saved locally.
 */
interface ConnectivityObserver {
    val isOnline: Boolean
    fun observe(): Flow<Boolean>
}

class AndroidConnectivityObserver(context: Context) : ConnectivityObserver {

    private val manager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Boolean
        get() {
            val network = manager?.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    override fun observe(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                // Not emitted as "online" yet — a network becomes available before it is
                // validated, and acting here is what produces a sync attempt that immediately
                // fails. onCapabilitiesChanged reports the validated transition.
                Log.d(TAG, "Network available, waiting for validation")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(usable)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost; actions will be queued locally")
                trySend(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        trySend(isOnline)
        manager?.registerNetworkCallback(request, callback)

        awaitClose { manager?.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private companion object {
        const val TAG = "Connectivity"
    }
}
