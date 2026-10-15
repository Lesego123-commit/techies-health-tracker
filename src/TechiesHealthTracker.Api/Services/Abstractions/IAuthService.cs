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
/// Handles POST /api/auth/login (Requirement 1). Firebase has already verified the identity by
/// the time this runs; the job here is to find or create the matching Users row so that the rest
/// of the API has a local id to hang dependants, medications and notifications off.
/// </summary>
public interface IAuthService
{
    /// <summary>
    /// Returns the profile for the caller, creating it on first sign-in. Idempotent: the Android
    /// client calls this on every launch and must get the same user back each time.
    /// </summary>
    Task<UserResponse> LoginAsync(string? fcmToken, CancellationToken cancellationToken = default);
}
