// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 16 September 2026].
//   JetBrains. 2024. Composing Suspending Functions — async/await. [Online]. Available at:
//    https://kotlinlang.org/docs/composing-suspending-functions.html [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.3 (Screen 3 — Home).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.network.ConnectivityObserver
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.dto.AdherenceDto
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.remote.dto.DoseScheduleDto
import za.co.techies.healthtracker.data.remote.dto.MedicationDto
import za.co.techies.healthtracker.data.repository.DoseRepository
import za.co.techies.healthtracker.data.repository.DoseStatus
import za.co.techies.healthtracker.data.repository.MedicationRepository
import za.co.techies.healthtracker.data.repository.SyncEngine
import za.co.techies.healthtracker.data.repository.SyncWorker
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.uiText
import java.time.Instant

/** One medication's next due dose, carrying the dependant it belongs to. */
data class DueDose(
    val medication: MedicationDto,
    val schedule: DoseScheduleDto,
    val dependantFirstName: String,
    val dependantSurnameInitial: String,
    val dueAt: Instant?
) {
    val key: String get() = "${medication.medicationId}:${schedule.scheduleId}"

    /** "Thabo M" — how the design document labels a dose's owner on the Home list. */
    val ownerLabel: String get() = "$dependantFirstName $dependantSurnameInitial".trim()
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val greeting: UiText? = null,
    val userFirstName: String = "",

    /** Doses across every dependant, soonest first. */
    val doses: List<DueDose> = emptyList(),

    val dependants: List<DependantDto> = emptyList(),
    val selectedDependantId: String? = null,
    val adherence: AdherenceDto? = null,

    /** Rows queued locally awaiting sync, driving the offline banner. */
    val pendingSyncCount: Int = 0,

    /** True when the device has no validated connection right now. */
    val isOffline: Boolean = false,

    /** True while a sync run is in flight, so the banner does not offer a second one. */
    val isSyncing: Boolean = false,

    val loggingKey: String? = null,
    val message: UiText? = null,
    val hasDependants: Boolean = true
)

/**
 * Home — screen 3 of the design document (section 4.3).
 *
 * The important behaviour here is that Home shows **every dependant's doses in one list**, not
 * just the selected one. That is the whole point of the caregiver dashboard in Additional
 * Feature 5: someone managing a child and a parent needs to see this morning's doses for both
 * without switching context and remembering what they saw on the other screen.
 *
 * Each dependant's medications come from a separate call, so they are fetched concurrently
 * rather than one after another — with the ten dependants the design allows, doing it serially
 * would be ten round trips of latency stacked end to end.
 */
class HomeViewModel(
    private val appContext: Context,
    private val medicationRepository: MedicationRepository,
    private val doseRepository: DoseRepository,
    private val syncEngine: SyncEngine,
    private val connectivity: ConnectivityObserver,
    private val session: Session
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Watching connectivity is what makes this automatic rather than a button the user has
        // to find. Regaining signal drains the queue on its own; losing it flips the banner so
        // the next tap is visibly saved locally rather than appearing to fail.
        viewModelScope.launch {
            connectivity.observe().collect { online ->
                _uiState.update { it.copy(isOffline = !online) }

                if (online) {
                    Log.i(TAG, "Connection restored; draining the offline queue")
                    syncNow()
                } else {
                    refreshPendingCount()
                }
            }
        }
    }

    // No init-load: the screen calls load() whenever its destination resumes, which covers both
    // arriving here and coming back from a form on top. Loading here as well would mean two
    // identical round trips on every entry.

    fun load() {
        val dependants = session.current.dependants

        _uiState.update {
            it.copy(
                isLoading = it.doses.isEmpty(),
                error = null,
                greeting = greetingForNow(),
                userFirstName = session.current.displayName.substringBefore(' '),
                dependants = dependants,
                selectedDependantId = session.current.selectedDependantId,
                hasDependants = dependants.isNotEmpty()
            )
        }

        if (dependants.isEmpty()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        viewModelScope.launch {
            val results = coroutineScope {
                dependants.map { dependant ->
                    async {
                        dependant to medicationRepository.listForDependant(dependant.dependantId)
                    }
                }.awaitAll()
            }

            val failure = results.firstNotNullOfOrNull { (_, result) -> result.exceptionOrNull() }
            val doses = mutableListOf<DueDose>()

            results.forEach { (dependant, result) ->
                result.getOrNull()?.forEach { medication ->
                    if (!medication.isActive) return@forEach

                    medication.schedules.filter { it.isActive }.forEach { schedule ->
                        doses += DueDose(
                            medication = medication,
                            schedule = schedule,
                            dependantFirstName = dependant.firstName,
                            dependantSurnameInitial = dependant.lastName.take(1),
                            dueAt = schedule.nextDoseAt
                        )
                    }
                }
            }

            // A medication with no active schedule has no due time, so it sorts last rather than
            // first — otherwise nulls would bury the doses that actually need attention.
            doses.sortBy { it.dueAt ?: Instant.MAX }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    doses = doses,

                    // Only surface a failure when nothing loaded at all. One dependant's call
                    // failing should not hide the doses that did come back.
                    error = if (doses.isEmpty()) failure else null
                )
            }

            session.current.selectedDependantId?.let { loadAdherence(it) }
        }
    }

    private fun loadAdherence(dependantId: String) {
        viewModelScope.launch {
            doseRepository.adherence(dependantId)
                .onSuccess { adherence -> _uiState.update { it.copy(adherence = adherence) } }
                .onFailure {
                    // The streak card is supporting detail. Losing it must not take the dose list
                    // down with it, since the list is what the caregiver opened the app for.
                    Log.d(TAG, "Adherence unavailable; hiding the streak card")
                }
        }
    }

    fun selectDependant(dependantId: String) {
        session.selectDependant(dependantId)
        _uiState.update { it.copy(selectedDependantId = dependantId) }
        loadAdherence(dependantId)
    }

    /**
     * Records a dose, offline or not (Requirement 5).
     *
     * The local queue is written **first, always** — not only when the network fails. A request
     * that dies halfway, or a process killed mid-flight, would otherwise lose a dose the
     * caregiver believes they recorded. Writing locally makes it durable before anything can go
     * wrong; the queue then carries it whether this attempt succeeds or not.
     */
    fun logDose(dose: DueDose, taken: Boolean) {
        if (_uiState.value.loggingKey != null) return

        _uiState.update { it.copy(loggingKey = dose.key, message = null) }

        val status = if (taken) DoseStatus.TAKEN else DoseStatus.MISSED

        viewModelScope.launch {
            val queued = syncEngine.enqueue(
                scheduleId = dose.schedule.scheduleId,
                dependantId = dose.medication.dependantId,
                medicationId = dose.medication.medicationId,
                status = status,
                loggedAt = Instant.now(),
                scheduledAt = dose.dueAt
            )

            if (!connectivity.isOnline) {
                Log.i(TAG, "Offline: dose ${queued.localId} held for sync")

                // WorkManager carries this even if the app is closed before signal returns.
                SyncWorker.enqueue(appContext)

                applyLocally(dose, taken)
                refreshPendingCount()
                return@launch
            }

            val result = if (taken) {
                doseRepository.markTaken(
                    scheduleId = dose.schedule.scheduleId,
                    dependantId = dose.medication.dependantId,
                    medicationId = dose.medication.medicationId,

                    // The slot this card is showing. Sending it means the log lands on the dose
                    // the caregiver tapped rather than on whichever one the server infers from
                    // the clock — at two in the afternoon those are not the same dose.
                    scheduledAt = dose.dueAt
                )
            } else {
                doseRepository.markMissed(
                    scheduleId = dose.schedule.scheduleId,
                    dependantId = dose.medication.dependantId,
                    medicationId = dose.medication.medicationId,
                    scheduledAt = dose.dueAt
                )
            }

            result
                .onSuccess { response ->
                    Log.i(
                        TAG,
                        "Dose logged for ${dose.medication.name}; " +
                            "${response.remainingQuantity} left, next ${response.nextDoseAt}"
                    )

                    // It reached the server directly, so the queued copy has nothing left to do.
                    syncEngine.settle(queued.localId)

                    // The response carries the new stock, the recalculated next dose and a fresh
                    // adherence summary, so the screen is updated from it rather than re-fetching.
                    _uiState.update { current ->
                        current.copy(
                            loggingKey = null,
                            adherence = response.adherenceSummary,
                            doses = current.doses.map { existing ->
                                if (existing.key == dose.key) {
                                    existing.copy(
                                        dueAt = response.nextDoseAt,
                                        medication = existing.medication.copy(
                                            remainingQuantity = response.remainingQuantity
                                        )
                                    )
                                } else {
                                    existing
                                }
                            }.sortedBy { it.dueAt ?: Instant.MAX },
                            message = buildMessage(dose, taken, response.refillAlertTriggered)
                        )
                    }

                    refreshPendingCount()
                }
                .onFailure { throwable ->
                    // The dose is already in the queue, so this is a delay rather than a loss.
                    // Saying so is the difference between the caregiver trusting the app and
                    // tapping again — which would be a second dose in the record.
                    Log.w(TAG, "Could not reach the server; dose ${queued.localId} stays queued", throwable)
                    SyncWorker.enqueue(appContext)
                    applyLocally(dose, taken)
                    refreshPendingCount()
                }
        }
    }

    /**
     * Updates the card from what is known on the device, for when the server has not answered.
     *
     * Stock is decremented here but adherence is not recalculated: adherence is the server's
     * answer, computed from every schedule and every log, and a guess made on one device would
     * disagree with it the moment the queue drained.
     */
    private fun applyLocally(dose: DueDose, taken: Boolean) {
        _uiState.update { current ->
            current.copy(
                loggingKey = null,
                doses = current.doses.map { existing ->
                    if (existing.key == dose.key && taken) {
                        existing.copy(
                            medication = existing.medication.copy(
                                remainingQuantity = (existing.medication.remainingQuantity - 1)
                                    .coerceAtLeast(0)
                            )
                        )
                    } else {
                        existing
                    }
                },
                message = uiText(
                    if (taken) R.string.home_msg_taken_offline else R.string.home_msg_missed_offline,
                    dose.medication.name
                )
            )
        }
    }

    /** Drains the queue and reports what changed. Safe to call whenever connectivity returns. */
    fun syncNow() {
        if (_uiState.value.isSyncing) return

        _uiState.update { it.copy(isSyncing = true) }

        viewModelScope.launch {
            val outcome = syncEngine.sync()

            if (outcome.changedAnything) {
                Log.i(TAG, "Sync settled ${outcome.settled}, conflicts ${outcome.conflicted}")
                // The server now holds doses this screen was showing from local state, so the
                // authoritative figures are re-fetched rather than patched.
                load()
            }

            _uiState.update { it.copy(isSyncing = false) }
            refreshPendingCount()
        }
    }

    private suspend fun refreshPendingCount() {
        val pending = syncEngine.pendingCount()
        _uiState.update { it.copy(pendingSyncCount = pending, isOffline = !connectivity.isOnline) }
    }

    /**
     * Picks the confirmation for a logged dose.
     *
     * Four whole sentences rather than a stem with a clause appended. English happens to let a
     * trailing "— running low" be bolted on; isiZulu and Afrikaans do not, so the low-stock variant
     * is its own string and the choice is made here.
     */
    private fun buildMessage(dose: DueDose, taken: Boolean, refillAlert: Boolean): UiText = uiText(
        when {
            taken && refillAlert -> R.string.home_msg_taken_low
            taken -> R.string.home_msg_taken
            refillAlert -> R.string.home_msg_missed_low
            else -> R.string.home_msg_missed
        },
        dose.medication.name
    )

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        private const val TAG = "HomeViewModel"

        /** "Good morning" / "Good afternoon" / "Good evening", as in the design mockup. */
        internal fun greetingForNow(hour: Int = java.time.LocalTime.now().hour): UiText =
            uiText(
                when (hour) {
                    in 5..11 -> R.string.greeting_morning
                    in 12..16 -> R.string.greeting_afternoon
                    else -> R.string.greeting_evening
                }
            )

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        appContext = container.applicationContext,
                        medicationRepository = container.medicationRepository,
                        doseRepository = container.doseRepository,
                        syncEngine = container.syncEngine,
                        connectivity = container.connectivity,
                        session = container.session
                    ) as T
            }
    }
}
