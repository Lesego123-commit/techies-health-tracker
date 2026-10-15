// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 4.6 and 4.7
//    (Screens 6 and 7 — Add Medication and Edit Medication).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.medication

import android.util.Log
import androidx.annotation.StringRes
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
import za.co.techies.healthtracker.data.remote.dto.CreateDoseScheduleRequest
import za.co.techies.healthtracker.data.remote.dto.CreateMedicationRequest
import za.co.techies.healthtracker.data.remote.dto.MedicationDto
import za.co.techies.healthtracker.data.remote.dto.UpdateMedicationRequest
import za.co.techies.healthtracker.data.repository.MedicationRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText
import java.time.LocalDate

/**
 * The frequencies the API accepts, with how many clock times each one requires.
 *
 * The count is not decoration. The API rejects a mismatch outright — a twice-daily course saved
 * with one time would otherwise halve the adherence figure for reasons the caregiver could never
 * see — so the form adds and removes time fields to match the choice rather than letting the two
 * drift apart.
 */
enum class Frequency(
    val value: String,

    /** A resource id rather than text, so the chips follow the chosen language. */
    @StringRes val labelRes: Int,

    val timeCount: Int
) {
    ONCE_DAILY("once_daily", R.string.freq_once_daily, 1),
    TWICE_DAILY("twice_daily", R.string.freq_twice_daily, 2),
    THREE_TIMES_DAILY("three_times_daily", R.string.freq_three_times_daily, 3);

    companion object {
        fun defaultTimes(frequency: Frequency): List<String> = when (frequency) {
            ONCE_DAILY -> listOf("08:00")
            TWICE_DAILY -> listOf("08:00", "20:00")
            THREE_TIMES_DAILY -> listOf("08:00", "14:00", "20:00")
        }

        /** Maps an API value back to the enum, falling back rather than throwing on an unknown. */
        fun from(value: String?): Frequency =
            entries.firstOrNull { it.value == value } ?: TWICE_DAILY
    }
}

data class MedicationFormUiState(
    /** True only while an existing medication is being fetched for editing. */
    val isLoading: Boolean = false,

    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: UiText? = null,
    val fieldErrors: Map<String, List<String>> = emptyMap(),

    /** Add and Edit share this state; this is what tells the screen which one it is. */
    val isEditing: Boolean = false,

    val name: String = "",
    val dosageAmount: String = "",
    val dosageUnit: String = "mg",
    val form: String = "tablet",
    val totalQuantity: String = "",
    val remainingQuantity: String = "",
    val instructions: String = "",
    val frequency: Frequency = Frequency.TWICE_DAILY,
    val times: List<String> = Frequency.defaultTimes(Frequency.TWICE_DAILY)
)

/**
 * Add and Edit Medication — screens 6 and 7 of the design document (sections 4.6 and 4.7).
 *
 * Adding is one form and two API calls: the medication, then its dose schedule. They are separate
 * endpoints because a medication can carry more than one schedule, but a caregiver adding a new
 * prescription thinks of it as a single act, so the form treats it as one.
 *
 * Editing touches the medication only. The API has no update-schedule endpoint by design — a
 * dose schedule is the thing adherence is measured against, so rewriting one in place would
 * silently re-score history that was already recorded. The form therefore shows the schedule as
 * read-only and says why, rather than offering a control that would quietly be ignored.
 */
class MedicationFormViewModel(
    private val repository: MedicationRepository,
    private val session: Session,

    /** Null for Add; an existing id puts the form into Edit mode. */
    private val medicationId: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MedicationFormUiState(isLoading = medicationId != null, isEditing = medicationId != null)
    )
    val uiState: StateFlow<MedicationFormUiState> = _uiState.asStateFlow()

    val dosageUnits = listOf("mg", "ml", "tablets", "g", "mcg", "puffs", "drops")
    val forms = listOf("tablet", "capsule", "liquid", "injection", "inhaler", "drops")

    /** Retained so an edit can send back only what actually changed. */
    private var original: MedicationDto? = null

    init {
        if (medicationId != null) load(medicationId)
    }

    private fun load(id: String) {
        viewModelScope.launch {
            repository.get(id)
                .onSuccess { medication ->
                    original = medication
                    val schedule = medication.schedules.firstOrNull { it.isActive }
                        ?: medication.schedules.firstOrNull()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            name = medication.name,
                            dosageAmount = medication.dosageAmount,
                            dosageUnit = medication.dosageUnit,
                            form = medication.form,
                            totalQuantity = medication.totalQuantity.toString(),
                            remainingQuantity = medication.remainingQuantity.toString(),
                            instructions = medication.instructions.orEmpty(),
                            frequency = Frequency.from(schedule?.frequency),
                            times = schedule?.times?.takeIf { times -> times.isNotEmpty() }
                                ?: Frequency.defaultTimes(Frequency.from(schedule?.frequency))
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load medication $id", throwable)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message?.asUiText() ?: uiText(R.string.err_load_medication)
                        )
                    }
                }
        }
    }

    fun onName(value: String) = _uiState.update { it.copy(name = value) }
    fun onDosageAmount(value: String) = _uiState.update { it.copy(dosageAmount = value) }
    fun onDosageUnit(value: String) = _uiState.update { it.copy(dosageUnit = value) }
    fun onForm(value: String) = _uiState.update { it.copy(form = value) }
    fun onTotalQuantity(value: String) = _uiState.update { it.copy(totalQuantity = value) }
    fun onRemainingQuantity(value: String) = _uiState.update { it.copy(remainingQuantity = value) }
    fun onInstructions(value: String) = _uiState.update { it.copy(instructions = value) }

    fun onFrequency(frequency: Frequency) = _uiState.update {
        // Resetting the times to match keeps the form in a state the API will accept.
        it.copy(frequency = frequency, times = Frequency.defaultTimes(frequency))
    }

    fun onTime(index: Int, value: String) = _uiState.update { state ->
        state.copy(
            times = state.times.mapIndexed { i, existing -> if (i == index) value else existing }
        )
    }

    fun save() {
        val state = _uiState.value

        if (state.isSaving) return

        if (state.name.isBlank() || state.dosageAmount.isBlank()) {
            _uiState.update { it.copy(error = uiText(R.string.validate_med_name_dose)) }
            return
        }

        val quantity = state.totalQuantity.toIntOrNull()

        if (quantity == null || quantity <= 0) {
            _uiState.update { it.copy(error = uiText(R.string.validate_pack_size)) }
            return
        }

        if (state.isEditing) {
            saveEdit(state, quantity)
        } else {
            saveNew(state, quantity)
        }
    }

    private fun saveNew(state: MedicationFormUiState, quantity: Int) {
        val dependantId = session.current.selectedDependantId

        if (dependantId == null) {
            _uiState.update { it.copy(error = uiText(R.string.validate_select_dependant)) }
            return
        }

        val timesError = validateTimes(state.times)

        if (timesError != null) {
            _uiState.update { it.copy(error = timesError) }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            val today = LocalDate.now()

            repository.create(
                dependantId,
                CreateMedicationRequest(
                    name = state.name.trim(),
                    dosageAmount = state.dosageAmount.trim(),
                    dosageUnit = state.dosageUnit,
                    form = state.form,
                    totalQuantity = quantity,

                    // Omitted on purpose: a new prescription is a full pack, and the API seeds
                    // the remaining count from the total when it is not supplied.
                    remainingQuantity = null,

                    startDate = today,
                    instructions = state.instructions.takeIf { it.isNotBlank() }?.trim()
                )
            )
                .onSuccess { medication ->
                    Log.i(TAG, "Medication ${medication.medicationId} created")
                    createSchedule(medication.medicationId, state, today)
                }
                .onFailure { throwable -> reportFailure(throwable) }
        }
    }

    private fun saveEdit(state: MedicationFormUiState, quantity: Int) {
        val id = medicationId ?: return
        val remaining = state.remainingQuantity.toIntOrNull()

        if (remaining == null || remaining < 0) {
            _uiState.update { it.copy(error = uiText(R.string.validate_remaining_min)) }
            return
        }

        if (remaining > quantity) {
            // Caught here rather than left to the API purely so the message can name both
            // numbers; the API enforces the same rule.
            _uiState.update {
                it.copy(error = uiText(R.string.validate_remaining_max, quantity))
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            repository.update(
                id,
                UpdateMedicationRequest(
                    name = state.name.trim(),
                    dosageAmount = state.dosageAmount.trim(),
                    dosageUnit = state.dosageUnit,
                    form = state.form,
                    totalQuantity = quantity,
                    remainingQuantity = remaining,

                    // An empty string clears the field server-side, which is what a caregiver
                    // deleting the instructions means; null would leave the old text in place.
                    instructions = state.instructions.trim()
                )
            )
                .onSuccess { medication ->
                    Log.i(TAG, "Medication ${medication.medicationId} updated")
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                }
                .onFailure { throwable -> reportFailure(throwable) }
        }
    }

    private fun validateTimes(times: List<String>): UiText? = when {
        times.any { !TIME_PATTERN.matches(it.trim()) } -> uiText(R.string.validate_time_format)

        times.map { it.trim() }.distinct().size != times.size ->
            uiText(R.string.validate_times_distinct)

        else -> null
    }

    private fun createSchedule(
        medicationId: String,
        state: MedicationFormUiState,
        startDate: LocalDate
    ) {
        viewModelScope.launch {
            repository.createSchedule(
                medicationId,
                CreateDoseScheduleRequest(
                    frequency = state.frequency.value,
                    times = state.times.map { it.trim() },

                    // Empty means every day. Weekly schedules would need days here, but the form
                    // only offers daily frequencies, so there is nothing to ask the user for.
                    daysOfWeek = emptyList(),
                    startDate = startDate
                )
            )
                .onSuccess { schedule ->
                    Log.i(TAG, "Schedule ${schedule.scheduleId}; next dose ${schedule.nextDoseAt}")
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                }
                .onFailure { throwable ->
                    // The medication saved but the schedule did not, so it exists with no doses
                    // due. Saying so is better than a bare error: the caregiver would otherwise
                    // add it a second time and end up with a duplicate.
                    Log.w(TAG, "Schedule creation failed after the medication was saved", throwable)

                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = uiText(
                                R.string.err_schedule_not_saved,
                                throwable.message.orEmpty()
                            )
                        )
                    }
                }
        }
    }

    private fun reportFailure(throwable: Throwable) {
        Log.w(TAG, "Could not save medication", throwable)
        val apiError = throwable as? ApiException

        _uiState.update {
            it.copy(
                isSaving = false,
                error = throwable.message?.asUiText() ?: uiText(R.string.err_save),
                fieldErrors = apiError?.fieldErrors.orEmpty()
            )
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    companion object {
        private const val TAG = "MedicationFormViewModel"
        private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        fun factory(
            container: AppContainer,
            medicationId: String? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MedicationFormViewModel(
                        repository = container.medicationRepository,
                        session = container.session,
                        medicationId = medicationId
                    ) as T
            }
    }
}
