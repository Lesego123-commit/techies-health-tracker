// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 16 September 2026].
//   JetBrains. 2024. Composing Suspending Functions — async/await. [Online]. Available at:
//    https://kotlinlang.org/docs/composing-suspending-functions.html [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.5 (Screen 5 — Dependant Profile).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.dependants

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.data.remote.dto.AppointmentDto
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.remote.dto.DocumentDto
import za.co.techies.healthtracker.data.remote.dto.MedicationDto
import za.co.techies.healthtracker.data.remote.dto.RefillForecastDto
import za.co.techies.healthtracker.data.repository.AppointmentRepository
import za.co.techies.healthtracker.data.repository.DependantRepository
import za.co.techies.healthtracker.data.repository.MedicationRepository
import za.co.techies.healthtracker.di.AppContainer

data class DependantProfileUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val dependant: DependantDto? = null,
    val medications: List<MedicationDto> = emptyList(),

    /** Keyed by medication id, so a card can show the API's severity without a second lookup. */
    val forecasts: Map<String, RefillForecastDto> = emptyMap(),

    val nextAppointment: AppointmentDto? = null,
    val documents: List<DocumentDto> = emptyList()
)

/**
 * Dependant Profile — screen 5 of the design document (section 4.5).
 *
 * The hub for one person: their active medications, next appointment and recent documents, drawn
 * together from three separate endpoints. The design puts all three on one screen because a
 * caregiver checking on someone wants the whole picture, not three trips through a menu.
 *
 * The four calls run concurrently. Serially they would stack four round trips of latency, which
 * on a weak mobile connection is the difference between a screen that appears and one that makes
 * you wait.
 */
class DependantProfileViewModel(
    private val dependantId: String,
    private val dependantRepository: DependantRepository,
    private val medicationRepository: MedicationRepository,
    private val appointmentRepository: AppointmentRepository,
    private val documentsLoader: suspend (String) -> Result<List<DocumentDto>>
) : ViewModel() {

    private val _uiState = MutableStateFlow(DependantProfileUiState())
    val uiState: StateFlow<DependantProfileUiState> = _uiState.asStateFlow()

    // No init-load: the screen calls load() whenever its destination resumes, which covers both
    // arriving here and coming back from a form on top. Loading here as well would mean two
    // identical round trips on every entry.

    fun load() {
        _uiState.update { it.copy(isLoading = it.dependant == null, error = null) }

        viewModelScope.launch {
            // All four start together and are awaited afterwards, so the screen costs one round
            // trip of latency rather than four.
            val profile: Result<DependantDto>
            val medications: Result<List<MedicationDto>>
            val appointments: Result<List<AppointmentDto>>
            val documents: Result<List<DocumentDto>>

            coroutineScope {
                val profileCall = async { dependantRepository.get(dependantId) }
                val medicationCall = async { medicationRepository.listForDependant(dependantId) }
                val appointmentCall = async {
                    appointmentRepository.listForDependant(dependantId, upcomingOnly = true)
                }
                val documentCall = async { documentsLoader(dependantId) }

                profile = profileCall.await()
                medications = medicationCall.await()
                appointments = appointmentCall.await()
                documents = documentCall.await()
            }

            profile
                .onSuccess { dependant ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            dependant = dependant,
                            medications = medications.getOrNull().orEmpty(),

                            // The design shows only the next visit here; the full list lives on
                            // the Appointments tab.
                            nextAppointment = appointments.getOrNull()
                                ?.filter { appointment -> appointment.isUpcoming }
                                ?.minByOrNull { appointment -> appointment.appointmentDateTime },

                            documents = documents.getOrNull().orEmpty()
                        )
                    }

                    loadForecasts()
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load dependant $dependantId", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    /**
     * Loads the refill forecasts so each medication card can show the API's own severity band.
     * Deciding "low stock" on the client would mean hard-coding a threshold the user can change
     * in Settings, so the screen would disagree with the alerts the server actually raises.
     */
    private fun loadForecasts() {
        viewModelScope.launch {
            medicationRepository.refillForecastsForDependant(dependantId)
                .onSuccess { forecasts ->
                    _uiState.update {
                        it.copy(forecasts = forecasts.associateBy { f -> f.medicationId })
                    }
                }
                .onFailure {
                    // Cards fall back to showing no badge, which is honest: better than guessing.
                    Log.d(TAG, "Forecasts unavailable; medication cards will show no stock badge")
                }
        }
    }

    companion object {
        private const val TAG = "DependantProfileVM"

        fun factory(container: AppContainer, dependantId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DependantProfileViewModel(
                        dependantId = dependantId,
                        dependantRepository = container.dependantRepository,
                        medicationRepository = container.medicationRepository,
                        appointmentRepository = container.appointmentRepository,
                        documentsLoader = container.documentRepository::listForDependant
                    ) as T
            }
    }
}
