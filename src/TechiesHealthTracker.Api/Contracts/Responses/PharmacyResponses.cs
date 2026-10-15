// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>
/// Response of GET /api/pharmacies/nearby (section 5.4.8). The API calls GeoApify, works out
/// each distance with the Haversine formula and sorts the list, so the client only renders.
/// </summary>
public record PharmacySearchResponse
{
    public GeoPoint UserLocation { get; init; } = new();
    public int RadiusMetres { get; init; }
    public IReadOnlyList<PharmacyResult> Results { get; init; } = Array.Empty<PharmacyResult>();
    public int TotalResults { get; init; }

    /// <summary>True when this was served from the 24-hour cache rather than a live GeoApify call.</summary>
    public bool FromCache { get; init; }
}

/// <summary>A latitude and longitude pair in decimal degrees.</summary>
public record GeoPoint
{
    public double Lat { get; init; }
    public double Lon { get; init; }
}

/// <summary>One pharmacy returned by the search, already sorted by distance.</summary>
public record PharmacyResult
{
    public string Name { get; init; } = string.Empty;
    public string FormattedAddress { get; init; } = string.Empty;
    public double Lat { get; init; }
    public double Lon { get; init; }

    /// <summary>Great-circle distance from the caller, rounded to two decimals.</summary>
    public double DistanceKm { get; init; }

    public string? OpeningHours { get; init; }
    public string? Phone { get; init; }
}
