// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 16 September 2026].
//   Google. 2024. Text Fields in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/text/user-input [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.5 (Screen 5 — Dependant Profile).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.dependants

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import za.co.techies.healthtracker.data.remote.dto.UpdateDependantRequest
import za.co.techies.healthtracker.data.repository.DependantRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.ui.components.relationshipLabel
import za.co.techies.healthtracker.ui.components.resolve
import za.co.techies.healthtracker.core.text.uiText
import java.time.LocalDate

data class DependantEditUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: UiText? = null,
    val fieldErrors: Map<String, List<String>> = emptyMap(),

    val firstName: String = "",
    val lastName: String = "",
    val dateOfBirth: String = "",
    val relationship: String = "child",
    val medicalAidNumber: String = "",
    val allergies: String = ""
)

/**
 * Edit a dependant's profile, reached from the Dependant Profile screen (section 4.5).
 *
 * The form is prefilled from the server rather than from whatever the list happened to be
 * holding, because two caregivers can share an account: what was on screen a minute ago is not
 * necessarily what is stored now, and a partial update built from stale values would quietly
 * revert the other person's change.
 */
class DependantEditViewModel(
    private val repository: DependantRepository,
    private val dependantId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(DependantEditUiState())
    val uiState: StateFlow<DependantEditUiState> = _uiState.asStateFlow()

    val relationships = listOf("child", "parent", "spouse", "self", "other")

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            repository.get(dependantId)
                .onSuccess { dependant ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            firstName = dependant.firstName,
                            lastName = dependant.lastName,
                            dateOfBirth = dependant.dateOfBirth.toString(),
                            relationship = dependant.relationship,
                            medicalAidNumber = dependant.medicalAidNumber.orEmpty(),
                            allergies = dependant.allergies.orEmpty()
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load dependant $dependantId", throwable)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message?.asUiText() ?: uiText(R.string.err_load_profile)
                        )
                    }
                }
        }
    }

    fun onFirstName(value: String) = _uiState.update { it.copy(firstName = value) }
    fun onLastName(value: String) = _uiState.update { it.copy(lastName = value) }
    fun onDateOfBirth(value: String) = _uiState.update { it.copy(dateOfBirth = value) }
    fun onRelationship(value: String) = _uiState.update { it.copy(relationship = value) }
    fun onMedicalAidNumber(value: String) = _uiState.update { it.copy(medicalAidNumber = value) }
    fun onAllergies(value: String) = _uiState.update { it.copy(allergies = value) }

    fun save() {
        val state = _uiState.value

        if (state.isSaving) return

        if (state.firstName.isBlank() || state.lastName.isBlank()) {
            _uiState.update { it.copy(error = uiText(R.string.validate_name_required)) }
            return
        }

        val dateOfBirth = runCatching { LocalDate.parse(state.dateOfBirth.trim()) }.getOrNull()

        if (dateOfBirth == null) {
            _uiState.update {
                it.copy(error = uiText(R.string.validate_dob_format))
            }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            repository.update(
                dependantId,
                UpdateDependantRequest(
                    firstName = state.firstName.trim(),
                    lastName = state.lastName.trim(),
                    dateOfBirth = dateOfBirth,
                    relationship = state.relationship,

                    // Empty strings rather than nulls: clearing a medical aid number or an
                    // allergy note is a real edit, and null would mean "leave it alone".
                    medicalAidNumber = state.medicalAidNumber.trim(),
                    allergies = state.allergies.trim()
                )
            )
                .onSuccess { updated ->
                    Log.i(TAG, "Dependant ${updated.dependantId} updated")
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not update dependant $dependantId", throwable)
                    val apiError = throwable as? ApiException

                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = throwable.message?.asUiText() ?: uiText(R.string.err_save),
                            fieldErrors = apiError?.fieldErrors.orEmpty()
                        )
                    }
                }
        }
    }

    companion object {
        private const val TAG = "DependantEditViewModel"

        fun factory(container: AppContainer, dependantId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DependantEditViewModel(
                        repository = container.dependantRepository,
                        dependantId = dependantId
                    ) as T
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DependantEditScreen(
    state: DependantEditUiState,
    relationships: List<String>,
    onFirstName: (String) -> Unit,
    onLastName: (String) -> Unit,
    onDateOfBirth: (String) -> Unit,
    onRelationship: (String) -> Unit,
    onMedicalAidNumber: (String) -> Unit,
    onAllergies: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        LoadingState(modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = state.firstName,
            onValueChange = onFirstName,
            label = { Text(stringResource(R.string.field_first_name)) },
            singleLine = true,
            isError = state.fieldErrors.containsKey("FirstName"),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.lastName,
            onValueChange = onLastName,
            label = { Text(stringResource(R.string.field_last_name)) },
            singleLine = true,
            isError = state.fieldErrors.containsKey("LastName"),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.dateOfBirth,
            onValueChange = onDateOfBirth,
            label = { Text(stringResource(R.string.field_date_of_birth)) },
            placeholder = { Text(stringResource(R.string.field_dob_placeholder)) },
            supportingText = { Text(stringResource(R.string.field_dob_hint)) },
            singleLine = true,
            isError = state.fieldErrors.containsKey("DateOfBirth"),
            modifier = Modifier.fillMaxWidth()
        )

        Column {
            Text(
                text = stringResource(R.string.field_relationship),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                relationships.forEach { option ->
                    FilterChip(
                        selected = option == state.relationship,
                        onClick = { onRelationship(option) },
                        label = { Text(relationshipLabel(option)) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.medicalAidNumber,
            onValueChange = onMedicalAidNumber,
            label = { Text(stringResource(R.string.field_medical_aid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.allergies,
            onValueChange = onAllergies,
            label = { Text(stringResource(R.string.field_allergies)) },
            placeholder = { Text(stringResource(R.string.field_allergies_placeholder)) },
            supportingText = { Text(stringResource(R.string.field_allergies_hint)) },
            modifier = Modifier.fillMaxWidth()
        )

        state.error?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = message.resolve(),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.action_save_changes), fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
