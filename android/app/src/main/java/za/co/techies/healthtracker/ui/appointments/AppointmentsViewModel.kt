// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 16 September 2026].
//   Oracle. 2024. java.time.LocalDateTime. [Online]. Available at:
//    https://docs.oracle.com/javase/8/docs/api/java/time/LocalDateTime.html [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.9 (Screen 9 — Appointments).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.appointments

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
import za.co.techies.healthtracker.data.remote.dto.AppointmentDto
import za.co.techies.healthtracker.data.remote.dto.CreateAppointmentRequest
import za.co.techies.healthtracker.data.repository.AppointmentRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class AppointmentsUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val dependantName: String = "",
    val upcoming: List<AppointmentDto> = emptyList(),
    val past: List<AppointmentDto> = emptyList(),
    val hasDependants: Boolean = true,

    // ---- Add form ----
    val isFormOpen: Boolean = false,
    val isSaving: Boolean = false,
    val title: String = "",
    val date: String = "",
    val time: String = "",
    val location: String = "",
    val reminderMinutes: Int = 60,
    val formError: UiText? = null,
    val fieldErrors: Map<String, List<String>> = emptyMap()
)

/**
 * Appointments — screen 9 of the design document (section 4.9).
 *
 * Upcoming and past are split by the API's own `isUpcoming` flag rather than by comparing
 * timestamps here. That matters: a handset with a skewed clock would otherwise file a visit under
 * the wrong heading, and the server is the only party with an authoritative view of "now".
 */
class AppointmentsViewModel(
    private val repository: AppointmentRepository,
    private val session: Session
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppointmentsUiState())
    val uiState: StateFlow<AppointmentsUiState> = _uiState.asStateFlow()

    /** Lead times offered by the form. The API enforces a five-minute floor. */
    val reminderOptions = listOf(15, 30, 60, 120, 1440)

    // No init-load: the screen calls load() whenever its destination resumes, which covers both
    // arriving here and coming back from a form on top. Loading here as well would mean two
    // identical round trips on every entry.

    fun load() {
        val dependantId = session.current.selectedDependantId

        if (dependantId == null) {
            _uiState.update { it.copy(isLoading = false, hasDependants = false) }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = it.upcoming.isEmpty() && it.past.isEmpty(),
                error = null,
                hasDependants = true,
                dependantName = session.current.selectedDependant?.firstName.orEmpty()
            )
        }

        viewModelScope.launch {
            repository.listForDependant(dependantId)
                .onSuccess { appointments ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            upcoming = appointments.filter { a -> a.isUpcoming },
                            past = appointments.filterNot { a -> a.isUpcoming }
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load appointments", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    // ---- Add form ----

    fun openForm() = _uiState.update {
        it.copy(
            isFormOpen = true,
            title = "",
            date = LocalDate.now().plusDays(1).toString(),
            time = "09:00",
            location = "",
            reminderMinutes = 60,
            formError = null,
            fieldErrors = emptyMap()
        )
    }

    fun closeForm() = _uiState.update { it.copy(isFormOpen = false, formError = null) }

    fun onTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun onDate(value: String) = _uiState.update { it.copy(date = value) }
    fun onTime(value: String) = _uiState.update { it.copy(time = value) }
    fun onLocation(value: String) = _uiState.update { it.copy(location = value) }
    fun onReminder(minutes: Int) = _uiState.update { it.copy(reminderMinutes = minutes) }

    fun save() {
        val state = _uiState.value
        val dependantId = session.current.selectedDependantId ?: return

        if (state.isSaving) return

        if (state.title.isBlank()) {
            _uiState.update { it.copy(formError = uiText(R.string.validate_appointment_title)) }
            return
        }

        val localDateTime = runCatching {
            LocalDateTime.of(
                LocalDate.parse(state.date.trim()),
                LocalTime.parse(state.time.trim())
            )
        }.getOrNull()

        if (localDateTime == null) {
            _uiState.update {
                it.copy(formError = uiText(R.string.validate_appointment_datetime))
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, formError = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            repository.create(
                dependantId,
                CreateAppointmentRequest(
                    title = state.title.trim(),

                    // The user picks a wall-clock time in their own zone; the API stores instants,
                    // so it is converted here rather than sent ambiguously.
                    appointmentDateTime = localDateTime.atZone(ZoneId.systemDefault()).toInstant(),

                    location = state.location.takeIf { it.isNotBlank() }?.trim(),
                    reminderMinutesBefore = state.reminderMinutes
                )
            )
                .onSuccess {
                    Log.i(TAG, "Appointment created for dependant $dependantId")
                    _uiState.update { it.copy(isSaving = false, isFormOpen = false) }
                    load()
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not create appointment", throwable)
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

    fun cancel(appointmentId: String) {
        viewModelScope.launch {
            repository.cancel(appointmentId)
                .onSuccess {
                    Log.i(TAG, "Appointment $appointmentId cancelled")
                    load()
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            formError = throwable.message?.asUiText()
                                ?: uiText(R.string.err_cancel_visit)
                        )
                    }
                }
        }
    }

    companion object {
        private const val TAG = "AppointmentsViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppointmentsViewModel(
                        repository = container.appointmentRepository,
                        session = container.session
                    ) as T
            }
    }
}
