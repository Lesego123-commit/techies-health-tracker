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
/// Appointment endpoints addressed by appointment id (section 5.4.7). Creating and listing are
/// per-dependant operations and live on <see cref="DependantsController"/>.
/// </summary>
[ApiController]
[Route("api/appointments")]
[Authorize]
[Produces("application/json")]
public class AppointmentsController : ControllerBase
{
    private readonly IAppointmentService _appointmentService;

    public AppointmentsController(IAppointmentService appointmentService)
        => _appointmentService = appointmentService;

    /// <summary>Updates an appointment. Only the supplied fields are changed.</summary>
    [HttpPut("{appointmentId:guid}")]
    [ProducesResponseType(typeof(AppointmentResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<AppointmentResponse>> Update(
        Guid appointmentId,
        [FromBody] UpdateAppointmentRequest request,
        CancellationToken cancellationToken)
        => Ok(await _appointmentService.UpdateAsync(appointmentId, request, cancellationToken));

    /// <summary>
    /// Cancels an appointment. The record is marked cancelled rather than deleted, so the visit
    /// history stays intact. Cancelling twice is not an error.
    /// </summary>
    [HttpDelete("{appointmentId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Cancel(Guid appointmentId, CancellationToken cancellationToken)
    {
        await _appointmentService.CancelAsync(appointmentId, cancellationToken);
        return NoContent();
    }
}
