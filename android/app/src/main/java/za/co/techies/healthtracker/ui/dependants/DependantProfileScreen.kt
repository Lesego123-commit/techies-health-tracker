// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Material 3 Floating Action Button. [Online]. Available at:
//    https://m3.material.io/components/floating-action-button/overview [Accessed 16 September 2026].
//   Google. 2024. Lists and Grids in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/lists [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.5 (Screen 5 — Dependant Profile).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.dependants

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.data.remote.dto.AppointmentDto
import za.co.techies.healthtracker.data.remote.dto.DocumentDto
import za.co.techies.healthtracker.data.remote.dto.MedicationDto
import za.co.techies.healthtracker.data.remote.dto.RefillForecastDto
import za.co.techies.healthtracker.data.repository.RefillSeverity
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.SectionHeader
import za.co.techies.healthtracker.ui.components.bulleted
import za.co.techies.healthtracker.ui.components.medicationFormLabel
import za.co.techies.healthtracker.ui.components.relationshipLabel
import za.co.techies.healthtracker.ui.components.StatusChip

/**
 * Dependant Profile — one person's medications, next appointment and documents in one place
 * (section 4.5). The FAB adds a medication; "Edit" opens their own profile form.
 */
@Composable
fun DependantProfileScreen(
    state: DependantProfileUiState,
    onAddMedication: () -> Unit,
    onEditMedication: (String) -> Unit,
    onEditDependant: () -> Unit,
    onOpenRefillForecast: () -> Unit,
    onOpenDocuments: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null -> ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        state.dependant == null -> ErrorState(null, onRetry = onRetry, modifier = modifier)

        else -> {
            val dependant = state.dependant

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    // The identity strip from the mockup: relationship, date of birth and
                    // medical aid, which is what a caregiver is asked for at a clinic desk.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // Assembled from whole phrases joined by punctuation rather than
                            // glued together word by word, so a translator can reorder each part
                            // without the separators ending up in the wrong place.
                            text = bulleted(
                                relationshipLabel(dependant.relationship),
                                stringResource(R.string.profile_age, dependant.age),
                                stringResource(
                                    R.string.profile_dob,
                                    Formatting.date(dependant.dateOfBirth)
                                ),
                                dependant.medicalAidNumber?.let {
                                    stringResource(R.string.profile_medical_aid, it)
                                }
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TextButton(onClick = onEditDependant) { Text(stringResource(R.string.action_edit)) }
                    }
                }

                dependant.allergies?.takeIf { it.isNotBlank() }?.let { allergies ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    text = stringResource(R.string.profile_allergies),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(text = allergies, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                item { SectionHeader(stringResource(R.string.profile_active_medications)) }

                if (state.medications.none { it.isActive }) {
                    item { PlaceholderCard(stringResource(R.string.profile_no_medications)) }
                }

                items(state.medications.filter { it.isActive }, key = { it.medicationId }) { medication ->
                    MedicationCard(
                        medication = medication,
                        forecast = state.forecasts[medication.medicationId],
                        onClick = { onEditMedication(medication.medicationId) }
                    )
                }

                if (state.medications.any { it.isActive }) {
                    item {
                        OutlinedButton(onClick = onOpenRefillForecast, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.profile_view_refill))
                        }
                    }
                }

                item { SectionHeader(stringResource(R.string.profile_appointments)) }

                item {
                    state.nextAppointment?.let { AppointmentPreview(it) }
                        ?: PlaceholderCard(stringResource(R.string.profile_no_appointments))
                }

                item { SectionHeader(stringResource(R.string.profile_documents)) }

                if (state.documents.isEmpty()) {
                    item {
                        PlaceholderCard(stringResource(R.string.profile_no_documents))
                    }
                }

                // Five, then a way through to the rest. The profile is a summary of one person;
                // scrolling forty prescriptions here would bury the medications above them.
                items(state.documents.take(5), key = { it.documentId }) { document ->
                    DocumentRow(document)
                }

                item {
                    OutlinedButton(
                        onClick = onOpenDocuments,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.profile_manage_documents))
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun MedicationCard(
    medication: MedicationDto,
    forecast: RefillForecastDto?,
    onClick: () -> Unit
) {
    // The badge mirrors the API's own severity band rather than a threshold decided here, so the
    // card cannot contradict the refill alert the server would raise.
    val (chipLabel, chipColour) = when (forecast?.severity) {
        RefillSeverity.DEPLETED ->
            stringResource(R.string.stock_out) to MaterialTheme.colorScheme.error

        RefillSeverity.LOW ->
            stringResource(R.string.stock_low) to MaterialTheme.colorScheme.tertiary

        RefillSeverity.OK ->
            stringResource(R.string.stock_active) to MaterialTheme.colorScheme.secondary
        else -> null to MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(80.dp)
                    .background(chipColour)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${medication.name} ${medication.dosageDisplay}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = bulleted(
                            medicationFormLabel(medication.form),
                            medication.schedules.firstOrNull()?.let {
                                pluralStringResource(
                                    R.plurals.profile_times_daily,
                                    it.times.size,
                                    it.times.size
                                )
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = stringResource(
                            R.string.profile_remaining,
                            medication.remainingQuantity,
                            medication.totalQuantity
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                chipLabel?.let { StatusChip(it, chipColour) }
            }
        }
    }
}

@Composable
private fun AppointmentPreview(appointment: AppointmentDto) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = appointment.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = Formatting.dateTime(appointment.appointmentDateTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            appointment.location?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DocumentRow(document: DocumentDto) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(text = document.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(
                    R.string.profile_document_size,
                    document.documentType.replace('_', ' '),
                    document.fileSizeBytes / 1024
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaceholderCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
