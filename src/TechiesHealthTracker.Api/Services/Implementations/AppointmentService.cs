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
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Manages doctor visits and check-ups for a dependant.
/// See <see cref="IAppointmentService"/> for the contract.
/// </summary>
public class AppointmentService : IAppointmentService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly IAccessGuard _accessGuard;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<AppointmentService> _logger;

    /// <summary>
    /// Section 6.6 sets a five-minute floor on the reminder lead time. Anything shorter would
    /// fire too late to be any use.
    /// </summary>
    private const int MinimumReminderMinutes = 5;

    /// <summary>How far ahead an appointment may be booked. Two years is generous for a check-up.</summary>
    private const int MaximumBookingYears = 2;

    public AppointmentService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        IAccessGuard accessGuard,
        IDateTimeProvider clock,
        ILogger<AppointmentService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _accessGuard = accessGuard;
        _clock = clock;
        _logger = logger;
    }

    public async Task<AppointmentResponse> CreateAsync(
        Guid dependantId,
        CreateAppointmentRequest request,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        ValidateDateTime(request.AppointmentDateTime);
        ValidateReminder(request.ReminderMinutesBefore);

        var appointment = new Appointment
        {
            DependantId = dependantId,
            Title = request.Title.Trim(),
            AppointmentDateTime = DateTime.SpecifyKind(request.AppointmentDateTime, DateTimeKind.Utc),
            Location = Blank(request.Location),
            Notes = Blank(request.Notes),
            ReminderMinutesBefore = request.ReminderMinutesBefore,
            IsCancelled = false
        };

        await _unitOfWork.Appointments.AddAsync(appointment, cancellationToken);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Appointment {AppointmentId} booked for dependant {DependantId} at {When}",
            appointment.AppointmentId, dependantId, appointment.AppointmentDateTime);

        return appointment.ToResponse(_clock.UtcNow);
    }

    public async Task<IReadOnlyList<AppointmentResponse>> ListForDependantAsync(
        Guid dependantId,
        bool upcomingOnly = false,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var now = _clock.UtcNow;

        var query = _context.Appointments
            .AsNoTracking()
            .Where(a => a.DependantId == dependantId);

        if (upcomingOnly)
        {
            query = query.Where(a => !a.IsCancelled && a.AppointmentDateTime > now);
        }

        var appointments = await query.ToListAsync(cancellationToken);

        // The Appointments screen shows upcoming visits first in the order they will happen, then
        // past visits with the most recent at the top. Ordering that in memory keeps it readable;
        // the set for one dependant is small.
        return appointments
            .Select(a => a.ToResponse(now))
            .OrderByDescending(a => a.IsUpcoming)
            .ThenBy(a => a.IsUpcoming ? a.AppointmentDateTime : DateTime.MaxValue)
            .ThenByDescending(a => a.AppointmentDateTime)
            .ToList();
    }

    public async Task<AppointmentResponse> UpdateAsync(
        Guid appointmentId,
        UpdateAppointmentRequest request,
        CancellationToken cancellationToken = default)
    {
        var appointment = await _accessGuard.EnsureAppointmentAsync(appointmentId, cancellationToken);

        if (appointment.IsCancelled)
        {
            throw new BusinessRuleException(
                "This appointment has been cancelled and can no longer be edited.");
        }

        if (!string.IsNullOrWhiteSpace(request.Title))
        {
            appointment.Title = request.Title.Trim();
        }

        if (request.AppointmentDateTime.HasValue)
        {
            ValidateDateTime(request.AppointmentDateTime.Value);
            appointment.AppointmentDateTime =
                DateTime.SpecifyKind(request.AppointmentDateTime.Value, DateTimeKind.Utc);
        }

        if (request.Location is not null)
        {
            appointment.Location = Blank(request.Location);
        }

        if (request.Notes is not null)
        {
            appointment.Notes = Blank(request.Notes);
        }

        if (request.ReminderMinutesBefore.HasValue)
        {
            ValidateReminder(request.ReminderMinutesBefore.Value);
            appointment.ReminderMinutesBefore = request.ReminderMinutesBefore.Value;
        }

        _unitOfWork.Appointments.Update(appointment);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation("Appointment {AppointmentId} updated", appointmentId);

        return appointment.ToResponse(_clock.UtcNow);
    }

    public async Task CancelAsync(Guid appointmentId, CancellationToken cancellationToken = default)
    {
        var appointment = await _accessGuard.EnsureAppointmentAsync(appointmentId, cancellationToken);

        if (appointment.IsCancelled)
        {
            // Cancelling twice is not an error: the client may retry on a flaky connection and
            // should get the same outcome.
            return;
        }

        appointment.IsCancelled = true;
        _unitOfWork.Appointments.Update(appointment);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation("Appointment {AppointmentId} cancelled", appointmentId);
    }

    private void ValidateDateTime(DateTime appointmentDateTime)
    {
        // Past appointments are allowed, because a caregiver often records a visit after the fact.
        // Only implausibly distant future bookings are refused.
        if (appointmentDateTime > _clock.UtcNow.AddYears(MaximumBookingYears))
        {
            throw new BusinessRuleException(
                $"An appointment cannot be booked more than {MaximumBookingYears} years ahead.");
        }
    }

    private static void ValidateReminder(int reminderMinutesBefore)
    {
        if (reminderMinutesBefore < MinimumReminderMinutes)
        {
            throw new BusinessRuleException(
                $"The reminder lead time must be at least {MinimumReminderMinutes} minutes.");
        }
    }

    private static string? Blank(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
