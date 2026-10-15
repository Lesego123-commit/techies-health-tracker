// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Show a Biometric Authentication Dialog. [Online]. Available at:
//    https://developer.android.com/identity/sign-in/biometric-auth [Accessed 25 September 2026].
//   Google. 2024. Lifecycle-aware Components in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/side-effects [Accessed 25 September 2026].
//   Google. 2024. Material 3 Buttons. [Online]. Available at:
//    https://m3.material.io/components/buttons/overview [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.2 (Requirement 2:
//    Biometric authentication).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.auth.BiometricAvailability

/** The same navy as the Sign In screen, so locked and signed-out read as one family. */
private val LockNavy = Color(0xFF0D2B45)
private val LockMuted = Color(0xFF9DB2C4)

/**
 * The lock screen shown in front of the app when biometric unlock is on (Requirement 2).
 *
 * It deliberately shows nothing about the caregiver or their dependants — no name, no next dose,
 * no notification count. A lock screen that leaks what it is protecting is decoration, and on a
 * medication app the thing being protected is health information about someone else.
 *
 * The prompt is raised automatically on arrival; this screen is what stands behind it, and what
 * the user lands on if they dismiss it.
 */
@Composable
fun LockScreen(
    state: LockUiState,
    onUnlock: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LockNavy),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = when (state.availability) {
                    BiometricAvailability.NotEnrolled ->
                        stringResource(R.string.lock_not_enrolled)

                    BiometricAvailability.TemporarilyUnavailable ->
                        stringResource(R.string.lock_unavailable)

                    else ->
                        stringResource(R.string.lock_verify)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LockMuted,
                textAlign = TextAlign.Center
            )

            state.error?.let { message ->
                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(14.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            if (state.availability == BiometricAvailability.NotEnrolled) {
                // Sending them to the right settings screen is the difference between a fixable
                // problem and a dead end, since the app cannot enrol a fingerprint itself.
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        }
                    },
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = LockNavy
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(stringResource(R.string.lock_open_settings), fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onUnlock,
                    enabled = !state.isPrompting,
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = LockNavy,
                        disabledContainerColor = Color.White.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(stringResource(R.string.lock_unlock), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(8.dp))

            // The way out for someone who cannot unlock on this phone at all. Signing out clears
            // the Firebase session, so the next person gets the sign-in screen rather than a
            // locked door with somebody else's data behind it.
            TextButton(onClick = onSignOut) {
                Text(
                    text = stringResource(R.string.lock_sign_out_instead),
                    style = MaterialTheme.typography.labelMedium,
                    color = LockMuted
                )
            }
        }
    }
}

/** Everything the lock screen renders from. */
data class LockUiState(
    val isPrompting: Boolean = false,
    val availability: BiometricAvailability = BiometricAvailability.Available,
    val error: String? = null
)
