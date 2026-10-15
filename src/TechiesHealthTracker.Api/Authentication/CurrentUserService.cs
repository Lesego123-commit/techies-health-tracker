// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Configure JWT Bearer Authentication in ASP.NET Core. [Online]. Available
//    at: https://learn.microsoft.com/aspnet/core/security/authentication/configure-jwt-bearer-authentication
//    [Accessed 16 September 2026].
//   Google. 2024. Verify ID Tokens — Firebase Authentication. [Online]. Available at:
//    https://firebase.google.com/docs/auth/admin/verify-id-tokens [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.1 (Requirement 1: SSO).
// ----------------------------------------------------------------------------------------------

using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Authentication;

/// <summary>
/// Reads the caller's identity out of the validated Firebase token on the current request.
/// Registered as scoped, so the Users row is loaded at most once per request even when several
/// services ask for it.
/// </summary>
public class CurrentUserService : ICurrentUserService
{
    private readonly IHttpContextAccessor _httpContextAccessor;
    private readonly AppDbContext _context;
    private User? _cachedUser;

    public CurrentUserService(IHttpContextAccessor httpContextAccessor, AppDbContext context)
    {
        _httpContextAccessor = httpContextAccessor;
        _context = context;
    }

    private ClaimsPrincipal? Principal => _httpContextAccessor.HttpContext?.User;

    /// <summary>
    /// Firebase puts the UID in user_id and mirrors it in sub. Both are checked because the
    /// development token used for local testing only carries the standard sub claim.
    /// </summary>
    public string? FirebaseUid =>
        Principal?.FindFirstValue("user_id")
        ?? Principal?.FindFirstValue(ClaimTypes.NameIdentifier)
        ?? Principal?.FindFirstValue("sub");

    public string? Email =>
        Principal?.FindFirstValue(ClaimTypes.Email) ?? Principal?.FindFirstValue("email");

    public string? DisplayName =>
        Principal?.FindFirstValue("name") ?? Principal?.FindFirstValue(ClaimTypes.Name);

    public string? PhotoUrl => Principal?.FindFirstValue("picture");

    public bool IsAuthenticated =>
        Principal?.Identity?.IsAuthenticated == true && !string.IsNullOrWhiteSpace(FirebaseUid);

    public async Task<User> GetRequiredUserAsync(CancellationToken cancellationToken = default)
    {
        if (_cachedUser is not null)
        {
            return _cachedUser;
        }

        var firebaseUid = FirebaseUid;

        if (string.IsNullOrWhiteSpace(firebaseUid))
        {
            throw new ForbiddenException("The request is not associated with an authenticated user.");
        }

        _cachedUser = await _context.Users
            .FirstOrDefaultAsync(u => u.FirebaseUid == firebaseUid, cancellationToken);

        if (_cachedUser is null)
        {
            // A valid token with no matching row means the client skipped POST /api/auth/login,
            // which is the call that provisions the profile on first sign-in.
            throw new NotFoundException(
                "No profile exists for this account yet. Call POST /api/auth/login first.");
        }

        return _cachedUser;
    }

    public async Task<Guid> GetRequiredUserIdAsync(CancellationToken cancellationToken = default)
        => (await GetRequiredUserAsync(cancellationToken)).UserId;
}
