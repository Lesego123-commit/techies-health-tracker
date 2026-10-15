// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 25 September 2026].
//   Google. 2024. Architecture: UI layer. [Online]. Available at:
//    https://developer.android.com/topic/architecture/ui-layer [Accessed 25 September 2026].
//   Geoapify GmbH. 2024. Places API — Developer Documentation. [Online]. Available at:
//    https://apidocs.geoapify.com/docs/places/ [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.8 (Additional Feature 1 —
//    Pharmacy Finder) and section 5.4.8 (GET /api/pharmacies/nearby).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.pharmacy

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
import za.co.techies.healthtracker.core.location.LocationProvider
import za.co.techies.healthtracker.core.location.LocationResult
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText
import za.co.techies.healthtracker.data.remote.dto.PharmacyResultDto
import za.co.techies.healthtracker.data.repository.PharmacyRepository
import za.co.techies.healthtracker.data.repository.PharmacySearch
import za.co.techies.healthtracker.di.AppContainer

/**
 * What the pharmacy screen is showing.
 *
 * [blocker] separates "we cannot search yet, and here is the button that fixes it" from [error],
 * which is a search that was attempted and failed. They look similar on screen but lead to
 * different actions, and merging them is how an app ends up offering "Try again" for a missing
 * permission that retrying will never grant.
 */
data class PharmacyUiState(
    val isLoading: Boolean = false,
    val blocker: LocationBlocker? = null,
    val error: UiText? = null,
    val results: List<PharmacyResultDto> = emptyList(),
    val radiusMetres: Int = PharmacySearch.DefaultRadiusMetres,

    /** True once a search has actually run, so an empty list can be told from a screen not used yet. */
    val hasSearched: Boolean = false,

    /** True when the API served this from its 24-hour cache rather than calling GeoApify. */
    val fromCache: Boolean = false
)

/** Why a search cannot start, and therefore which remedy the screen should offer. */
enum class LocationBlocker {

    /** The app has not been granted location access. The screen can ask for it. */
    PermissionMissing,

    /** Location services are off device-wide. Only the user can change that, in system settings. */
    ServicesDisabled,

    /** Permission and services are fine, but no fix arrived. Retrying is worth offering. */
    NoFix
}

/**
 * The pharmacy finder (Additional Feature 1).
 *
 * The GeoApify key never reaches the handset: this asks our own API for coordinates near the user
 * and the server makes the upstream call. That is the whole reason the feature is shaped this way —
 * a key shipped inside an APK is a key anyone who downloads it can extract and spend.
 */
class PharmacyViewModel(
    private val repository: PharmacyRepository,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(PharmacyUiState())
    val uiState: StateFlow<PharmacyUiState> = _uiState.asStateFlow()

    val radiusChoices: List<Int> = PharmacySearch.RadiusChoices

    /**
     * Finds the user, then the pharmacies around them.
     *
     * Nothing happens automatically on arrival when permission is missing. The screen shows what it
     * is for and waits to be asked, because a location prompt that appears before the user has read
     * why earns a refusal — and on Android a refused location permission is close to permanent.
     */
    fun search() {
        if (_uiState.value.isLoading) return

        _uiState.update { it.copy(isLoading = true, error = null, blocker = null) }

        viewModelScope.launch {
            when (val location = locationProvider.current()) {
                is LocationResult.Available -> lookUp(location.latitude, location.longitude)

                LocationResult.PermissionMissing -> block(LocationBlocker.PermissionMissing)
                LocationResult.Disabled -> block(LocationBlocker.ServicesDisabled)
                LocationResult.Unavailable -> block(LocationBlocker.NoFix)
            }
        }
    }

    private suspend fun lookUp(latitude: Double, longitude: Double) {
        val radius = _uiState.value.radiusMetres

        repository.nearby(latitude, longitude, radius)
            .onSuccess { response ->
                Log.i(
                    TAG,
                    "Found ${response.results.size} pharmacies within ${radius}m" +
                        if (response.fromCache) " (from the API cache)" else ""
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        results = response.results,
                        fromCache = response.fromCache,
                        error = null
                    )
                }
            }
            .onFailure { throwable ->
                Log.w(TAG, "Pharmacy search failed", throwable)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        // The API words this one: a missing GeoApify key and an upstream outage
                        // are different sentences, and the server is the only side that knows
                        // which happened.
                        error = throwable.message?.asUiText() ?: uiText(R.string.pharmacy_err_search)
                    )
                }
            }
    }

    private fun block(blocker: LocationBlocker) {
        Log.i(TAG, "Pharmacy search blocked: $blocker")
        _uiState.update { it.copy(isLoading = false, blocker = blocker) }
    }

    /**
     * Changes the radius and searches again.
     *
     * Re-running immediately rather than waiting for a second tap: the chip is a question about the
     * results already on screen, so leaving them stale until the user presses something else would
     * make the chip look broken.
     */
    fun onRadius(metres: Int) {
        val clamped = PharmacySearch.clampRadius(metres)
        if (clamped == _uiState.value.radiusMetres) return

        _uiState.update { it.copy(radiusMetres = clamped) }

        if (_uiState.value.hasSearched || _uiState.value.blocker == null) search()
    }

    /** Called after the permission dialog closes, whichever way it went. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            search()
        } else {
            // Not an error and not a dead end: the refill screen still hands off to a maps app,
            // so the caregiver has a way through that needs nothing from us.
            _uiState.update { it.copy(blocker = LocationBlocker.PermissionMissing) }
        }
    }

    companion object {
        private const val TAG = "PharmacyViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PharmacyViewModel(
                    repository = container.pharmacyRepository,
                    locationProvider = container.locationProvider
                ) as T
            }
    }
}
