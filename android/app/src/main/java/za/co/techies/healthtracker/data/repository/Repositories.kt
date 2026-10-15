// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Data Layer — Android Architecture Guide. [Online]. Available at:
//    https://developer.android.com/topic/architecture/data-layer [Accessed 16 September 2026].
//   Kotlin Foundation. 2024. Result — Kotlin Standard Library. [Online]. Available at:
//    https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-result/ [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.repository

import android.content.ContentResolver
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import za.co.techies.healthtracker.core.files.ContentUriRequestBody
import za.co.techies.healthtracker.core.files.PickedFile
import za.co.techies.healthtracker.core.network.ApiCall
import za.co.techies.healthtracker.data.remote.TechiesApi
import za.co.techies.healthtracker.data.remote.dto.AdherenceDto
import za.co.techies.healthtracker.data.remote.dto.AppointmentDto
import za.co.techies.healthtracker.data.remote.dto.CreateAppointmentRequest
import za.co.techies.healthtracker.data.remote.dto.CreateDependantRequest
import za.co.techies.healthtracker.data.remote.dto.CreateDoseLogRequest
import za.co.techies.healthtracker.data.remote.dto.CreateDoseScheduleRequest
import za.co.techies.healthtracker.data.remote.dto.CreateMedicationRequest
import za.co.techies.healthtracker.data.remote.dto.DependantDto
import za.co.techies.healthtracker.data.remote.dto.DocumentDto
import za.co.techies.healthtracker.data.remote.dto.DoseLogCreatedDto
import za.co.techies.healthtracker.data.remote.dto.DoseLogDto
import za.co.techies.healthtracker.data.remote.dto.DoseScheduleDto
import za.co.techies.healthtracker.data.remote.dto.MedicationDto
import za.co.techies.healthtracker.data.remote.dto.NotificationDto
import za.co.techies.healthtracker.data.remote.dto.PharmacySearchDto
import za.co.techies.healthtracker.data.remote.dto.RefillForecastDto
import za.co.techies.healthtracker.data.remote.dto.SettingsDto
import za.co.techies.healthtracker.data.remote.dto.SyncBatchRequest
import za.co.techies.healthtracker.data.remote.dto.SyncBatchResultDto
import za.co.techies.healthtracker.data.remote.dto.UpdateAppointmentRequest
import za.co.techies.healthtracker.data.remote.dto.UpdateDependantRequest
import za.co.techies.healthtracker.data.remote.dto.UpdateMedicationRequest
import za.co.techies.healthtracker.data.remote.dto.UpdateSettingsRequest
import za.co.techies.healthtracker.data.remote.dto.UserDto
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The repository layer.
 *
 * Every method returns `Result`, with failures already normalised to `ApiException` by [ApiCall].
 * A ViewModel therefore needs one `onFailure` branch to show any error, rather than a try/catch
 * per exception family.
 *
 * These repositories are deliberately thin. The API does the computation — next dose times,
 * adherence percentages, run-out dates, ages, formatted dosages — so there is nothing here to
 * transform, and adding a mapping layer that re-derived any of it would just create a second
 * source of truth that could disagree with the server.
 *
 * Offline caching with RoomDB is a POE-phase requirement and slots in behind these same methods.
 */

/** Date query parameters are sent as `yyyy-MM-dd`, matching the API's DateOnly parsing. */
private val QUERY_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

private fun LocalDate?.asQuery(): String? = this?.format(QUERY_DATE)

class AuthRepository(private val api: TechiesApi) {

    /**
     * Provisions or returns the caller's profile. Call on every launch, before anything else.
     */
    suspend fun login(fcmToken: String? = null): Result<UserDto> =
        ApiCall.execute { api.login(fcmToken) }

    suspend fun me(): Result<UserDto> = ApiCall.execute { api.me() }
}

class SettingsRepository(private val api: TechiesApi) {

    suspend fun get(userId: String): Result<SettingsDto> =
        ApiCall.execute { api.getSettings(userId) }

    /** Partial update: unchanged fields are omitted from the request body entirely. */
    suspend fun update(userId: String, request: UpdateSettingsRequest): Result<SettingsDto> =
        ApiCall.execute { api.updateSettings(userId, request) }
}

class DependantRepository(private val api: TechiesApi) {

    suspend fun list(): Result<List<DependantDto>> = ApiCall.execute { api.listDependants() }

    suspend fun get(dependantId: String): Result<DependantDto> =
        ApiCall.execute { api.getDependant(dependantId) }

    suspend fun create(request: CreateDependantRequest): Result<DependantDto> =
        ApiCall.execute { api.createDependant(request) }

    suspend fun update(dependantId: String, request: UpdateDependantRequest): Result<DependantDto> =
        ApiCall.execute { api.updateDependant(dependantId, request) }

    /** Soft delete. The dose history is retained server-side. */
    suspend fun delete(dependantId: String): Result<Unit> =
        ApiCall.execute { api.deleteDependant(dependantId) }
}

class MedicationRepository(private val api: TechiesApi) {

    /** Each medication arrives with its schedules attached. */
    suspend fun listForDependant(
        dependantId: String,
        includeInactive: Boolean = false
    ): Result<List<MedicationDto>> =
        ApiCall.execute { api.listMedications(dependantId, includeInactive) }

    suspend fun get(medicationId: String): Result<MedicationDto> =
        ApiCall.execute { api.getMedication(medicationId) }

    suspend fun create(
        dependantId: String,
        request: CreateMedicationRequest
    ): Result<MedicationDto> = ApiCall.execute { api.createMedication(dependantId, request) }

    suspend fun update(
        medicationId: String,
        request: UpdateMedicationRequest
    ): Result<MedicationDto> = ApiCall.execute { api.updateMedication(medicationId, request) }

    suspend fun createSchedule(
        medicationId: String,
        request: CreateDoseScheduleRequest
    ): Result<DoseScheduleDto> = ApiCall.execute { api.createSchedule(medicationId, request) }

    // ---- Additional Feature 4: low-stock refill prediction ----

    suspend fun refillForecast(medicationId: String): Result<RefillForecastDto> =
        ApiCall.execute { api.getRefillForecast(medicationId) }

    /** Already ordered by urgency, so the Refill screen renders the list as it arrives. */
    suspend fun refillForecastsForDependant(dependantId: String): Result<List<RefillForecastDto>> =
        ApiCall.execute { api.listRefillForecasts(dependantId) }
}

class DoseRepository(private val api: TechiesApi) {

    /**
     * Logs a dose. The response carries the refreshed adherence summary, the remaining stock and
     * the recalculated next dose, so the Home screen updates without a second call.
     */
    suspend fun log(request: CreateDoseLogRequest): Result<DoseLogCreatedDto> =
        ApiCall.execute { api.logDose(request) }

    /**
     * Convenience for the one-tap "mark taken" on the Home screen.
     *
     * `scheduledAt` is the slot shown on the card the caregiver actually tapped. Passing it is
     * what makes the tap unambiguous: left out, the server has to work out which slot was meant
     * from the time of day, and its answer can differ from the dose the card was showing.
     */
    suspend fun markTaken(
        scheduleId: String,
        dependantId: String,
        medicationId: String,
        scheduledAt: Instant? = null,
        notes: String? = null
    ): Result<DoseLogCreatedDto> = log(
        CreateDoseLogRequest(
            scheduleId = scheduleId,
            dependantId = dependantId,
            medicationId = medicationId,
            status = DoseStatus.TAKEN,
            scheduledAt = scheduledAt,
            notes = notes
        )
    )

    suspend fun markMissed(
        scheduleId: String,
        dependantId: String,
        medicationId: String,
        scheduledAt: Instant? = null,
        notes: String? = null
    ): Result<DoseLogCreatedDto> = log(
        CreateDoseLogRequest(
            scheduleId = scheduleId,
            dependantId = dependantId,
            medicationId = medicationId,
            status = DoseStatus.MISSED,
            scheduledAt = scheduledAt,
            notes = notes
        )
    )

    suspend fun history(
        dependantId: String,
        from: LocalDate? = null,
        to: LocalDate? = null
    ): Result<List<DoseLogDto>> =
        ApiCall.execute { api.listDoseLogs(dependantId, from.asQuery(), to.asQuery()) }

    // ---- Additional Feature 3: adherence analytics ----

    /** Defaults to the last 30 days, which is what the Trends screen shows. */
    suspend fun adherence(
        dependantId: String,
        from: LocalDate? = null,
        to: LocalDate? = null
    ): Result<AdherenceDto> =
        ApiCall.execute { api.getAdherence(dependantId, from.asQuery(), to.asQuery()) }

    /** POE phase: reconciles the RoomDB queue once connectivity returns. */
    suspend fun syncOfflineLogs(request: SyncBatchRequest): Result<SyncBatchResultDto> =
        ApiCall.execute { api.syncDoseLogs(request) }
}

class AppointmentRepository(private val api: TechiesApi) {

    suspend fun listForDependant(
        dependantId: String,
        upcomingOnly: Boolean = false
    ): Result<List<AppointmentDto>> =
        ApiCall.execute { api.listAppointments(dependantId, upcomingOnly) }

    suspend fun create(
        dependantId: String,
        request: CreateAppointmentRequest
    ): Result<AppointmentDto> = ApiCall.execute { api.createAppointment(dependantId, request) }

    suspend fun update(
        appointmentId: String,
        request: UpdateAppointmentRequest
    ): Result<AppointmentDto> = ApiCall.execute { api.updateAppointment(appointmentId, request) }

    suspend fun cancel(appointmentId: String): Result<Unit> =
        ApiCall.execute { api.cancelAppointment(appointmentId) }
}

/**
 * Documents (Additional Feature 2). Reading is wired up now because the Dependant Profile screen
 * lists them; uploading and deleting arrive with the POE phase, when the camera and file-picker
 * work lands on the client.
 */
/**
 * Document management (Additional Feature 2, section 3.9).
 *
 * The signed download URL that comes back on every read is short-lived — fifteen minutes — so
 * nothing here caches one. [get] exists precisely to ask for a fresh link when an old one has
 * expired, which is cheaper and safer than re-listing.
 */
class DocumentRepository(
    private val api: TechiesApi,
    private val contentResolver: ContentResolver
) {

    suspend fun listForDependant(dependantId: String): Result<List<DocumentDto>> =
        ApiCall.execute { api.listDocuments(dependantId) }

    suspend fun get(documentId: String): Result<DocumentDto> =
        ApiCall.execute { api.getDocument(documentId) }

    /**
     * Uploads a chosen file against a dependant.
     *
     * The file is streamed from its content URI rather than read into memory first; see
     * [ContentUriRequestBody] for why that matters on a handset.
     */
    suspend fun upload(
        dependantId: String,
        file: PickedFile,
        title: String,
        documentType: String
    ): Result<DocumentDto> = ApiCall.execute {
        val part = MultipartBody.Part.createFormData(
            name = "File",
            filename = file.displayName,
            body = ContentUriRequestBody(contentResolver, file)
        )

        api.uploadDocument(
            dependantId = dependantId,
            file = part,
            title = title.trim().toPlainPart(),
            documentType = documentType.toPlainPart()
        )
    }

    suspend fun delete(documentId: String): Result<Unit> =
        ApiCall.execute { api.deleteDocument(documentId) }

    /**
     * A form field with no charset on its Content-Type.
     *
     * Retrofit's default would append `; charset=utf-8`, which ASP.NET Core's multipart binder
     * treats as part of the value rather than metadata — the document type arrives unrecognised
     * and the upload comes back as a validation failure that looks like the app sent nonsense.
     */
    private fun String.toPlainPart(): RequestBody = toRequestBody(null)
}

class NotificationRepository(private val api: TechiesApi) {

    suspend fun list(limit: Int = 50): Result<List<NotificationDto>> =
        ApiCall.execute { api.listNotifications(limit) }
}

/**
 * Pharmacy search (Additional Feature 1, section 3.8).
 *
 * Thin even by the standards of this layer, because the API does all of it: it holds the GeoApify
 * key, calls the Places endpoint, works out each distance with the Haversine formula, sorts the
 * list and caches the response for 24 hours per grid cell. The handset sends two coordinates and
 * renders what comes back.
 */
class PharmacyRepository(private val api: TechiesApi) {

    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMetres: Int = PharmacySearch.DefaultRadiusMetres
    ): Result<PharmacySearchDto> = ApiCall.execute {
        api.findNearbyPharmacies(latitude, longitude, PharmacySearch.clampRadius(radiusMetres))
    }
}

/** The search radii the screen offers, and the bounds the API enforces. */
object PharmacySearch {

    const val MinRadiusMetres = 500
    const val MaxRadiusMetres = 25_000
    const val DefaultRadiusMetres = 5_000

    /** The three chips on the screen: near enough to walk, the default, and a wider sweep. */
    val RadiusChoices = listOf(1_000, 5_000, 10_000)

    /**
     * Holds a radius inside what the API accepts.
     *
     * Clamped on the client as well as the server so a value outside the range comes back as a
     * shorter list rather than a 400 the caregiver cannot act on.
     */
    fun clampRadius(metres: Int): Int = metres.coerceIn(MinRadiusMetres, MaxRadiusMetres)
}

/** The dose statuses the API accepts. */
object DoseStatus {
    const val TAKEN = "taken"
    const val MISSED = "missed"
}

/** The severity bands returned by the refill forecast, for colour-coding the Refill screen. */
object RefillSeverity {
    const val OK = "ok"
    const val LOW = "low"
    const val DEPLETED = "depleted"
}
