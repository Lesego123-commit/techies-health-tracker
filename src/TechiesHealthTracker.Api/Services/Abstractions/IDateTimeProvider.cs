// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Supplies the current time. Everything that schedules, forecasts or measures adherence depends
/// on this rather than calling DateTime.UtcNow directly, so those calculations can be unit tested
/// against a fixed clock instead of whatever time the test happens to run at.
/// </summary>
public interface IDateTimeProvider
{
    DateTime UtcNow { get; }

    DateOnly Today => DateOnly.FromDateTime(UtcNow);
}
