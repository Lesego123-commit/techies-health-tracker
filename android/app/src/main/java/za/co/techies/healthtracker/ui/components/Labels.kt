// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Localise your app. [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/localization [Accessed 24 September 2026].
//   Google. 2024. Resources in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/resources [Accessed 24 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5 (API Contract).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import za.co.techies.healthtracker.R

/**
 * Turns the codes the API stores into the words a caregiver reads.
 *
 * The server keeps dose statuses as lower-case identifiers — "taken", "missed" — because they are
 * data that queries and reports depend on, not prose. Capitalising one for display was fine while
 * the app was English only; in Afrikaans it produced "Taken" in the middle of an otherwise
 * translated list.
 *
 * An unrecognised code falls back to the code itself rather than to a blank. If the API ever adds a
 * status this build has not heard of, the row still says something true.
 */
@Composable
fun doseStatusLabel(status: String): String = when (status.lowercase()) {
    "taken" -> stringResource(R.string.dose_status_taken)
    "missed" -> stringResource(R.string.dose_status_missed)
    "skipped" -> stringResource(R.string.dose_status_skipped)
    "pending" -> stringResource(R.string.dose_status_pending)
    else -> status.replaceFirstChar { it.uppercase() }
}

/**
 * The relationship a dependant has to the caregiver.
 *
 * A controlled vocabulary on the API side, which is why the form offers chips rather than a text
 * field. The stored value never changes; only the word shown for it does.
 */
@Composable
fun relationshipLabel(relationship: String): String = when (relationship.lowercase()) {
    "child" -> stringResource(R.string.relationship_child)
    "parent" -> stringResource(R.string.relationship_parent)
    "spouse" -> stringResource(R.string.relationship_spouse)
    "self" -> stringResource(R.string.relationship_self)
    "other" -> stringResource(R.string.relationship_other)
    else -> relationship.replaceFirstChar { it.uppercase() }
}

/**
 * A dosage unit.
 *
 * mg, ml, g and mcg are returned unchanged: they are international symbols rather than words, and
 * translating them would be wrong in any language. Only the ones that are actually words get a
 * resource.
 */
@Composable
fun dosageUnitLabel(unit: String): String = when (unit.lowercase()) {
    "tablets" -> stringResource(R.string.unit_tablets)
    "puffs" -> stringResource(R.string.unit_puffs)
    "drops" -> stringResource(R.string.unit_drops)
    else -> unit
}

/** The physical form a medication comes in. */
@Composable
fun medicationFormLabel(form: String): String = when (form.lowercase()) {
    "tablet" -> stringResource(R.string.form_tablet)
    "capsule" -> stringResource(R.string.form_capsule)
    "liquid" -> stringResource(R.string.form_liquid)
    "injection" -> stringResource(R.string.form_injection)
    "inhaler" -> stringResource(R.string.form_inhaler)
    "drops" -> stringResource(R.string.form_drops)
    else -> form.replaceFirstChar { it.uppercase() }
}

/**
 * The category a document was filed under.
 *
 * Another controlled vocabulary: the API stores `lab_result`, and replacing the underscore with a
 * space was enough while the app was English only. It is not a translation.
 */
@Composable
fun documentTypeLabel(documentType: String): String = when (documentType.lowercase()) {
    "prescription" -> stringResource(R.string.doc_type_prescription)
    "lab_result" -> stringResource(R.string.doc_type_lab_result)
    "medical_report" -> stringResource(R.string.doc_type_medical_report)
    "other" -> stringResource(R.string.doc_type_other)
    else -> documentType.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Joins the parts of a summary line with the bullet the mockups use.
 *
 * Blank and null parts drop out, so a dependant with no medical aid number does not get a trailing
 * separator. The separator itself is punctuation rather than words, so it is the same in every
 * language and stays here rather than in strings.xml.
 */
fun bulleted(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ")
