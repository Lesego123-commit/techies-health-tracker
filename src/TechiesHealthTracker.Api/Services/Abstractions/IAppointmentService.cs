// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>The /api/appointments endpoints (section 5.4.7).</summary>
public interface IAppointmentService
{
    Task<AppointmentResponse> CreateAsync(
        Guid dependantId, CreateAppointmentRequest request, CancellationToken cancellationToken = default);

    /// <summary>
    /// Appointments for a dependant. Upcoming ones come first in chronological order, then past
    /// ones newest first, matching the two sections on the Appointments screen.
    /// </summary>
    Task<IReadOnlyList<AppointmentResponse>> ListForDependantAsync(
        Guid dependantId, bool upcomingOnly = false, CancellationToken cancellationToken = default);

    Task<AppointmentResponse> UpdateAsync(
        Guid appointmentId, UpdateAppointmentRequest request, CancellationToken cancellationToken = default);

    /// <summary>Marks the appointment cancelled rather than deleting it, so the history survives.</summary>
    Task CancelAsync(Guid appointmentId, CancellationToken cancellationToken = default);
}
