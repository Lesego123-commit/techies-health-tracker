// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// The dose-scheduling engine (section 5.1, Service Layer). This is the piece that keeps the
/// Android client lightweight: the client stores nothing but the schedule the user typed in, and
/// the API works out every concrete instant a dose is actually due.
/// </summary>
public interface IDoseScheduleService
{
    /// <summary>Creates a schedule for a medication and returns it with NextDoseAt already calculated.</summary>
    Task<DoseScheduleResponse> CreateAsync(
        Guid medicationId,
        CreateDoseScheduleRequest request,
        CancellationToken cancellationToken = default);

    /// <summary>
    /// The next instant this schedule is due after <paramref name="fromUtc"/>, or null when the
    /// schedule is inactive, has ended, or has no matching day inside the search horizon.
    /// Pure function: no database access, so it can be unit tested directly.
    /// </summary>
    DateTime? CalculateNextDoseAt(DoseSchedule schedule, DateTime fromUtc);

    /// <summary>
    /// Every dose slot this schedule produces in the half-open interval [fromUtc, toUtc).
    /// Adherence statistics are built on this, because "doses missed" only means anything once
    /// you know how many were due in the first place. Pure function.
    /// </summary>
    IReadOnlyList<DateTime> EnumerateSlots(DoseSchedule schedule, DateTime fromUtc, DateTime toUtc);

    /// <summary>
    /// Average number of doses this schedule consumes per day, accounting for schedules that only
    /// apply on certain days. Feeds the refill forecast in section 3.11.
    /// </summary>
    double CalculateDailyDoseRate(DoseSchedule schedule);

    /// <summary>Recomputes and persists NextDoseAt after a dose has been logged.</summary>
    Task RefreshNextDoseAsync(DoseSchedule schedule, CancellationToken cancellationToken = default);
}
