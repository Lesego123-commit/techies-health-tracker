// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Routing to Controller Actions in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/controllers/routing [Accessed 16 September 2026].
//   Microsoft. 2024. Create Web APIs with ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/web-api/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Controllers;

/// <summary>
/// Pharmacy search (section 5.4.8), wrapping the GeoApify Places API (Additional Feature 1).
/// </summary>
[ApiController]
[Route("api/pharmacies")]
[Authorize]
[Produces("application/json")]
public class PharmaciesController : ControllerBase
{
    private readonly IPharmacyService _pharmacyService;

    public PharmaciesController(IPharmacyService pharmacyService) => _pharmacyService = pharmacyService;

    /// <summary>
    /// Finds pharmacies near a set of coordinates, sorted by distance. The GeoApify key stays on
    /// the server and each response is cached for 24 hours per coordinate grid cell.
    /// </summary>
    /// <param name="lat">Latitude in decimal degrees.</param>
    /// <param name="lon">Longitude in decimal degrees.</param>
    /// <param name="radius">Search radius in metres. Defaults to 5000.</param>
    [HttpGet("nearby")]
    [ProducesResponseType(typeof(PharmacySearchResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status502BadGateway)]
    public async Task<ActionResult<PharmacySearchResponse>> FindNearby(
        [FromQuery] double lat,
        [FromQuery] double lon,
        [FromQuery] int radius = 5000,
        CancellationToken cancellationToken = default)
        => Ok(await _pharmacyService.FindNearbyAsync(lat, lon, radius, cancellationToken));
}
