// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 16 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 16 September 2026].
//   Google. 2024. Quantity strings (plurals). [Online]. Available at:
//    https://developer.android.com/guide/topics/resources/string-resource#Plurals
//    [Accessed 24 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.ui.components.Formatting
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Tests for the display formatting.
 *
 * Only two things are checked here, because only two things are the client's job: deciding which
 * wording a dose deserves, and deciding whether it is overdue. Everything else on screen — ages,
 * dosage strings, adherence percentages, days until run-out — is computed by the API and arrives
 * ready to render.
 *
 * These assert on the resource id and the quantity rather than on an English sentence. That is the
 * point of the refactor: the same classification now has to be right in three languages, and a test
 * that asserted "In 25 minutes" would pass or fail depending on which language the phone is set to.
 * The id and the count are the decisions this code actually makes; the sentence belongs to
 * strings.xml, and Android's own plural rules decide which form of it appears.
 */
class FormattingTest {

    private val now: Instant = Instant.parse("2026-04-15T12:00:00Z")

    @Test
    fun `describes a dose due right now`() {
        assertEquals(R.string.dose_due_now, Formatting.relativeDose(now, now).resId)
        assertEquals(R.string.dose_due_now, Formatting.relativeDose(now.plusSeconds(120), now).resId)
    }

    @Test
    fun `counts forward in minutes then hours`() {
        val soon = Formatting.relativeDose(now.plusSeconds(25 * 60), now)
        assertEquals(R.plurals.dose_in_minutes, soon.resId)
        assertEquals(25, soon.count)

        val later = Formatting.relativeDose(now.plusSeconds(3 * 3600), now)
        assertEquals(R.plurals.dose_in_hours, later.resId)
        assertEquals(3, later.count)
    }

    @Test
    fun `describes an overdue dose as overdue rather than negative`() {
        // "In -45 minutes" would be technically correct and useless. A caregiver glancing at the
        // list needs to see that something has been missed, and the count has to be positive or the
        // plural rules pick the wrong form.
        val missedMinutes = Formatting.relativeDose(now.minusSeconds(45 * 60), now)
        assertEquals(R.plurals.dose_overdue_minutes, missedMinutes.resId)
        assertEquals(45, missedMinutes.count)

        val missedHours = Formatting.relativeDose(now.minusSeconds(4 * 3600), now)
        assertEquals(R.plurals.dose_overdue_hours, missedHours.resId)
        assertEquals(4, missedHours.count)

        val missedDays = Formatting.relativeDose(now.minusSeconds(3 * 86400), now)
        assertEquals(R.plurals.dose_overdue_days, missedDays.resId)
        assertEquals(3, missedDays.count)
    }

    @Test
    fun `falls back to a date for something far ahead`() {
        val result = Formatting.relativeDose(now.plusSeconds(5 * 86400), now, Locale.UK)

        // Beyond a day, "in 120 hours" stops being useful and a date is clearer. The date is
        // substituted text rather than a quantity, so it must not carry a plural count.
        assertEquals(R.string.dose_on_date, result.resId)
        assertNull(result.count)
        assertTrue("Expected a formatted date, got '${result.text}'", result.text!!.contains("Apr"))
    }

    @Test
    fun `handles a medication with no schedule`() {
        val result = Formatting.relativeDose(null, now)
        assertEquals(R.string.dose_none_scheduled, result.resId)
        assertNull(result.count)
    }

    @Test
    fun `flags overdue instants only`() {
        assertTrue(Formatting.isOverdue(now.minusSeconds(60), now))
        assertFalse(Formatting.isOverdue(now.plusSeconds(60), now))
        assertFalse(Formatting.isOverdue(null, now))
    }

    @Test
    fun `formats percentages to one decimal place`() {
        assertEquals("92.5%", Formatting.percentage(92.5, Locale.UK))
        assertEquals("100.0%", Formatting.percentage(100.0, Locale.UK))
        assertEquals("0.0%", Formatting.percentage(0.0, Locale.UK))
    }

    @Test
    fun `formats percentages with the separator the locale uses`() {
        // Afrikaans writes the decimal comma. An English full stop inside an otherwise Afrikaans
        // sentence is a small thing that reads as a bug to the person it is aimed at.
        assertEquals("92,5%", Formatting.percentage(92.5, Locale.forLanguageTag("af")))
    }

    @Test
    fun `phrases the streak in words rather than a bare number`() {
        // Zero is its own sentence, not a quantity of days, so it gets a plain string.
        val none = Formatting.streak(0)
        assertEquals(R.string.streak_none, none.resId)
        assertNull(none.count)

        assertEquals(R.plurals.streak_days, Formatting.streak(1).resId)
        assertEquals(1, Formatting.streak(1).count)
        assertEquals(12, Formatting.streak(12).count)
    }

    @Test
    fun `renders a missing instant as a dash rather than throwing`() {
        assertEquals("—", Formatting.time(null))
        assertEquals("—", Formatting.dateTime(null))
        assertEquals("—", Formatting.date(null))
        assertEquals("—", Formatting.shortDate(null))
    }

    @Test
    fun `lead time reads back in the unit the chip offered`() {
        // The API stores minutes because that is the only unit that divides cleanly, but an
        // appointment set with a chip marked "1 day" must not read back as "1440 min".
        assertEquals(R.plurals.lead_time_days, Formatting.leadTime(1440).resId)
        assertEquals(1, Formatting.leadTime(1440).count)

        assertEquals(R.plurals.lead_time_days, Formatting.leadTime(2880).resId)
        assertEquals(2, Formatting.leadTime(2880).count)

        assertEquals(R.plurals.lead_time_hours, Formatting.leadTime(60).resId)
        assertEquals(1, Formatting.leadTime(60).count)

        assertEquals(R.plurals.lead_time_hours, Formatting.leadTime(120).resId)
        assertEquals(2, Formatting.leadTime(120).count)

        assertEquals(R.plurals.lead_time_minutes, Formatting.leadTime(15).resId)
        assertEquals(15, Formatting.leadTime(15).count)
    }

    @Test
    fun `lead time keeps minutes when they do not divide evenly`() {
        // 90 minutes is an hour and a half; calling it "1 hr" would be wrong, not just terse.
        assertEquals(R.plurals.lead_time_minutes, Formatting.leadTime(90).resId)
        assertEquals(90, Formatting.leadTime(90).count)
        assertEquals(R.plurals.lead_time_minutes, Formatting.leadTime(45).resId)
    }

    @Test
    fun `formats a date in the language that was asked for`() {
        // March, deliberately: April is spelled the same in all three languages, so it would prove
        // nothing. The month name has to come from the locale, otherwise a fully translated screen
        // still shows one English word in the middle of every date.
        val date = LocalDate.of(2026, 3, 15)

        assertEquals("15 March 2026", Formatting.date(date, Locale.UK))
        assertNotEquals(
            "Expected the month name to change with the locale",
            Formatting.date(date, Locale.UK),
            Formatting.date(date, Locale.forLanguageTag("af"))
        )
    }
}
