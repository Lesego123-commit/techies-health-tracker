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
/// Handles PUT /api/users/{userId}/settings (Requirement 3). Preferences are stored server-side
/// rather than only on the device so that they survive a reinstall or a change of phone.
/// </summary>
public interface IUserSettingsService
{
    Task<SettingsResponse> UpdateSettingsAsync(
        Guid userId,
        UpdateSettingsRequest request,
        CancellationToken cancellationToken = default);

    Task<SettingsResponse> GetSettingsAsync(Guid userId, CancellationToken cancellationToken = default);
}
