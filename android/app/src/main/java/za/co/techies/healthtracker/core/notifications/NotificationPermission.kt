// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Notification Runtime Permission. [Online]. Available at:
//    https://developer.android.com/develop/ui/views/notifications/notification-permission
//    [Accessed 25 September 2026].
//   Google. 2024. Request Runtime Permissions. [Online]. Available at:
//    https://developer.android.com/training/permissions/requesting [Accessed 25 September 2026].
//   Google. 2024. Activity Result APIs in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/libraries#activity_result
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.6 (Requirement 6:
//    Real-time notifications).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for permission to post notifications (Requirement 6).
 *
 * From Android 13 the manifest entry alone does nothing: an app that never asks is silently
 * blocked, which is how a push feature can be fully wired up, deliver every message, and still
 * appear broken. That is exactly what the device reported here before this existed —
 * `importance=NONE`.
 *
 * The request is deliberately made **after sign-in**, not on first launch. Asking someone who has
 * not yet seen the app what they want notified about earns a reflexive "no", and on Android 13+
 * a refusal is close to permanent — the system stops showing the dialog after two dismissals.
 */
@Composable
fun NotificationPermissionRequest(isSignedIn: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var alreadyAsked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // A refusal is not an error. The server still records every event and the in-app bell
        // still shows them, so the app degrades rather than breaking.
        Log.i(TAG, if (granted) "Notification permission granted" else "Notification permission declined")
    }

    LaunchedEffect(isSignedIn) {
        if (!isSignedIn || alreadyAsked) return@LaunchedEffect

        if (hasNotificationPermission(context)) {
            Log.d(TAG, "Notification permission already granted")
            return@LaunchedEffect
        }

        alreadyAsked = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** True when the app may post notifications — always true below Android 13. */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private const val TAG = "NotificationPermission"
