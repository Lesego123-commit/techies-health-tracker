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
/// Resolves the caller of the current request. Services take a dependency on this rather than
/// reading HttpContext directly, which keeps them testable and means an ownership rule cannot
/// accidentally be written against a user id supplied in the request body.
/// </summary>
public interface ICurrentUserService
{
    /// <summary>The Firebase UID from the validated identity token, or null when unauthenticated.</summary>
    string? FirebaseUid { get; }

    /// <summary>Email claim from the token, used when provisioning a profile on first sign-in.</summary>
    string? Email { get; }

    /// <summary>Display name claim from the Google profile, if the token carries one.</summary>
    string? DisplayName { get; }

    /// <summary>Profile picture claim from the Google profile, if the token carries one.</summary>
    string? PhotoUrl { get; }

    bool IsAuthenticated { get; }

    /// <summary>
    /// Loads the Users row for the caller, throwing rather than returning null: every endpoint
    /// behind [Authorize] must have a profile by the time it runs.
    /// </summary>
    Task<User> GetRequiredUserAsync(CancellationToken cancellationToken = default);

    /// <summary>The caller's UserId. Convenience wrapper over <see cref="GetRequiredUserAsync"/>.</summary>
    Task<Guid> GetRequiredUserIdAsync(CancellationToken cancellationToken = default);
}
