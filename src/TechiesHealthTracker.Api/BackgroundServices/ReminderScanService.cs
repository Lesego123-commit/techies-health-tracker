// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Background Tasks with Hosted Services in ASP.NET Core. [Online]. Available
//    at: https://learn.microsoft.com/aspnet/core/fundamentals/host/hosted-services
//    [Accessed 16 September 2026].
//   Microsoft. 2024. PeriodicTimer Class. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/api/system.threading.periodictimer [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 3.6 and 5.2 (Background Jobs).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Implementations;

namespace TechiesHealthTracker.Api.BackgroundServices;

/// <summary>Settings for <see cref="ReminderScanService"/>.</summary>
public class ReminderScanOptions
{
    public const string SectionName = "ReminderScan";

    /// <summary>Whether the sweep runs at all. Off by default in tests and CI.</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>How often the sweep runs. Five minutes is well inside the shortest useful lead time.</summary>
    public int IntervalMinutes { get; set; } = 5;
}

/// <summary>
/// The hosted background service named in the development stack (section 5.2). It sweeps the
/// schedule at a fixed interval and raises notification events for doses coming due, doses that
/// were never answered, and appointments approaching their reminder time.
///
/// This is what section 3.6 means by the API doing the heavy lifting: the phone is not relied on
/// to wake itself up and work out what is overdue, so a caregiver still gets told even if the app
/// has not been opened for days.
/// </summary>
public class ReminderScanService : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ReminderScanOptions _options;
    private readonly SchedulingOptions _schedulingOptions;
    private readonly ILogger<ReminderScanService> _logger;

    public ReminderScanService(
        IServiceScopeFactory scopeFactory,
        IOptions<ReminderScanOptions> options,
        IOptions<SchedulingOptions> schedulingOptions,
        ILogger<ReminderScanService> logger)
    {
        _scopeFactory = scopeFactory;
        _options = options.Value;
        _schedulingOptions = schedulingOptions.Value;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!_options.Enabled)
        {
            _logger.LogInformation("Reminder scan is disabled by configuration.");
            return;
        }

        var interval = TimeSpan.FromMinutes(Math.Max(1, _options.IntervalMinutes));

        _logger.LogInformation("Reminder scan started; sweeping every {Interval}.", interval);

        using var timer = new PeriodicTimer(interval);

        // The first sweep runs immediately rather than after one interval, so a restart does not
        // leave a five-minute hole in reminder coverage.
        do
        {
            try
            {
                await RunSweepAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                // A failed sweep must never take the hosted service down: the next tick should
                // still run, so the error is logged and swallowed here.
                _logger.LogError(ex, "Reminder sweep failed; the next sweep will still run.");
            }
        }
        while (await SafeWaitAsync(timer, stoppingToken));

        _logger.LogInformation("Reminder scan stopped.");
    }

    private static async Task<bool> SafeWaitAsync(PeriodicTimer timer, CancellationToken stoppingToken)
    {
        try
        {
            return await timer.WaitForNextTickAsync(stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    /// <summary>
    /// One pass over the data. A scope is created per sweep because the DbContext and the
    /// services that use it are registered as scoped and cannot be captured by a singleton.
    /// </summary>
    internal async Task RunSweepAsync(CancellationToken cancellationToken)
    {
        using var scope = _scopeFactory.CreateScope();

        var context = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var scheduleService = scope.ServiceProvider.GetRequiredService<IDoseScheduleService>();
        var clock = scope.ServiceProvider.GetRequiredService<IDateTimeProvider>();

        // One notification service for the whole sweep. Each RaiseAsync commits before returning,
        // so its duplicate-suppression check sees everything raised earlier in this same pass.
        var notifications = scope.ServiceProvider.GetRequiredService<INotificationService>();

        var now = clock.UtcNow;

        var doseReminders = await RaiseDoseRemindersAsync(
            context, notifications, now, cancellationToken);

        var missedDoses = await RaiseMissedDoseAlertsAsync(
            context, scheduleService, notifications, now, cancellationToken);

        var appointments = await RaiseAppointmentRemindersAsync(
            context, notifications, now, cancellationToken);

        if (doseReminders + missedDoses + appointments > 0)
        {
            _logger.LogInformation(
                "Reminder sweep raised {Doses} dose reminder(s), {Missed} missed-dose alert(s) and {Appointments} appointment reminder(s)",
                doseReminders, missedDoses, appointments);
        }
    }

    /// <summary>
    /// Raises a reminder for every schedule whose next dose falls inside its caregiver's
    /// configured lead time.
    /// </summary>
    private async Task<int> RaiseDoseRemindersAsync(
        AppDbContext context,
        INotificationService notifications,
        DateTime now,
        CancellationToken cancellationToken)
    {
        // The widest lead time any user has configured bounds the query, so a user on a 12-hour
        // lead time is served without scanning every schedule in the database for everyone else.
        var maxLeadMinutes = await context.Users
            .Select(u => (int?)u.ReminderLeadTimeMinutes)
            .MaxAsync(cancellationToken) ?? 0;

        if (maxLeadMinutes <= 0)
        {
            return 0;
        }

        var horizon = now.AddMinutes(maxLeadMinutes);

        var due = await context.DoseSchedules
            .Include(s => s.Medication)
            .ThenInclude(m => m.Dependant)
            .ThenInclude(d => d.Caregiver)
            .Where(s => s.IsActive
                        && s.Medication.IsActive
                        && s.Medication.Dependant.IsActive
                        && s.NextDoseAt != null
                        && s.NextDoseAt > now
                        && s.NextDoseAt <= horizon)
            .ToListAsync(cancellationToken);

        var raised = 0;

        foreach (var schedule in due)
        {
            var caregiver = schedule.Medication.Dependant.Caregiver;
            var leadTime = TimeSpan.FromMinutes(caregiver.ReminderLeadTimeMinutes);

            // The horizon above is the widest any user has set, so each schedule is re-checked
            // against its own caregiver's lead time.
            if (schedule.NextDoseAt > now.Add(leadTime))
            {
                continue;
            }

            // Composed in the caregiver's own language (section 3.7). The preference is already
            // loaded with the caregiver, so this costs nothing extra per notification.
            var created = await notifications.RaiseAsync(
                caregiver.UserId,
                NotificationCategories.DoseReminder,
                NotificationText.DoseReminderTitle(caregiver.PreferredLanguage),
                NotificationText.DoseReminderBody(
                    caregiver.PreferredLanguage,
                    schedule.Medication.Dependant.FirstName,
                    schedule.Medication.Name,
                    $"{schedule.Medication.DosageAmount} {schedule.Medication.DosageUnit}"),
                schedule.Medication.DependantId,
                $"techies://medications/{schedule.MedicationId}",
                cancellationToken);

            if (created is not null)
            {
                raised++;
            }
        }

        return raised;
    }

    /// <summary>
    /// Finds slots that came due more than the grace period ago with no dose log against them,
    /// records them as missed, and tells the caregiver.
    /// </summary>
    private async Task<int> RaiseMissedDoseAlertsAsync(
        AppDbContext context,
        IDoseScheduleService scheduleService,
        INotificationService notifications,
        DateTime now,
        CancellationToken cancellationToken)
    {
        var graceCutoff = now.AddMinutes(-_schedulingOptions.MissedDoseGraceMinutes);

        // Only the last day is examined. Anything older has already been swept, and the adherence
        // calculation derives misses from the schedule anyway, so nothing is lost by not going back.
        var windowStart = graceCutoff.AddDays(-1);

        var schedules = await context.DoseSchedules
            .Include(s => s.Medication)
            .ThenInclude(m => m.Dependant)
            .ThenInclude(d => d.Caregiver)
            .Where(s => s.IsActive && s.Medication.IsActive && s.Medication.Dependant.IsActive)
            .ToListAsync(cancellationToken);

        var raised = 0;

        foreach (var schedule in schedules)
        {
            var slots = scheduleService.EnumerateSlots(schedule, windowStart, graceCutoff);

            if (slots.Count == 0)
            {
                continue;
            }

            var loggedSlots = await context.DoseLogs
                .Where(l => l.ScheduleId == schedule.ScheduleId
                            && l.ScheduledAt >= windowStart
                            && l.ScheduledAt <= graceCutoff)
                .Select(l => l.ScheduledAt)
                .ToListAsync(cancellationToken);

            var missed = slots.Except(loggedSlots).ToList();

            if (missed.Count == 0)
            {
                continue;
            }

            var caregiver = schedule.Medication.Dependant.Caregiver;
            var created = await notifications.RaiseAsync(
                caregiver.UserId,
                NotificationCategories.MissedDose,
                NotificationText.MissedDoseTitle(caregiver.PreferredLanguage),
                NotificationText.MissedDoseBody(
                    caregiver.PreferredLanguage,
                    schedule.Medication.Dependant.FirstName,
                    schedule.Medication.Name,
                    missed.Count),
                schedule.Medication.DependantId,
                $"techies://medications/{schedule.MedicationId}",
                cancellationToken);

            if (created is not null)
            {
                raised++;
            }
        }

        return raised;
    }

    private async Task<int> RaiseAppointmentRemindersAsync(
        AppDbContext context,
        INotificationService notifications,
        DateTime now,
        CancellationToken cancellationToken)
    {
        var appointments = await context.Appointments
            .Include(a => a.Dependant)
            .ThenInclude(d => d.Caregiver)
            .Where(a => !a.IsCancelled && a.Dependant.IsActive && a.AppointmentDateTime > now)
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);

        var raised = 0;

        foreach (var appointment in appointments)
        {
            var reminderAt = appointment.AppointmentDateTime.AddMinutes(-appointment.ReminderMinutesBefore);

            if (reminderAt > now)
            {
                continue;
            }

            var caregiverLanguage = appointment.Dependant.Caregiver.PreferredLanguage;

            var created = await notifications.RaiseAsync(
                appointment.Dependant.Caregiver.UserId,
                NotificationCategories.AppointmentReminder,
                NotificationText.AppointmentTitle(caregiverLanguage),
                NotificationText.AppointmentBody(
                    caregiverLanguage,
                    appointment.Dependant.FirstName,
                    appointment.Title,
                    appointment.AppointmentDateTime),
                appointment.DependantId,
                $"techies://appointments/{appointment.AppointmentId}",
                cancellationToken);

            if (created is not null)
            {
                raised++;
            }
        }

        return raised;
    }

}
