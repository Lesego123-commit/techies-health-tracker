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
/// Offline synchronisation (section 5.4.10). WorkManager on the device posts everything RoomDB
/// has flagged as pending once connectivity returns (Requirement 5).
/// </summary>
[ApiController]
[Route("api/sync")]
[Authorize]
[Produces("application/json")]
public class SyncController : ControllerBase
{
    private readonly ISyncService _syncService;
    private readonly ILogger<SyncController> _logger;

    public SyncController(ISyncService syncService, ILogger<SyncController> logger)
    {
        _syncService = syncService;
        _logger = logger;
    }

    /// <summary>
    /// Reconciles a batch of offline dose logs. Every item is answered individually, so one bad
    /// entry never blocks the rest, and replaying the same batch is safe: an entry the server
    /// already holds comes back as a duplicate rather than being written twice.
    /// </summary>
    [HttpPost("batch")]
    [ProducesResponseType(typeof(SyncBatchResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<SyncBatchResponse>> SyncBatch(
        [FromBody] SyncBatchRequest request, CancellationToken cancellationToken)
    {
        _logger.LogInformation("Received an offline sync batch of {Count} dose log(s)", request.DoseLogs.Count);

        return Ok(await _syncService.SyncDoseLogsAsync(request, cancellationToken));
    }
}
