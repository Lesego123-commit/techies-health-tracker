// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Application Class. [Online]. Available at:
//    https://developer.android.com/reference/android/app/Application [Accessed 16 September 2026].
//   Google. 2024. Logging with Logcat. [Online]. Available at:
//    https://developer.android.com/studio/debug/logcat [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker

import android.app.Application
import android.util.Log
import za.co.techies.healthtracker.core.notifications.NotificationPresenter
import za.co.techies.healthtracker.di.AppContainer

/**
 * Application entry point.
 *
 * Holds the dependency graph for the process lifetime. The Part 2 brief asks for functional
 * logging that demonstrates an understanding of the application lifecycle, so the lifecycle
 * callbacks here and in [MainActivity] log meaningfully rather than decoratively.
 */
class TechiesApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(applicationContext)

        Log.i(TAG, "Application created. API base URL: ${container.apiBaseUrl}")

        // Channels are registered at startup, not when the first push lands. A notification
        // posted to a channel that does not exist yet is dropped silently by the system, and the
        // first push is exactly the one most likely to arrive while the app is not running.
        NotificationPresenter(applicationContext).ensureChannels()

        if (!container.isFirebaseConfigured) {
            // A loud warning rather than a silent failure: without this file every API call will
            // be unauthenticated and come back 401, and the cause is not obvious from that alone.
            Log.w(
                TAG,
                "google-services.json is missing, so Google sign-in is disabled and all API " +
                    "calls will be unauthenticated. See android/README.md section 2."
            )
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System reported low memory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory(level=$level)")
    }

    companion object {
        private const val TAG = "TechiesApplication"
    }
}
