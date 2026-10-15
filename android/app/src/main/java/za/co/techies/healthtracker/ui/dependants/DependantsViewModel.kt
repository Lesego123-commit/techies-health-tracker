// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4 (User Interface and Navigation Design).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.dependants

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
import za.co.techies.healthtracker.core.network.ApiException
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.dto.CreateDependantRequest
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.repository.DependantRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText
import java.time.LocalDate

data class DependantsUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val dependants: List<DependantDto> = emptyList(),
    val selectedId: String? = null,

    // ---- Add form ----
    val isFormOpen: Boolean = false,
    val isSaving: Boolean = false,
    val firstName: String = "",
    val lastName: String = "",
    val dateOfBirth: String = "",
    val relationship: String = "child",
    val allergies: String = "",
    val formError: UiText? = null,

    /** Per-field messages from the API's validation envelope, keyed by field name. */
    val fieldErrors: Map<String, List<String>> = emptyMap()
)

/**
 * Dependants — screen 4 of the design document (section 4.4), and the whole of Additional
 * Feature 5: one caregiver account managing several people's medication.
 *
 * Choosing a dependant here writes to [Session] rather than to this ViewModel's own state,
 * because Home, Trends and Refill all read from the same selection. Keeping it local would mean
 * the choice quietly reverting the moment the user navigated away.
 */
class DependantsViewModel(
    private val repository: DependantRepository,
    private val session: Session
) : ViewModel() {

    private val _uiState = MutableStateFlow(DependantsUiState())
    val uiState: StateFlow<DependantsUiState> = _uiState.asStateFlow()

    /** The relationships the API accepts. Offering a fixed list avoids a rejected free-text value. */
    val relationships = listOf("child", "parent", "spouse", "self", "other")

    // No init-load: the screen calls load() whenever its destination resumes, which covers both
    // arriving here and coming back from a form on top. Loading here as well would mean two
    // identical round trips on every entry.

    fun load() {
        _uiState.update { it.copy(isLoading = it.dependants.isEmpty(), error = null) }

        viewModelScope.launch {
            repository.list()
                .onSuccess { dependants ->
                    session.onDependantsChanged(dependants)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            dependants = dependants,
                            selectedId = session.current.selectedDependantId
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load dependants", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    fun select(dependantId: String) {
        session.selectDependant(dependantId)
        _uiState.update { it.copy(selectedId = dependantId) }
    }

    // ---- Add form ----

    fun openForm() = _uiState.update {
        it.copy(
            isFormOpen = true,
            firstName = "",
            lastName = "",
            dateOfBirth = "",
            relationship = "child",
            allergies = "",
            formError = null,
            fieldErrors = emptyMap()
        )
    }

    fun closeForm() = _uiState.update { it.copy(isFormOpen = false, formError = null) }

    fun onFirstName(value: String) = _uiState.update { it.copy(firstName = value) }
    fun onLastName(value: String) = _uiState.update { it.copy(lastName = value) }
    fun onDateOfBirth(value: String) = _uiState.update { it.copy(dateOfBirth = value) }
    fun onRelationship(value: String) = _uiState.update { it.copy(relationship = value) }
    fun onAllergies(value: String) = _uiState.update { it.copy(allergies = value) }

    fun save() {
        val state = _uiState.value

        if (state.isSaving) return

        // Cheap checks happen here so the user is not made to wait on a round trip to be told a
        // field is blank. Everything the API validates is still left to the API — duplicating its
        // rules would create a second set that could drift out of step.
        if (state.firstName.isBlank() || state.lastName.isBlank()) {
            _uiState.update { it.copy(formError = uiText(R.string.validate_name_required)) }
            return
        }

        val dateOfBirth = runCatching { LocalDate.parse(state.dateOfBirth.trim()) }.getOrNull()

        if (dateOfBirth == null) {
            _uiState.update {
                it.copy(formError = uiText(R.string.validate_dob_format))
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, formError = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            repository.create(
                CreateDependantRequest(
                    firstName = state.firstName.trim(),
                    lastName = state.lastName.trim(),
                    dateOfBirth = dateOfBirth,
                    relationship = state.relationship,
                    allergies = state.allergies.takeIf { it.isNotBlank() }?.trim()
                )
            )
                .onSuccess { created ->
                    Log.i(TAG, "Dependant ${created.dependantId} created")

                    // A caregiver who has just added someone almost certainly wants to work on
                    // them next, so the new profile becomes the selected one.
                    session.selectDependant(created.dependantId)

                    _uiState.update { it.copy(isSaving = false, isFormOpen = false) }
                    load()
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not create dependant", throwable)
                    val apiError = throwable as? ApiException

                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            formError = throwable.message?.asUiText() ?: uiText(R.string.err_save),
                            fieldErrors = apiError?.fieldErrors.orEmpty()
                        )
                    }
                }
        }
    }

    fun delete(dependantId: String) {
        viewModelScope.launch {
            repository.delete(dependantId)
                .onSuccess {
                    Log.i(TAG, "Dependant $dependantId removed")
                    load()
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            formError = throwable.message?.asUiText()
                                ?: uiText(R.string.err_remove_profile)
                        )
                    }
                }
        }
    }

    companion object {
        private const val TAG = "DependantsViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DependantsViewModel(
                        repository = container.dependantRepository,
                        session = container.session
                    ) as T
            }
    }
}
