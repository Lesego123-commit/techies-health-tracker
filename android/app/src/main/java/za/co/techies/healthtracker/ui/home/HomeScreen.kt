// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Lists and Grids in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/lists [Accessed 16 September 2026].
//   Google. 2024. Material 3 Cards. [Online]. Available at:
//    https://m3.material.io/components/cards/overview [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.3 (Screen 3 — Home).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.resolve
import za.co.techies.healthtracker.ui.components.SectionHeader

/**
 * Home — every dependant's doses for today, the dependant switcher and the streak card,
 * laid out as in the design document (section 4.3).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onLogDose: (DueDose, Boolean) -> Unit,
    onSelectDependant: (String) -> Unit,
    onAddDependant: () -> Unit,
    onOpenDependant: (String) -> Unit,
    onSyncNow: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null && state.doses.isEmpty() ->
            ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        !state.hasDependants -> EmptyState(
            title = stringResource(R.string.home_no_one_title),
            message = stringResource(R.string.home_no_one_body),
            actionLabel = stringResource(R.string.home_add_dependant),
            onAction = onAddDependant,
            modifier = modifier
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // Only rendered when something is actually queued. In Part 2 that is never, because
            if (state.isOffline || state.pendingSyncCount > 0) {
                item {
                    OfflineBanner(
                        isOffline = state.isOffline,
                        pendingCount = state.pendingSyncCount,
                        isSyncing = state.isSyncing,
                        onSyncNow = onSyncNow
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.home_todays_doses)) }

            if (state.doses.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.home_no_medications),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.doses, key = { it.key }) { dose ->
                DoseCard(
                    dose = dose,
                    isLogging = state.loggingKey == dose.key,
                    enabled = state.loggingKey == null,
                    onTaken = { onLogDose(dose, true) },
                    onMissed = { onLogDose(dose, false) }
                )
            }

            item { SectionHeader(stringResource(R.string.home_dependants)) }

            item {
                DependantRow(
                    dependants = state.dependants,
                    selectedId = state.selectedDependantId,
                    onSelect = onSelectDependant,
                    onOpen = onOpenDependant,
                    onAdd = onAddDependant
                )
            }

            state.adherence?.let { adherence ->
                item { SectionHeader(stringResource(R.string.home_adherence_streak)) }
                item { StreakCard(adherence.currentStreakDays, adherence.adherencePercentage) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun OfflineBanner(
    isOffline: Boolean,
    pendingCount: Int,
    isSyncing: Boolean,
    onSyncNow: () -> Unit
) {
    val accent = if (isOffline) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when {
                            isSyncing -> R.string.sync_in_progress
                            isOffline && pendingCount > 0 -> R.string.sync_offline_saved
                            isOffline -> R.string.sync_offline
                            else -> R.string.sync_waiting
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    // Nothing queued is a different sentence, not a zero case of the same one,
                    // so it stays a plain string and only the count uses a plural.
                    text = if (pendingCount == 0) {
                        stringResource(R.string.sync_none_pending)
                    } else {
                        pluralStringResource(R.plurals.sync_pending, pendingCount, pendingCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Only offered when it can actually do something: there is a queue, there is a
            // connection, and a run is not already in flight.
            if (!isOffline && pendingCount > 0) {
                if (isSyncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onSyncNow) { Text(stringResource(R.string.sync_now)) }
                }
            }
        }
    }
}

/**
 * One dose. The design shows the owning dependant on every row, because the list mixes people —
 * without it a caregiver could not tell whose 08:00 tablet they were about to confirm.
 */
@Composable
private fun DoseCard(
    dose: DueDose,
    isLogging: Boolean,
    enabled: Boolean,
    onTaken: () -> Unit,
    onMissed: () -> Unit
) {
    val overdue = Formatting.isOverdue(dose.dueAt)
    val accent = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            // The coloured spine from the mockup: readable at a glance without tinting the text.
            Box(
                Modifier
                    .width(4.dp)
                    .height(if (isLogging) 96.dp else 108.dp)
                    .background(accent)
            )

            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            // dosageDisplay arrives pre-formatted from the API, e.g. "500 mg".
                            text = "${dose.medication.name} ${dose.medication.dosageDisplay}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "${dose.ownerLabel} · ${Formatting.time(dose.dueAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = Formatting.relativeDose(dose.dueAt).resolve(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (overdue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.home_doses_left,
                            dose.medication.remainingQuantity
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dose.medication.remainingQuantity <= 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))

                if (isLogging) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onTaken, enabled = enabled) {
                            Text(
                                text = stringResource(R.string.home_mark_taken),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        TextButton(onClick = onMissed, enabled = enabled) {
                            Text(stringResource(R.string.home_missed), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The avatar strip from the mockup: initials per dependant plus an add button, so switching who
 * the streak card refers to never requires leaving Home.
 */
@Composable
private fun DependantRow(
    dependants: List<DependantDto>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        dependants.take(5).forEach { dependant ->
            val selected = dependant.dependantId == selectedId

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(dependant.dependantId) }
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${dependant.firstName.take(1)}${dependant.lastName.take(1)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = dependant.firstName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = { onOpen(dependant.dependantId) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(stringResource(R.string.home_open), fontSize = 11.sp)
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onAdd)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.home_add),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StreakCard(streakDays: Int, percentage: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
        )
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp))
                    .background(MaterialTheme.colorScheme.secondary)
            )

            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.home_streak_badge, Formatting.streak(streakDays).resolve()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.home_adherence_window,
                        Formatting.percentage(percentage)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
