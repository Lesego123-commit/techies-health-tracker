// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. UI Layer — Android Architecture Guide. [Online]. Available at:
//    https://developer.android.com/topic/architecture/ui-layer [Accessed 16 September 2026].
//   JetBrains. 2024. StateFlow and SharedFlow. [Online]. Available at:
//    https://kotlinlang.org/docs/flow.html#stateflow-and-sharedflow [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.12 (Additional Feature 5: multiple dependants).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.remote.dto.SettingsDto
import za.co.techies.healthtracker.data.remote.dto.UserDto

/**
 * The small amount of state that outlives any one screen: who is signed in, their preferences,
 * their dependants, and which dependant is currently being looked at.
 *
 * The selected dependant is the reason this exists. Additional Feature 5 lets one caregiver manage
 * several people, and Home, Trends, Refill and the profile screens all have to agree on which one
 * is in view. Holding it in a ViewModel would lose it on every navigation; holding it here means
 * switching dependant on one screen is reflected on all of them.
 *
 * Nothing here is persisted. It is rebuilt from `POST /api/auth/login` on each launch, which is
 * the call the API expects anyway.
 */
class Session {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val current: SessionState get() = _state.value

    /** Populates the session from a successful login. */
    fun onSignedIn(user: UserDto) {
        _state.update {
            it.copy(
                userId = user.userId,
                displayName = user.displayName,
                email = user.email,
                photoUrl = user.photoUrl,
                settings = user.settings,
                dependants = user.dependants,

                // Default to the first dependant so Home has something to show immediately. A
                // caregiver with one dependant — the common case — then never has to choose.
                selectedDependantId = it.selectedDependantId
                    ?.takeIf { id -> user.dependants.any { d -> d.dependantId == id } }
                    ?: user.dependants.firstOrNull()?.dependantId,

                isSignedIn = true
            )
        }
    }

    fun onDependantsChanged(dependants: List<DependantDto>) {
        _state.update {
            it.copy(
                dependants = dependants,

                // If the dependant in view has just been deleted, fall back rather than leaving
                // every screen pointed at a record that no longer exists.
                selectedDependantId = it.selectedDependantId
                    ?.takeIf { id -> dependants.any { d -> d.dependantId == id } }
                    ?: dependants.firstOrNull()?.dependantId
            )
        }
    }

    fun selectDependant(dependantId: String) {
        _state.update { it.copy(selectedDependantId = dependantId) }
    }

    fun onSettingsChanged(settings: SettingsDto) {
        _state.update { it.copy(settings = settings) }
    }

    /**
     * Records the language chosen on the Sign In screen, before there is a session to save it
     * against. It is applied to the server profile once login succeeds.
     */
    fun setPendingLanguage(code: String) {
        _state.update { it.copy(pendingLanguage = code) }
    }

    fun clearPendingLanguage() {
        _state.update { it.copy(pendingLanguage = null) }
    }

    fun signOut() {
        _state.value = SessionState()
    }
}

data class SessionState(
    val isSignedIn: Boolean = false,
    val userId: String? = null,
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val settings: SettingsDto = SettingsDto(),
    val dependants: List<DependantDto> = emptyList(),
    val selectedDependantId: String? = null,

    /** Chosen on the Sign In screen before a profile exists; pushed to the API after login. */
    val pendingLanguage: String? = null
) {
    val selectedDependant: DependantDto?
        get() = dependants.firstOrNull { it.dependantId == selectedDependantId }

    val hasDependants: Boolean get() = dependants.isNotEmpty()
}
