// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. State and Jetpack Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/state [Accessed 16 September 2026].
//   Google. 2024. Material 3 Switch. [Online]. Available at:
//    https://m3.material.io/components/switch/overview [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.13 (Screen 13 — Settings).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.SectionHeader
import za.co.techies.healthtracker.ui.components.SettingRow
import za.co.techies.healthtracker.ui.components.resolve

/**
 * Settings — screen 13 of the design document (section 4.13), satisfying Requirement 3.
 *
 * Laid out as the mockup does: an account header, then grouped rows with the label on the left
 * and the value or control on the right. A row carrying a choice expands in place rather than
 * pushing the user onto a sub-screen, which keeps the whole of section 4.13 on one page.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    languages: List<Pair<String, String>>,
    onLanguage: (String) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onBiometric: (Boolean) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onReminderLeadTime: (Int) -> Unit,
    onLowStockThreshold: (Int) -> Unit,
    onQuietHours: (String?, String?) -> Unit,
    onFindPharmacy: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null -> ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        else -> {
            // Which choice row is open. Held locally rather than in the ViewModel: it is
            // presentation, not state worth a round trip to the server or a process death.
            var expanded by remember { mutableStateOf<String?>(null) }

            val settings = state.settings

            val languageLabel = languages
                .firstOrNull { it.first == settings.preferredLanguage }
                ?.second
                ?: settings.preferredLanguage

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                item { AccountCard(state.displayName, state.email) }

                item { SectionHeader(stringResource(R.string.settings_preferences)) }

                item {
                    SettingsCard {
                        SettingRow(
                            label = stringResource(R.string.settings_language),
                            value = languageLabel,
                            supporting = stringResource(R.string.settings_language_detail),
                            modifier = Modifier.clickable {
                                expanded = toggle(expanded, LANGUAGE_ROW)
                            }
                        )

                        if (expanded == LANGUAGE_ROW) {
                            ChoiceRow(
                                options = languages,
                                selected = settings.preferredLanguage,
                                enabled = !state.isSaving,
                                onSelect = onLanguage
                            )
                        }

                        HorizontalDivider()

                        SettingRow(
                            label = stringResource(R.string.settings_dark_mode),
                            value = "",
                            supporting = if (settings.theme == "system") {
                                stringResource(R.string.settings_following_phone)
                            } else {
                                null
                            },
                            trailing = {
                                Switch(
                                    checked = settings.theme == "dark",
                                    enabled = !state.isSaving,
                                    onCheckedChange = onDarkTheme
                                )
                            }
                        )
                    }
                }

                item { SectionHeader(stringResource(R.string.settings_notifications)) }

                item {
                    SettingsCard {
                        SettingRow(
                            label = stringResource(R.string.settings_push),
                            value = "",
                            supporting = stringResource(R.string.settings_push_detail),
                            trailing = {
                                Switch(
                                    checked = settings.notificationsEnabled,
                                    enabled = !state.isSaving,
                                    onCheckedChange = onNotifications
                                )
                            }
                        )

                        HorizontalDivider()

                        SettingRow(
                            label = stringResource(R.string.settings_reminder_lead),
                            value = Formatting.leadTime(settings.reminderLeadTimeMinutes).resolve(),
                            modifier = Modifier.clickable {
                                expanded = toggle(expanded, LEAD_TIME_ROW)
                            }
                        )

                        if (expanded == LEAD_TIME_ROW) {
                            ChoiceRow(
                                options = leadTimeOptions(),
                                selected = settings.reminderLeadTimeMinutes,
                                enabled = !state.isSaving,
                                onSelect = onReminderLeadTime
                            )
                        }

                        HorizontalDivider()

                        val quietSet = settings.quietHoursStart != null

                        SettingRow(
                            label = stringResource(R.string.settings_quiet_hours),
                            value = "",
                            supporting = if (quietSet) {
                                stringResource(
                                    R.string.settings_quiet_hours_on,
                                    settings.quietHoursStart.orEmpty(),
                                    settings.quietHoursEnd.orEmpty()
                                )
                            } else {
                                stringResource(R.string.settings_quiet_hours_off)
                            },
                            trailing = {
                                Switch(
                                    checked = quietSet,
                                    enabled = !state.isSaving,
                                    onCheckedChange = { enabled ->
                                        // A sensible overnight default rather than a time picker:
                                        // this is the window almost everyone wants.
                                        if (enabled) onQuietHours("22:00", "06:00")
                                        else onQuietHours(null, null)
                                    }
                                )
                            }
                        )
                    }
                }

                item { SectionHeader(stringResource(R.string.settings_medication_stock)) }

                item {
                    SettingsCard {
                        SettingRow(
                            label = stringResource(R.string.settings_low_stock),
                            value = pluralStringResource(
                                R.plurals.days_count,
                                settings.lowStockThresholdDays,
                                settings.lowStockThresholdDays
                            ),
                            supporting = stringResource(R.string.settings_low_stock_detail),
                            modifier = Modifier.clickable {
                                expanded = toggle(expanded, LOW_STOCK_ROW)
                            }
                        )

                        if (expanded == LOW_STOCK_ROW) {
                            ChoiceRow(
                                options = lowStockOptions(),
                                selected = settings.lowStockThresholdDays,
                                enabled = !state.isSaving,
                                onSelect = onLowStockThreshold
                            )
                        }

                        HorizontalDivider()

                        // A second way into the pharmacy finder, and for most people the first
                        // one they will find. Its only other entry point is the refill forecast,
                        // which is itself only reachable once a dependant has a medication with an
                        // active schedule — so a caregiver who had not set all that up yet could
                        // not reach the feature at all. It sits under stock because it answers the
                        // same question the low-stock warning raises.
                        SettingRow(
                            label = stringResource(R.string.refill_find_pharmacy),
                            value = "",
                            supporting = stringResource(R.string.refill_maps_hint),
                            modifier = Modifier.clickable(onClick = onFindPharmacy)
                        )
                    }
                }

                item { SectionHeader(stringResource(R.string.settings_security)) }

                item {
                    SettingsCard {
                        SettingRow(
                            label = stringResource(R.string.settings_biometric),
                            value = "",
                            supporting = stringResource(R.string.settings_biometric_detail),
                            trailing = {
                                Switch(
                                    checked = settings.biometricEnabled,
                                    enabled = !state.isSaving,
                                    onCheckedChange = onBiometric
                                )
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(10.dp)) }

                item {
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_sign_out))
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.settings_stored_on_account),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The lead-time chips. A composable rather than a plain list because every label is a resource
 * lookup, so the options have to be built where the language is known.
 */
@Composable
private fun leadTimeOptions(): List<Pair<Int, String>> =
    listOf(15, 30, 60, 120).map { it to Formatting.leadTime(it).resolve() }

@Composable
private fun lowStockOptions(): List<Pair<Int, String>> =
    listOf(3, 7, 14, 30).map { it to pluralStringResource(R.plurals.days_count, it, it) }

private const val LANGUAGE_ROW = "language"
private const val LEAD_TIME_ROW = "lead_time"
private const val LOW_STOCK_ROW = "low_stock"

/** Collapses a row that is already open, so a second tap on the same row closes it. */
private fun toggle(current: String?, key: String): String? = if (current == key) null else key

/**
 * The chips revealed under an expanded row. Generic over the value type so the same row serves
 * language codes and threshold numbers without either of them being stringly typed.
 */
@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun AccountCard(displayName: String, email: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // An initial rather than a photograph: the API holds no profile image, and a generic
            // silhouette would say less than a letter does.
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.trim().take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    text = displayName.ifBlank { stringResource(R.string.settings_signed_in) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) { content() }
    }
}
