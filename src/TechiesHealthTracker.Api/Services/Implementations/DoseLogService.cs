// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Records a dose as taken or missed. This is the widest-reaching write in the API: it stores the
/// log, decrements the medication stock, recalculates the schedule's next dose and can raise a
/// refill alert, all inside one transaction so a partial failure cannot leave the stock count
/// disagreeing with the dose history.
/// See <see cref="IDoseLogService"/> for the contract.
/// </summary>
public class DoseLogService : IDoseLogService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly IAccessGuard _accessGuard;
    private readonly IDoseScheduleService _scheduleService;
    private readonly IAdherenceService _adherenceService;
    private readonly IRefillForecastService _refillForecastService;
    private readonly INotificationService _notificationService;
    private readonly ICurrentUserService _currentUser;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<DoseLogService> _logger;

    /// <summary>How far back a log may be dated. Beyond this it is almost certainly a client clock error.</summary>
    private const int MaximumBackdateDays = 30;

    /// <summary>
    /// How early a dose may be taken and still count against the slot it is anticipating. Half an
    /// hour covers leaving the house before the evening dose without letting a mid-afternoon tap
    /// claim a slot that is still hours away.
    /// </summary>
    private static readonly TimeSpan EarlyDoseGrace = TimeSpan.FromMinutes(30);

    public DoseLogService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        IAccessGuard accessGuard,
        IDoseScheduleService scheduleService,
        IAdherenceService adherenceService,
        IRefillForecastService refillForecastService,
        INotificationService notificationService,
        ICurrentUserService currentUser,
        IDateTimeProvider clock,
        ILogger<DoseLogService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _accessGuard = accessGuard;
        _scheduleService = scheduleService;
        _adherenceService = adherenceService;
        _refillForecastService = refillForecastService;
        _notificationService = notificationService;
        _currentUser = currentUser;
        _clock = clock;
        _logger = logger;
    }

    public async Task<DoseLogCreatedResponse> CreateAsync(
        CreateDoseLogRequest request, CancellationToken cancellationToken = default)
    {
        var schedule = await _accessGuard.EnsureScheduleAsync(request.ScheduleId, cancellationToken);
        var medication = schedule.Medication;

        // The client sends all three ids, so they are cross-checked rather than trusted: a
        // mismatch means the app has muddled two medications and the log would land on the wrong
        // dependant's history.
        if (medication.MedicationId != request.MedicationId)
        {
            throw new BusinessRuleException(
                "The medication does not match the schedule the dose is being logged against.");
        }

        if (medication.DependantId != request.DependantId)
        {
            throw new BusinessRuleException(
                "The dependant does not match the medication the dose is being logged against.");
        }

        var status = NormaliseStatus(request.Status);
        var loggedAt = request.LoggedAt ?? _clock.UtcNow;
        var scheduledAt = ResolveScheduledSlot(schedule, request.ScheduledAt, loggedAt);

        ValidateTiming(loggedAt);

        var existing = await _context.DoseLogs.FirstOrDefaultAsync(
            l => l.ScheduleId == schedule.ScheduleId && l.ScheduledAt == scheduledAt, cancellationToken);

        if (existing is not null)
        {
            // The unique index on (ScheduleId, ScheduledAt) makes this a conflict rather than a
            // second row, which is what keeps a double-tap from decrementing the stock twice.
            throw new BusinessRuleException(
                $"This dose was already logged as {existing.Status} at {existing.LoggedAt:u}.");
        }

        var log = new DoseLog
        {
            ScheduleId = schedule.ScheduleId,
            DependantId = medication.DependantId,
            MedicationId = medication.MedicationId,
            Status = status,
            ScheduledAt = scheduledAt,
            LoggedAt = loggedAt,
            Notes = string.IsNullOrWhiteSpace(request.Notes) ? null : request.Notes.Trim(),

            // Anything arriving on this endpoint reached the server, so it is settled by
            // definition. Only the offline batch path in ISyncService deals with pending rows.
            PendingSync = false
        };

        await _unitOfWork.DoseLogs.AddAsync(log, cancellationToken);

        var remaining = ApplyStockChange(medication, status);
        await _scheduleService.RefreshNextDoseAsync(schedule, cancellationToken);

        _unitOfWork.Medications.Update(medication);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Dose {Status} logged for medication {MedicationId} slot {ScheduledAt}; {Remaining} units remain",
            status, medication.MedicationId, scheduledAt, remaining);

        var forecast = await _refillForecastService.ForecastAsync(medication.MedicationId, cancellationToken);

        if (forecast.RefillAlertTriggered)
        {
            await RaiseRefillAlertAsync(medication, forecast, cancellationToken);
        }

        var adherence = await _adherenceService.CalculateAsync(
            medication.DependantId, cancellationToken: cancellationToken);

        return new DoseLogCreatedResponse
        {
            DoseLog = log.ToResponse(medication.Name),
            AdherenceSummary = adherence,
            RemainingQuantity = remaining,
            NextDoseAt = schedule.NextDoseAt,
            RefillAlertTriggered = forecast.RefillAlertTriggered
        };
    }

    public async Task<IReadOnlyList<DoseLogResponse>> GetHistoryAsync(
        Guid dependantId,
        DateOnly? from = null,
        DateOnly? to = null,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var toDate = to ?? _clock.Today;
        var fromDate = from ?? toDate.AddDays(-29);

        if (fromDate > toDate)
        {
            throw new BusinessRuleException("The start of the date range cannot fall after its end.");
        }

        var fromUtc = fromDate.ToUtcStartOfDay();
        var toUtc = toDate.AddDays(1).ToUtcStartOfDay();

        var logs = await _context.DoseLogs
            .AsNoTracking()
            .Include(l => l.Medication)
            .Where(l => l.DependantId == dependantId && l.ScheduledAt >= fromUtc && l.ScheduledAt < toUtc)
            .OrderByDescending(l => l.ScheduledAt)
            .ToListAsync(cancellationToken);

        return logs.Select(l => l.ToResponse(l.Medication.Name)).ToList();
    }

    /// <summary>
    /// Decrements the stock when a dose was actually taken. A missed dose leaves the count alone,
    /// which is what makes the refill forecast track real consumption rather than the prescription.
    /// </summary>
    private int ApplyStockChange(Medication medication, string status)
    {
        if (!string.Equals(status, DoseLogStatuses.Taken, StringComparison.OrdinalIgnoreCase))
        {
            return medication.RemainingQuantity;
        }

        if (medication.RemainingQuantity <= 0)
        {
            // The count is already at zero, most likely because the caregiver refilled without
            // telling the app. The dose is still recorded; the stock simply cannot go negative.
            _logger.LogWarning(
                "Dose taken for medication {MedicationId} with no stock recorded; leaving the count at zero",
                medication.MedicationId);

            medication.RemainingQuantity = 0;
            return 0;
        }

        medication.RemainingQuantity -= 1;
        return medication.RemainingQuantity;
    }

    /// <summary>
    /// Works out which scheduled slot this log belongs to when the client has not stated one.
    ///
    /// A dose cannot have been swallowed before it was due, so the slot chosen is the most recent
    /// one that has already come round. A slot still in the future is taken only when it is
    /// imminent — someone taking their evening dose a few minutes early — or when the window
    /// holds nothing earlier at all.
    ///
    /// Simply picking the *nearest* slot was wrong, and quietly so: a tap at two in the afternoon
    /// is closer to tonight's dose than to this morning's, so it was filed against a slot that had
    /// not happened yet. Adherence only scores slots that have already come due, so the dose the
    /// caregiver had just recorded counted for nothing, and this morning's went down as missed.
    /// </summary>
    private DateTime ResolveScheduledSlot(DoseSchedule schedule, DateTime? explicitSlot, DateTime loggedAt)
    {
        if (explicitSlot.HasValue)
        {
            return DateTime.SpecifyKind(explicitSlot.Value, DateTimeKind.Utc);
        }

        // A day either side of the tap is enough to cover a late-night dose logged after midnight
        // or an early one logged just before its slot.
        var candidates = _scheduleService.EnumerateSlots(
            schedule, loggedAt.AddDays(-1), loggedAt.AddDays(1));

        if (candidates.Count == 0)
        {
            throw new BusinessRuleException(
                "This schedule has no dose due near the time supplied. Send scheduledAt explicitly to log it anyway.");
        }

        var mostRecent = candidates
            .Where(slot => slot <= loggedAt)
            .OrderByDescending(slot => slot)
            .Cast<DateTime?>()
            .FirstOrDefault();

        var nextUp = candidates
            .Where(slot => slot > loggedAt)
            .OrderBy(slot => slot)
            .Cast<DateTime?>()
            .FirstOrDefault();

        if (nextUp.HasValue && nextUp.Value - loggedAt <= EarlyDoseGrace)
        {
            return nextUp.Value;
        }

        return mostRecent ?? nextUp!.Value;
    }

    private void ValidateTiming(DateTime loggedAt)
    {
        var now = _clock.UtcNow;

        // A small amount of clock skew between the handset and the server is normal; hours of it
        // is not, and would corrupt the adherence figures if accepted.
        if (loggedAt > now.AddMinutes(10))
        {
            throw new BusinessRuleException("A dose cannot be logged with a timestamp in the future.");
        }

        if (loggedAt < now.AddDays(-MaximumBackdateDays))
        {
            throw new BusinessRuleException(
                $"A dose cannot be logged more than {MaximumBackdateDays} days in the past.");
        }
    }

    private static string NormaliseStatus(string status)
    {
        var normalised = status?.Trim().ToLowerInvariant() ?? string.Empty;

        if (!DoseLogStatuses.All.Contains(normalised))
        {
            throw new BusinessRuleException(
                $"Status {status} is not valid. Use {DoseLogStatuses.Taken} or {DoseLogStatuses.Missed}.");
        }

        return normalised;
    }

    private async Task RaiseRefillAlertAsync(
        Medication medication, RefillForecastResponse forecast, CancellationToken cancellationToken)
    {
        var userId = await _currentUser.GetRequiredUserIdAsync(cancellationToken);

        // One extra column on a row that is already being fetched for the alert. Falling back to
        // English if the user has somehow gone is better than dropping the notification.
        var language = await _context.Users
            .Where(u => u.UserId == userId)
            .Select(u => u.PreferredLanguage)
            .FirstOrDefaultAsync(cancellationToken)
            ?? SupportedLanguages.English;

        await _notificationService.RaiseAsync(
            userId,
            NotificationCategories.RefillAlert,
            NotificationText.RefillTitle(language),
            NotificationText.RefillBody(language, medication.Name, forecast.DaysUntilRunOut),
            medication.DependantId,
            $"techies://medications/{medication.MedicationId}/refill",
            cancellationToken);
    }
}
