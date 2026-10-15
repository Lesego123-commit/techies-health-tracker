// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Routing to Controller Actions in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/controllers/routing [Accessed 16 September 2026].
//   Microsoft. 2024. Create Web APIs with ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/web-api/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Controllers;

/// <summary>
/// Authentication endpoints (section 5.4.1). Firebase has already established who the caller is
/// by the time a request reaches here; this controller exchanges that identity for a local profile.
/// </summary>
[ApiController]
[Route("api/auth")]
[Authorize]
[Produces("application/json")]
public class AuthController : ControllerBase
{
    private readonly IAuthService _authService;
    private readonly ILogger<AuthController> _logger;

    public AuthController(IAuthService authService, ILogger<AuthController> logger)
    {
        _authService = authService;
        _logger = logger;
    }

    /// <summary>
    /// Validates the Firebase identity token and returns the caller's profile, creating it on
    /// first sign-in. The Android client calls this on every launch, so it is idempotent.
    /// </summary>
    /// <param name="fcmToken">
    /// The device's current Firebase Cloud Messaging token. Optional, and sent as a query
    /// parameter so the endpoint keeps the empty request body described in the design document.
    /// </param>
    [HttpPost("login")]
    [ProducesResponseType(typeof(UserResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    public async Task<ActionResult<UserResponse>> Login(
        [FromQuery] string? fcmToken, CancellationToken cancellationToken)
    {
        var response = await _authService.LoginAsync(fcmToken, cancellationToken);

        _logger.LogInformation(
            "Login completed for user {UserId} (new profile: {IsNewUser})",
            response.UserId, response.IsNewUser);

        return Ok(response);
    }

    /// <summary>
    /// Returns the caller's profile without creating one. Useful for confirming a token is still
    /// valid without the write that <c>login</c> performs.
    /// </summary>
    [HttpGet("me")]
    [ProducesResponseType(typeof(UserResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<UserResponse>> Me(
        [FromServices] ICurrentUserService currentUser, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetRequiredUserAsync(cancellationToken);
        return Ok(user.ToResponse());
    }
}
