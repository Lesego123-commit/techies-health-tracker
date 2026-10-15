// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Testing your Compose layout. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/testing [Accessed 25 September 2026].
//   Google. 2024. ContextThemeWrapper. [Online]. Available at:
//    https://developer.android.com/reference/android/view/ContextThemeWrapper
//    [Accessed 25 September 2026].
//   Google. 2024. Activity Result APIs in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/libraries#activity_result
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.7 (Requirement 7 —
//    Multi-language support).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.locale

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.locale.AppLanguage
import za.co.techies.healthtracker.core.locale.LocalizedApp

/**
 * Tests for the language override.
 *
 * These exist because of two crashes that both traced back to one mistake, and neither of which
 * looked like a language bug from the stack trace. The first version of [LocalizedApp] built its
 * context with `createConfigurationContext`, which returns a fresh `ContextImpl` with no link back
 * to the Activity. A lot of Android resolves things by walking up the context chain to find one, so
 * after the override:
 *
 *  - `rememberLauncherForActivityResult` threw "No ActivityResultRegistryOwner was provided",
 *    crashing any screen that asks for a runtime permission — which is how the pharmacy finder
 *    died the first time it was opened.
 *  - `startActivity` threw "Calling startActivity() from outside of an Activity context requires
 *    the FLAG_ACTIVITY_NEW_TASK flag", so tapping Directions or Call killed the app.
 *
 * Unit tests could not have caught either: both need a real Activity and a real composition. That
 * is why these are instrumented, and why they assert the *invariant* — an Activity stays reachable
 * from the provided context — rather than the specific two symptoms. A third caller that resolves
 * something else from the context would break the same way, and this is what would catch it.
 */
@RunWith(AndroidJUnit4::class)
class LocalizedAppInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    /** Walks the base-context chain the way Android's own owner lookup does. */
    private fun Context.findActivity(): Activity? {
        var current: Context? = this

        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }

        return null
    }

    @Test
    fun theLocalisedContextStillLeadsBackToAnActivity() {
        var found: Activity? = null

        compose.setContent {
            LocalizedApp(languageCode = AppLanguage.Zulu.code) {
                found = LocalContext.current.findActivity()
            }
        }

        compose.waitForIdle()

        // The whole class of crash in one assertion. A context with no Activity behind it is the
        // shape createConfigurationContext returns, and it is what broke permissions and intents.
        assertNotNull(
            "The localised context must keep an Activity in its base chain, or every " +
                "permission launcher and startActivity call inside the app fails",
            found
        )
    }

    @Test
    fun theOwnersActivitiesProvideAreStillResolvable() {
        var resultRegistryOwner: Any? = null
        var backPressedOwner: Any? = null

        compose.setContent {
            LocalizedApp(languageCode = AppLanguage.Afrikaans.code) {
                // Both of these fall back to searching up from LocalContext. Reading them here is
                // exactly what rememberLauncherForActivityResult and NavHost do.
                resultRegistryOwner = LocalActivityResultRegistryOwner.current
                backPressedOwner = LocalOnBackPressedDispatcherOwner.current
            }
        }

        compose.waitForIdle()

        assertNotNull("Permission launchers need an ActivityResultRegistryOwner", resultRegistryOwner)
        assertNotNull("System back through NavHost needs an OnBackPressedDispatcherOwner", backPressedOwner)
    }

    @Test
    fun stringsComeBackInTheChosenLanguage() {
        var english: String? = null
        var zulu: String? = null
        var afrikaans: String? = null

        compose.setContent {
            LocalizedApp(AppLanguage.English.code) {
                english = LocalContext.current.getString(R.string.nav_home)
            }
            LocalizedApp(AppLanguage.Zulu.code) {
                zulu = LocalContext.current.getString(R.string.nav_home)
            }
            LocalizedApp(AppLanguage.Afrikaans.code) {
                afrikaans = LocalContext.current.getString(R.string.nav_home)
            }
        }

        compose.waitForIdle()

        assertEquals("Home", english)
        assertEquals("Ikhaya", zulu)
        assertEquals("Tuis", afrikaans)
    }

    @Test
    fun switchingLanguageRebuildsTheContextRatherThanReusingIt() {
        val language = mutableStateOf(AppLanguage.English.code)
        val seen = mutableListOf<String>()

        compose.setContent {
            val code by language

            LocalizedApp(languageCode = code) {
                seen += LocalContext.current.getString(R.string.nav_home)
            }
        }

        compose.waitForIdle()
        language.value = AppLanguage.Zulu.code
        compose.waitForIdle()

        // applyOverrideConfiguration can only be called once per wrapper, so a cached context would
        // silently keep the old language. This is what proves `remember` is keyed correctly.
        assertTrue(
            "Expected the language to change without an activity restart, saw $seen",
            seen.contains("Home") && seen.contains("Ikhaya")
        )
    }

    @Test
    fun anUnknownLanguageCodeFallsBackToEnglishRatherThanCrashing() {
        var text: String? = null

        compose.setContent {
            // The preference travels from the server, so a build that has not shipped a language
            // yet can still be handed its code.
            LocalizedApp(languageCode = "xh") {
                text = LocalContext.current.getString(R.string.nav_home)
            }
        }

        compose.waitForIdle()

        assertEquals("Home", text)
    }
}
