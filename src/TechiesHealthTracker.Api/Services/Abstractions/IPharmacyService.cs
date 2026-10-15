// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Pharmacy search (Additional Feature 1, section 3.8). The API owns the GeoApify call, the
/// distance maths and the 24-hour cache so that the handset never holds an upstream API key and
/// several caregivers searching the same suburb cost one upstream request between them.
/// </summary>
public interface IPharmacyService
{
    Task<PharmacySearchResponse> FindNearbyAsync(
        double latitude,
        double longitude,
        int radiusMetres,
        CancellationToken cancellationToken = default);
}
