// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4 (User Interface and Navigation Design).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.trends

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
import za.co.techies.healthtracker.data.remote.dto.AdherenceDto
import za.co.techies.healthtracker.data.remote.dto.DoseLogDto
import za.co.techies.healthtracker.data.repository.DoseRepository
import za.co.techies.healthtracker.di.AppContainer
import java.time.LocalDate

/** The ranges the Trends screen offers. */
/**
 * The ranges the Trends chips offer.
 *
 * The label is derived from [days] at render time rather than stored as text, so the chips read
 * "30 days" or "30 dae" without this enum knowing anything about language.
 */
enum class TrendRange(val days: Long) {
    WEEK(7),
    MONTH(30),
    QUARTER(90)
}

data class TrendsUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val dependantName: String = "",
    val range: TrendRange = TrendRange.MONTH,
    val adherence: AdherenceDto? = null,
    val history: List<DoseLogDto> = emptyList(),
    val hasDependants: Boolean = true
)

/**
 * Trends and adherence — screen 7 of the design document (section 4.7) and Additional Feature 3.
 *
 * Everything displayed is calculated by the API: the percentage, the per-day series, the streak
 * and the plain-language pattern insight. That matters more than it looks. The server derives
 * "doses scheduled" from the schedules themselves rather than counting logged rows, so a dose the
 * caregiver never answered still counts against them. Recomputing any of this on the client from
 * the dose history would quietly produce a kinder, wrong number.
 */
class TrendsViewModel(
    private val doseRepository: DoseRepository,
    private val session: Session
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrendsUiState())
    val uiState: StateFlow<TrendsUiState> = _uiState.asStateFlow()

    // No init-load: the screen calls load() whenever its destination resumes, which covers both
    // arriving here and coming back from a form on top. Loading here as well would mean two
    // identical round trips on every entry.

    fun setRange(range: TrendRange) {
        if (range == _uiState.value.range) return
        _uiState.update { it.copy(range = range) }
        load()
    }

    fun load() {
        val dependantId = session.current.selectedDependantId

        if (dependantId == null) {
            _uiState.update { it.copy(isLoading = false, hasDependants = false) }
            return
        }

        val range = _uiState.value.range
        val to = LocalDate.now()
        val from = to.minusDays(range.days - 1)

        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                hasDependants = true,
                dependantName = session.current.selectedDependant?.firstName.orEmpty()
            )
        }

        viewModelScope.launch {
            doseRepository.adherence(dependantId, from, to)
                .onSuccess { adherence ->
                    _uiState.update { it.copy(isLoading = false, adherence = adherence) }
                    loadHistory(dependantId, from, to)
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load adherence", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    private fun loadHistory(dependantId: String, from: LocalDate, to: LocalDate) {
        viewModelScope.launch {
            doseRepository.history(dependantId, from, to)
                .onSuccess { logs -> _uiState.update { it.copy(history = logs) } }
                .onFailure {
                    // The chart and the summary are the point of this screen; the raw log below
                    // them is supporting detail and not worth failing the whole screen over.
                    Log.d(TAG, "Dose history unavailable; showing the summary only")
                }
        }
    }

    companion object {
        private const val TAG = "TrendsViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TrendsViewModel(
                        doseRepository = container.doseRepository,
                        session = container.session
                    ) as T
            }
    }
}
