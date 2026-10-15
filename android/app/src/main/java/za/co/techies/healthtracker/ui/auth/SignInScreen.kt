// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Sign in Your User with Credential Manager. [Online]. Available at:
//    https://developer.android.com/identity/sign-in/credential-manager-siwg [Accessed 16 September 2026].
//   Google. 2024. Text Fields in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/text/user-input [Accessed 20 September 2026].
//   Google. 2024. Material 3 Buttons. [Online]. Available at:
//    https://m3.material.io/components/buttons/overview [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.1 (Screen 1 — Sign In).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.auth

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.locale.AppLanguage
import za.co.techies.healthtracker.ui.components.resolve

/** The navy treatment from the mockup, fixed rather than theme-derived so it reads the same in dark mode. */
private val SignInNavy = Color(0xFF0D2B45)
private val SignInMuted = Color(0xFF9DB2C4)
private val FieldLine = Color(0xFF3D5B78)

/**
 * Sign In — screen 1 of the design document (section 4.1), satisfying Requirement 1.
 *
 * Two ways in, because one is not enough. Google SSO is the fastest route for most people, but a
 * caregiver without a Google account — or on a handset where adding one is not an option — needs
 * a way to register and log in that depends on nothing but an email address. The tabs at the top
 * make the register-or-log-in choice explicit rather than hiding account creation behind a button
 * marked "sign in".
 *
 * The language row stays at the bottom: someone who reads isiZulu should be able to set that
 * before being dropped into an English interface, so the choice is captured here and pushed to
 * the server profile as soon as login succeeds.
 */
@Composable
fun SignInScreen(
    state: SignInUiState,
    onSignInWithGoogle: () -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirmPassword: (String) -> Unit,
    onDisplayName: (String) -> Unit,
    onMode: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SignInNavy)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = SignInNavy
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = SignInMuted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))

        if (!state.isFirebaseConfigured) {
            NotConfiguredNotice()
            Spacer(Modifier.height(24.dp))
        } else {
            ModeTabs(mode = state.mode, enabled = !state.isBusy, onMode = onMode)

            Spacer(Modifier.height(20.dp))

            EmailForm(
                state = state,
                onEmail = onEmail,
                onPassword = onPassword,
                onConfirmPassword = onConfirmPassword,
                onDisplayName = onDisplayName,
                onSubmit = onSubmit
            )

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = onSubmit,
                enabled = !state.isBusy,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = SignInNavy,
                    disabledContainerColor = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = SignInNavy
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (state.isRegistering) R.string.auth_create_account
                            else R.string.auth_log_in
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!state.isRegistering) {
                TextButton(onClick = onForgotPassword, enabled = !state.isBusy) {
                    Text(
                        text = stringResource(R.string.auth_forgot_password),
                        style = MaterialTheme.typography.labelMedium,
                        color = SignInMuted
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }

            OrDivider()

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onSignInWithGoogle,
                enabled = !state.isBusy,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Transparent
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, FieldLine),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = stringResource(R.string.auth_continue_with_google),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.auth_secured_by_firebase),
                style = MaterialTheme.typography.labelSmall,
                color = SignInMuted.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        state.error?.let { Notice(text = it.resolve(), isError = true) }
        state.message?.let { Notice(text = it.resolve(), isError = false) }

        Spacer(Modifier.height(28.dp))

        LanguageRow(selected = state.selectedLanguage, onSelect = onLanguage)

        Spacer(Modifier.height(32.dp))
    }
}

/** "Log in | Create account" — the choice the design asks to be visible rather than implied. */
@Composable
private fun ModeTabs(mode: AuthMode, enabled: Boolean, onMode: (AuthMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(4.dp)
    ) {
        AuthMode.entries.forEach { option ->
            val selected = option == mode

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(enabled = enabled && !selected) { onMode(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (option == AuthMode.SignIn) R.string.auth_log_in
                        else R.string.auth_create_account
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) SignInNavy else SignInMuted
                )
            }
        }
    }
}

@Composable
private fun EmailForm(
    state: SignInUiState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirmPassword: (String) -> Unit,
    onDisplayName: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.isRegistering) {
            NavyField(
                value = state.displayName,
                onValueChange = onDisplayName,
                label = stringResource(R.string.auth_full_name),
                enabled = !state.isBusy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        NavyField(
            value = state.email,
            onValueChange = onEmail,
            label = stringResource(R.string.auth_email),
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
        )

        NavyField(
            value = state.password,
            onValueChange = onPassword,
            label = stringResource(R.string.auth_password),
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (state.isRegistering) ImeAction.Next else ImeAction.Done
            ),
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(
                        text = stringResource(
                            if (passwordVisible) R.string.auth_hide else R.string.auth_show
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = SignInMuted
                    )
                }
            },
            supporting = if (state.isRegistering) {
                stringResource(R.string.auth_password_hint)
            } else {
                null
            }
        )

        if (state.isRegistering) {
            NavyField(
                value = state.confirmPassword,
                onValueChange = onConfirmPassword,
                label = stringResource(R.string.auth_confirm_password),
                enabled = !state.isBusy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                }
            )
        }
    }
}

/**
 * A text field legible on the navy field. Material's defaults assume a light surface, so the
 * label and outline would otherwise sit at near-zero contrast against this background.
 */
@Composable
private fun NavyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        supportingText = supporting?.let { { Text(it, color = SignInMuted) } },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = SignInMuted,
            cursorColor = Color.White,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = FieldLine,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = SignInMuted
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = FieldLine)

        Text(
            text = stringResource(R.string.auth_or),
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = SignInMuted
        )

        HorizontalDivider(modifier = Modifier.weight(1f), color = FieldLine)
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Spacer(Modifier.height(18.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    Color.White.copy(alpha = 0.12f)
                }
            )
            .padding(14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else Color.White
        )
    }
}

/** "English | isiZulu | Afrikaans" — the three languages Requirement 7 promises. */
@Composable
private fun LanguageRow(selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppLanguage.all.forEachIndexed { index, language ->
            Text(
                // Each language is written in its own language rather than translated, which is
                // the convention every language picker follows: someone looking for isiZulu is
                // not helped by seeing it spelled the English way.
                text = language.nativeName,
                modifier = Modifier
                    .clickable { onSelect(language.code) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (language.code == selected) FontWeight.Bold else FontWeight.Normal,
                color = if (language.code == selected) Color.White else SignInMuted
            )

            if (index < AppLanguage.all.lastIndex) {
                Text(text = "|", color = SignInMuted.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
    }
}

/**
 * Shown when google-services.json is absent. It names the fix rather than just saying sign-in is
 * unavailable, because on this project whoever sees it is usually whoever can resolve it.
 */
@Composable
private fun NotConfiguredNotice() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(20.dp)
    ) {
        Column {
            Text(
                text = stringResource(R.string.firebase_not_configured),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.firebase_not_configured_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
