// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. Text Fields in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/text/user-input [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 4.6 and 4.7
//    (Screens 6 and 7 — Add Medication and Edit Medication).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.dosageUnitLabel
import za.co.techies.healthtracker.ui.components.medicationFormLabel
import za.co.techies.healthtracker.ui.components.resolve

/**
 * Add or edit a medication (sections 4.6 and 4.7).
 *
 * One composable serves both, because the mockups differ in exactly two ways: editing shows the
 * remaining stock, and it shows the dose schedule as a read-only summary rather than as fields.
 */
@Composable
fun MedicationFormScreen(
    state: MedicationFormUiState,
    dosageUnits: List<String>,
    forms: List<String>,
    onName: (String) -> Unit,
    onDosageAmount: (String) -> Unit,
    onDosageUnit: (String) -> Unit,
    onForm: (String) -> Unit,
    onTotalQuantity: (String) -> Unit,
    onRemainingQuantity: (String) -> Unit,
    onInstructions: (String) -> Unit,
    onFrequency: (Frequency) -> Unit,
    onTime: (Int, String) -> Unit,
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
            value = state.name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.med_name)) },
            placeholder = { Text(stringResource(R.string.med_name_placeholder)) },
            singleLine = true,
            isError = state.fieldErrors.containsKey("Name"),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.dosageAmount,
                onValueChange = onDosageAmount,
                label = { Text(stringResource(R.string.med_dose)) },
                placeholder = { Text(stringResource(R.string.med_dose_placeholder)) },
                singleLine = true,
                isError = state.fieldErrors.containsKey("DosageAmount"),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = state.totalQuantity,
                onValueChange = onTotalQuantity,
                label = { Text(stringResource(R.string.med_pack_size)) },
                placeholder = { Text(stringResource(R.string.med_pack_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.fieldErrors.containsKey("TotalQuantity"),
                modifier = Modifier.weight(1f)
            )
        }

        if (state.isEditing) {
            // Only offered when editing. On a new prescription the pack is full, and the API
            // seeds the remaining count itself — asking would invite a typo on day one.
            OutlinedTextField(
                value = state.remainingQuantity,
                onValueChange = onRemainingQuantity,
                label = { Text(stringResource(R.string.med_remaining)) },
                supportingText = { Text(stringResource(R.string.med_remaining_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.fieldErrors.containsKey("RemainingQuantity"),
                modifier = Modifier.fillMaxWidth()
            )
        }

        ChipGroup(
            label = stringResource(R.string.med_unit),
            options = dosageUnits,
            selected = state.dosageUnit,
            onSelect = onDosageUnit,
            optionLabel = { dosageUnitLabel(it) }
        )

        ChipGroup(
            label = stringResource(R.string.med_form),
            options = forms,
            selected = state.form,
            onSelect = onForm,
            optionLabel = { medicationFormLabel(it) }
        )

        Text(
            text = stringResource(R.string.med_schedule),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (state.isEditing) {
            ScheduleSummary(state)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Frequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = state.frequency == frequency,
                        onClick = { onFrequency(frequency) },
                        label = { Text(stringResource(frequency.labelRes)) }
                    )
                }
            }

            // One field per required time. The count follows the frequency, because the API
            // rejects a schedule whose times do not match it.
            state.times.forEachIndexed { index, time ->
                OutlinedTextField(
                    value = time,
                    onValueChange = { onTime(index, it) },
                    label = { Text(stringResource(R.string.med_dose_time, index + 1)) },
                    placeholder = { Text(stringResource(R.string.med_time_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = stringResource(R.string.med_times_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = state.instructions,
            onValueChange = onInstructions,
            label = { Text(stringResource(R.string.med_instructions)) },
            placeholder = { Text(stringResource(R.string.med_instructions_placeholder)) },
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
                Text(
                    stringResource(
                        if (state.isEditing) R.string.action_save_changes else R.string.med_save
                    )
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The schedule as it stands, read-only.
 *
 * Adherence is measured against the schedule that was in force when each dose was due, so the API
 * deliberately offers no way to rewrite one — editing it would silently re-score history that has
 * already been recorded. Saying that here is better than showing fields that would be ignored.
 */
@Composable
private fun ScheduleSummary(state: MedicationFormUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(state.frequency.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = state.times.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.med_schedule_locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Flowed rather than a single row: seven dosage units will not fit across a handset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    optionLabel: @Composable (String) -> String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(6.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) }
                )
            }
        }
    }
}
