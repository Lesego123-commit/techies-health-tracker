// ----------------------------------------------------------------------------------------------
//  References
//   Square. 2024. Retrofit — A Type-safe HTTP Client for Android and Java. [Online]. Available
//    at: https://square.github.io/retrofit/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
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

/**
 * Every endpoint exposed by the Techies Health Tracker API, matching section 5.4 of the Part 1
 * Planning and Design document.
 *
 * All calls carry a Firebase identity token, attached automatically by `AuthInterceptor`. The
 * only exception is the signed document download, which authenticates through its own URL
 * signature and is fetched directly rather than through this interface.
 *
 * Dates in query parameters are `yyyy-MM-dd` strings; the API parses them as calendar dates.
 */
interface TechiesApi {

    // ---- Authentication and users (section 5.4.1) ----

    /**
     * Validates the Firebase token and returns the caller's profile, creating it on first sign-in.
     *
     * **Call this on every launch.** It is idempotent, and every other endpoint returns 404 until
     * it has run at least once for the account. The response includes the dependants list, so the
     * Home screen can render from this one call.
     */
    @POST("api/auth/login")
    suspend fun login(@Query("fcmToken") fcmToken: String? = null): UserDto

    /** The caller's profile without the write that [login] performs. */
    @GET("api/auth/me")
    suspend fun me(): UserDto

    @GET("api/users/{userId}/settings")
    suspend fun getSettings(@Path("userId") userId: String): SettingsDto

    /** Partial update: send only the preferences that changed. */
    @PUT("api/users/{userId}/settings")
    suspend fun updateSettings(
        @Path("userId") userId: String,
        @Body request: UpdateSettingsRequest
    ): SettingsDto

    // ---- Dependants (section 5.4.2) ----

    @POST("api/dependants")
    suspend fun createDependant(@Body request: CreateDependantRequest): DependantDto

    @GET("api/dependants")
    suspend fun listDependants(): List<DependantDto>

    @GET("api/dependants/{dependantId}")
    suspend fun getDependant(@Path("dependantId") dependantId: String): DependantDto

    @PUT("api/dependants/{dependantId}")
    suspend fun updateDependant(
        @Path("dependantId") dependantId: String,
        @Body request: UpdateDependantRequest
    ): DependantDto

    /** Soft-deletes the profile and retires its medications, keeping the dose history. */
    @DELETE("api/dependants/{dependantId}")
    suspend fun deleteDependant(@Path("dependantId") dependantId: String)

    // ---- Medications and schedules (sections 5.4.3, 5.4.4) ----

    @POST("api/dependants/{dependantId}/medications")
    suspend fun createMedication(
        @Path("dependantId") dependantId: String,
        @Body request: CreateMedicationRequest
    ): MedicationDto

    /** Each medication comes back with its schedules attached. */
    @GET("api/dependants/{dependantId}/medications")
    suspend fun listMedications(
        @Path("dependantId") dependantId: String,
        @Query("includeInactive") includeInactive: Boolean = false
    ): List<MedicationDto>

    @GET("api/medications/{medicationId}")
    suspend fun getMedication(@Path("medicationId") medicationId: String): MedicationDto

    @PUT("api/medications/{medicationId}")
    suspend fun updateMedication(
        @Path("medicationId") medicationId: String,
        @Body request: UpdateMedicationRequest
    ): MedicationDto

    /** The response carries the `nextDoseAt` the API calculated. The client never supplies it. */
    @POST("api/medications/{medicationId}/schedules")
    suspend fun createSchedule(
        @Path("medicationId") medicationId: String,
        @Body request: CreateDoseScheduleRequest
    ): DoseScheduleDto

    // ---- Refill forecast (section 5.4.6, Additional Feature 4) ----

    @GET("api/medications/{medicationId}/refill-forecast")
    suspend fun getRefillForecast(@Path("medicationId") medicationId: String): RefillForecastDto

    /** All of a dependant's forecasts, already ranked by urgency. */
    @GET("api/dependants/{dependantId}/refill-forecast")
    suspend fun listRefillForecasts(
        @Path("dependantId") dependantId: String
    ): List<RefillForecastDto>

    // ---- Dose logs and adherence (section 5.4.5, Additional Feature 3) ----

    /**
     * Logs a dose as taken or missed. The three ids and a status are enough — the API resolves
     * which scheduled slot the tap belongs to.
     */
    @POST("api/doselogs")
    suspend fun logDose(@Body request: CreateDoseLogRequest): DoseLogCreatedDto

    @GET("api/dependants/{dependantId}/doselogs")
    suspend fun listDoseLogs(
        @Path("dependantId") dependantId: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): List<DoseLogDto>

    /** Defaults to the last 30 days when no range is given. */
    @GET("api/dependants/{dependantId}/adherence")
    suspend fun getAdherence(
        @Path("dependantId") dependantId: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): AdherenceDto

    // ---- Appointments (section 5.4.7) ----

    @POST("api/dependants/{dependantId}/appointments")
    suspend fun createAppointment(
        @Path("dependantId") dependantId: String,
        @Body request: CreateAppointmentRequest
    ): AppointmentDto

    /** Upcoming appointments first, then past ones newest first. */
    @GET("api/dependants/{dependantId}/appointments")
    suspend fun listAppointments(
        @Path("dependantId") dependantId: String,
        @Query("upcomingOnly") upcomingOnly: Boolean = false
    ): List<AppointmentDto>

    @PUT("api/appointments/{appointmentId}")
    suspend fun updateAppointment(
        @Path("appointmentId") appointmentId: String,
        @Body request: UpdateAppointmentRequest
    ): AppointmentDto

    /** Marks the appointment cancelled rather than deleting it. Safe to call twice. */
    @DELETE("api/appointments/{appointmentId}")
    suspend fun cancelAppointment(@Path("appointmentId") appointmentId: String)

    // ---- Documents (section 5.4.9, Additional Feature 2) ----

    /** Maximum 10 MB. JPEG, PNG, HEIC, WebP and PDF only. */
    @Multipart
    @POST("api/dependants/{dependantId}/documents")
    suspend fun uploadDocument(
        @Path("dependantId") dependantId: String,
        @Part file: MultipartBody.Part,
        @Part("Title") title: RequestBody,
        @Part("DocumentType") documentType: RequestBody
    ): DocumentDto

    @GET("api/dependants/{dependantId}/documents")
    suspend fun listDocuments(@Path("dependantId") dependantId: String): List<DocumentDto>

    /** Returns metadata with a freshly signed download URL. */
    @GET("api/documents/{documentId}")
    suspend fun getDocument(@Path("documentId") documentId: String): DocumentDto

    @DELETE("api/documents/{documentId}")
    suspend fun deleteDocument(@Path("documentId") documentId: String)

    // ---- Pharmacy finder (section 5.4.8, Additional Feature 1) ----

    /** Results arrive sorted by distance. Radius is in metres, between 500 and 25000. */
    @GET("api/pharmacies/nearby")
    suspend fun findNearbyPharmacies(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("radius") radiusMetres: Int = 5000
    ): PharmacySearchDto

    // ---- Offline synchronisation (section 5.4.10, Requirement 5) ----

    /** Every item is answered individually, so one bad entry never blocks the rest. */
    @POST("api/sync/batch")
    suspend fun syncDoseLogs(@Body request: SyncBatchRequest): SyncBatchResultDto

    // ---- Notifications ----

    @GET("api/notifications")
    suspend fun listNotifications(@Query("limit") limit: Int = 50): List<NotificationDto>

    // ---- Health ----

    /** Anonymous liveness probe. Useful for a "cannot reach the server" diagnostic screen. */
    @GET("api/health")
    suspend fun health(): retrofit2.Response<Unit>
}
