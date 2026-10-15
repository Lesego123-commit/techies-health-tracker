// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Localise your app. [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/localization [Accessed 24 September 2026].
//   Google. 2024. Architecture: UI layer. [Online]. Available at:
//    https://developer.android.com/topic/architecture/ui-layer [Accessed 24 September 2026].
//   Kotlin Foundation. 2024. Sealed classes and interfaces. [Online]. Available at:
//    https://kotlinlang.org/docs/sealed-classes.html [Accessed 24 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A message the app wants shown, before it becomes words.
 *
 * A ViewModel cannot call `stringResource`: it has no composition, and giving it a Context so it
 * could would also mean a rotation or a language change left the old language cached in state.
 * Holding a `String` instead is what produced the real problem this type fixes — every validation
 * message the app writes itself ("First and last name are both required.") stayed in English no
 * matter which language was chosen, because it had already been turned into text before the screen
 * ever saw it.
 *
 * So state carries the *decision* and the screen does the rendering. It lives in `core` rather
 * than beside the other UI helpers because the validators and the auth clients also choose
 * messages, and they have no business importing from the UI package:
 *
 *  - [Res] is a message this app owns. It names a resource, so it is written in whichever language
 *    is selected at the moment it is drawn — including after the user switches language while the
 *    message is still on screen.
 *  - [Raw] is text that arrived from somewhere that has already chosen the words: the API, which
 *    localises its own messages from the same stored preference, or Firebase. Re-translating those
 *    on the device would mean keeping a copy of every message the server can produce.
 */
sealed interface UiText {

    /** A message this app owns, named by resource so it follows the chosen language. */
    data class Res(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText

    /** Text from the API or an SDK, already in the language it is going to be read in. */
    data class Raw(val value: String) : UiText
}

/** Builds a [UiText.Res], keeping the call sites short: `uiText(R.string.err_save)`. */
fun uiText(@StringRes resId: Int, vararg args: Any): UiText = UiText.Res(resId, args.toList())

/** Wraps text the app did not write, such as an API message. */
fun String.asUiText(): UiText = UiText.Raw(this)

/** Renders a [UiText] in whichever language is currently selected. */
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> stringResource(resId, *args.toTypedArray())
    is UiText.Raw -> value
}
