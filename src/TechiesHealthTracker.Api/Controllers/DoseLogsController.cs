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
/// Dose logging (section 5.4.5). Reading dose history is a per-dependant query and lives on
/// <see cref="DependantsController"/>; writing a log is not scoped to a route parameter because
/// the schedule id already identifies everything the server needs.
/// </summary>
[ApiController]
[Route("api/doselogs")]
[Authorize]
[Produces("application/json")]
public class DoseLogsController : ControllerBase
{
    private readonly IDoseLogService _doseLogService;

    public DoseLogsController(IDoseLogService doseLogService) => _doseLogService = doseLogService;

    /// <summary>
    /// Records a dose as taken or missed. The response carries the refreshed adherence summary,
    /// the remaining stock and the recalculated next dose, so the Home screen updates its streak
    /// card and dose list without a second round trip.
    /// </summary>
    [HttpPost]
    [ProducesResponseType(typeof(DoseLogCreatedResponse), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<DoseLogCreatedResponse>> Create(
        [FromBody] CreateDoseLogRequest request, CancellationToken cancellationToken)
    {
        var result = await _doseLogService.CreateAsync(request, cancellationToken);

        return CreatedAtAction(
            nameof(DependantsController.ListDoseLogs),
            "Dependants",
            new { dependantId = result.DoseLog.DependantId },
            result);
    }
}
