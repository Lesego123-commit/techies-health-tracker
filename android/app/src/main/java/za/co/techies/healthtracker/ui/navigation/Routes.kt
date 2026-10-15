// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Navigation with Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/navigation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.14 (Navigation Flow).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import za.co.techies.healthtracker.R

/**
 * Every destination in the graph, matching Figure 2 of the design document.
 *
 * The document splits these into three levels, and the split is what decides where a screen
 * lives rather than how important it feels: the five bottom-navigation tabs, their first-level
 * drill-downs, and a smaller set beneath those. Refill Forecast, for instance, is reached from a
 * dependant's profile rather than from the tab bar — it is about one person's medication, not a
 * section of the app in its own right.
 */
object Routes {
    // ---- Authentication ----
    const val SIGN_IN = "sign_in"

    // ---- Bottom navigation (top level) ----
    const val HOME = "home"
    const val MEDS = "meds"
    const val TRENDS = "trends"
    const val APPOINTMENTS = "appointments"
    const val MORE = "more"

    // ---- First-level drill-downs ----
    const val DEPENDANT_PROFILE = "dependant_profile/{dependantId}"
    const val NOTIFICATIONS = "notifications"

    // ---- Second-level drill-downs ----
    const val ADD_MEDICATION = "add_medication"
    const val EDIT_MEDICATION = "edit_medication/{medicationId}"
    const val REFILL_FORECAST = "refill_forecast"
    const val EDIT_DEPENDANT = "edit_dependant/{dependantId}"
    const val PHARMACY_FINDER = "pharmacy_finder"
    const val DOCUMENTS = "documents"

    fun dependantProfile(dependantId: String) = "dependant_profile/$dependantId"
    fun editMedication(medicationId: String) = "edit_medication/$medicationId"
    fun editDependant(dependantId: String) = "edit_dependant/$dependantId"
}

/** One of the five bottom-navigation tabs. */
data class NavTab(
    val route: String,

    /** A resource id rather than text, so the bar re-reads it when the language changes. */
    @StringRes val labelRes: Int,

    val icon: ImageVector
)

/**
 * The tab bar from Figure 2: Home, Dependants/Meds, Trends, Appointments, More.
 *
 * Only icons from material-icons-core are used, which Material 3 already brings in. The extended
 * set would give closer matches, but it adds roughly 10 MB to the APK for five glyphs.
 */
val navTabs = listOf(
    NavTab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    NavTab(Routes.MEDS, R.string.nav_meds, Icons.AutoMirrored.Filled.List),
    NavTab(Routes.TRENDS, R.string.nav_trends, Icons.Filled.CheckCircle),
    NavTab(Routes.APPOINTMENTS, R.string.nav_appointments, Icons.Filled.DateRange),
    NavTab(Routes.MORE, R.string.nav_more, Icons.Filled.MoreVert)
)
