// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Compose Layout Basics. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/layouts/basics [Accessed 16 September 2026].
//   Google. 2024. Localise your app. [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/localization [Accessed 24 September 2026].
//   Google. 2024. Quantity strings (plurals). [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/string-resource#Plurals
//    [Accessed 24 September 2026].
//   Oracle. 2024. java.time.format.DateTimeFormatter. [Online]. Available at:
//    https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html
//    [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import za.co.techies.healthtracker.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Wording the app has decided on but not yet rendered.
 *
 * [Formatting] used to return finished English sentences, which made the phrasing untranslatable:
 * the decision of *which* sentence to use and the sentence itself were the same line of code.
 * Splitting them means the arithmetic stays in a plain Kotlin object that unit tests can reach
 * without a device, while the words themselves live in strings.xml and follow the chosen language.
 *
 * [count] carries a quantity, which selects both the plural form and the number substituted into
 * it — isiZulu and Afrikaans do not agree with English on when a noun becomes plural, so that
 * decision belongs to the resource system rather than to a `when` branch here.
 */
data class Phrase(
    @StringRes @PluralsRes val resId: Int,
    val count: Int? = null,
    val text: String? = null
)

/** Renders a [Phrase] in whichever language is currently selected. */
@Composable
fun Phrase.resolve(): String = when {
    count != null -> pluralStringResource(resId, count, count)
    text != null -> stringResource(resId, text)
    else -> stringResource(resId)
}

/**
 * Display formatting.
 *
 * Note what is *not* here: ages, dosage strings, adherence percentages and days-until-run-out are
 * all computed by the API and arrive ready to render. This file only converts the two things a
 * server cannot decide — an instant into the device's own time zone, and a duration into the
 * phrasing a person would actually use.
 *
 * Every function takes the locale rather than reading it at the point of use, so a test can pin one
 * and the app can pass the one the user chose. The default is [Locale.getDefault], which the
 * language switch sets, so callers that do not care get the right answer anyway.
 */
object Formatting {

    private const val Missing = "—"

    /**
     * Month and day names come from the locale, so a date reads in the chosen language rather than
     * always in English.
     */
    private class Formatters(locale: Locale) {
        val timeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        val dayAndMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", locale)
        val fullDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
        val dayMonthTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", locale)
    }

    // Built once per language rather than per row: a dose list re-formats on every recomposition,
    // and DateTimeFormatter.ofPattern parses the pattern again on each call.
    private val cache = ConcurrentHashMap<Locale, Formatters>()

    private fun formatters(locale: Locale): Formatters =
        cache.computeIfAbsent(locale) { Formatters(it) }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 18:00 UTC becomes "20:00" for a South African user. */
    fun time(instant: Instant?, locale: Locale = Locale.getDefault()): String =
        instant?.atZone(zone)?.format(formatters(locale).timeOfDay) ?: Missing

    fun dateTime(instant: Instant?, locale: Locale = Locale.getDefault()): String =
        instant?.atZone(zone)?.format(formatters(locale).dayMonthTime) ?: Missing

    fun date(date: LocalDate?, locale: Locale = Locale.getDefault()): String =
        date?.format(formatters(locale).fullDate) ?: Missing

    fun shortDate(date: LocalDate?, locale: Locale = Locale.getDefault()): String =
        date?.format(formatters(locale).dayAndMonth) ?: Missing

    /**
     * A dose time in the words a caregiver would use: "due now", "in 25 minutes", "overdue by 2
     * hours". A bare timestamp makes the reader do the arithmetic, which is exactly the work this
     * app exists to remove.
     */
    fun relativeDose(
        target: Instant?,
        now: Instant = Instant.now(),
        locale: Locale = Locale.getDefault()
    ): Phrase {
        if (target == null) return Phrase(R.string.dose_none_scheduled)

        val minutes = Duration.between(now, target).toMinutes()

        return when {
            minutes < -1440 ->
                Phrase(R.plurals.dose_overdue_days, count = (-minutes / 1440).toInt())

            minutes < -60 -> Phrase(R.plurals.dose_overdue_hours, count = (-minutes / 60).toInt())
            minutes < -5 -> Phrase(R.plurals.dose_overdue_minutes, count = (-minutes).toInt())
            minutes <= 5 -> Phrase(R.string.dose_due_now)
            minutes < 60 -> Phrase(R.plurals.dose_in_minutes, count = minutes.toInt())
            minutes < 1440 -> Phrase(R.plurals.dose_in_hours, count = (minutes / 60).toInt())
            else -> Phrase(R.string.dose_on_date, text = dateTime(target, locale))
        }
    }

    /** True when the instant falls on today in the device's own time zone. */
    fun isToday(instant: Instant?): Boolean =
        instant?.atZone(zone)?.toLocalDate() == LocalDate.now(zone)

    fun isOverdue(instant: Instant?, now: Instant = Instant.now()): Boolean =
        instant != null && instant.isBefore(now)

    /**
     * "92.5%" — the value itself is calculated server-side.
     *
     * The locale decides the decimal separator, so Afrikaans reads "92,5%". Formatting a number the
     * English way inside an otherwise Afrikaans sentence is the kind of detail that makes a
     * translated app feel half-finished.
     */
    fun percentage(value: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f%%", value)

    /**
     * A reminder lead time in the words the user chose it with — "1 day", not "1440 min".
     *
     * The API stores every lead time in minutes because that is the only unit that divides
     * cleanly. Rendering that raw is what produced "Reminder 1440 min before" on an appointment
     * the user had set by tapping a chip labelled "1 day".
     */
    fun leadTime(minutes: Int): Phrase = when {
        minutes % 1440 == 0 && minutes >= 1440 ->
            Phrase(R.plurals.lead_time_days, count = minutes / 1440)

        minutes % 60 == 0 && minutes >= 60 ->
            Phrase(R.plurals.lead_time_hours, count = minutes / 60)

        else -> Phrase(R.plurals.lead_time_minutes, count = minutes)
    }

    /**
     * A distance in the units a person would say out loud.
     *
     * Under a kilometre it reads in whole metres, because "0,4 km" is how a map thinks and "400 m"
     * is how someone deciding whether to walk thinks. Above that, one decimal is enough precision
     * for choosing between two pharmacies, and the locale supplies the separator.
     */
    fun distance(kilometres: Double, locale: Locale = Locale.getDefault()): Phrase = when {
        kilometres < 1.0 ->
            Phrase(R.string.distance_metres, text = (kilometres * 1000).roundToInt().toString())

        else -> Phrase(
            R.string.distance_kilometres,
            text = String.format(locale, "%.1f", kilometres)
        )
    }

    /**
     * A file size in the unit a person would use.
     *
     * Binary units, because that is what a phone's own file manager shows and a document that
     * reads "2.1 MB" there should not read "2.2 MB" here. Below a megabyte it rounds to whole
     * kilobytes: nobody needs a decimal place to know a prescription photo is small.
     */
    fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes < 1024 -> String.format(locale, "%d B", bytes)
        bytes < 1024 * 1024 -> String.format(locale, "%d kB", bytes / 1024)
        else -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Day-streak phrasing for the Home and Trends cards. */
    fun streak(days: Int): Phrase =
        if (days == 0) Phrase(R.string.streak_none) else Phrase(R.plurals.streak_days, count = days)
}
