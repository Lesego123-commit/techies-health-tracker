// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4 (User Interface and Navigation Design).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.refill

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.dto.RefillForecastDto
import za.co.techies.healthtracker.data.repository.MedicationRepository
import za.co.techies.healthtracker.di.AppContainer

data class RefillUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val dependantName: String = "",
    val forecasts: List<RefillForecastDto> = emptyList(),
    val hasDependants: Boolean = true
) {
    /** Anything the API has flagged as needing attention. Drives the summary line at the top. */
    val alertCount: Int get() = forecasts.count { it.refillAlertTriggered }
}

/**
 * Refill and low stock — screen 8 of the design document (section 4.8) and Additional Feature 4.
 *
 * This screen renders and nothing more. The consumption rate, the run-out date, the days
 * remaining and the severity band are all worked out by the API against the caregiver's own
 * low-stock threshold, and the list arrives already ordered by urgency.
 *
 * Deciding the colour on the client would mean hard-coding a threshold here that the user can
 * change in Settings — so the screen would disagree with the alert the server actually raises.
 */
class RefillViewModel(
    private val medicationRepository: MedicationRepository,
    private val session: Session
) : ViewModel() {

    private val _uiState = MutableStateFlow(RefillUiState())
    val uiState: StateFlow<RefillUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val dependantId = session.current.selectedDependantId

        if (dependantId == null) {
            _uiState.update { it.copy(isLoading = false, hasDependants = false) }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = it.forecasts.isEmpty(),
                error = null,
                hasDependants = true,
                dependantName = session.current.selectedDependant?.firstName.orEmpty()
            )
        }

        viewModelScope.launch {
            medicationRepository.refillForecastsForDependant(dependantId)
                .onSuccess { forecasts ->
                    Log.d(TAG, "Loaded ${forecasts.size} forecast(s)")

                    // Already ordered by urgency server-side, so it is rendered as it arrives.
                    _uiState.update { it.copy(isLoading = false, forecasts = forecasts) }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load refill forecasts", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    companion object {
        private const val TAG = "RefillViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RefillViewModel(
                        medicationRepository = container.medicationRepository,
                        session = container.session
                    ) as T
            }
    }
}
