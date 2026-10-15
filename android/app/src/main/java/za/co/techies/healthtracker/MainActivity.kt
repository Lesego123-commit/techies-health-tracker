// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. The Activity Lifecycle. [Online]. Available at:
//    https://developer.android.com/guide/components/activities/activity-lifecycle
//    [Accessed 16 September 2026].
//   Google. 2024. Compose in an Activity — setContent. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/setup [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import za.co.techies.healthtracker.core.locale.LocalizedApp
import za.co.techies.healthtracker.core.notifications.NotificationPermissionRequest
import za.co.techies.healthtracker.ui.lock.BiometricLock
import za.co.techies.healthtracker.ui.navigation.TechiesApp
import za.co.techies.healthtracker.ui.theme.TechiesTheme

/**
 * The single activity hosting the Compose UI.
 *
 * Extends FragmentActivity rather than ComponentActivity because BiometricPrompt requires one;
 * Compose is unaffected, since FragmentActivity is itself a ComponentActivity.
 *
 * All navigation happens inside [TechiesApp]; this class only owns the Android lifecycle. The
 * logging here is required by the Part 2 brief, which asks for functional logging that
 * demonstrates an understanding of the application lifecycle and state transitions.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A non-null savedInstanceState means the activity is being rebuilt after a configuration
        // change or after being killed in the background, not started fresh.
        Log.i(TAG, "onCreate (restored=${savedInstanceState != null})")

        val container = (application as TechiesApplication).container

        setContent {
            val sessionState by container.session.state.collectAsState()

            // The theme comes from the stored preference rather than from the phone alone, so a
            // caregiver who chose dark keeps it on a new device. "system" defers to the phone,
            // which is also what an account that has never touched the switch falls back to.
            val darkTheme = when (sessionState.settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            // Asked once the caregiver is in, so the dialog arrives when the request makes
            // sense rather than before they have seen what the app does.
            NotificationPermissionRequest(isSignedIn = sessionState.isSignedIn)

            // The language chosen on the Sign In screen applies before there is a profile
            // to read it from, so the pending choice wins until the server has one stored.
            val language = sessionState.pendingLanguage
                ?: sessionState.settings.preferredLanguage

            LocalizedApp(languageCode = language) {
            TechiesTheme(darkTheme = darkTheme) {
                // Biometric unlock wraps the whole app rather than any one screen, so there is
                // no route that can be reached around it.
                BiometricLock(container = container) {
                    TechiesApp(container = container)
                }
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: the activity is visible")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: the activity is in the foreground and accepting input")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: the activity is losing focus")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: the activity is no longer visible")
    }

    override fun onDestroy() {
        super.onDestroy()

        // isFinishing distinguishes the activity genuinely going away from it being recreated for
        // a rotation, which is the difference that matters when debugging state loss.
        Log.i(TAG, "onDestroy (finishing=$isFinishing)")
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
