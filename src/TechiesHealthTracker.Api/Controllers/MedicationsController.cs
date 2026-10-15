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
/// Medication endpoints addressed by medication id (sections 5.4.3, 5.4.4 and 5.4.6). Routes that
/// create a medication live on <see cref="DependantsController"/>, because a medication only ever
/// exists under a dependant.
/// </summary>
[ApiController]
[Route("api/medications")]
[Authorize]
[Produces("application/json")]
public class MedicationsController : ControllerBase
{
    private readonly IMedicationService _medicationService;
    private readonly IDoseScheduleService _scheduleService;
    private readonly IRefillForecastService _refillForecastService;

    public MedicationsController(
        IMedicationService medicationService,
        IDoseScheduleService scheduleService,
        IRefillForecastService refillForecastService)
    {
        _medicationService = medicationService;
        _scheduleService = scheduleService;
        _refillForecastService = refillForecastService;
    }

    /// <summary>Returns one medication with its dose schedules.</summary>
    [HttpGet("{medicationId:guid}")]
    [ProducesResponseType(typeof(MedicationResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<MedicationResponse>> Get(
        Guid medicationId, CancellationToken cancellationToken)
        => Ok(await _medicationService.GetAsync(medicationId, cancellationToken));

    /// <summary>
    /// Updates a medication. Retiring one through <c>isActive</c> also deactivates its schedules,
    /// so reminders stop for a course the caregiver has stopped.
    /// </summary>
    [HttpPut("{medicationId:guid}")]
    [ProducesResponseType(typeof(MedicationResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<MedicationResponse>> Update(
        Guid medicationId,
        [FromBody] UpdateMedicationRequest request,
        CancellationToken cancellationToken)
        => Ok(await _medicationService.UpdateAsync(medicationId, request, cancellationToken));

    /// <summary>
    /// Creates a dose schedule for a medication (section 5.4.4). The response carries the
    /// <c>nextDoseAt</c> the API calculated; the client never supplies it.
    /// </summary>
    [HttpPost("{medicationId:guid}/schedules")]
    [ProducesResponseType(typeof(DoseScheduleResponse), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<DoseScheduleResponse>> CreateSchedule(
        Guid medicationId,
        [FromBody] CreateDoseScheduleRequest request,
        CancellationToken cancellationToken)
    {
        var schedule = await _scheduleService.CreateAsync(medicationId, request, cancellationToken);

        return CreatedAtAction(nameof(Get), new { medicationId }, schedule);
    }

    /// <summary>
    /// Refill prediction for one medication (section 5.4.6): stock on hand, the daily consumption
    /// rate implied by its schedules, and the estimated run-out date (Additional Feature 4).
    /// </summary>
    [HttpGet("{medicationId:guid}/refill-forecast")]
    [ProducesResponseType(typeof(RefillForecastResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<RefillForecastResponse>> GetRefillForecast(
        Guid medicationId, CancellationToken cancellationToken)
        => Ok(await _refillForecastService.ForecastAsync(medicationId, cancellationToken));
}
