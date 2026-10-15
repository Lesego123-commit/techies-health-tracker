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

/// <summary>
/// The /api/doselogs endpoints (section 5.4.5). Logging a dose is the busiest write in the app
/// and the one that fans out furthest: it records the log, decrements the medication stock,
/// recalculates the schedule's next dose and can raise a refill alert, all in one transaction.
/// </summary>
public interface IDoseLogService
{
    Task<DoseLogCreatedResponse> CreateAsync(
        CreateDoseLogRequest request, CancellationToken cancellationToken = default);

    /// <summary>Dose history for a dependant over a date range, newest first.</summary>
    Task<IReadOnlyList<DoseLogResponse>> GetHistoryAsync(
        Guid dependantId,
        DateOnly? from = null,
        DateOnly? to = null,
        CancellationToken cancellationToken = default);
}
