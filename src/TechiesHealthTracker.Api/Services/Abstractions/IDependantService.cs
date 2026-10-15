// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// The /api/dependants endpoints (section 5.4.2), which are the backbone of Additional Feature 5:
/// one caregiver account managing several people's medication from a single profile.
/// </summary>
public interface IDependantService
{
    Task<DependantResponse> CreateAsync(
        CreateDependantRequest request, CancellationToken cancellationToken = default);

    /// <summary>Every active dependant belonging to the caller, with their active medication counts.</summary>
    Task<IReadOnlyList<DependantResponse>> ListForCurrentUserAsync(CancellationToken cancellationToken = default);

    Task<DependantResponse> GetAsync(Guid dependantId, CancellationToken cancellationToken = default);

    Task<DependantResponse> UpdateAsync(
        Guid dependantId, UpdateDependantRequest request, CancellationToken cancellationToken = default);

    /// <summary>
    /// Soft-deletes the dependant by clearing IsActive. Medical history is never physically
    /// removed: a caregiver who deletes a profile by mistake would otherwise lose the dose log
    /// that explains a past reaction.
    /// </summary>
    Task DeleteAsync(Guid dependantId, CancellationToken cancellationToken = default);
}
