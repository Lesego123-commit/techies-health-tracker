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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.relationshipLabel
import za.co.techies.healthtracker.ui.components.resolve

/**
 * Dependants — the switcher and the add form (section 4.4, Additional Feature 5).
 */
@Composable
fun DependantsScreen(
    state: DependantsUiState,
    relationships: List<String>,
    onSelect: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenForm: () -> Unit,
    onCloseForm: () -> Unit,
    onFirstName: (String) -> Unit,
    onLastName: (String) -> Unit,
    onDateOfBirth: (String) -> Unit,
    onRelationship: (String) -> Unit,
    onAllergies: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null && state.dependants.isEmpty() ->
            ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        state.dependants.isEmpty() -> EmptyState(
            title = stringResource(R.string.dep_none_title),
            message = stringResource(R.string.dep_none_body),
            actionLabel = stringResource(R.string.dep_add),
            onAction = onOpenForm,
            modifier = modifier
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            items(state.dependants, key = { it.dependantId }) { dependant ->
                DependantCard(
                    dependant = dependant,
                    isSelected = dependant.dependantId == state.selectedId,
                    onSelect = { onSelect(dependant.dependantId) },
                    onOpen = { onOpenProfile(dependant.dependantId) }
                )
            }

            item {
                Button(onClick = onOpenForm, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dep_add_another))
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (state.isFormOpen) {
        AddDependantDialog(
            state = state,
            relationships = relationships,
            onDismiss = onCloseForm,
            onFirstName = onFirstName,
            onLastName = onLastName,
            onDateOfBirth = onDateOfBirth,
            onRelationship = onRelationship,
            onAllergies = onAllergies,
            onSave = onSave
        )
    }
}

@Composable
private fun DependantCard(
    dependant: DependantDto,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // fullName and age are both calculated by the API, so every screen agrees.
                    text = dependant.fullName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(
                        R.string.dep_relationship_and_age,
                        relationshipLabel(dependant.relationship),
                        dependant.age
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = if (dependant.activeMedicationCount == 0) {
                        stringResource(R.string.dep_no_active_medications)
                    } else {
                        pluralStringResource(
                            R.plurals.dep_active_medications,
                            dependant.activeMedicationCount,
                            dependant.activeMedicationCount
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (isSelected) {
                    AssistChip(onClick = onSelect, label = { Text(stringResource(R.string.dep_viewing)) })
                }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
            }
        }
    }
}

@Composable
private fun AddDependantDialog(
    state: DependantsUiState,
    relationships: List<String>,
    onDismiss: () -> Unit,
    onFirstName: (String) -> Unit,
    onLastName: (String) -> Unit,
    onDateOfBirth: (String) -> Unit,
    onRelationship: (String) -> Unit,
    onAllergies: (String) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text(stringResource(R.string.dep_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.firstName,
                    onValueChange = onFirstName,
                    label = { Text(stringResource(R.string.field_first_name)) },
                    singleLine = true,
                    // The API returns field-level errors keyed by property name; surfacing them
                    // on the offending input is the whole reason that map exists.
                    isError = state.fieldErrors.containsKey("FirstName"),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.lastName,
                    onValueChange = onLastName,
                    label = { Text(stringResource(R.string.field_last_name)) },
                    singleLine = true,
                    isError = state.fieldErrors.containsKey("LastName"),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.dateOfBirth,
                    onValueChange = onDateOfBirth,
                    label = { Text(stringResource(R.string.field_date_of_birth)) },
                    placeholder = { Text(stringResource(R.string.field_dob_placeholder)) },
                    singleLine = true,
                    isError = state.fieldErrors.containsKey("DateOfBirth"),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.field_relationship),
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(Modifier.height(4.dp))

                // A fixed set of chips rather than a text field: the API rejects anything outside
                // its controlled vocabulary, so there is no reason to let the value be typed.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    relationships.take(3).forEach { option ->
                        AssistChip(
                            onClick = { onRelationship(option) },
                            label = { Text(relationshipLabel(option)) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    relationships.drop(3).forEach { option ->
                        AssistChip(
                            onClick = { onRelationship(option) },
                            label = { Text(relationshipLabel(option)) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.field_selected, relationshipLabel(state.relationship)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.allergies,
                    onValueChange = onAllergies,
                    label = { Text(stringResource(R.string.field_allergies)) },
                    modifier = Modifier.fillMaxWidth()
                )

                state.formError?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !state.isSaving) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
