// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Material 3 Theming in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/designsystems/material3
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 2.1 (App Identity and palette).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The navy, teal and orange palette from section 2.1 of the Part 1 design document.
 *
 * Both schemes are defined from the start. The Settings screen exposes a light/dark/system
 * preference (Requirement 3), so a dark scheme is a feature here rather than a nicety, and
 * retrofitting one after the screens are built is considerably more work.
 */
private val Navy = Color(0xFF0D2B45)
private val Teal = Color(0xFF1B9AAA)
private val Orange = Color(0xFFEF7B45)

private val LightColours = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = Orange,
    onTertiary = Color.White,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE)
)

private val DarkColours = darkColorScheme(
    primary = Teal,
    onPrimary = Navy,
    secondary = Color(0xFF7FD4DE),
    onSecondary = Navy,
    tertiary = Orange,
    onTertiary = Navy,
    background = Color(0xFF101418),
    surface = Color(0xFF101418)
)

@Composable
fun TechiesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColours else LightColours,
        content = content
    )
}
