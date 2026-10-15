// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 16 September 2026].
//   Google. 2024. Lists and Grids in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/lists [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.12 (Screen 12 — Notifications).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.notifications

import android.util.Log
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.data.remote.dto.NotificationDto
import za.co.techies.healthtracker.data.repository.NotificationRepository
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.ui.components.ErrorState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState

data class NotificationsUiState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val notifications: List<NotificationDto> = emptyList()
)

/**
 * Notifications — screen 12 of the design document (section 4.12).
 *
 * Reached from the bell in the app bar rather than the bottom navigation, as the design
 * specifies. The list reads straight off the API's NotificationEvents table, so what the
 * caregiver sees here and what the server decided to send can never drift apart — including
 * events held back by quiet hours, which are recorded but not yet delivered.
 */
class NotificationsViewModel(
    private val repository: NotificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = it.notifications.isEmpty(), error = null) }

        viewModelScope.launch {
            repository.list()
                .onSuccess { notifications ->
                    _uiState.update { it.copy(isLoading = false, notifications = notifications) }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load notifications", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable) }
                }
        }
    }

    companion object {
        private const val TAG = "NotificationsViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotificationsViewModel(container.notificationRepository) as T
            }
    }
}

@Composable
fun NotificationsScreen(
    state: NotificationsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.error != null && state.notifications.isEmpty() ->
            ErrorState(state.error, onRetry = onRetry, modifier = modifier)

        state.notifications.isEmpty() -> EmptyState(
            title = stringResource(R.string.notif_empty_title),
            message = stringResource(R.string.notif_empty_body),
            modifier = modifier
        )

        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            items(state.notifications, key = { it.notificationId }) { notification ->
                NotificationCard(notification)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NotificationCard(notification: NotificationDto) {
    // Colour by category, as the mockup does: a missed dose should not look like a reminder.
    val accent: Color = when (notification.category) {
        "missed_dose" -> MaterialTheme.colorScheme.error
        "refill_alert" -> MaterialTheme.colorScheme.tertiary
        "appointment_reminder" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .background(accent)
            )

            Column(Modifier.padding(14.dp)) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = notification.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    // Being explicit beats silence: an event recorded but not delivered is
                    // usually quiet hours doing its job, not a failure.
                    text = if (notification.isSent) {
                        Formatting.dateTime(notification.createdAt)
                    } else {
                        stringResource(
                            R.string.notif_not_delivered,
                            Formatting.dateTime(notification.createdAt)
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
