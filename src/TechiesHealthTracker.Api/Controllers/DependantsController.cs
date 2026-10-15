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
/// Dependant endpoints (section 5.4.2) and everything nested under a dependant: medications,
/// dose history, adherence, appointments and documents.
///
/// These nested routes are grouped here rather than split across controllers because they share
/// one thing that matters: the dependant id in the route is the ownership boundary, and each
/// service re-checks it through <see cref="IAccessGuard"/> before touching anything.
/// </summary>
[ApiController]
[Route("api/dependants")]
[Authorize]
[Produces("application/json")]
public class DependantsController : ControllerBase
{
    private readonly IDependantService _dependantService;
    private readonly IMedicationService _medicationService;
    private readonly IDoseLogService _doseLogService;
    private readonly IAdherenceService _adherenceService;
    private readonly IAppointmentService _appointmentService;
    private readonly IRefillForecastService _refillForecastService;
    private readonly ILogger<DependantsController> _logger;

    public DependantsController(
        IDependantService dependantService,
        IMedicationService medicationService,
        IDoseLogService doseLogService,
        IAdherenceService adherenceService,
        IAppointmentService appointmentService,
        IRefillForecastService refillForecastService,
        ILogger<DependantsController> logger)
    {
        _dependantService = dependantService;
        _medicationService = medicationService;
        _doseLogService = doseLogService;
        _adherenceService = adherenceService;
        _appointmentService = appointmentService;
        _refillForecastService = refillForecastService;
        _logger = logger;
    }

    // ---- Dependants (section 5.4.2) ----

    /// <summary>Creates a dependant profile under the caller's account.</summary>
    [HttpPost]
    [ProducesResponseType(typeof(DependantResponse), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<DependantResponse>> Create(
        [FromBody] CreateDependantRequest request, CancellationToken cancellationToken)
    {
        var dependant = await _dependantService.CreateAsync(request, cancellationToken);

        _logger.LogInformation("Dependant {DependantId} created", dependant.DependantId);

        return CreatedAtAction(nameof(Get), new { dependantId = dependant.DependantId }, dependant);
    }

    /// <summary>Lists every active dependant belonging to the caller.</summary>
    [HttpGet]
    [ProducesResponseType(typeof(IReadOnlyList<DependantResponse>), StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<DependantResponse>>> List(
        CancellationToken cancellationToken)
        => Ok(await _dependantService.ListForCurrentUserAsync(cancellationToken));

    /// <summary>Returns one dependant profile.</summary>
    [HttpGet("{dependantId:guid}")]
    [ProducesResponseType(typeof(DependantResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<DependantResponse>> Get(
        Guid dependantId, CancellationToken cancellationToken)
        => Ok(await _dependantService.GetAsync(dependantId, cancellationToken));

    /// <summary>Updates a dependant profile. Only the supplied fields are changed.</summary>
    [HttpPut("{dependantId:guid}")]
    [ProducesResponseType(typeof(DependantResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<DependantResponse>> Update(
        Guid dependantId,
        [FromBody] UpdateDependantRequest request,
        CancellationToken cancellationToken)
        => Ok(await _dependantService.UpdateAsync(dependantId, request, cancellationToken));

    /// <summary>Soft-deletes a dependant and retires their medications, keeping the dose history.</summary>
    [HttpDelete("{dependantId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid dependantId, CancellationToken cancellationToken)
    {
        await _dependantService.DeleteAsync(dependantId, cancellationToken);
        return NoContent();
    }

    // ---- Medications (section 5.4.3) ----

    /// <summary>Adds a medication to a dependant.</summary>
    [HttpPost("{dependantId:guid}/medications")]
    [ProducesResponseType(typeof(MedicationResponse), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<MedicationResponse>> CreateMedication(
        Guid dependantId,
        [FromBody] CreateMedicationRequest request,
        CancellationToken cancellationToken)
    {
        var medication = await _medicationService.CreateAsync(dependantId, request, cancellationToken);

        return CreatedAtAction(
            nameof(MedicationsController.Get),
            "Medications",
            new { medicationId = medication.MedicationId },
            medication);
    }

    /// <summary>Lists a dependant's medications, each with its dose schedules attached.</summary>
    [HttpGet("{dependantId:guid}/medications")]
    [ProducesResponseType(typeof(IReadOnlyList<MedicationResponse>), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<IReadOnlyList<MedicationResponse>>> ListMedications(
        Guid dependantId,
        [FromQuery] bool includeInactive = false,
        CancellationToken cancellationToken = default)
        => Ok(await _medicationService.ListForDependantAsync(dependantId, includeInactive, cancellationToken));

    /// <summary>
    /// Refill forecasts for all of a dependant's active medications, ordered by urgency.
    /// Backs the Refill and Low-Stock Alerts screen (Additional Feature 4).
    /// </summary>
    [HttpGet("{dependantId:guid}/refill-forecast")]
    [ProducesResponseType(typeof(IReadOnlyList<RefillForecastResponse>), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<IReadOnlyList<RefillForecastResponse>>> ListRefillForecasts(
        Guid dependantId, CancellationToken cancellationToken)
        => Ok(await _refillForecastService.ForecastForDependantAsync(dependantId, cancellationToken));

    // ---- Dose logs and adherence (section 5.4.5) ----

    /// <summary>Dose history for a dependant over a date range, newest first.</summary>
    [HttpGet("{dependantId:guid}/doselogs")]
    [ProducesResponseType(typeof(IReadOnlyList<DoseLogResponse>), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<IReadOnlyList<DoseLogResponse>>> ListDoseLogs(
        Guid dependantId,
        [FromQuery] DateOnly? from,
        [FromQuery] DateOnly? to,
        CancellationToken cancellationToken)
        => Ok(await _doseLogService.GetHistoryAsync(dependantId, from, to, cancellationToken));

    /// <summary>
    /// Adherence statistics and trends for a dependant, defaulting to the last 30 days.
    /// Backs the Trends screen (Additional Feature 3).
    /// </summary>
    [HttpGet("{dependantId:guid}/adherence")]
    [ProducesResponseType(typeof(AdherenceResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<AdherenceResponse>> GetAdherence(
        Guid dependantId,
        [FromQuery] DateOnly? from,
        [FromQuery] DateOnly? to,
        CancellationToken cancellationToken)
        => Ok(await _adherenceService.CalculateAsync(dependantId, from, to, cancellationToken));

    // ---- Appointments (section 5.4.7) ----

    /// <summary>Books an appointment for a dependant.</summary>
    [HttpPost("{dependantId:guid}/appointments")]
    [ProducesResponseType(typeof(AppointmentResponse), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<AppointmentResponse>> CreateAppointment(
        Guid dependantId,
        [FromBody] CreateAppointmentRequest request,
        CancellationToken cancellationToken)
    {
        var appointment = await _appointmentService.CreateAsync(dependantId, request, cancellationToken);

        return CreatedAtAction(
            nameof(AppointmentsController.Update),
            "Appointments",
            new { appointmentId = appointment.AppointmentId },
            appointment);
    }

    /// <summary>Lists a dependant's appointments, upcoming ones first.</summary>
    [HttpGet("{dependantId:guid}/appointments")]
    [ProducesResponseType(typeof(IReadOnlyList<AppointmentResponse>), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<IReadOnlyList<AppointmentResponse>>> ListAppointments(
        Guid dependantId,
        [FromQuery] bool upcomingOnly = false,
        CancellationToken cancellationToken = default)
        => Ok(await _appointmentService.ListForDependantAsync(dependantId, upcomingOnly, cancellationToken));
}
