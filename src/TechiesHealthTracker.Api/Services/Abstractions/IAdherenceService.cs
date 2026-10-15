// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Adherence analytics (Additional Feature 3 and section 3.10). Works out how many doses were
/// actually due over a period, how many were taken, and what pattern the misses follow.
/// </summary>
public interface IAdherenceService
{
    /// <summary>
    /// Adherence for one dependant over a date range. Defaults to the last 30 days when the
    /// range is omitted, which is what the Trends screen asks for.
    /// </summary>
    Task<AdherenceResponse> CalculateAsync(
        Guid dependantId,
        DateOnly? from = null,
        DateOnly? to = null,
        CancellationToken cancellationToken = default);
}
