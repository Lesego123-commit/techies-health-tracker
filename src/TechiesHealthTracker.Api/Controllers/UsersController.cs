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
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Controllers;

/// <summary>
/// User preference endpoints (section 5.4.1), backing the Settings screen (Requirement 3).
/// </summary>
[ApiController]
[Route("api/users")]
[Authorize]
[Produces("application/json")]
public class UsersController : ControllerBase
{
    private readonly IUserSettingsService _settingsService;

    public UsersController(IUserSettingsService settingsService) => _settingsService = settingsService;

    /// <summary>Returns the caller's stored preferences.</summary>
    [HttpGet("{userId:guid}/settings")]
    [ProducesResponseType(typeof(SettingsResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<SettingsResponse>> GetSettings(
        Guid userId, CancellationToken cancellationToken)
        => Ok(await _settingsService.GetSettingsAsync(userId, cancellationToken));

    /// <summary>
    /// Updates the caller's preferences. Every field is optional, so the Settings screen can send
    /// only what changed. The userId in the route must be the caller's own.
    /// </summary>
    [HttpPut("{userId:guid}/settings")]
    [ProducesResponseType(typeof(SettingsResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<SettingsResponse>> UpdateSettings(
        Guid userId,
        [FromBody] UpdateSettingsRequest request,
        CancellationToken cancellationToken)
        => Ok(await _settingsService.UpdateSettingsAsync(userId, request, cancellationToken));
}
