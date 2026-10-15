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
using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Data;

namespace TechiesHealthTracker.Api.Controllers;

/// <summary>
/// Liveness and readiness probe. Azure App Service uses this to decide whether an instance is
/// healthy, so it is anonymous and deliberately cheap.
/// </summary>
[ApiController]
[Route("api/health")]
[AllowAnonymous]
[Produces("application/json")]
public class HealthController : ControllerBase
{
    private readonly AppDbContext _context;
    private readonly ILogger<HealthController> _logger;

    public HealthController(AppDbContext context, ILogger<HealthController> logger)
    {
        _context = context;
        _logger = logger;
    }

    /// <summary>Confirms the API is running and can reach its database.</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status503ServiceUnavailable)]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        bool databaseReachable;

        try
        {
            databaseReachable = await _context.Database.CanConnectAsync(cancellationToken);
        }
        catch (Exception ex)
        {
            // A health probe never throws: an unreachable database is a reported state, not a
            // 500, or the platform cannot tell the difference between "down" and "broken".
            _logger.LogError(ex, "Health check could not reach the database");
            databaseReachable = false;
        }

        var payload = new
        {
            status = databaseReachable ? "healthy" : "degraded",
            database = databaseReachable ? "reachable" : "unreachable",
            timestamp = DateTime.UtcNow
        };

        return databaseReachable
            ? Ok(payload)
            : StatusCode(StatusCodes.Status503ServiceUnavailable, payload);
    }
}
