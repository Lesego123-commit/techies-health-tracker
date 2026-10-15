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

/**
 * Medications, dose schedules and the refill forecast
 * (design document sections 5.4.3, 5.4.4 and 5.4.6).
 */
@Serializable
data class MedicationDto(
    val medicationId: String,
    val dependantId: String,
    val name: String,

    /** Text rather than a number, because prescriptions use values like "1/2". */
    val dosageAmount: String,
    val dosageUnit: String,

    /** Pre-formatted by the API, e.g. "500 mg". Display this rather than joining the two above. */
    val dosageDisplay: String,

    /** One of `capsule`, `tablet`, `liquid`, `injection`, `inhaler`, `drops`. */
    val form: String,

    val totalQuantity: Int,

    /** Decremented server-side on every dose logged as taken. */
    val remainingQuantity: Int,

    val prescribedBy: String? = null,

    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,

    /** Null means the course is ongoing. */
    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate? = null,

    val instructions: String? = null,
    val isActive: Boolean = true,

    /** Attached by the list and get endpoints, so the profile screen needs one call. */
    val schedules: List<DoseScheduleDto> = emptyList(),

    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant? = null,

    val rowVersion: Int = 0
)

@Serializable
data class CreateMedicationRequest(
    val name: String,
    val dosageAmount: String,
    val dosageUnit: String,
    val form: String,
    val totalQuantity: Int,

    /** Omit on a new prescription and the API seeds it from `totalQuantity`. */
    val remainingQuantity: Int? = null,

    val prescribedBy: String? = null,

    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,

    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate? = null,

    val instructions: String? = null
)

@Serializable
data class UpdateMedicationRequest(
    val name: String? = null,
    val dosageAmount: String? = null,
    val dosageUnit: String? = null,
    val form: String? = null,
    val totalQuantity: Int? = null,
    val remainingQuantity: Int? = null,
    val prescribedBy: String? = null,

    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate? = null,

    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate? = null,

    val instructions: String? = null,

    /** Set false to retire a medication. The API deactivates its schedules too. */
    val isActive: Boolean? = null
)

@Serializable
data class DoseScheduleDto(
    val scheduleId: String,
    val medicationId: String,

    /** One of `once_daily`, `twice_daily`, `three_times_daily`, `weekly`, `custom`. */
    val frequency: String,

    /**
     * Local wall-clock times in 24-hour `HH:mm`. Not instants: "08:00" means 08:00 to the
     * caregiver, and the API converts through Africa/Johannesburg when it needs a real moment.
     */
    val times: List<String> = emptyList(),

    /** Lowercase English day names. Empty means every day. */
    val daysOfWeek: List<String> = emptyList(),

    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,

    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate? = null,

    /** Calculated by the API. Never send this; just render it. */
    @Serializable(with = InstantSerializer::class)
    val nextDoseAt: Instant? = null,

    val isActive: Boolean = true
)

@Serializable
data class CreateDoseScheduleRequest(
    val frequency: String,

    /**
     * Must agree with the frequency: one time for `once_daily`, two for `twice_daily`, three for
     * `three_times_daily`. The API rejects a mismatch rather than silently halving adherence.
     */
    val times: List<String>,

    /** Required for `weekly`; leave empty for a daily schedule. */
    val daysOfWeek: List<String> = emptyList(),

    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,

    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate? = null
)

/**
 * The low-stock refill prediction (Additional Feature 4). Every value here is computed by the
 * API, including the severity band the Refill screen colour-codes from.
 */
@Serializable
data class RefillForecastDto(
    val medicationId: String,
    val medicationName: String,
    val remainingQuantity: Int,

    /** Units consumed per day, derived from the active schedules. */
    val dailyConsumptionRate: Double,

    /** Null when nothing is being consumed, so no countdown should be drawn. */
    @Serializable(with = LocalDateSerializer::class)
    val estimatedRunOutDate: LocalDate? = null,

    val daysUntilRunOut: Int? = null,

    /** True once the forecast falls inside the caregiver's configured threshold. */
    val refillAlertTriggered: Boolean = false,

    val lowStockThresholdDays: Int = 7,

    /** One of `ok`, `low`, `depleted`. Drive the colour from this, not from the day count. */
    val severity: String = "ok"
)
