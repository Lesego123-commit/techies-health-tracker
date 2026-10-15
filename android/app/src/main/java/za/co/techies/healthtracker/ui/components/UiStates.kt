// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Compose Layout Basics. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/layouts/basics [Accessed 16 September 2026].
//   Oracle. 2024. java.time.format.DateTimeFormatter. [Online]. Available at:
//    https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html
//    [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.network.ApiException

/**
 * The three states every data-backed screen shares: loading, failed, and nothing to show.
 *
 * Keeping them here rather than repeating a Box-with-a-spinner in eight screens means an
 * improvement to any of them lands everywhere, and — more usefully — that error handling is
 * consistent. A caregiver gets the same wording and the same retry affordance wherever a call
 * fails, instead of a different treatment per screen.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Renders a failed call.
 *
 * The message comes straight from [ApiException], which the API writes to be read by a user. Only
 * genuinely retryable failures get a retry button: offering one for a validation error or a
 * permission refusal invites the user to hammer a request that cannot start working.
 */
@Composable
fun ErrorState(
    error: Throwable?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val apiError = error as? ApiException
    val retryable = apiError?.isRetryable ?: true

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.state_error_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                // The client\'s own failures name a resource, so they follow the chosen
                // language; a message the API wrote is shown exactly as it arrived, because the
                // server already localised it from the same stored preference.
                text = apiError?.messageRes?.let { stringResource(it) }
                    ?: error?.message
                    ?: stringResource(R.string.state_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (apiError?.traceId != null) {
                Spacer(Modifier.height(8.dp))

                // Correlates with the server log. Only worth showing for a server-side failure,
                // where it is the difference between a vague report and a findable one.
                Text(
                    text = stringResource(R.string.state_error_reference, apiError.traceId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onRetry != null && retryable) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.state_retry)) }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
        }
    }
}
