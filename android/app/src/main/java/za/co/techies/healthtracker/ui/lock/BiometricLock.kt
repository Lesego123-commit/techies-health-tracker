// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Show a Biometric Authentication Dialog. [Online]. Available at:
//    https://developer.android.com/identity/sign-in/biometric-auth [Accessed 25 September 2026].
//   Google. 2024. Side-effects in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/side-effects [Accessed 25 September 2026].
//   Google. 2024. Lifecycle. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/lifecycle
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.2 (Requirement 2:
//    Biometric authentication).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.lock

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.auth.BiometricPolicy
import za.co.techies.healthtracker.core.auth.BiometricResult
import za.co.techies.healthtracker.di.AppContainer

/**
 * Stands the lock screen in front of [content] whenever the account has biometric unlock on.
 *
 * The lock re-arms when the app goes to the background, which is the behaviour that makes the
 * feature worth anything: unlocking once per install would protect nothing the moment the phone
 * was handed over or picked up off a desk. `ON_STOP` is used rather than `ON_PAUSE` because the
 * biometric prompt itself pauses the activity — re-arming on pause would fight the prompt it just
 * raised and loop forever.
 */
@Composable
fun BiometricLock(
    container: AppContainer,
    content: @Composable () -> Unit
) {
    val sessionState by container.session.state.collectAsState()
    val gate = container.biometricGate
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val promptTitle = stringResource(R.string.lock_prompt_title)
    val promptSubtitle = stringResource(R.string.lock_prompt_subtitle)

    var isUnlocked by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(LockUiState()) }

    val enabled = sessionState.settings.biometricEnabled
    val signedIn = sessionState.isSignedIn
    val available = remember(enabled, signedIn) { gate.isAvailable }

    val locked = BiometricPolicy.shouldLock(
        enabled = enabled,
        isSignedIn = signedIn,
        isUnlocked = isUnlocked,
        available = available
    )

    // Re-arm on returning from the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                Log.d(TAG, "App backgrounded; re-arming the lock")
                isUnlocked = false
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun prompt() {
        val activity = context as? FragmentActivity

        if (activity == null) {
            // Cannot happen with the current single-activity host, but failing open here would
            // silently disable the lock, so it fails closed and says why.
            Log.e(TAG, "Host activity is not a FragmentActivity; cannot prompt")
            state = state.copy(error = "Biometric unlock is unavailable on this screen.")
            return
        }

        state = state.copy(isPrompting = true, error = null)

        scope.launch {
            when (val result = gate.authenticate(
                activity = activity,
                title = promptTitle,
                subtitle = promptSubtitle
            )) {
                is BiometricResult.Success -> {
                    isUnlocked = true
                    state = LockUiState()
                }

                is BiometricResult.Cancelled ->
                    // Dismissing the prompt leaves them on the lock screen with the Unlock button.
                    state = state.copy(isPrompting = false)

                is BiometricResult.Failed ->
                    state = state.copy(isPrompting = false, error = result.message)

                is BiometricResult.Unavailable ->
                    state = state.copy(
                        isPrompting = false,
                        availability = result.availability
                    )
            }
        }
    }

    // Raise the prompt as soon as the lock appears, so the common case is one glance at the
    // sensor rather than a screen that asks to be tapped first.
    LaunchedEffect(locked) {
        if (locked) {
            state = LockUiState(availability = gate.availability())
            prompt()
        }
    }

    if (locked) {
        LockScreen(
            state = state,
            onUnlock = ::prompt,
            onSignOut = {
                container.googleAuthClient.signOut()
                container.session.signOut()
                isUnlocked = false
            }
        )
    } else {
        content()
    }
}

private const val TAG = "BiometricLock"
