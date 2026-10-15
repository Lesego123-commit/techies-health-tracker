// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Low-stock refill prediction (Additional Feature 4, section 3.11).
///
/// The calculation is deliberately simple and explainable: stock on hand divided by the number of
/// units the active schedules consume per day gives the number of days left, which is projected
/// forward onto a calendar date. A caregiver can check the answer in their head, which matters
/// more here than statistical sophistication would.
/// </summary>
public class RefillForecastService : IRefillForecastService
{
    private readonly AppDbContext _context;
    private readonly IAccessGuard _accessGuard;
    private readonly IDoseScheduleService _scheduleService;
    private readonly ICurrentUserService _currentUser;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<RefillForecastService> _logger;

    /// <summary>Severity values returned to the client, which colour-codes the Refill screen from them.</summary>
    private const string SeverityOk = "ok";
    private const string SeverityLow = "low";
    private const string SeverityDepleted = "depleted";

    public RefillForecastService(
        AppDbContext context,
        IAccessGuard accessGuard,
        IDoseScheduleService scheduleService,
        ICurrentUserService currentUser,
        IDateTimeProvider clock,
        ILogger<RefillForecastService> logger)
    {
        _context = context;
        _accessGuard = accessGuard;
        _scheduleService = scheduleService;
        _currentUser = currentUser;
        _clock = clock;
        _logger = logger;
    }

    public async Task<RefillForecastResponse> ForecastAsync(
        Guid medicationId, CancellationToken cancellationToken = default)
    {
        var medication = await _accessGuard.EnsureMedicationAsync(medicationId, cancellationToken);

        var schedules = await _context.DoseSchedules
            .AsNoTracking()
            .Where(s => s.MedicationId == medicationId && s.IsActive)
            .ToListAsync(cancellationToken);

        var user = await _currentUser.GetRequiredUserAsync(cancellationToken);

        return Forecast(medication, schedules, user.LowStockThresholdDays);
    }

    public async Task<IReadOnlyList<RefillForecastResponse>> ForecastForDependantAsync(
        Guid dependantId, CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var medications = await _context.Medications
            .AsNoTracking()
            .Include(m => m.DoseSchedules)
            .Where(m => m.DependantId == dependantId && m.IsActive)
            .ToListAsync(cancellationToken);

        var user = await _currentUser.GetRequiredUserAsync(cancellationToken);

        var forecasts = medications
            .Select(m => Forecast(
                m,
                m.DoseSchedules.Where(s => s.IsActive).ToList(),
                user.LowStockThresholdDays))
            .ToList();

        // The Refill and Low-Stock Alerts screen ranks by urgency, so anything already out or
        // running out soonest comes first. Medications with no schedule have no run-out date and
        // are pushed to the bottom rather than being treated as urgent.
        return forecasts
            .OrderBy(f => f.DaysUntilRunOut ?? int.MaxValue)
            .ThenBy(f => f.MedicationName)
            .ToList();
    }

    /// <summary>
    /// The forecast itself, kept free of database access so it can be unit tested directly
    /// against a medication and a set of schedules.
    /// </summary>
    internal RefillForecastResponse Forecast(
        Medication medication,
        IReadOnlyCollection<DoseSchedule> activeSchedules,
        int lowStockThresholdDays)
    {
        var dailyRate = activeSchedules.Sum(s => _scheduleService.CalculateDailyDoseRate(s));
        var remaining = Math.Max(0, medication.RemainingQuantity);

        // Nothing is being consumed, so there is no run-out date to give. Reporting null rather
        // than a very distant date keeps the client from drawing a misleading countdown.
        if (dailyRate <= 0)
        {
            _logger.LogDebug(
                "Medication {MedicationId} has no active schedule, so no run-out date can be forecast",
                medication.MedicationId);

            return new RefillForecastResponse
            {
                MedicationId = medication.MedicationId,
                MedicationName = medication.Name,
                RemainingQuantity = remaining,
                DailyConsumptionRate = 0,
                EstimatedRunOutDate = null,
                DaysUntilRunOut = null,
                RefillAlertTriggered = remaining == 0,
                LowStockThresholdDays = lowStockThresholdDays,
                Severity = remaining == 0 ? SeverityDepleted : SeverityOk
            };
        }

        // Floor rather than round: half a day of stock left is no day at all, and over-stating
        // supply is the failure that actually hurts someone on a chronic medication.
        var daysUntilRunOut = (int)Math.Floor(remaining / dailyRate);
        var today = _clock.Today;
        var runOutDate = today.AddDays(daysUntilRunOut);

        var severity = remaining == 0
            ? SeverityDepleted
            : daysUntilRunOut <= lowStockThresholdDays
                ? SeverityLow
                : SeverityOk;

        return new RefillForecastResponse
        {
            MedicationId = medication.MedicationId,
            MedicationName = medication.Name,
            RemainingQuantity = remaining,
            DailyConsumptionRate = Math.Round(dailyRate, 2),
            EstimatedRunOutDate = runOutDate,
            DaysUntilRunOut = daysUntilRunOut,
            RefillAlertTriggered = daysUntilRunOut <= lowStockThresholdDays,
            LowStockThresholdDays = lowStockThresholdDays,
            Severity = severity
        };
    }
}
