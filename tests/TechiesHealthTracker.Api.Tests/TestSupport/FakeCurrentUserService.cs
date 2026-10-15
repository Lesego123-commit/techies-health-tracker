// ----------------------------------------------------------------------------------------------
//  References
//   .NET Foundation. 2024. Unit Testing in .NET with xUnit. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/core/testing/unit-testing-with-dotnet-test
//    [Accessed 16 September 2026].
//   Fluent Assertions. 2024. Documentation. [Online]. Available at:
//    https://fluentassertions.com/introduction [Accessed 16 September 2026].
//   Moq. 2024. Quickstart. [Online]. Available at:
//    https://github.com/devlooped/moq/wiki/Quickstart [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Tests.TestSupport;

/// <summary>
/// Stands in for the signed-in caregiver.
///
/// The real implementation reads claims off an HttpContext, which a service-level test has no
/// reason to construct. Swapping in this fake is what lets the ownership rules be tested directly:
/// point it at one user, ask for another user's dependant, and assert on the refusal.
/// </summary>
public class FakeCurrentUserService : ICurrentUserService
{
    private User? _user;

    public FakeCurrentUserService(User? user = null) => _user = user;

    /// <summary>Switches the signed-in caregiver mid-test.</summary>
    public void SignInAs(User user) => _user = user;

    /// <summary>Simulates an unauthenticated request.</summary>
    public void SignOut() => _user = null;

    public string? FirebaseUid => _user?.FirebaseUid;

    public string? Email => _user?.Email;

    public string? DisplayName => _user?.DisplayName;

    public string? PhotoUrl => _user?.PhotoUrl;

    public bool IsAuthenticated => _user is not null;

    public Task<User> GetRequiredUserAsync(CancellationToken cancellationToken = default)
        => _user is null
            ? throw new ForbiddenException("The request is not associated with an authenticated user.")
            : Task.FromResult(_user);

    public Task<Guid> GetRequiredUserIdAsync(CancellationToken cancellationToken = default)
        => _user is null
            ? throw new ForbiddenException("The request is not associated with an authenticated user.")
            : Task.FromResult(_user.UserId);
}
