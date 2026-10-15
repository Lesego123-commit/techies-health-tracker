// ----------------------------------------------------------------------------------------------
//  References
//   JetBrains. 2024. Kotlin Serialization Guide. [Online]. Available at:
//    https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serialization-guide.md
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.remote.dto

import kotlinx.serialization.Serializable
import za.co.techies.healthtracker.core.network.InstantSerializer
import za.co.techies.healthtracker.core.network.LocalDateSerializer
import java.time.Instant
import java.time.LocalDate

/** Dose logging and adherence analytics (design document section 5.4.5). */
@Serializable
data class DoseLogDto(
    val doseLogId: String,
    val scheduleId: String,
    val dependantId: String,
    val medicationId: String,

    /** Joined by the API so the history list needs no second lookup. */
    val medicationName: String = "",

    /** Either `taken` or `missed`. */
    val status: String,

    /** The slot this log belongs to, resolved server-side. */
    @Serializable(with = InstantSerializer::class)
    val scheduledAt: Instant,

    /** When the user actually tapped. */
    @Serializable(with = InstantSerializer::class)
    val loggedAt: Instant,

    val notes: String? = null,
    val rowVersion: Int = 0
)

/**
 * Logging a dose needs very little from the client.
 *
 * Omit `scheduledAt` and the API resolves which slot the tap belongs to from the schedule itself;
 * omit `loggedAt` and it uses the time the request arrives. In practice the three ids and a
 * status are enough.
 */
@Serializable
data class CreateDoseLogRequest(
    val scheduleId: String,
    val dependantId: String,
    val medicationId: String,

    /** `taken` or `missed`. */
    val status: String,

    @Serializable(with = InstantSerializer::class)
    val loggedAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val scheduledAt: Instant? = null,

    val notes: String? = null
)

/**
 * The response to logging a dose.
 *
 * One request, four outcomes: the Home screen updates its dose list, streak card and stock
 * figure from this single payload rather than re-fetching.
 */
@Serializable
data class DoseLogCreatedDto(
    val doseLog: DoseLogDto,
    val adherenceSummary: AdherenceDto,

    /** Stock after this dose, already decremented when the status was `taken`. */
    val remainingQuantity: Int,

    /** The recalculated next dose for this schedule. */
    @Serializable(with = InstantSerializer::class)
    val nextDoseAt: Instant? = null,

    /** True when this dose pushed the medication under the low-stock threshold. */
    val refillAlertTriggered: Boolean = false
)

/**
 * Adherence statistics (Additional Feature 3).
 *
 * Note that `totalScheduledDoses` is derived from the schedules, not counted from the log table:
 * a dose the caregiver never responded to leaves no row behind, so counting rows would report
 * perfect adherence for someone taking nothing at all.
 */
@Serializable
data class AdherenceDto(
    val dependantId: String = "",

    @Serializable(with = LocalDateSerializer::class)
    val fromDate: LocalDate? = null,

    @Serializable(with = LocalDateSerializer::class)
    val toDate: LocalDate? = null,

    val totalScheduledDoses: Int = 0,
    val dosesTaken: Int = 0,
    val dosesMissed: Int = 0,

    /** Taken as a percentage of scheduled, to one decimal place. */
    val adherencePercentage: Double = 0.0,

    /** Consecutive days ending today with no missed dose. Feeds the streak card. */
    val currentStreakDays: Int = 0,

    /** One point per day in the range, including days with nothing scheduled. */
    val trends: List<AdherenceTrendPointDto> = emptyList(),

    /**
     * A plain-language call-out such as "Evening doses are missed most often". Null when there is
     * too little history to say anything useful, in which case show nothing rather than a blank.
     */
    val patternInsight: String? = null
)

/** One day of the adherence series, ready to plot. */
@Serializable
data class AdherenceTrendPointDto(
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,

    val scheduled: Int = 0,
    val taken: Int = 0,
    val missed: Int = 0,
    val adherencePercentage: Double = 0.0
)
