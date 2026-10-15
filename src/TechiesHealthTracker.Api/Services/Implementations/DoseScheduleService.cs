// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. TimeZoneInfo Class. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/api/system.timezoneinfo [Accessed 16 September 2026].
//   Microsoft. 2024. Custom Date and Time Format Strings. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/standard/base-types/custom-date-and-time-format-strings
//    [Accessed 16 September 2026]. Note HH is 24-hour; hh is 12-hour.
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 3.10 and 6.4.
// ----------------------------------------------------------------------------------------------

using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Turns a stored schedule ("twice daily at 08:00 and 20:00, weekdays only") into concrete UTC
/// instants. See <see cref="IDoseScheduleService"/> for why this work sits on the server.
/// </summary>
public class DoseScheduleService : IDoseScheduleService
{
    private readonly IUnitOfWork _unitOfWork;
    private readonly IAccessGuard _accessGuard;
    private readonly IDateTimeProvider _clock;
    private readonly SchedulingOptions _options;
    private readonly TimeZoneInfo _timeZone;
    private readonly ILogger<DoseScheduleService> _logger;

    public DoseScheduleService(
        IUnitOfWork unitOfWork,
        IAccessGuard accessGuard,
        IDateTimeProvider clock,
        IOptions<SchedulingOptions> options,
        ILogger<DoseScheduleService> logger)
    {
        _unitOfWork = unitOfWork;
        _accessGuard = accessGuard;
        _clock = clock;
        _options = options.Value;
        _logger = logger;
        _timeZone = ResolveTimeZone(_options.TimeZoneId, logger);
    }

    public async Task<DoseScheduleResponse> CreateAsync(
        Guid medicationId,
        CreateDoseScheduleRequest request,
        CancellationToken cancellationToken = default)
    {
        var medication = await _accessGuard.EnsureMedicationAsync(medicationId, cancellationToken);

        Validate(request);

        var schedule = new DoseSchedule
        {
            MedicationId = medication.MedicationId,
            Frequency = request.Frequency.ToLowerInvariant(),
            Times = NormaliseTimes(request.Times),
            DaysOfWeek = request.DaysOfWeek.Select(d => d.ToLowerInvariant()).Distinct().ToList(),
            StartDate = request.StartDate,
            EndDate = request.EndDate,
            IsActive = true
        };

        // NextDoseAt is derived, never accepted from the client (section 6.4).
        schedule.NextDoseAt = CalculateNextDoseAt(schedule, _clock.UtcNow);

        await _unitOfWork.DoseSchedules.AddAsync(schedule, cancellationToken);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Created {Frequency} schedule {ScheduleId} for medication {MedicationId}; next dose {NextDoseAt}",
            schedule.Frequency, schedule.ScheduleId, medicationId, schedule.NextDoseAt);

        return schedule.ToResponse();
    }

    public DateTime? CalculateNextDoseAt(DoseSchedule schedule, DateTime fromUtc)
    {
        if (!schedule.IsActive || schedule.Times.Count == 0)
        {
            return null;
        }

        var horizonEnd = fromUtc.AddDays(_options.NextDoseSearchHorizonDays);

        // EnumerateSlots already applies the start date, end date and day-of-week rules, so the
        // next dose is the first slot it yields. Reusing it keeps the two in step: a fix to one
        // can never silently leave the other behind.
        //
        // The search starts one tick after fromUtc because "next" has to mean strictly later.
        // EnumerateSlots uses a half-open interval so that adherence counts a slot falling exactly
        // on a window boundary once; here that same inclusive lower bound would hand back the dose
        // that has just been logged, and the schedule would never advance past it.
        var slots = EnumerateSlots(schedule, fromUtc.AddTicks(1), horizonEnd);

        return slots.Count > 0 ? slots[0] : null;
    }

    public IReadOnlyList<DateTime> EnumerateSlots(DoseSchedule schedule, DateTime fromUtc, DateTime toUtc)
    {
        var slots = new List<DateTime>();

        if (!schedule.IsActive || schedule.Times.Count == 0 || toUtc <= fromUtc)
        {
            return slots;
        }

        var parsedTimes = schedule.Times
            .Select(ParseTimeOrNull)
            .Where(t => t.HasValue)
            .Select(t => t!.Value)
            .OrderBy(t => t)
            .ToList();

        if (parsedTimes.Count == 0)
        {
            return slots;
        }

        // The window is widened by a day at each end before being walked: a slot at 23:00 local
        // time can land on the previous or next UTC day once the offset is applied, and dropping
        // it would quietly under-count adherence.
        var firstDay = DateOnly.FromDateTime(ToLocal(fromUtc).AddDays(-1));
        var lastDay = DateOnly.FromDateTime(ToLocal(toUtc).AddDays(1));

        if (firstDay < schedule.StartDate)
        {
            firstDay = schedule.StartDate;
        }

        if (schedule.EndDate.HasValue && lastDay > schedule.EndDate.Value)
        {
            lastDay = schedule.EndDate.Value;
        }

        for (var day = firstDay; day <= lastDay; day = day.AddDays(1))
        {
            // An empty DaysOfWeek means every day; anything else restricts the schedule.
            if (schedule.DaysOfWeek.Count > 0)
            {
                var dayName = DaysOfWeekValues.From(day.DayOfWeek);

                if (!schedule.DaysOfWeek.Contains(dayName, StringComparer.OrdinalIgnoreCase))
                {
                    continue;
                }
            }

            foreach (var time in parsedTimes)
            {
                var slotUtc = ToUtc(day, time);

                if (slotUtc >= fromUtc && slotUtc < toUtc)
                {
                    slots.Add(slotUtc);
                }
            }
        }

        slots.Sort();
        return slots;
    }

    public double CalculateDailyDoseRate(DoseSchedule schedule)
    {
        if (!schedule.IsActive || schedule.Times.Count == 0)
        {
            return 0;
        }

        var validTimes = schedule.Times.Count(t => ParseTimeOrNull(t).HasValue);

        if (validTimes == 0)
        {
            return 0;
        }

        // A schedule limited to certain days consumes less per day on average: three tablets on
        // each of two days a week is 6/7 of a tablet a day, not three.
        var applicableDays = schedule.DaysOfWeek.Count == 0 ? 7 : schedule.DaysOfWeek.Count;

        return validTimes * (applicableDays / 7.0);
    }

    public Task RefreshNextDoseAsync(DoseSchedule schedule, CancellationToken cancellationToken = default)
    {
        var recalculated = CalculateNextDoseAt(schedule, _clock.UtcNow);

        if (schedule.NextDoseAt != recalculated)
        {
            schedule.NextDoseAt = recalculated;
            _unitOfWork.DoseSchedules.Update(schedule);

            _logger.LogDebug(
                "Schedule {ScheduleId} next dose moved to {NextDoseAt}", schedule.ScheduleId, recalculated);
        }

        // The caller owns the transaction: a dose log updates the log, the medication stock and
        // this schedule, and all three have to commit together.
        return Task.CompletedTask;
    }

    // ---- Validation and conversion helpers ----

    private static void Validate(CreateDoseScheduleRequest request)
    {
        var frequency = request.Frequency.ToLowerInvariant();

        if (!ScheduleFrequencies.All.Contains(frequency))
        {
            throw new BusinessRuleException(
                $"Frequency {request.Frequency} is not supported. Use one of: "
                + string.Join(", ", ScheduleFrequencies.All) + ".");
        }

        foreach (var time in request.Times)
        {
            if (!ParseTimeOrNull(time).HasValue)
            {
                throw new BusinessRuleException($"Time {time} is not a valid 24-hour HH:mm value.");
            }
        }

        // A fixed frequency implies a fixed number of times. Catching the mismatch here is what
        // stops a twice-daily course being saved with a single reminder, which would otherwise
        // halve the adherence figure for reasons the caregiver could never see.
        var expected = ScheduleFrequencies.ExpectedTimeCount(frequency);

        if (expected.HasValue && request.Times.Count != expected.Value)
        {
            throw new BusinessRuleException(
                $"Frequency {frequency} expects exactly {expected.Value} time(s) but {request.Times.Count} were supplied.");
        }

        var distinctTimes = request.Times
            .Select(t => t.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count();

        if (distinctTimes != request.Times.Count)
        {
            throw new BusinessRuleException("Schedule times must be unique.");
        }

        foreach (var day in request.DaysOfWeek)
        {
            if (!DaysOfWeekValues.All.Contains(day))
            {
                throw new BusinessRuleException(
                    $"{day} is not a valid day of the week. Use lowercase English day names.");
            }
        }

        if (frequency == ScheduleFrequencies.Weekly && request.DaysOfWeek.Count == 0)
        {
            throw new BusinessRuleException("A weekly schedule must specify at least one day of the week.");
        }

        if (request.EndDate.HasValue && request.EndDate.Value < request.StartDate)
        {
            throw new BusinessRuleException("The schedule end date cannot fall before its start date.");
        }
    }

    private static List<string> NormaliseTimes(IEnumerable<string> times)
        => times
            .Select(t => ParseTimeOrNull(t)!.Value)
            .OrderBy(t => t)
            .Select(t => t.ToString(TimeFormat))
            .ToList();

    /// <summary>
    /// Schedule times are stored and returned on a 24-hour clock. The capital HH matters: the
    /// lowercase hh specifier is a 12-hour clock, which would silently rewrite an 20:00 evening
    /// dose as 08:00 and move it to the morning.
    /// </summary>
    private const string TimeFormat = @"HH\:mm";

    private static TimeOnly? ParseTimeOrNull(string? value)
        => TimeOnly.TryParseExact(value?.Trim(), "HH:mm", out var parsed) ? parsed : null;

    private DateTime ToLocal(DateTime utc)
        => TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utc, DateTimeKind.Utc), _timeZone);

    private DateTime ToUtc(DateOnly day, TimeOnly time)
    {
        var local = DateTime.SpecifyKind(day.ToDateTime(time), DateTimeKind.Unspecified);

        // Twice a year a local time either does not exist or happens twice. South Africa has no
        // daylight saving, so this never fires today, but handling it keeps the engine correct
        // if the deployment time zone is ever changed.
        if (_timeZone.IsInvalidTime(local))
        {
            local = local.AddHours(1);
        }

        return TimeZoneInfo.ConvertTimeToUtc(local, _timeZone);
    }

    private static TimeZoneInfo ResolveTimeZone(string timeZoneId, ILogger logger)
    {
        try
        {
            return TimeZoneInfo.FindSystemTimeZoneById(timeZoneId);
        }
        catch (Exception ex) when (ex is TimeZoneNotFoundException or InvalidTimeZoneException)
        {
            // Falling back to UTC keeps the API running on a host with a trimmed time-zone
            // database rather than failing every scheduling request at startup.
            logger.LogWarning(
                ex, "Time zone {TimeZoneId} is unavailable; scheduling will use UTC instead.", timeZoneId);

            return TimeZoneInfo.Utc;
        }
    }
}
