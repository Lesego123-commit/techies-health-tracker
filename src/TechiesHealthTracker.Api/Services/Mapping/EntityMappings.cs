// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Services.Mapping;

/// <summary>
/// Hand-written entity-to-response mappings. A mapping library was considered and rejected: there
/// are only nine entities, the conversions are explicit about which fields never leave the server
/// (Document.BlobUrl, User.FirebaseUid, User.FcmToken), and a wrong mapping fails to compile here
/// rather than silently at run time.
/// </summary>
public static class EntityMappings
{
    /// <summary>
    /// Quiet hours are returned on a 24-hour clock. The capital HH matters: the lowercase hh
    /// specifier is a 12-hour clock, which would report a 22:00 window start as 10:00.
    /// </summary>
    private const string TimeFormat = @"HH\:mm";

    public static SettingsResponse ToSettingsResponse(this User user) => new()
    {
        PreferredLanguage = user.PreferredLanguage,
        Theme = user.Theme,
        BiometricEnabled = user.BiometricEnabled,
        NotificationsEnabled = user.NotificationsEnabled,
        ReminderLeadTimeMinutes = user.ReminderLeadTimeMinutes,
        LowStockThresholdDays = user.LowStockThresholdDays,
        QuietHoursStart = user.QuietHoursStart?.ToString(TimeFormat),
        QuietHoursEnd = user.QuietHoursEnd?.ToString(TimeFormat),
        UpdatedAt = user.UpdatedAt,
        RowVersion = user.RowVersion
    };

    public static UserResponse ToResponse(
        this User user,
        IEnumerable<DependantResponse>? dependants = null,
        bool isNewUser = false) => new()
    {
        UserId = user.UserId,
        DisplayName = user.DisplayName,
        Email = user.Email,
        PhotoUrl = user.PhotoUrl,
        Settings = user.ToSettingsResponse(),
        Dependants = dependants?.ToList() ?? new List<DependantResponse>(),
        IsNewUser = isNewUser,
        CreatedAt = user.CreatedAt
    };

    public static DependantResponse ToResponse(
        this Dependant dependant,
        DateOnly today,
        int activeMedicationCount = 0) => new()
    {
        DependantId = dependant.DependantId,
        FirstName = dependant.FirstName,
        LastName = dependant.LastName,
        FullName = $"{dependant.FirstName} {dependant.LastName}".Trim(),
        DateOfBirth = dependant.DateOfBirth,
        Age = CalculateAge(dependant.DateOfBirth, today),
        Relationship = dependant.Relationship,
        MedicalAidNumber = dependant.MedicalAidNumber,
        Allergies = dependant.Allergies,
        ActiveMedicationCount = activeMedicationCount,
        CreatedAt = dependant.CreatedAt,
        UpdatedAt = dependant.UpdatedAt,
        RowVersion = dependant.RowVersion
    };

    public static MedicationResponse ToResponse(
        this Medication medication,
        IEnumerable<DoseScheduleResponse>? schedules = null) => new()
    {
        MedicationId = medication.MedicationId,
        DependantId = medication.DependantId,
        Name = medication.Name,
        DosageAmount = medication.DosageAmount,
        DosageUnit = medication.DosageUnit,
        DosageDisplay = $"{medication.DosageAmount} {medication.DosageUnit}".Trim(),
        Form = medication.Form,
        TotalQuantity = medication.TotalQuantity,
        RemainingQuantity = medication.RemainingQuantity,
        PrescribedBy = medication.PrescribedBy,
        StartDate = medication.StartDate,
        EndDate = medication.EndDate,
        Instructions = medication.Instructions,
        IsActive = medication.IsActive,
        Schedules = schedules?.ToList() ?? new List<DoseScheduleResponse>(),
        CreatedAt = medication.CreatedAt,
        UpdatedAt = medication.UpdatedAt,
        RowVersion = medication.RowVersion
    };

    public static DoseScheduleResponse ToResponse(this DoseSchedule schedule) => new()
    {
        ScheduleId = schedule.ScheduleId,
        MedicationId = schedule.MedicationId,
        Frequency = schedule.Frequency,
        Times = schedule.Times.ToList(),
        DaysOfWeek = schedule.DaysOfWeek.ToList(),
        StartDate = schedule.StartDate,
        EndDate = schedule.EndDate,
        NextDoseAt = schedule.NextDoseAt,
        IsActive = schedule.IsActive
    };

    public static DoseLogResponse ToResponse(this DoseLog log, string medicationName = "") => new()
    {
        DoseLogId = log.DoseLogId,
        ScheduleId = log.ScheduleId,
        DependantId = log.DependantId,
        MedicationId = log.MedicationId,
        MedicationName = string.IsNullOrEmpty(medicationName)
            ? log.Medication?.Name ?? string.Empty
            : medicationName,
        Status = log.Status,
        ScheduledAt = log.ScheduledAt,
        LoggedAt = log.LoggedAt,
        Notes = log.Notes,
        RowVersion = log.RowVersion
    };

    public static AppointmentResponse ToResponse(this Appointment appointment, DateTime utcNow) => new()
    {
        AppointmentId = appointment.AppointmentId,
        DependantId = appointment.DependantId,
        Title = appointment.Title,
        AppointmentDateTime = appointment.AppointmentDateTime,
        Location = appointment.Location,
        Notes = appointment.Notes,
        ReminderMinutesBefore = appointment.ReminderMinutesBefore,
        IsCancelled = appointment.IsCancelled,
        IsUpcoming = !appointment.IsCancelled && appointment.AppointmentDateTime > utcNow,
        ReminderAt = appointment.AppointmentDateTime.AddMinutes(-appointment.ReminderMinutesBefore),
        CreatedAt = appointment.CreatedAt,
        UpdatedAt = appointment.UpdatedAt
    };

    /// <summary>
    /// Note the signed URL is passed in rather than read off the entity: <see cref="Document.BlobUrl"/>
    /// is the internal storage path and must never reach the client (section 6.7).
    /// </summary>
    public static DocumentResponse ToResponse(
        this Document document,
        string downloadUrl,
        DateTime downloadUrlExpiresAt) => new()
    {
        DocumentId = document.DocumentId,
        DependantId = document.DependantId,
        Title = document.Title,
        DocumentType = document.DocumentType,
        FileName = document.FileName,
        ContentType = document.ContentType,
        FileSizeBytes = document.FileSizeBytes,
        DownloadUrl = downloadUrl,
        DownloadUrlExpiresAt = downloadUrlExpiresAt,
        UploadedAt = document.UploadedAt
    };

    public static NotificationResponse ToResponse(this NotificationEvent notification) => new()
    {
        NotificationId = notification.NotificationId,
        DependantId = notification.DependantId,
        Category = notification.Category,
        Title = notification.Title,
        Body = notification.Body,
        DeepLink = notification.DeepLink,
        IsSent = notification.IsSent,
        SentAt = notification.SentAt,
        CreatedAt = notification.CreatedAt
    };

    /// <summary>
    /// Age in completed years. Calculated server-side so that the Home screen, the Dependant
    /// Profile and any future report can never disagree about how old a dependant is.
    /// </summary>
    public static int CalculateAge(DateOnly dateOfBirth, DateOnly today)
    {
        if (dateOfBirth > today)
        {
            return 0;
        }

        var age = today.Year - dateOfBirth.Year;

        // Their birthday has not come round yet this year.
        if (dateOfBirth.AddYears(age) > today)
        {
            age--;
        }

        return age;
    }
}
