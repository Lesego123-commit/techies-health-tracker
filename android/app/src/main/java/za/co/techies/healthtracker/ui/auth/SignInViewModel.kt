// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. Authenticate with Firebase using Password-Based Accounts on Android. [Online].
//    Available at: https://firebase.google.com/docs/auth/android/password-auth
//    [Accessed 20 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.1 (Screen 1 — Sign In)
//    and section 3.1 (Requirement 1: Registration and login).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.auth.Credentials
import za.co.techies.healthtracker.core.auth.EmailPasswordAuthClient
import za.co.techies.healthtracker.core.auth.GoogleAuthClient
import za.co.techies.healthtracker.core.auth.SignInResult
import za.co.techies.healthtracker.core.notifications.PushTokenProvider
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.dto.UpdateSettingsRequest
import za.co.techies.healthtracker.data.repository.AuthRepository
import za.co.techies.healthtracker.data.repository.SettingsRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText

/** Which of the two things the email form is doing. */
enum class AuthMode { SignIn, Register }

data class SignInUiState(
    val isBusy: Boolean = false,
    val isSignedIn: Boolean = false,
    val error: UiText? = null,

    /** Confirmations, such as a password reset having been requested. Not a failure. */
    val message: UiText? = null,

    val isFirebaseConfigured: Boolean = true,
    val apiBaseUrl: String = "",

    /** Chosen on the language row; applied to the profile once login succeeds. */
    val selectedLanguage: String = "en",

    // ---- Email and password form ----
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val displayName: String = ""
) {
    val isRegistering: Boolean get() = mode == AuthMode.Register
}

/**
 * Registration and login (Requirement 1), by either of two routes.
 *
 * Google SSO is the quick path, but it cannot be the only one: a caregiver without a Google
 * account, or on a device where adding one is not practical, would otherwise be locked out
 * entirely. Email and password covers them, and both routes converge on the same Firebase
 * session, so nothing downstream has to know which was used.
 *
 * Signing in is two steps either way, and both must succeed before the user is really in:
 *
 *   1. Firebase — this establishes *who* they are.
 *   2. `POST /api/auth/login` — this creates or fetches their profile on our server.
 *
 * The second step is not optional. Every other endpoint returns 404 until a profile exists, so
 * stopping after Firebase would leave the user apparently signed in but unable to load anything.
 */
class SignInViewModel(
    private val googleAuth: GoogleAuthClient,
    private val emailAuth: EmailPasswordAuthClient,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val pushTokenProvider: PushTokenProvider,
    private val session: Session,
    apiBaseUrl: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SignInUiState(
            isFirebaseConfigured = googleAuth.isConfigured || emailAuth.isConfigured,
            apiBaseUrl = apiBaseUrl
        )
    )

    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    init {
        // Firebase keeps the session across restarts, so a returning user should not be asked to
        // sign in again — but the profile still has to be fetched before the app is usable.
        if (googleAuth.currentUid() != null) {
            Log.i(TAG, "Existing Firebase session found; restoring profile")
            loadProfile()
        }
    }

    /** Records the language picked before sign-in, so it can be applied to the new profile. */
    fun selectLanguage(code: String) {
        session.setPendingLanguage(code)
        _uiState.update { it.copy(selectedLanguage = code) }
    }

    // ---- Email and password ----

    fun onEmail(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPassword(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onConfirmPassword(value: String) = _uiState.update { it.copy(confirmPassword = value) }
    fun onDisplayName(value: String) = _uiState.update { it.copy(displayName = value) }

    /** Switches between signing in and creating an account, clearing anything mode-specific. */
    fun setMode(mode: AuthMode) = _uiState.update {
        it.copy(mode = mode, error = null, message = null, password = "", confirmPassword = "")
    }

    fun submitEmailForm() {
        val state = _uiState.value

        if (state.isBusy) return

        // Checked before Firebase is touched, so an obvious mistake comes back instantly and the
        // message names the field rather than quoting the SDK at someone who mistyped a password.
        val problem = Credentials.validate(
            email = state.email,
            password = state.password,
            registering = state.isRegistering,
            displayName = state.displayName,
            confirmPassword = state.confirmPassword
        )

        if (problem != null) {
            _uiState.update { it.copy(error = problem) }
            return
        }

        _uiState.update { it.copy(isBusy = true, error = null, message = null) }

        viewModelScope.launch {
            val result = if (state.isRegistering) {
                emailAuth.register(state.email, state.password, state.displayName)
            } else {
                emailAuth.signIn(state.email, state.password)
            }

            handle(result)
        }
    }

    /** Sends a reset link. Worded so that it cannot confirm whether an account exists. */
    fun sendPasswordReset() {
        val email = _uiState.value.email

        if (!Credentials.isValidEmail(email)) {
            _uiState.update { it.copy(error = uiText(R.string.validate_email_first)) }
            return
        }

        _uiState.update { it.copy(isBusy = true, error = null, message = null) }

        viewModelScope.launch {
            emailAuth.sendPasswordReset(email)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            message = uiText(R.string.auth_reset_sent)
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            error = throwable.message?.asUiText() ?: uiText(R.string.err_send_reset)
                        )
                    }
                }
        }
    }

    // ---- Google SSO ----

    fun signIn() {
        if (_uiState.value.isBusy) return

        _uiState.update { it.copy(isBusy = true, error = null, message = null) }

        viewModelScope.launch { handle(googleAuth.signIn()) }
    }

    /** Both routes end in a Firebase session, so both are dealt with the same way. */
    private fun handle(result: SignInResult) {
        when (result) {
            is SignInResult.Success -> loadProfile()

            is SignInResult.Cancelled ->
                // Backing out of the account chooser is a decision, not a failure. Showing an
                // error here would be scolding the user for changing their mind.
                _uiState.update { it.copy(isBusy = false, error = null) }

            is SignInResult.NotConfigured ->
                _uiState.update { it.copy(isBusy = false, isFirebaseConfigured = false) }

            is SignInResult.Failure ->
                _uiState.update { it.copy(isBusy = false, error = result.message) }
        }
    }

    /** Exchanges the Firebase session for the server-side profile. */
    private fun loadProfile() {
        _uiState.update { it.copy(isBusy = true, error = null) }

        viewModelScope.launch {
            // Sent on every login rather than only at registration: the token changes when the
            // app is reinstalled or its data is cleared, and a stale one means the server pushes
            // into the void while the caregiver wonders why no reminders arrive.
            val fcmToken = pushTokenProvider.currentToken()

            authRepository.login(fcmToken)
                .onSuccess { user ->
                    Log.i(TAG, "Profile loaded for ${user.userId} (new: ${user.isNewUser})")
                    session.onSignedIn(user)
                    applyPendingLanguage(user.userId, user.settings.preferredLanguage)
                    _uiState.update { it.copy(isBusy = false, isSignedIn = true) }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Profile load failed", throwable)

                    // Firebase succeeded but our API did not. Leaving the Firebase session in
                    // place would strand the user in a half-signed-in state that looks fine until
                    // every screen fails, so it is cleared and they can try again cleanly.
                    googleAuth.signOut()

                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            error = throwable.message?.asUiText() ?: uiText(R.string.err_reach_server)
                        )
                    }
                }
        }
    }

    /**
     * Pushes the language chosen on the Sign In screen to the profile, but only when it actually
     * differs from what the server already holds — a returning user who set isiZulu on another
     * device should not have it overwritten just because this screen defaulted to English.
     */
    private fun applyPendingLanguage(userId: String, currentLanguage: String) {
        val pending = session.current.pendingLanguage ?: return
        session.clearPendingLanguage()

        if (pending == currentLanguage) return

        viewModelScope.launch {
            settingsRepository.update(userId, UpdateSettingsRequest(preferredLanguage = pending))
                .onSuccess { settings ->
                    Log.i(TAG, "Language set to ${settings.preferredLanguage} on first sign-in")
                    session.onSettingsChanged(settings)
                }
                .onFailure {
                    // Not worth blocking entry over. The user can change it in Settings.
                    Log.d(TAG, "Could not apply the chosen language; leaving the profile default")
                }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null, message = null) }

    companion object {
        private const val TAG = "SignInViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SignInViewModel(
                        googleAuth = container.googleAuthClient,
                        emailAuth = container.emailPasswordAuthClient,
                        authRepository = container.authRepository,
                        settingsRepository = container.settingsRepository,
                        pushTokenProvider = container.pushTokenProvider,
                        session = container.session,
                        apiBaseUrl = container.apiBaseUrl
                    ) as T
            }
    }
}
