// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel
//    [Accessed 16 September 2026].
//   Google. 2024. Common Intents — Maps. [Online]. Available at:
//    https://developer.android.com/guide/components/intents-common#Maps [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.8 (Screen 8 — Refill Alerts).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.refill

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import za.co.techies.healthtracker.data.remote.dto.RefillForecastDto
import za.co.techies.healthtracker.data.repository.RefillSeverity
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.SectionHeader

/**
 * Refill and low-stock alerts (section 4.8, Additional Feature 4).
 */
@Composable
fun RefillScreen(
    state: RefillUiState,
    onFindPharmacy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null && state.forecasts.isEmpty() ->
            ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        !state.hasDependants -> EmptyState(
            title = stringResource(R.string.refill_no_dependant_title),
            message = stringResource(R.string.refill_no_dependant_body),
            modifier = modifier
        )

        state.forecasts.isEmpty() -> EmptyState(
            title = stringResource(R.string.refill_empty_title),
            message = stringResource(R.string.refill_empty_body),
            modifier = modifier
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            item {
                Text(
                    text = if (state.alertCount == 0) {
                        stringResource(R.string.refill_all_ok)
                    } else {
                        pluralStringResource(
                            R.plurals.refill_alerts,
                            state.alertCount,
                            state.alertCount
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.alertCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            items(state.forecasts, key = { it.medicationId }) { forecast ->
                ForecastCard(forecast)
            }

            item { SectionHeader(stringResource(R.string.refill_nearest_pharmacy)) }

            item { PharmacyCard(onFindPharmacy) }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ForecastCard(forecast: RefillForecastDto) {
    // The severity band is decided by the API against the caregiver's own configured threshold,
    // so the colour here always agrees with the alert the server would raise.
    val accent = when (forecast.severity) {
        RefillSeverity.DEPLETED -> MaterialTheme.colorScheme.error
        RefillSeverity.LOW -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (forecast.refillAlertTriggered) {
                accent.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(Modifier.fillMaxWidth()) {
            // A colour stripe rather than a coloured card: it reads at a glance when scanning a
            // list, without making the text harder to read.
            Box(
                Modifier
                    .width(6.dp)
                    .height(112.dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    .background(accent)
            )

            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = forecast.medicationName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = stringResource(R.string.home_doses_left, forecast.remainingQuantity),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = forecast.daysUntilRunOut?.let { "$it" } ?: "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                        Text(
                            text = stringResource(
                                if (forecast.daysUntilRunOut != null) {
                                    R.string.refill_days_left
                                } else {
                                    R.string.refill_no_schedule_short
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (forecast.estimatedRunOutDate != null) {
                    Text(
                        text = stringResource(
                            R.string.refill_runs_out,
                            Formatting.date(forecast.estimatedRunOutDate)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text = stringResource(
                            R.string.refill_usage,
                            forecast.dailyConsumptionRate.toString()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Null means nothing is being consumed, so there is genuinely no countdown to
                    // draw — said plainly rather than shown as zero days, which would read as
                    // urgent when it is the opposite.
                    Text(
                        text = stringResource(R.string.refill_no_schedule_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (forecast.refillAlertTriggered) {
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = when (forecast.severity) {
                            RefillSeverity.DEPLETED -> stringResource(R.string.refill_out_of_stock)
                            else -> stringResource(
                                R.string.refill_below_threshold,
                                forecast.lowStockThresholdDays
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * The "Find a pharmacy nearby" shortcut from mockup 4.8.
 *
 * Through Part 2 this handed straight off to a maps app with a `geo:0,0?q=pharmacy` query, which
 * left the maps app to guess where the caregiver was. It now opens the pharmacy finder, which
 * searches a chosen radius around an actual position fix through the API's GeoApify integration
 * (Additional Feature 1) — the card itself did not have to change, which is what the hand-off was
 * shaped to allow.
 */
@Composable
private fun PharmacyCard(onFindPharmacy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.refill_repeat_prescription),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = stringResource(R.string.refill_maps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = onFindPharmacy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.refill_find_pharmacy))
            }
        }
    }
}
