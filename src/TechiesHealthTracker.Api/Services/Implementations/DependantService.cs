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
/// Manages dependant profiles, the backbone of Additional Feature 5.
/// See <see cref="IDependantService"/> for the contract.
/// </summary>
public class DependantService : IDependantService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly ICurrentUserService _currentUser;
    private readonly IAccessGuard _accessGuard;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<DependantService> _logger;

    /// <summary>
    /// Nobody alive is older than this. Used as a sanity bound on date of birth, alongside the
    /// obvious rule that it cannot be in the future.
    /// </summary>
    private const int MaximumPlausibleAgeYears = 130;

    public DependantService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        ICurrentUserService currentUser,
        IAccessGuard accessGuard,
        IDateTimeProvider clock,
        ILogger<DependantService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _currentUser = currentUser;
        _accessGuard = accessGuard;
        _clock = clock;
        _logger = logger;
    }

    public async Task<DependantResponse> CreateAsync(
        CreateDependantRequest request, CancellationToken cancellationToken = default)
    {
        var userId = await _currentUser.GetRequiredUserIdAsync(cancellationToken);

        ValidateDateOfBirth(request.DateOfBirth);
        var relationship = NormaliseRelationship(request.Relationship);

        var dependant = new Dependant
        {
            CaregiverUserId = userId,
            FirstName = request.FirstName.Trim(),
            LastName = request.LastName.Trim(),
            DateOfBirth = request.DateOfBirth,
            Relationship = relationship,
            MedicalAidNumber = Blank(request.MedicalAidNumber),
            Allergies = Blank(request.Allergies),
            IsActive = true
        };

        await _unitOfWork.Dependants.AddAsync(dependant, cancellationToken);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Caregiver {UserId} added dependant {DependantId} ({Relationship})",
            userId, dependant.DependantId, relationship);

        return dependant.ToResponse(_clock.Today);
    }

    public async Task<IReadOnlyList<DependantResponse>> ListForCurrentUserAsync(
        CancellationToken cancellationToken = default)
    {
        var userId = await _currentUser.GetRequiredUserIdAsync(cancellationToken);
        var today = _clock.Today;

        // The medication count is projected in the same query rather than counted per row, which
        // keeps the Dependants screen to a single round trip however many dependants there are.
        var rows = await _context.Dependants
            .AsNoTracking()
            .Where(d => d.CaregiverUserId == userId && d.IsActive)
            .OrderBy(d => d.FirstName)
            .ThenBy(d => d.LastName)
            .Select(d => new
            {
                Dependant = d,
                ActiveMedicationCount = d.Medications.Count(m => m.IsActive)
            })
            .ToListAsync(cancellationToken);

        return rows
            .Select(r => r.Dependant.ToResponse(today, r.ActiveMedicationCount))
            .ToList();
    }

    public async Task<DependantResponse> GetAsync(
        Guid dependantId, CancellationToken cancellationToken = default)
    {
        var dependant = await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var activeMedicationCount = await _context.Medications
            .CountAsync(m => m.DependantId == dependantId && m.IsActive, cancellationToken);

        return dependant.ToResponse(_clock.Today, activeMedicationCount);
    }

    public async Task<DependantResponse> UpdateAsync(
        Guid dependantId,
        UpdateDependantRequest request,
        CancellationToken cancellationToken = default)
    {
        var dependant = await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        if (!string.IsNullOrWhiteSpace(request.FirstName))
        {
            dependant.FirstName = request.FirstName.Trim();
        }

        if (!string.IsNullOrWhiteSpace(request.LastName))
        {
            dependant.LastName = request.LastName.Trim();
        }

        if (request.DateOfBirth.HasValue)
        {
            ValidateDateOfBirth(request.DateOfBirth.Value);
            dependant.DateOfBirth = request.DateOfBirth.Value;
        }

        if (!string.IsNullOrWhiteSpace(request.Relationship))
        {
            dependant.Relationship = NormaliseRelationship(request.Relationship);
        }

        // Medical aid number and allergies are nullable and clearable, so an empty string is a
        // meaningful instruction here rather than a no-op.
        if (request.MedicalAidNumber is not null)
        {
            dependant.MedicalAidNumber = Blank(request.MedicalAidNumber);
        }

        if (request.Allergies is not null)
        {
            dependant.Allergies = Blank(request.Allergies);
        }

        _unitOfWork.Dependants.Update(dependant);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        var activeMedicationCount = await _context.Medications
            .CountAsync(m => m.DependantId == dependantId && m.IsActive, cancellationToken);

        _logger.LogInformation("Dependant {DependantId} updated", dependantId);

        return dependant.ToResponse(_clock.Today, activeMedicationCount);
    }

    public async Task DeleteAsync(Guid dependantId, CancellationToken cancellationToken = default)
    {
        var dependant = await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        dependant.IsActive = false;

        // The linked medications are retired alongside the profile so they stop appearing in
        // reminder sweeps, but the dose history underneath them is left intact.
        var medications = await _context.Medications
            .Where(m => m.DependantId == dependantId && m.IsActive)
            .ToListAsync(cancellationToken);

        foreach (var medication in medications)
        {
            medication.IsActive = false;
        }

        var schedules = await _context.DoseSchedules
            .Where(s => medications.Select(m => m.MedicationId).Contains(s.MedicationId) && s.IsActive)
            .ToListAsync(cancellationToken);

        foreach (var schedule in schedules)
        {
            schedule.IsActive = false;
            schedule.NextDoseAt = null;
        }

        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Dependant {DependantId} soft-deleted along with {MedicationCount} medication(s)",
            dependantId, medications.Count);
    }

    private void ValidateDateOfBirth(DateOnly dateOfBirth)
    {
        var today = _clock.Today;

        if (dateOfBirth > today)
        {
            throw new BusinessRuleException("A date of birth cannot be in the future.");
        }

        if (dateOfBirth < today.AddYears(-MaximumPlausibleAgeYears))
        {
            throw new BusinessRuleException(
                $"A date of birth more than {MaximumPlausibleAgeYears} years ago is not valid.");
        }
    }

    private static string NormaliseRelationship(string relationship)
    {
        var normalised = relationship.Trim().ToLowerInvariant();

        if (!RelationshipTypes.All.Contains(normalised))
        {
            throw new BusinessRuleException(
                $"Relationship {relationship} is not recognised. Use one of: "
                + string.Join(", ", RelationshipTypes.All) + ".");
        }

        return normalised;
    }

    /// <summary>Treats a whitespace-only optional field as absent rather than storing blanks.</summary>
    private static string? Blank(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
