// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Lists and Grids in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/lists [Accessed 25 September 2026].
//   Google. 2024. Request Runtime Permissions. [Online]. Available at:
//    https://developer.android.com/training/permissions/requesting [Accessed 25 September 2026].
//   Google. 2024. Activity Result APIs in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/libraries#activity_result
//    [Accessed 25 September 2026].
//   Google. 2024. Common Intents — Maps and Phone. [Online]. Available at:
//    https://developer.android.com/guide/components/intents-common [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.10 (Screen 10 —
//    Pharmacy Finder) and section 3.8 (Additional Feature 1).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.pharmacy

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.data.remote.dto.PharmacyResultDto
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.resolve

/**
 * Pharmacy Finder — screen 10 of the design document (section 4.10), satisfying Additional
 * Feature 1.
 *
 * The list arrives sorted by distance from the API, which also works out each distance, so this
 * screen sorts nothing and calculates nothing. What it does own is everything around the list: the
 * permission conversation, the three radius chips, and handing a chosen pharmacy off to the maps
 * or phone app the caregiver already has.
 */
@Composable
fun PharmacyScreen(
    state: PharmacyUiState,
    radiusChoices: List<Int>,
    onSearch: () -> Unit,
    onRadius: (Int) -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onPermissionResult
    )

    // Coarse rather than fine: a search measured in kilometres does not need a street-level fix,
    // and asking for one would be requesting more about someone than the feature can use.
    val requestPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }

    // Runs once on arrival. It searches only when permission is already held — the first visit
    // shows what the screen is for and waits, because a location prompt fired before the user has
    // read why earns a reflexive refusal that Android then makes close to permanent.
    LaunchedEffect(Unit) {
        if (!state.hasSearched && state.blocker == null) onSearch()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        RadiusRow(
            choices = radiusChoices,
            selected = state.radiusMetres,
            enabled = !state.isLoading,
            onSelect = onRadius
        )

        Spacer(Modifier.height(8.dp))

        when {
            state.isLoading && state.results.isEmpty() -> LoadingPanel()

            state.blocker != null -> BlockerPanel(
                blocker = state.blocker,
                onRequestPermission = requestPermission,
                onOpenSettings = { context.openLocationSettings() },
                onRetry = onSearch
            )

            state.error != null -> ErrorPanel(message = state.error.resolve(), onRetry = onSearch)

            state.hasSearched && state.results.isEmpty() -> EmptyState(
                title = stringResource(R.string.pharmacy_none_title),
                message = stringResource(R.string.pharmacy_none_body),
                actionLabel = stringResource(R.string.pharmacy_search_wider),
                onAction = { onRadius(state.radiusMetres * 2) }
            )

            else -> ResultList(state = state, context = context)
        }
    }
}

@Composable
private fun RadiusRow(
    choices: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { metres ->
            FilterChip(
                selected = metres == selected,
                enabled = enabled,
                onClick = { onSelect(metres) },
                label = {
                    Text(
                        pluralStringResource(
                            R.plurals.pharmacy_radius_km,
                            metres / 1000,
                            metres / 1000
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun LoadingPanel() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))

        // Named rather than a bare spinner: finding a fix is the slow half, and saying so stops a
        // ten-second wait looking like a hung screen.
        Text(
            text = stringResource(R.string.pharmacy_locating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What to do when the search cannot start.
 *
 * Each case offers the one action that actually fixes it. Showing "Try again" for a missing
 * permission would be an invitation to press a button that cannot work.
 */
@Composable
private fun BlockerPanel(
    blocker: LocationBlocker,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    val (bodyRes, actionRes, action) = when (blocker) {
        LocationBlocker.PermissionMissing ->
            Triple(R.string.pharmacy_needs_permission, R.string.pharmacy_allow_location, onRequestPermission)

        LocationBlocker.ServicesDisabled ->
            Triple(R.string.pharmacy_services_off, R.string.pharmacy_open_settings, onOpenSettings)

        LocationBlocker.NoFix ->
            Triple(R.string.pharmacy_no_fix, R.string.state_retry, onRetry)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.pharmacy_find_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(actionRes))
        }

        Spacer(Modifier.height(8.dp))

        // Said plainly rather than buried in a privacy policy nobody opens. It is also true: the
        // coordinates go to our API for one search and are not stored against the account.
        Text(
            text = stringResource(R.string.pharmacy_privacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.state_error_title),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        Button(onClick = onRetry) { Text(stringResource(R.string.state_retry)) }
    }
}

@Composable
private fun ResultList(state: PharmacyUiState, context: Context) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                text = pluralStringResource(
                    R.plurals.pharmacy_found,
                    state.results.size,
                    state.results.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Coordinates rather than an index, so a re-search that returns the same pharmacies
        // reuses their rows instead of rebuilding the list.
        items(state.results, key = { "${it.lat},${it.lon},${it.name}" }) { pharmacy ->
            PharmacyCard(
                pharmacy = pharmacy,
                onDirections = { context.openDirections(pharmacy) },
                onCall = pharmacy.phone?.let { { context.dial(it) } }
            )
        }

        if (state.fromCache) {
            item {
                // Worth saying: a pharmacy that closed down this morning can still be in a
                // 24-hour cache, and the caregiver should know the list is not live.
                Text(
                    text = stringResource(R.string.pharmacy_from_cache),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PharmacyCard(
    pharmacy: PharmacyResultDto,
    onDirections: () -> Unit,
    onCall: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = pharmacy.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = Formatting.distance(pharmacy.distanceKm).resolve(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (pharmacy.formattedAddress.isNotBlank()) {
                Text(
                    text = pharmacy.formattedAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Both are optional upstream: GeoApify has them for some places and not others, and a
            // row that renders "null" is worse than one that renders nothing.
            pharmacy.openingHours?.takeIf { it.isNotBlank() }?.let { hours ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hours,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onDirections) {
                    Text(stringResource(R.string.pharmacy_directions))
                }

                if (onCall != null) {
                    TextButton(onClick = onCall) { Text(stringResource(R.string.pharmacy_call)) }
                }
            }
        }
    }
}

/**
 * Opens the phone's own maps application at the pharmacy.
 *
 * A `geo:` URI with a label rather than a hard-coded package, so whichever maps app the device
 * actually has answers it — and the coordinates carry the name, so the pin is identifiable rather
 * than an unlabelled dot.
 */
private fun Context.openDirections(pharmacy: PharmacyResultDto) {
    val label = Uri.encode(pharmacy.name)
    val uri = Uri.parse("geo:${pharmacy.lat},${pharmacy.lon}?q=${pharmacy.lat},${pharmacy.lon}($label)")

    start(Intent(Intent.ACTION_VIEW, uri), R.string.pharmacy_no_maps_app)
}

/**
 * Opens the dialler with the number filled in.
 *
 * ACTION_DIAL, not ACTION_CALL: dialling needs no permission and leaves the decision to press the
 * green button with the caregiver, which is the right default for a number the app found rather
 * than one they typed.
 */
private fun Context.dial(phone: String) {
    start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")), R.string.pharmacy_no_phone_app)
}

private fun Context.openLocationSettings() {
    start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), R.string.pharmacy_no_settings_app)
}

/** Starts an intent, saying so plainly when the device has nothing that can handle it. */
private fun Context.start(intent: Intent, fallbackMessage: Int) {
    try {
        startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Toast.makeText(this, getString(fallbackMessage), Toast.LENGTH_SHORT).show()
    }
}
