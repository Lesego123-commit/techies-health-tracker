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

/// <summary>The /api/medications endpoints (section 5.4.3).</summary>
public interface IMedicationService
{
    Task<MedicationResponse> CreateAsync(
        Guid dependantId, CreateMedicationRequest request, CancellationToken cancellationToken = default);

    /// <summary>
    /// Active medications for a dependant, each with its schedules attached so the Dependant
    /// Profile screen renders from a single call.
    /// </summary>
    Task<IReadOnlyList<MedicationResponse>> ListForDependantAsync(
        Guid dependantId, bool includeInactive = false, CancellationToken cancellationToken = default);

    Task<MedicationResponse> GetAsync(Guid medicationId, CancellationToken cancellationToken = default);

    Task<MedicationResponse> UpdateAsync(
        Guid medicationId, UpdateMedicationRequest request, CancellationToken cancellationToken = default);
}
