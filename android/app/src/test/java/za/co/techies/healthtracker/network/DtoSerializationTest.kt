// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 16 September 2026].
//   Square. 2024. MockWebServer. [Online]. Available at:
//    https://github.com/square/okhttp/tree/master/mockwebserver [Accessed 16 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.network

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.core.network.ApiCall
import za.co.techies.healthtracker.data.remote.dto.AdherenceDto
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.remote.dto.DoseScheduleDto
import za.co.techies.healthtracker.data.remote.dto.RefillForecastDto
import za.co.techies.healthtracker.data.remote.dto.SettingsDto
import za.co.techies.healthtracker.data.remote.dto.UpdateSettingsRequest
import za.co.techies.healthtracker.data.remote.dto.UserDto
import java.time.Instant
import java.time.LocalDate

/**
 * Tests that the DTOs actually match what the API sends.
 *
 * The payloads below are copied from real responses, so these tests fail the moment the two sides
 * drift apart. That matters more than usual on this project: the API is built by one person and
 * consumed by another, and a renamed field would otherwise show up as an unexplained blank on a
 * screen rather than as a build failure.
 */
class DtoSerializationTest {

    private val json = ApiCall.json

    @Test
    fun `parses the login response`() {
        val payload = """
        {
          "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "displayName": "Lesego Masike",
          "email": "lesego@example.com",
          "photoUrl": null,
          "settings": {
            "preferredLanguage": "en",
            "theme": "light",
            "biometricEnabled": false,
            "reminderLeadTimeMinutes": 30,
            "lowStockThresholdDays": 7,
            "quietHoursStart": null,
            "quietHoursEnd": null,
            "updatedAt": "2026-04-15T06:00:00Z",
            "rowVersion": 0
          },
          "dependants": [],
          "isNewUser": true,
          "createdAt": "2026-04-15T06:00:00Z"
        }
        """.trimIndent()

        val user = json.decodeFromString<UserDto>(payload)

        assertEquals("Lesego Masike", user.displayName)
        assertEquals("en", user.settings.preferredLanguage)
        assertTrue(user.isNewUser)
        assertEquals(Instant.parse("2026-04-15T06:00:00Z"), user.createdAt)
    }

    @Test
    fun `parses a dependant including the server-calculated age`() {
        val payload = """
        {
          "dependantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "firstName": "Thandi",
          "lastName": "Masike",
          "fullName": "Thandi Masike",
          "dateOfBirth": "2014-03-12",
          "age": 12,
          "relationship": "child",
          "medicalAidNumber": null,
          "allergies": null,
          "activeMedicationCount": 2,
          "createdAt": "2026-04-15T06:00:00Z",
          "updatedAt": "2026-04-15T06:00:00Z",
          "rowVersion": 1
        }
        """.trimIndent()

        val dependant = json.decodeFromString<DependantDto>(payload)

        // A date of birth is a calendar date, not an instant. Parsing it as one would shift it a
        // day for anyone east of UTC, which is every user of this app.
        assertEquals(LocalDate.of(2014, 3, 12), dependant.dateOfBirth)
        assertEquals(12, dependant.age)
        assertEquals(2, dependant.activeMedicationCount)
    }

    @Test
    fun `parses a dose schedule and keeps clock times as local strings`() {
        val payload = """
        {
          "scheduleId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "medicationId": "3fa85f64-5717-4562-b3fc-2c963f66afa7",
          "frequency": "twice_daily",
          "times": ["08:00", "20:00"],
          "daysOfWeek": [],
          "startDate": "2026-04-01",
          "endDate": null,
          "nextDoseAt": "2026-04-15T18:00:00Z",
          "isActive": true
        }
        """.trimIndent()

        val schedule = json.decodeFromString<DoseScheduleDto>(payload)

        // 20:00 is a wall-clock time to the caregiver, not a moment. It stays a string; the
        // instant it resolves to is nextDoseAt, which the API worked out.
        assertEquals(listOf("08:00", "20:00"), schedule.times)
        assertEquals(Instant.parse("2026-04-15T18:00:00Z"), schedule.nextDoseAt)
        assertTrue(schedule.daysOfWeek.isEmpty())
    }

    @Test
    fun `parses a refill forecast with a null run-out date`() {
        val payload = """
        {
          "medicationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "medicationName": "Amoxicillin",
          "remainingQuantity": 30,
          "dailyConsumptionRate": 0.0,
          "estimatedRunOutDate": null,
          "daysUntilRunOut": null,
          "refillAlertTriggered": false,
          "lowStockThresholdDays": 7,
          "severity": "ok"
        }
        """.trimIndent()

        val forecast = json.decodeFromString<RefillForecastDto>(payload)

        // Null means "nothing is being consumed, so there is no countdown to draw". The screen
        // must be able to tell that apart from zero days left.
        assertNull(forecast.estimatedRunOutDate)
        assertNull(forecast.daysUntilRunOut)
        assertEquals("ok", forecast.severity)
    }

    @Test
    fun `parses adherence including a null pattern insight`() {
        val payload = """
        {
          "dependantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "fromDate": "2026-03-17",
          "toDate": "2026-04-15",
          "totalScheduledDoses": 60,
          "dosesTaken": 54,
          "dosesMissed": 6,
          "adherencePercentage": 90.0,
          "currentStreakDays": 4,
          "trends": [
            { "date": "2026-04-15", "scheduled": 2, "taken": 2, "missed": 0, "adherencePercentage": 100.0 }
          ],
          "patternInsight": null
        }
        """.trimIndent()

        val adherence = json.decodeFromString<AdherenceDto>(payload)

        assertEquals(90.0, adherence.adherencePercentage, 0.001)
        assertEquals(4, adherence.currentStreakDays)
        assertEquals(1, adherence.trends.size)
        assertEquals(LocalDate.of(2026, 4, 15), adherence.trends.first().date)
        assertNull(adherence.patternInsight)
    }

    @Test
    fun `omits null fields from a partial settings update`() {
        val request = UpdateSettingsRequest(theme = "dark")

        val encoded = json.encodeToString(request)

        // This is what makes the partial-update contract work. Serialising the untouched fields
        // as explicit nulls would ask the API to clear preferences the user never touched.
        assertEquals("""{"theme":"dark"}""", encoded)
    }

    @Test
    fun `sends a false notifications toggle rather than dropping it`() {
        val request = UpdateSettingsRequest(notificationsEnabled = false)

        val encoded = json.encodeToString(request)

        // The one case where `explicitNulls = false` could bite: turning notifications off is a
        // false, not a null, so it has to survive the same encoder that strips untouched fields.
        assertEquals("""{"notificationsEnabled":false}""", encoded)
    }

    @Test
    fun `defaults the notifications toggle on when the API omits it`() {
        // An older deployment that predates the column would send settings without the field.
        // Defaulting to off would silence every reminder for anyone on that build, so the client
        // has to agree with the server's own default of true.
        val payload = """
        {
          "preferredLanguage": "en",
          "theme": "light",
          "biometricEnabled": false,
          "reminderLeadTimeMinutes": 30,
          "lowStockThresholdDays": 7,
          "quietHoursStart": null,
          "quietHoursEnd": null,
          "rowVersion": 0
        }
        """.trimIndent()

        val settings = json.decodeFromString<SettingsDto>(payload)

        assertTrue(settings.notificationsEnabled)
    }

    @Test
    fun `parses the notifications toggle when the API sends it`() {
        val payload = """
        {
          "preferredLanguage": "zu",
          "theme": "dark",
          "biometricEnabled": true,
          "notificationsEnabled": false,
          "reminderLeadTimeMinutes": 60,
          "lowStockThresholdDays": 14,
          "quietHoursStart": "22:00",
          "quietHoursEnd": "06:00",
          "rowVersion": 3
        }
        """.trimIndent()

        val settings = json.decodeFromString<SettingsDto>(payload)

        assertEquals(false, settings.notificationsEnabled)
        assertEquals("22:00", settings.quietHoursStart)
    }

    @Test
    fun `ignores fields the client does not know about`() {
        val payload = """
        {
          "dependantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "firstName": "Thandi",
          "lastName": "Masike",
          "fullName": "Thandi Masike",
          "dateOfBirth": "2014-03-12",
          "age": 12,
          "relationship": "child",
          "activeMedicationCount": 0,
          "createdAt": "2026-04-15T06:00:00Z",
          "updatedAt": "2026-04-15T06:00:00Z",
          "rowVersion": 1,
          "someFieldAddedLater": "value"
        }
        """.trimIndent()

        // An API that gained a field must not break a client that has not been rebuilt. Students
        // ship at different times; this keeps the two sides loosely coupled.
        val dependant = json.decodeFromString<DependantDto>(payload)

        assertEquals("Thandi", dependant.firstName)
    }

    @Test
    fun `tolerates a timestamp with no timezone marker`() {
        val payload = """
        {
          "scheduleId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "medicationId": "3fa85f64-5717-4562-b3fc-2c963f66afa7",
          "frequency": "once_daily",
          "times": ["08:00"],
          "daysOfWeek": [],
          "startDate": "2026-04-01",
          "nextDoseAt": "2026-04-15T18:00:00",
          "isActive": true
        }
        """.trimIndent()

        // .NET omits the trailing Z when a DateTime's kind is unspecified. Every timestamp this
        // API returns is UTC, so it is read as UTC rather than failing the whole response.
        val schedule = json.decodeFromString<DoseScheduleDto>(payload)

        assertEquals(Instant.parse("2026-04-15T18:00:00Z"), schedule.nextDoseAt)
    }
}
