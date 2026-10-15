// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Enforces caregiver ownership for every dependant-scoped record. See <see cref="IAccessGuard"/>
/// for why this lives in one class rather than being repeated per service.
/// </summary>
public class AccessGuard : IAccessGuard
{
    private readonly AppDbContext _context;
    private readonly ICurrentUserService _currentUser;
    private readonly ILogger<AccessGuard> _logger;

    public AccessGuard(AppDbContext context, ICurrentUserService currentUser, ILogger<AccessGuard> logger)
    {
        _context = context;
        _currentUser = currentUser;
        _logger = logger;
    }

    public async Task<Dependant> EnsureDependantAsync(
        Guid dependantId,
        CancellationToken cancellationToken = default)
    {
        var dependant = await _context.Dependants
            .FirstOrDefaultAsync(d => d.DependantId == dependantId && d.IsActive, cancellationToken);

        if (dependant is null)
        {
            throw new NotFoundException("Dependant", dependantId);
        }

        await AssertOwnedAsync(dependant.CaregiverUserId, "Dependant", dependantId, cancellationToken);
        return dependant;
    }

    public async Task<Medication> EnsureMedicationAsync(
        Guid medicationId,
        CancellationToken cancellationToken = default)
    {
        var medication = await _context.Medications
            .Include(m => m.Dependant)
            .FirstOrDefaultAsync(m => m.MedicationId == medicationId, cancellationToken);

        if (medication is null)
        {
            throw new NotFoundException("Medication", medicationId);
        }

        await AssertOwnedAsync(
            medication.Dependant.CaregiverUserId, "Medication", medicationId, cancellationToken);

        return medication;
    }

    public async Task<DoseSchedule> EnsureScheduleAsync(
        Guid scheduleId,
        CancellationToken cancellationToken = default)
    {
        var schedule = await _context.DoseSchedules
            .Include(s => s.Medication)
            .ThenInclude(m => m.Dependant)
            .FirstOrDefaultAsync(s => s.ScheduleId == scheduleId, cancellationToken);

        if (schedule is null)
        {
            throw new NotFoundException("Dose schedule", scheduleId);
        }

        await AssertOwnedAsync(
            schedule.Medication.Dependant.CaregiverUserId, "Dose schedule", scheduleId, cancellationToken);

        return schedule;
    }

    public async Task<Appointment> EnsureAppointmentAsync(
        Guid appointmentId,
        CancellationToken cancellationToken = default)
    {
        var appointment = await _context.Appointments
            .Include(a => a.Dependant)
            .FirstOrDefaultAsync(a => a.AppointmentId == appointmentId, cancellationToken);

        if (appointment is null)
        {
            throw new NotFoundException("Appointment", appointmentId);
        }

        await AssertOwnedAsync(
            appointment.Dependant.CaregiverUserId, "Appointment", appointmentId, cancellationToken);

        return appointment;
    }

    public async Task<Document> EnsureDocumentAsync(
        Guid documentId,
        CancellationToken cancellationToken = default)
    {
        var document = await _context.Documents
            .Include(d => d.Dependant)
            .FirstOrDefaultAsync(d => d.DocumentId == documentId && !d.IsDeleted, cancellationToken);

        if (document is null)
        {
            throw new NotFoundException("Document", documentId);
        }

        await AssertOwnedAsync(
            document.Dependant.CaregiverUserId, "Document", documentId, cancellationToken);

        return document;
    }

    private async Task AssertOwnedAsync(
        Guid ownerUserId,
        string resource,
        Guid resourceId,
        CancellationToken cancellationToken)
    {
        var callerId = await _currentUser.GetRequiredUserIdAsync(cancellationToken);

        if (ownerUserId == callerId)
        {
            return;
        }

        // Logged so that a genuine attempt to reach another caregiver's records is visible in the
        // Azure App Service logs, rather than disappearing into a plain 403.
        _logger.LogWarning(
            "Access denied: user {CallerId} requested {Resource} {ResourceId} owned by {OwnerId}",
            callerId, resource, resourceId, ownerUserId);

        throw new ForbiddenException($"This {resource.ToLowerInvariant()} belongs to another caregiver.");
    }
}
