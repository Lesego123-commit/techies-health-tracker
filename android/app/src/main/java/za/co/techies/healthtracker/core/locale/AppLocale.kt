// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Localize Your App. [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/localization [Accessed 25 September 2026].
//   Google. 2024. Per-app Language Preferences. [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/app-languages
//    [Accessed 25 September 2026].
//   Google. 2024. CompositionLocal. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/compositionlocal
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.7 (Requirement 7:
//    Multi-language support).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.locale

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The languages the app ships (Requirement 7).
 *
 * English plus two official South African languages, which is what the brief asks for. The codes
 * match what the API stores and validates, so the same value drives the app's resources and the
 * language the server writes its notification text in — one preference, not two that can drift.
 */
enum class AppLanguage(val code: String, val englishName: String, val nativeName: String) {
    English("en", "English", "English"),
    Zulu("zu", "isiZulu", "isiZulu"),
    Afrikaans("af", "Afrikaans", "Afrikaans");

    companion object {
        val all = entries.toList()

        /**
         * Resolves a stored code, falling back to English rather than throwing.
         *
         * The preference comes from the server and travels between devices, so a build that has
         * not shipped a language yet can still receive its code. Falling back shows the app in
         * English instead of crashing on a locale nobody has translated.
         */
        fun from(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: English
    }
}

/**
 * Renders [content] in the caregiver's chosen language.
 *
 * This overrides the Compose context rather than calling
 * `AppCompatDelegate.setApplicationLocales`, for two reasons. The AppCompat route recreates every
 * activity on change, which on this single-activity app means tearing down the whole navigation
 * stack to switch a label. And it stores the choice in its own per-app preference, giving a
 * second source of truth alongside the one already stored on the account — the two would disagree
 * the moment the user signed in on a second device.
 *
 * Providing both the context and the configuration matters: `stringResource` reads resources from
 * the context, but recomposes on the configuration, so overriding only one gives text that
 * changes language only after something else happens to redraw the screen.
 *
 * **The override has to stay an Activity context**, which is why this wraps rather than calling
 * `createConfigurationContext`. That method returns a fresh `ContextImpl` with no link back to the
 * Activity, and a surprising amount of Android resolves things by walking up from the context to
 * find one. Two failures came from exactly that, and neither looked like a language problem:
 *
 *  - `rememberLauncherForActivityResult` crashed with "No ActivityResultRegistryOwner was
 *    provided", taking down any screen that asks for a runtime permission.
 *  - `startActivity` threw "Calling startActivity() from outside of an Activity context requires
 *    the FLAG_ACTIVITY_NEW_TASK flag", so opening a map or the dialler killed the app.
 *
 * A [ContextThemeWrapper] keeps the Activity as its base, so both of those resolve normally, and
 * `applyOverrideConfiguration` gives it the localised resources. The flag would have papered over
 * the second failure alone while leaving the first, and every future one, in place.
 */
@Composable
fun LocalizedApp(languageCode: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val language = AppLanguage.from(languageCode)

    val localizedContext = remember(language, context) {
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }

        // Theme 0 means "inherit from the base", so this changes the language and nothing else.
        // applyOverrideConfiguration throws once resources have been read, which is safe here:
        // remember hands out a fresh wrapper whenever the language changes.
        ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(configuration) }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration
    ) {
        content()
    }
}
