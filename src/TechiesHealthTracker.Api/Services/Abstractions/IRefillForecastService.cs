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
/// Low-stock refill prediction (Additional Feature 4 and section 3.11). Divides the stock on hand
/// by the daily consumption rate implied by the active schedules to estimate a run-out date. This
/// logic lives entirely on the server, which is what keeps the client lightweight.
/// </summary>
public interface IRefillForecastService
{
    Task<RefillForecastResponse> ForecastAsync(
        Guid medicationId, CancellationToken cancellationToken = default);

    /// <summary>
    /// Forecasts every active medication for a dependant, ordered by urgency. Backs the
    /// Refill and Low-Stock Alerts screen (section 4.8).
    /// </summary>
    Task<IReadOnlyList<RefillForecastResponse>> ForecastForDependantAsync(
        Guid dependantId, CancellationToken cancellationToken = default);
}
