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
import java.time.Instant

/**
 * Data transfer objects for the authentication and user endpoints (design document section 5.4.1).
 *
 * These mirror the API's contracts field for field. The API serialises with camelCase, which
 * matches Kotlin's own naming, so no `@SerialName` annotations are needed — a mismatch would show
 * up immediately as a missing-field failure in `DtoSerializationTest`.
 */
@Serializable
data class UserDto(
    val userId: String,
    val displayName: String,
    val email: String,
    val photoUrl: String? = null,
    val settings: SettingsDto,

    /** Returned by login so the Home screen can render from one call. */
    val dependants: List<DependantDto> = emptyList(),

    /** True only on the call that created the profile. Useful for a first-run walkthrough. */
    val isNewUser: Boolean = false,

    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant
)

/** The preferences behind the Settings screen (Requirement 3). */
@Serializable
data class SettingsDto(
    /** One of `en`, `zu` or `af`. */
    val preferredLanguage: String = "en",

    /** One of `light`, `dark` or `system`. */
    val theme: String = "light",

    val biometricEnabled: Boolean = false,

    /**
     * The master switch on the Settings screen. Distinct from quiet hours: this stops delivery
     * altogether, whereas quiet hours only defers it. Either way the API still records the
     * event, so the Notifications screen stays a complete history.
     */
    val notificationsEnabled: Boolean = true,

    val reminderLeadTimeMinutes: Int = 30,
    val lowStockThresholdDays: Int = 7,

    /** Local wall-clock `HH:mm`, or null when no quiet period is set. */
    val quietHoursStart: String? = null,
    val quietHoursEnd: String? = null,

    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant? = null,

    val rowVersion: Int = 0
)

/**
 * A partial update: every field is optional, and null means "leave this as it is".
 *
 * `explicitNulls = false` on the Json instance means null properties are omitted from the request
 * body entirely rather than sent as `"field": null`, which is what makes the partial semantics
 * work against the API.
 */
@Serializable
data class UpdateSettingsRequest(
    val preferredLanguage: String? = null,
    val theme: String? = null,
    val biometricEnabled: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val reminderLeadTimeMinutes: Int? = null,
    val lowStockThresholdDays: Int? = null,

    /** Send both ends together, or an empty string for either to clear the window. */
    val quietHoursStart: String? = null,
    val quietHoursEnd: String? = null,

    val fcmToken: String? = null
)
