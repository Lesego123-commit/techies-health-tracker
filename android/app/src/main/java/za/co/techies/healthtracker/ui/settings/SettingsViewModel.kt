// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4 (User Interface and Navigation Design).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.settings

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
import za.co.techies.healthtracker.core.auth.BiometricAvailability
import za.co.techies.healthtracker.core.auth.BiometricGate
import za.co.techies.healthtracker.core.auth.GoogleAuthClient
import za.co.techies.healthtracker.core.locale.AppLanguage
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.dto.SettingsDto
import za.co.techies.healthtracker.data.remote.dto.UpdateSettingsRequest
import za.co.techies.healthtracker.data.repository.SettingsRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText

data class SettingsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: Throwable? = null,
    val message: UiText? = null,
    val settings: SettingsDto = SettingsDto(),
    val displayName: String = "",
    val email: String = "",
    val signedOut: Boolean = false
)

/**
 * Settings — screen 13 of the design document (section 4.13), satisfying Requirement 3.
 *
 * Preferences live on the server, not on the device. That is deliberate: the requirement is that
 * they survive a reinstall or a change of phone, which a purely local DataStore would not give.
 *
 * Every change is a partial update — only the field that moved is sent — and the API's response
 * becomes the new state rather than the optimistic local value. So if the server normalises or
 * rejects something, the toggle reflects what was actually stored rather than what was tapped.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val session: Session,
    private val googleAuth: GoogleAuthClient,
    private val biometricGate: BiometricGate
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * The languages offered, taken from [AppLanguage] rather than listed again here.
     *
     * The same enum decides which resource folder the app reads and which code the API stores, so
     * a fourth language is one enum entry and a values-xx folder, not three lists to keep in step.
     * Each is named in its own language: someone looking for isiZulu is not helped by seeing it
     * spelled the English way.
     */
    val languages = AppLanguage.all.map { it.code to it.nativeName }

    init {
        load()
    }

    fun load() {
        val userId = session.current.userId

        if (userId == null) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                displayName = session.current.displayName,
                email = session.current.email
            )
        }

        viewModelScope.launch {
            repository.get(userId)
                .onSuccess { settings ->
                    session.onSettingsChanged(settings)
                    _uiState.update { it.copy(isLoading = false, settings = settings) }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load settings", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    fun setLanguage(code: String) = update(UpdateSettingsRequest(preferredLanguage = code))

    /**
     * The mockup offers a single "Dark mode" switch rather than the three-way light/dark/system
     * choice the API stores. Off is sent as `system` rather than `light`, so a caregiver who has
     * never touched the switch follows the phone instead of being forced into a light theme.
     */
    fun setDarkTheme(enabled: Boolean) =
        update(UpdateSettingsRequest(theme = if (enabled) "dark" else "system"))

    /**
     * Turning this on is checked against the device first. Storing a preference the phone cannot
     * honour would give a switch that appears to work and silently does nothing — and because the
     * preference travels with the account, it would follow the user to every other device too.
     *
     * Turning it off is never blocked: someone whose sensor has stopped working must still be
     * able to get back in.
     */
    fun setBiometric(enabled: Boolean) {
        if (!enabled) {
            update(UpdateSettingsRequest(biometricEnabled = false))
            return
        }

        when (biometricGate.availability()) {
            BiometricAvailability.Available ->
                update(UpdateSettingsRequest(biometricEnabled = true))

            BiometricAvailability.NotEnrolled ->
                _uiState.update {
                    it.copy(
                        message = uiText(R.string.settings_biometric_not_enrolled)
                    )
                }

            BiometricAvailability.NoHardware ->
                _uiState.update {
                    it.copy(message = uiText(R.string.settings_biometric_no_hardware))
                }

            BiometricAvailability.TemporarilyUnavailable ->
                _uiState.update {
                    it.copy(message = uiText(R.string.settings_biometric_unavailable))
                }
        }
    }

    /** Whether the row should be offered at all — a phone with no sensor should not see it. */
    val isBiometricSupported: Boolean
        get() = biometricGate.availability() != BiometricAvailability.NoHardware

    fun setNotifications(enabled: Boolean) =
        update(UpdateSettingsRequest(notificationsEnabled = enabled))

    fun setReminderLeadTime(minutes: Int) =
        update(UpdateSettingsRequest(reminderLeadTimeMinutes = minutes))

    fun setLowStockThreshold(days: Int) =
        update(UpdateSettingsRequest(lowStockThresholdDays = days))

    /** Sends both ends together, since the API treats quiet hours as all or nothing. */
    fun setQuietHours(start: String?, end: String?) = update(
        UpdateSettingsRequest(
            quietHoursStart = start ?: "",
            quietHoursEnd = end ?: ""
        )
    )

    private fun update(request: UpdateSettingsRequest) {
        val userId = session.current.userId ?: return

        if (_uiState.value.isSaving) return

        _uiState.update { it.copy(isSaving = true, message = null) }

        viewModelScope.launch {
            repository.update(userId, request)
                .onSuccess { settings ->
                    Log.i(TAG, "Settings saved")
                    session.onSettingsChanged(settings)

                    _uiState.update {
                        it.copy(
                                isSaving = false,
                                settings = settings,
                                message = uiText(R.string.settings_saved)
                            )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not save settings", throwable)

                    // The state is left untouched on failure, so the control springs back to the
                    // stored value rather than showing a change that was never persisted.
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            message = throwable.message?.asUiText() ?: uiText(R.string.err_save_change)
                        )
                    }
                }
        }
    }

    fun signOut() {
        googleAuth.signOut()
        session.signOut()
        _uiState.update { it.copy(signedOut = true) }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        private const val TAG = "SettingsViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        repository = container.settingsRepository,
                        session = container.session,
                        googleAuth = container.googleAuthClient,
                        biometricGate = container.biometricGate
                    ) as T
            }
    }
}
