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

/** Appointments (design document section 5.4.7). */
@Serializable
data class AppointmentDto(
    val appointmentId: String,
    val dependantId: String,
    val title: String,

    @Serializable(with = InstantSerializer::class)
    val appointmentDateTime: Instant,

    val location: String? = null,
    val notes: String? = null,
    val reminderMinutesBefore: Int = 60,

    /** Cancelled rather than deleted, so the visit history survives. */
    val isCancelled: Boolean = false,

    /** Decided server-side, so a device with a skewed clock cannot misfile an appointment. */
    val isUpcoming: Boolean = false,

    /** When the reminder fires: the appointment time less the lead time. */
    @Serializable(with = InstantSerializer::class)
    val reminderAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant? = null
)

@Serializable
data class CreateAppointmentRequest(
    val title: String,

    @Serializable(with = InstantSerializer::class)
    val appointmentDateTime: Instant,

    val location: String? = null,
    val notes: String? = null,

    /** Minimum 5 minutes, enforced by the API. */
    val reminderMinutesBefore: Int = 60
)

@Serializable
data class UpdateAppointmentRequest(
    val title: String? = null,

    @Serializable(with = InstantSerializer::class)
    val appointmentDateTime: Instant? = null,

    val location: String? = null,
    val notes: String? = null,
    val reminderMinutesBefore: Int? = null
)

/**
 * Document metadata (section 5.4.9). The internal storage path is never returned; downloads go
 * through the short-lived signed URL below.
 */
@Serializable
data class DocumentDto(
    val documentId: String,
    val dependantId: String,
    val title: String,

    /** One of `prescription`, `lab_result`, `medical_report`, `other`. */
    val documentType: String,

    val fileName: String,
    val contentType: String,
    val fileSizeBytes: Long = 0,

    /**
     * Signed and time-limited, and needs no Authorization header — hand it straight to an image
     * loader. Do not cache it past [downloadUrlExpiresAt]; re-fetch the metadata for a fresh one.
     */
    val downloadUrl: String = "",

    @Serializable(with = InstantSerializer::class)
    val downloadUrlExpiresAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val uploadedAt: Instant? = null
)

/** Pharmacy search (section 5.4.8). The GeoApify key stays on the server. */
@Serializable
data class PharmacySearchDto(
    val userLocation: GeoPointDto? = null,
    val radiusMetres: Int = 0,
    val results: List<PharmacyResultDto> = emptyList(),
    val totalResults: Int = 0,

    /** True when served from the API's 24-hour cache rather than a live upstream call. */
    val fromCache: Boolean = false
)

@Serializable
data class GeoPointDto(val lat: Double = 0.0, val lon: Double = 0.0)

@Serializable
data class PharmacyResultDto(
    val name: String,
    val formattedAddress: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,

    /** Great-circle distance from the caller, already sorted ascending by the API. */
    val distanceKm: Double = 0.0,

    val openingHours: String? = null,
    val phone: String? = null
)

/** Notification history (Requirement 6), read straight off the API's NotificationEvents table. */
@Serializable
data class NotificationDto(
    val notificationId: String,
    val dependantId: String? = null,

    /** One of `dose_reminder`, `missed_dose`, `appointment_reminder`, `refill_alert`. */
    val category: String,

    val title: String,
    val body: String,

    /** In-app deep link, e.g. `techies://medications/{id}`. */
    val deepLink: String? = null,

    val isSent: Boolean = false,

    @Serializable(with = InstantSerializer::class)
    val sentAt: Instant? = null,

    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null
)
