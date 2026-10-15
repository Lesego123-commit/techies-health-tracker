// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// The single place where "does this record belong to the caller?" is answered. Section 3.12 of
/// the design document requires that a caregiver can only reach their own dependants and
/// everything hanging off them; concentrating that rule here means a new endpoint cannot quietly
/// forget to apply it, and it is the part of the API most worth testing directly.
///
/// Every method loads the record, walks back to its owning dependant, and throws
/// <see cref="Common.NotFoundException"/> when it does not exist or
/// <see cref="Common.ForbiddenException"/> when it belongs to someone else.
/// </summary>
public interface IAccessGuard
{
    Task<Dependant> EnsureDependantAsync(Guid dependantId, CancellationToken cancellationToken = default);

    Task<Medication> EnsureMedicationAsync(Guid medicationId, CancellationToken cancellationToken = default);

    Task<DoseSchedule> EnsureScheduleAsync(Guid scheduleId, CancellationToken cancellationToken = default);

    Task<Appointment> EnsureAppointmentAsync(Guid appointmentId, CancellationToken cancellationToken = default);

    Task<Document> EnsureDocumentAsync(Guid documentId, CancellationToken cancellationToken = default);
}
