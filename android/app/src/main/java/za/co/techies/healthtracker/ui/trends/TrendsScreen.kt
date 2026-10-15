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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.data.remote.dto.AdherenceTrendPointDto
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.doseStatusLabel

/**
 * Trends and adherence (section 4.7, Additional Feature 3).
 */
@Composable
fun TrendsScreen(
    state: TrendsUiState,
    onRangeChange: (TrendRange) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading && state.adherence == null -> LoadingState(modifier)

        state.error != null && state.adherence == null ->
            ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        !state.hasDependants -> EmptyState(
            title = stringResource(R.string.trends_no_dependant_title),
            message = stringResource(R.string.trends_no_dependant_body),
            modifier = modifier
        )

        state.adherence == null -> EmptyState(
            title = stringResource(R.string.trends_empty_title),
            message = stringResource(R.string.trends_empty_body),
            modifier = modifier
        )

        else -> {
            val adherence = state.adherence

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrendRange.entries.forEach { range ->
                            FilterChip(
                                selected = range == state.range,
                                onClick = { onRangeChange(range) },
                                label = {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.days_count,
                                            range.days.toInt(),
                                            range.days.toInt()
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    SummaryCard(
                        percentage = adherence.adherencePercentage,
                        taken = adherence.dosesTaken,
                        missed = adherence.dosesMissed,
                        scheduled = adherence.totalScheduledDoses,
                        streakDays = adherence.currentStreakDays
                    )
                }

                // Only shown when the API decided there is a genuine pattern. It returns null
                // below three missed doses rather than inventing an insight from noise.
                adherence.patternInsight?.let { insight ->
                    item { InsightCard(insight) }
                }

                if (adherence.trends.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.trends_daily_breakdown),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item { AdherenceChart(adherence.trends) }
                }

                if (state.history.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.trends_recent_doses),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    items(state.history.take(30), key = { it.doseLogId }) { log ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(log.medicationName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = Formatting.dateTime(log.scheduledAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = doseStatusLabel(log.status),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (log.status.equals("taken", true)) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    percentage: Double,
    taken: Int,
    missed: Int,
    scheduled: Int,
    streakDays: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = Formatting.percentage(percentage),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(R.string.trends_adherence),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Stat(stringResource(R.string.trends_stat_taken), taken.toString())
                Stat(stringResource(R.string.trends_stat_missed), missed.toString())
                Stat(stringResource(R.string.trends_stat_scheduled), scheduled.toString())
                Stat(stringResource(R.string.trends_stat_streak), streakDays.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InsightCard(insight: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.trends_pattern),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(4.dp))
            Text(text = insight, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A simple bar chart drawn with layout primitives rather than a charting library.
 *
 * The data arrives ready to plot — one point per day including days with nothing scheduled, so
 * the axis has no gaps — and the only thing needed is a proportional height per bar. Pulling in a
 * charting dependency for that would be more version surface than it is worth.
 */
@Composable
private fun AdherenceChart(points: List<AdherenceTrendPointDto>) {
    // Cap the number of bars so a 90-day range stays legible on a phone.
    val visible = points.takeLast(30)
    val maxScheduled = visible.maxOfOrNull { it.scheduled }?.coerceAtLeast(1) ?: 1

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                visible.forEach { point ->
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val takenFraction = point.taken.toFloat() / maxScheduled
                        val missedFraction = point.missed.toFloat() / maxScheduled

                        if (missedFraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(missedFraction.coerceAtLeast(0.02f))
                                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }

                        if (takenFraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(takenFraction.coerceAtLeast(0.02f))
                                    .background(MaterialTheme.colorScheme.secondary)
                            )
                        }

                        // A day with nothing scheduled still gets a faint placeholder, so the
                        // chart reads as a continuous calendar rather than a ragged gap.
                        if (point.scheduled == 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = Formatting.shortDate(visible.firstOrNull()?.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot("Taken", MaterialTheme.colorScheme.secondary)
                    LegendDot("Missed", MaterialTheme.colorScheme.error)
                }

                Text(
                    text = Formatting.shortDate(visible.lastOrNull()?.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colour)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
