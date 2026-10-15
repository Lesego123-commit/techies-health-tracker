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
 * Dependant profiles (design document section 5.4.2) — the backbone of Additional Feature 5,
 * one caregiver account managing several people's medication.
 */
@Serializable
data class DependantDto(
    val dependantId: String,
    val firstName: String,
    val lastName: String,

    /** Pre-joined by the API so no screen has to concatenate names itself. */
    val fullName: String,

    @Serializable(with = LocalDateSerializer::class)
    val dateOfBirth: LocalDate,

    /** Calculated server-side, so every screen shows the same number. Do not recompute it. */
    val age: Int,

    /** One of `child`, `parent`, `spouse`, `self` or `other`. */
    val relationship: String,

    val medicalAidNumber: String? = null,
    val allergies: String? = null,

    /** Ready-made subtitle for the Dependants list. */
    val activeMedicationCount: Int = 0,

    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant? = null,

    val rowVersion: Int = 0
)

@Serializable
data class CreateDependantRequest(
    val firstName: String,
    val lastName: String,

    @Serializable(with = LocalDateSerializer::class)
    val dateOfBirth: LocalDate,

    val relationship: String,
    val medicalAidNumber: String? = null,
    val allergies: String? = null
)

/** Partial update. Null leaves a field unchanged; an empty string clears an optional one. */
@Serializable
data class UpdateDependantRequest(
    val firstName: String? = null,
    val lastName: String? = null,

    @Serializable(with = LocalDateSerializer::class)
    val dateOfBirth: LocalDate? = null,

    val relationship: String? = null,
    val medicalAidNumber: String? = null,
    val allergies: String? = null
)
