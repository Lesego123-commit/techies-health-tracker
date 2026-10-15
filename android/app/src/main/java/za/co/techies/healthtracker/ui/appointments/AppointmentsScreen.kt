// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Material 3 Dialogs. [Online]. Available at:
//    https://m3.material.io/components/dialogs/overview [Accessed 16 September 2026].
//   Google. 2024. Lists and Grids in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/lists [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.9 (Screen 9 — Appointments).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.appointments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.data.remote.dto.AppointmentDto
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.SectionHeader
import za.co.techies.healthtracker.ui.components.resolve

/**
 * Appointments — upcoming and past visits for the selected dependant (section 4.9).
 */
@Composable
fun AppointmentsScreen(
    state: AppointmentsUiState,
    reminderOptions: List<Int>,
    onOpenForm: () -> Unit,
    onCloseForm: () -> Unit,
    onTitle: (String) -> Unit,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onLocation: (String) -> Unit,
    onReminder: (Int) -> Unit,
    onSave: () -> Unit,
    onCancelAppointment: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null && state.upcoming.isEmpty() && state.past.isEmpty() ->
            ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        !state.hasDependants -> EmptyState(
            title = stringResource(R.string.appt_no_dependant_title),
            message = stringResource(R.string.appt_no_dependant_body),
            modifier = modifier
        )

        state.upcoming.isEmpty() && state.past.isEmpty() -> EmptyState(
            title = stringResource(R.string.appt_none_title),
            message = stringResource(R.string.appt_none_body),
            actionLabel = stringResource(R.string.appt_add),
            onAction = onOpenForm,
            modifier = modifier
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.appt_upcoming)) }

                items(state.upcoming, key = { it.appointmentId }) { appointment ->
                    AppointmentCard(
                        appointment = appointment,
                        dependantName = state.dependantName,
                        onCancel = { onCancelAppointment(appointment.appointmentId) }
                    )
                }
            }

            if (state.past.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.appt_past)) }

                items(state.past, key = { it.appointmentId }) { appointment ->
                    AppointmentCard(
                        appointment = appointment,
                        dependantName = state.dependantName,
                        onCancel = null
                    )
                }
            }

            item {
                Button(onClick = onOpenForm, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appt_add))
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (state.isFormOpen) {
        AddAppointmentDialog(
            state = state,
            reminderOptions = reminderOptions,
            onDismiss = onCloseForm,
            onTitle = onTitle,
            onDate = onDate,
            onTime = onTime,
            onLocation = onLocation,
            onReminder = onReminder,
            onSave = onSave
        )
    }
}

@Composable
private fun AppointmentCard(
    appointment: AppointmentDto,
    dependantName: String,
    onCancel: (() -> Unit)?
) {
    val accent = when {
        appointment.isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        appointment.isUpcoming -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(if (onCancel != null) 96.dp else 72.dp)
                    .background(accent)
            )

            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = appointment.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = dependantName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    // Built from one resource with two substitutions rather than three pieces
                    // glued together: word order is not the same in all three languages, and a
                    // sentence assembled by concatenation cannot be reordered by a translator.
                    text = if (appointment.isUpcoming) {
                        stringResource(
                            R.string.appt_when_with_reminder,
                            Formatting.dateTime(appointment.appointmentDateTime),
                            Formatting.leadTime(appointment.reminderMinutesBefore).resolve()
                        )
                    } else {
                        Formatting.dateTime(appointment.appointmentDateTime)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                appointment.location?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (appointment.isCancelled) {
                    Text(
                        text = stringResource(R.string.appt_cancelled),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.appt_cancel_visit)) }
                }
            }
        }
    }
}

@Composable
private fun AddAppointmentDialog(
    state: AppointmentsUiState,
    reminderOptions: List<Int>,
    onDismiss: () -> Unit,
    onTitle: (String) -> Unit,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onLocation: (String) -> Unit,
    onReminder: (Int) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text(stringResource(R.string.appt_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitle,
                    label = { Text(stringResource(R.string.appt_title_label)) },
                    placeholder = { Text(stringResource(R.string.appt_title_placeholder)) },
                    singleLine = true,
                    isError = state.fieldErrors.containsKey("Title"),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.date,
                        onValueChange = onDate,
                        label = { Text(stringResource(R.string.appt_date_label)) },
                        placeholder = { Text(stringResource(R.string.appt_date_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )

                    OutlinedTextField(
                        value = state.time,
                        onValueChange = onTime,
                        label = { Text(stringResource(R.string.appt_time_label)) },
                        placeholder = { Text(stringResource(R.string.appt_time_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.location,
                    onValueChange = onLocation,
                    label = { Text(stringResource(R.string.appt_location_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.appt_remind_me),
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reminderOptions.take(3).forEach { minutes ->
                        FilterChip(
                            selected = state.reminderMinutes == minutes,
                            onClick = { onReminder(minutes) },
                            label = { Text(Formatting.leadTime(minutes).resolve()) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reminderOptions.drop(3).forEach { minutes ->
                        FilterChip(
                            selected = state.reminderMinutes == minutes,
                            onClick = { onReminder(minutes) },
                            label = { Text(Formatting.leadTime(minutes).resolve()) }
                        )
                    }
                }

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
            TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
