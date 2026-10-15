// ----------------------------------------------------------------------------------------------
//  References
//   Geoapify GmbH. 2024. Places API — Developer Documentation. [Online]. Available at:
//    https://apidocs.geoapify.com/docs/places/ [Accessed 16 September 2026].
//   Sinnott, R.W. 1984. Virtues of the Haversine. Sky and Telescope, 68(2), p.159.
//    Great-circle distance formula used in CalculateHaversineKm.
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.8 (Additional Feature 1).
// ----------------------------------------------------------------------------------------------

using System.Globalization;
using System.Text.Json;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.External;

/// <summary>Settings for the GeoApify Places integration (section 3.8).</summary>
public class GeoApifyOptions
{
    public const string SectionName = "GeoApify";

    public string ApiKey { get; set; } = string.Empty;

    public string BaseUrl { get; set; } = "https://api.geoapify.com/v2/";

    /// <summary>The GeoApify category searched. Fixed by the feature, but configurable for testing.</summary>
    public string Category { get; set; } = "healthcare.pharmacy";

    /// <summary>Upper bound on results asked for in one call, to stay inside the free tier.</summary>
    public int MaxResults { get; set; } = 20;

    /// <summary>How long a response stays in the cache. Section 6.9 specifies 24 hours.</summary>
    public int CacheHours { get; set; } = 24;

    /// <summary>Request timeout. Kept short so a slow upstream does not hold the handset waiting.</summary>
    public int TimeoutSeconds { get; set; } = 10;
}

/// <summary>
/// Talks to the GeoApify Places API. Kept as its own class behind an interface so the pharmacy
/// service can be tested without reaching the network, and so the API key stays on the server.
/// </summary>
public interface IGeoApifyClient
{
    Task<IReadOnlyList<PharmacyResult>> SearchPharmaciesAsync(
        double latitude, double longitude, int radiusMetres, CancellationToken cancellationToken = default);
}

/// <inheritdoc cref="IGeoApifyClient"/>
public class GeoApifyClient : IGeoApifyClient
{
    private readonly HttpClient _httpClient;
    private readonly GeoApifyOptions _options;
    private readonly ILogger<GeoApifyClient> _logger;

    public GeoApifyClient(
        HttpClient httpClient,
        IOptions<GeoApifyOptions> options,
        ILogger<GeoApifyClient> logger)
    {
        _httpClient = httpClient;
        _options = options.Value;
        _logger = logger;
    }

    public async Task<IReadOnlyList<PharmacyResult>> SearchPharmaciesAsync(
        double latitude,
        double longitude,
        int radiusMetres,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(_options.ApiKey))
        {
            throw new ExternalServiceException(
                "The pharmacy finder is not configured on this deployment.");
        }

        // Coordinates are formatted with the invariant culture on purpose: on a machine with a
        // comma decimal separator the default formatting would send 26,2041 and GeoApify would
        // read it as two arguments.
        var lat = latitude.ToString("F6", CultureInfo.InvariantCulture);
        var lon = longitude.ToString("F6", CultureInfo.InvariantCulture);

        var requestUri =
            $"places?categories={Uri.EscapeDataString(_options.Category)}"
            + $"&filter=circle:{lon},{lat},{radiusMetres}"
            + $"&bias=proximity:{lon},{lat}"
            + $"&limit={_options.MaxResults}"
            + $"&apiKey={Uri.EscapeDataString(_options.ApiKey)}";

        try
        {
            using var response = await _httpClient.GetAsync(requestUri, cancellationToken);

            if (!response.IsSuccessStatusCode)
            {
                // The status is logged but not passed on: an upstream 401 caused by our own key
                // is not something the caregiver's phone can act on.
                _logger.LogError(
                    "GeoApify returned {StatusCode} for a pharmacy search near {Lat},{Lon}",
                    (int)response.StatusCode, lat, lon);

                throw new ExternalServiceException(
                    "The pharmacy search service is unavailable. Please try again shortly.");
            }

            var json = await response.Content.ReadAsStringAsync(cancellationToken);
            return ParseResults(json, latitude, longitude);
        }
        catch (TaskCanceledException ex) when (!cancellationToken.IsCancellationRequested)
        {
            _logger.LogError(ex, "GeoApify pharmacy search timed out after {Timeout}s", _options.TimeoutSeconds);

            throw new ExternalServiceException(
                "The pharmacy search service did not respond in time. Please try again shortly.");
        }
        catch (HttpRequestException ex)
        {
            _logger.LogError(ex, "GeoApify pharmacy search failed");

            throw new ExternalServiceException(
                "The pharmacy search service could not be reached. Please try again shortly.");
        }
    }

    /// <summary>
    /// Maps the GeoJSON feature collection onto our own result shape, calculating each distance
    /// with the Haversine formula as specified in section 3.8. Exposed internally so the parsing
    /// can be unit tested against a recorded response without an HTTP call.
    /// </summary>
    internal static IReadOnlyList<PharmacyResult> ParseResults(string json, double originLat, double originLon)
    {
        var results = new List<PharmacyResult>();

        using var document = JsonDocument.Parse(json);

        if (!document.RootElement.TryGetProperty("features", out var features)
            || features.ValueKind != JsonValueKind.Array)
        {
            return results;
        }

        foreach (var feature in features.EnumerateArray())
        {
            if (!feature.TryGetProperty("properties", out var properties))
            {
                continue;
            }

            var lat = ReadDouble(properties, "lat");
            var lon = ReadDouble(properties, "lon");

            if (lat is null || lon is null)
            {
                continue;
            }

            results.Add(new PharmacyResult
            {
                // A pharmacy with no name in OpenStreetMap is still worth showing, so a neutral
                // label is used rather than dropping the result.
                Name = ReadString(properties, "name") ?? "Unnamed pharmacy",
                FormattedAddress = ReadString(properties, "formatted") ?? string.Empty,
                Lat = lat.Value,
                Lon = lon.Value,
                DistanceKm = Math.Round(CalculateHaversineKm(originLat, originLon, lat.Value, lon.Value), 2),
                OpeningHours = ReadString(properties, "opening_hours")
                    ?? ReadNestedString(properties, "datasource", "raw", "opening_hours"),

                // GeoApify puts the number under a nested "contact" object, not a flat
                // "contact.phone" key. The earlier lookup used the dotted name as if it were one
                // property, so it matched nothing and every pharmacy that does publish a number
                // came back without one. Verified against a live Johannesburg search: of 18
                // results, one carried properties.contact.phone and it was being dropped.
                Phone = ReadString(properties, "phone")
                    ?? ReadNestedString(properties, "contact", "phone")
                    ?? ReadNestedString(properties, "datasource", "raw", "phone")
            });
        }

        return results.OrderBy(r => r.DistanceKm).ToList();
    }

    /// <summary>
    /// Great-circle distance in kilometres between two points. Accurate enough at city scale and
    /// far cheaper than asking GeoApify for a routed distance on every result.
    /// </summary>
    internal static double CalculateHaversineKm(double lat1, double lon1, double lat2, double lon2)
    {
        const double earthRadiusKm = 6371.0;

        var dLat = ToRadians(lat2 - lat1);
        var dLon = ToRadians(lon2 - lon1);

        var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2)
                + Math.Cos(ToRadians(lat1)) * Math.Cos(ToRadians(lat2))
                * Math.Sin(dLon / 2) * Math.Sin(dLon / 2);

        return earthRadiusKm * 2 * Math.Atan2(Math.Sqrt(a), Math.Sqrt(1 - a));
    }

    private static double ToRadians(double degrees) => degrees * Math.PI / 180.0;

    private static string? ReadString(JsonElement element, string propertyName)
        => element.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static double? ReadDouble(JsonElement element, string propertyName)
        => element.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number
            ? value.GetDouble()
            : null;

    /// <summary>
    /// Walks a chain of nested objects and reads the string at the end of it.
    /// </summary>
    /// <remarks>
    /// GeoApify nests some fields one or two levels down, and different places carry the same
    /// detail in different spots: a phone number can sit under "contact" or, when it came straight
    /// from OpenStreetMap, under "datasource.raw". Walking the path properly is what a dotted
    /// property name looked like it was doing but never did.
    /// </remarks>
    private static string? ReadNestedString(JsonElement element, params string[] path)
    {
        var current = element;

        foreach (var segment in path[..^1])
        {
            if (!current.TryGetProperty(segment, out var next) || next.ValueKind != JsonValueKind.Object)
            {
                return null;
            }

            current = next;
        }

        return ReadString(current, path[^1]);
    }
}
