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

namespace TechiesHealthTracker.Api.Controllers;

/// <summary>
/// Notification history, backing the Notifications screen (section 4.12). The screen reads
/// straight off the NotificationEvents table, so what the caregiver sees in the app and what the
/// API decided to send can never drift apart.
/// </summary>
[ApiController]
[Route("api/notifications")]
[Authorize]
[Produces("application/json")]
public class NotificationsController : ControllerBase
{
    private readonly INotificationService _notificationService;
    private readonly ICurrentUserService _currentUser;

    public NotificationsController(
        INotificationService notificationService, ICurrentUserService currentUser)
    {
        _notificationService = notificationService;
        _currentUser = currentUser;
    }

    /// <summary>Returns the caller's notification history, newest first.</summary>
    /// <param name="limit">Maximum number of entries to return. Clamped to between 1 and 200.</param>
    [HttpGet]
    [ProducesResponseType(typeof(IReadOnlyList<NotificationResponse>), StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<NotificationResponse>>> List(
        [FromQuery] int limit = 50, CancellationToken cancellationToken = default)
    {
        var userId = await _currentUser.GetRequiredUserIdAsync(cancellationToken);

        return Ok(await _notificationService.ListForUserAsync(userId, limit, cancellationToken));
    }
}
