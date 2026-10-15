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
/// Manages the medications on a dependant's profile.
/// See <see cref="IMedicationService"/> for the contract.
/// </summary>
public class MedicationService : IMedicationService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly IAccessGuard _accessGuard;
    private readonly IDoseScheduleService _scheduleService;
    private readonly ILogger<MedicationService> _logger;

    public MedicationService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        IAccessGuard accessGuard,
        IDoseScheduleService scheduleService,
        ILogger<MedicationService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _accessGuard = accessGuard;
        _scheduleService = scheduleService;
        _logger = logger;
    }

    public async Task<MedicationResponse> CreateAsync(
        Guid dependantId,
        CreateMedicationRequest request,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var form = Normalise(request.Form, MedicationForms.All, nameof(request.Form));
        var unit = Normalise(request.DosageUnit, DosageUnits.All, nameof(request.DosageUnit));

        if (request.EndDate.HasValue && request.EndDate.Value < request.StartDate)
        {
            throw new BusinessRuleException("The medication end date cannot fall before its start date.");
        }

        // On a brand-new prescription the pack is full, so remaining defaults to the total. An
        // explicit value is still honoured for a course the caregiver is part-way through.
        var remaining = request.RemainingQuantity ?? request.TotalQuantity;

        if (remaining > request.TotalQuantity)
        {
            throw new BusinessRuleException(
                "The remaining quantity cannot be greater than the total quantity prescribed.");
        }

        var medication = new Medication
        {
            DependantId = dependantId,
            Name = request.Name.Trim(),
            DosageAmount = request.DosageAmount.Trim(),
            DosageUnit = unit,
            Form = form,
            TotalQuantity = request.TotalQuantity,
            RemainingQuantity = remaining,
            PrescribedBy = string.IsNullOrWhiteSpace(request.PrescribedBy) ? null : request.PrescribedBy.Trim(),
            StartDate = request.StartDate,
            EndDate = request.EndDate,
            Instructions = string.IsNullOrWhiteSpace(request.Instructions) ? null : request.Instructions.Trim(),
            IsActive = true
        };

        await _unitOfWork.Medications.AddAsync(medication, cancellationToken);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Added medication {MedicationId} ({Name}) to dependant {DependantId} with {Remaining} of {Total} units",
            medication.MedicationId, medication.Name, dependantId, remaining, request.TotalQuantity);

        return medication.ToResponse();
    }

    public async Task<IReadOnlyList<MedicationResponse>> ListForDependantAsync(
        Guid dependantId,
        bool includeInactive = false,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        // Schedules are included rather than fetched per medication: the Dependant Profile screen
        // needs both, and a second round trip per row would be slow on a poor connection.
        var medications = await _context.Medications
            .AsNoTracking()
            .Include(m => m.DoseSchedules)
            .Where(m => m.DependantId == dependantId && (includeInactive || m.IsActive))
            .OrderBy(m => m.Name)
            .ToListAsync(cancellationToken);

        return medications
            .Select(m => m.ToResponse(m.DoseSchedules.Select(s => s.ToResponse())))
            .ToList();
    }

    public async Task<MedicationResponse> GetAsync(
        Guid medicationId, CancellationToken cancellationToken = default)
    {
        var medication = await _accessGuard.EnsureMedicationAsync(medicationId, cancellationToken);

        var schedules = await _context.DoseSchedules
            .AsNoTracking()
            .Where(s => s.MedicationId == medicationId)
            .ToListAsync(cancellationToken);

        return medication.ToResponse(schedules.Select(s => s.ToResponse()));
    }

    public async Task<MedicationResponse> UpdateAsync(
        Guid medicationId,
        UpdateMedicationRequest request,
        CancellationToken cancellationToken = default)
    {
        var medication = await _accessGuard.EnsureMedicationAsync(medicationId, cancellationToken);

        if (!string.IsNullOrWhiteSpace(request.Name))
        {
            medication.Name = request.Name.Trim();
        }

        if (!string.IsNullOrWhiteSpace(request.DosageAmount))
        {
            medication.DosageAmount = request.DosageAmount.Trim();
        }

        if (!string.IsNullOrWhiteSpace(request.DosageUnit))
        {
            medication.DosageUnit = Normalise(request.DosageUnit, DosageUnits.All, nameof(request.DosageUnit));
        }

        if (!string.IsNullOrWhiteSpace(request.Form))
        {
            medication.Form = Normalise(request.Form, MedicationForms.All, nameof(request.Form));
        }

        // Total and remaining are applied together before the relationship between them is
        // checked, so that a repeat prescription can raise both in one request.
        if (request.TotalQuantity.HasValue)
        {
            medication.TotalQuantity = request.TotalQuantity.Value;
        }

        if (request.RemainingQuantity.HasValue)
        {
            medication.RemainingQuantity = request.RemainingQuantity.Value;
        }

        if (medication.RemainingQuantity > medication.TotalQuantity)
        {
            throw new BusinessRuleException(
                "The remaining quantity cannot be greater than the total quantity prescribed.");
        }

        if (request.PrescribedBy is not null)
        {
            medication.PrescribedBy = string.IsNullOrWhiteSpace(request.PrescribedBy)
                ? null
                : request.PrescribedBy.Trim();
        }

        if (request.StartDate.HasValue)
        {
            medication.StartDate = request.StartDate.Value;
        }

        if (request.EndDate.HasValue)
        {
            medication.EndDate = request.EndDate.Value;
        }

        if (medication.EndDate.HasValue && medication.EndDate.Value < medication.StartDate)
        {
            throw new BusinessRuleException("The medication end date cannot fall before its start date.");
        }

        if (request.Instructions is not null)
        {
            medication.Instructions = string.IsNullOrWhiteSpace(request.Instructions)
                ? null
                : request.Instructions.Trim();
        }

        var schedules = await _context.DoseSchedules
            .Where(s => s.MedicationId == medicationId)
            .ToListAsync(cancellationToken);

        if (request.IsActive.HasValue && request.IsActive.Value != medication.IsActive)
        {
            medication.IsActive = request.IsActive.Value;

            // Retiring a medication has to silence its reminders too, otherwise the background
            // service keeps raising doses for a course the caregiver has already stopped.
            foreach (var schedule in schedules)
            {
                schedule.IsActive = medication.IsActive;
                schedule.NextDoseAt = null;
            }
        }

        // Dates may have moved, so every live schedule is re-projected against the new window.
        foreach (var schedule in schedules.Where(s => s.IsActive))
        {
            await _scheduleService.RefreshNextDoseAsync(schedule, cancellationToken);
        }

        _unitOfWork.Medications.Update(medication);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation("Medication {MedicationId} updated", medicationId);

        return medication.ToResponse(schedules.Select(s => s.ToResponse()));
    }

    /// <summary>
    /// Lower-cases a controlled-vocabulary value and rejects anything outside the list, so that
    /// Tablet, tablet and TABLET cannot end up as three different forms in the database.
    /// </summary>
    private static string Normalise(string value, IReadOnlySet<string> allowed, string fieldName)
    {
        var normalised = value.Trim().ToLowerInvariant();

        if (!allowed.Contains(normalised))
        {
            throw new BusinessRuleException(
                $"{fieldName} value {value} is not recognised. Use one of: "
                + string.Join(", ", allowed) + ".");
        }

        return normalised;
    }
}
