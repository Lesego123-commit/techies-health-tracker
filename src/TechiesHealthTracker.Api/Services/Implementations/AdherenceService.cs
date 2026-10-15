// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Adherence analytics (Additional Feature 3, section 3.10).
///
/// The important design decision here is that "doses scheduled" is derived from the schedules
/// themselves, not counted from the dose-log table. A dose the caregiver simply never responded
/// to leaves no row behind, so counting rows would quietly report perfect adherence for someone
/// who has not taken anything at all.
/// </summary>
public class AdherenceService : IAdherenceService
{
    private readonly AppDbContext _context;
    private readonly IAccessGuard _accessGuard;
    private readonly IDoseScheduleService _scheduleService;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<AdherenceService> _logger;

    /// <summary>The window the Trends screen asks for when the caller does not specify one.</summary>
    private const int DefaultWindowDays = 30;

    /// <summary>Longest range that will be calculated in one request, to bound the work done.</summary>
    private const int MaximumWindowDays = 365;

    /// <summary>
    /// How many misses a time slot needs before it is called out as a pattern. Below this the
    /// "insight" would just be noise from one forgetful evening.
    /// </summary>
    private const int MinimumMissesForPattern = 3;

    public AdherenceService(
        AppDbContext context,
        IAccessGuard accessGuard,
        IDoseScheduleService scheduleService,
        IDateTimeProvider clock,
        ILogger<AdherenceService> logger)
    {
        _context = context;
        _accessGuard = accessGuard;
        _scheduleService = scheduleService;
        _clock = clock;
        _logger = logger;
    }

    public async Task<AdherenceResponse> CalculateAsync(
        Guid dependantId,
        DateOnly? from = null,
        DateOnly? to = null,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var today = _clock.Today;
        var toDate = to ?? today;
        var fromDate = from ?? toDate.AddDays(-(DefaultWindowDays - 1));

        if (fromDate > toDate)
        {
            throw new BusinessRuleException("The start of the date range cannot fall after its end.");
        }

        if (toDate.DayNumber - fromDate.DayNumber > MaximumWindowDays)
        {
            throw new BusinessRuleException(
                $"Adherence can be calculated over at most {MaximumWindowDays} days in one request.");
        }

        // Only whole slots that have already come due count. Including this evening's dose before
        // it is due would show every caregiver as behind for the rest of the day.
        var windowStartUtc = fromDate.ToUtcStartOfDay();
        var windowEndUtc = toDate.AddDays(1).ToUtcStartOfDay();
        var effectiveEndUtc = windowEndUtc > _clock.UtcNow ? _clock.UtcNow : windowEndUtc;

        var schedules = await _context.DoseSchedules
            .AsNoTracking()
            .Include(s => s.Medication)
            .Where(s => s.Medication.DependantId == dependantId)
            .ToListAsync(cancellationToken);

        var logs = await _context.DoseLogs
            .AsNoTracking()
            .Where(l => l.DependantId == dependantId
                        && l.ScheduledAt >= windowStartUtc
                        && l.ScheduledAt < windowEndUtc)
            .ToListAsync(cancellationToken);

        var expectedSlots = EnumerateExpectedSlots(schedules, windowStartUtc, effectiveEndUtc);
        var takenSlots = logs
            .Where(l => string.Equals(l.Status, DoseLogStatuses.Taken, StringComparison.OrdinalIgnoreCase))
            .Select(l => l.ScheduledAt)
            .ToHashSet();

        var trends = BuildDailyTrends(fromDate, toDate, expectedSlots, takenSlots);

        var totalScheduled = trends.Sum(t => t.Scheduled);
        var totalTaken = trends.Sum(t => t.Taken);
        var totalMissed = trends.Sum(t => t.Missed);

        var percentage = totalScheduled == 0
            ? 0
            : Math.Round(totalTaken * 100.0 / totalScheduled, 1);

        _logger.LogDebug(
            "Adherence for dependant {DependantId} from {From} to {To}: {Taken}/{Scheduled} ({Percentage}%)",
            dependantId, fromDate, toDate, totalTaken, totalScheduled, percentage);

        return new AdherenceResponse
        {
            DependantId = dependantId,
            FromDate = fromDate,
            ToDate = toDate,
            TotalScheduledDoses = totalScheduled,
            DosesTaken = totalTaken,
            DosesMissed = totalMissed,
            AdherencePercentage = percentage,
            CurrentStreakDays = CalculateStreak(trends, today),
            Trends = trends,
            PatternInsight = BuildPatternInsight(expectedSlots, takenSlots)
        };
    }

    /// <summary>
    /// Expands every schedule belonging to the dependant into the concrete slots that fell inside
    /// the window. The same engine that drives reminders is reused, so a dose can never be
    /// reminded about but not counted, or the other way round.
    /// </summary>
    private List<DateTime> EnumerateExpectedSlots(
        IEnumerable<DoseSchedule> schedules, DateTime fromUtc, DateTime toUtc)
    {
        if (toUtc <= fromUtc)
        {
            return new List<DateTime>();
        }

        var slots = new List<DateTime>();

        foreach (var schedule in schedules)
        {
            // A schedule that has since been retired still produced real doses while it was live,
            // so it is enumerated against the window using a copy that is temporarily active.
            var projection = schedule.IsActive
                ? schedule
                : CloneAsActive(schedule);

            slots.AddRange(_scheduleService.EnumerateSlots(projection, fromUtc, toUtc));
        }

        return slots;
    }

    private static DoseSchedule CloneAsActive(DoseSchedule schedule) => new()
    {
        ScheduleId = schedule.ScheduleId,
        MedicationId = schedule.MedicationId,
        Frequency = schedule.Frequency,
        Times = schedule.Times,
        DaysOfWeek = schedule.DaysOfWeek,
        StartDate = schedule.StartDate,
        EndDate = schedule.EndDate,
        IsActive = true
    };

    private static List<AdherenceTrendPoint> BuildDailyTrends(
        DateOnly fromDate,
        DateOnly toDate,
        IReadOnlyCollection<DateTime> expectedSlots,
        IReadOnlySet<DateTime> takenSlots)
    {
        var byDay = expectedSlots
            .GroupBy(slot => DateOnly.FromDateTime(slot))
            .ToDictionary(g => g.Key, g => g.ToList());

        var trends = new List<AdherenceTrendPoint>();

        // Every day in the range gets a point, including days with nothing scheduled, so the bar
        // chart on the Trends screen has no gaps in its x-axis.
        for (var day = fromDate; day <= toDate; day = day.AddDays(1))
        {
            var scheduled = byDay.TryGetValue(day, out var slots) ? slots : new List<DateTime>();
            var taken = scheduled.Count(takenSlots.Contains);
            var missed = scheduled.Count - taken;

            trends.Add(new AdherenceTrendPoint
            {
                Date = day,
                Scheduled = scheduled.Count,
                Taken = taken,
                Missed = missed,
                AdherencePercentage = scheduled.Count == 0
                    ? 0
                    : Math.Round(taken * 100.0 / scheduled.Count, 1)
            });
        }

        return trends;
    }

    /// <summary>
    /// Consecutive days ending today with no missed dose. Days with nothing scheduled do not
    /// break the streak but do not extend it either, which is what a caregiver on a weekday-only
    /// course would expect over a weekend.
    /// </summary>
    private static int CalculateStreak(IReadOnlyList<AdherenceTrendPoint> trends, DateOnly today)
    {
        var streak = 0;

        foreach (var point in trends.Where(t => t.Date <= today).Reverse())
        {
            if (point.Missed > 0)
            {
                break;
            }

            if (point.Scheduled > 0)
            {
                streak++;
            }
        }

        return streak;
    }

    /// <summary>
    /// The plain-language call-out promised in section 2.2, e.g. "Evening doses are missed most
    /// often". Returns null unless one part of the day is clearly worse than the others, because
    /// a confident-sounding insight drawn from two data points is worse than no insight.
    /// </summary>
    private static string? BuildPatternInsight(
        IReadOnlyCollection<DateTime> expectedSlots, IReadOnlySet<DateTime> takenSlots)
    {
        var missed = expectedSlots.Where(slot => !takenSlots.Contains(slot)).ToList();

        if (missed.Count < MinimumMissesForPattern)
        {
            return null;
        }

        var worst = missed
            .GroupBy(slot => DescribePartOfDay(slot.Hour))
            .Select(g => new { PartOfDay = g.Key, Count = g.Count() })
            .OrderByDescending(x => x.Count)
            .ToList();

        if (worst.Count == 0 || worst[0].Count < MinimumMissesForPattern)
        {
            return null;
        }

        // With misses spread evenly there is no pattern worth naming, so a clear leader is
        // required before anything is claimed.
        if (worst.Count > 1 && worst[0].Count == worst[1].Count)
        {
            return $"{missed.Count} doses were missed across the period, with no particular time of day standing out.";
        }

        return $"{worst[0].PartOfDay} doses are missed most often ({worst[0].Count} of {missed.Count} missed doses).";
    }

    private static string DescribePartOfDay(int hourUtc) => hourUtc switch
    {
        >= 5 and < 12 => "Morning",
        >= 12 and < 17 => "Afternoon",
        >= 17 and < 22 => "Evening",
        _ => "Night"
    };
}
