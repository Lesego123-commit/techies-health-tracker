// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using System.Globalization;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.External;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Finds pharmacies near a set of coordinates (Additional Feature 1, section 3.8), caching each
/// upstream response for 24 hours against a coordinate grid cell.
/// See <see cref="IPharmacyService"/> for the contract.
/// </summary>
public class PharmacyService : IPharmacyService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly IGeoApifyClient _geoApify;
    private readonly IDateTimeProvider _clock;
    private readonly GeoApifyOptions _options;
    private readonly ILogger<PharmacyService> _logger;

    private const int MinimumRadiusMetres = 500;
    private const int MaximumRadiusMetres = 25_000;
    private const int DefaultRadiusMetres = 5_000;

    /// <summary>
    /// Coordinates are rounded to two decimal places to build the cache key, which is roughly a
    /// one-kilometre cell. Two caregivers searching from the same suburb therefore share a cache
    /// entry, which is the whole point of caching server-side rather than on the device.
    /// </summary>
    private const int GridPrecisionDecimals = 2;

    public PharmacyService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        IGeoApifyClient geoApify,
        IDateTimeProvider clock,
        IOptions<GeoApifyOptions> options,
        ILogger<PharmacyService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _geoApify = geoApify;
        _clock = clock;
        _options = options.Value;
        _logger = logger;
    }

    public async Task<PharmacySearchResponse> FindNearbyAsync(
        double latitude,
        double longitude,
        int radiusMetres,
        CancellationToken cancellationToken = default)
    {
        ValidateCoordinates(latitude, longitude);

        var radius = radiusMetres <= 0 ? DefaultRadiusMetres : radiusMetres;

        if (radius < MinimumRadiusMetres || radius > MaximumRadiusMetres)
        {
            throw new BusinessRuleException(
                $"The search radius must be between {MinimumRadiusMetres} and {MaximumRadiusMetres} metres.");
        }

        var gridKey = BuildGridCellKey(latitude, longitude, radius);
        var now = _clock.UtcNow;

        var cached = await _context.PharmacyCache
            .AsNoTracking()
            .Where(c => c.GridCellKey == gridKey && c.ExpiresAt > now)
            .OrderByDescending(c => c.CachedAt)
            .FirstOrDefaultAsync(cancellationToken);

        if (cached is not null)
        {
            var cachedResults = Deserialise(cached.ResponseJson);

            if (cachedResults is not null)
            {
                _logger.LogDebug("Pharmacy search for {GridKey} served from cache", gridKey);
                return BuildResponse(latitude, longitude, radius, cachedResults, fromCache: true);
            }

            // A cache entry that will not deserialise is treated as a miss rather than an error,
            // so a change to the stored shape degrades to an extra upstream call.
            _logger.LogWarning("Cache entry {CacheId} could not be read; falling back to GeoApify", cached.CacheId);
        }

        var results = await _geoApify.SearchPharmaciesAsync(latitude, longitude, radius, cancellationToken);

        await CacheAsync(gridKey, results, now, cancellationToken);

        _logger.LogInformation(
            "Pharmacy search near {GridKey} returned {Count} result(s) from GeoApify", gridKey, results.Count);

        return BuildResponse(latitude, longitude, radius, results, fromCache: false);
    }

    private async Task CacheAsync(
        string gridKey,
        IReadOnlyList<PharmacyResult> results,
        DateTime now,
        CancellationToken cancellationToken)
    {
        try
        {
            var expired = await _context.PharmacyCache
                .Where(c => c.GridCellKey == gridKey)
                .ToListAsync(cancellationToken);

            // Superseded entries for this cell are cleared out as they are replaced, which keeps
            // the table from growing without needing a separate cleanup job.
            foreach (var entry in expired)
            {
                _unitOfWork.PharmacyCache.Remove(entry);
            }

            await _unitOfWork.PharmacyCache.AddAsync(new PharmacyCacheEntry
            {
                GridCellKey = gridKey,
                ResponseJson = JsonSerializer.Serialize(results),
                CachedAt = now,
                ExpiresAt = now.AddHours(_options.CacheHours)
            }, cancellationToken);

            await _unitOfWork.SaveChangesAsync(cancellationToken);
        }
        catch (Exception ex)
        {
            // A caching failure must not cost the caregiver their search results, which are
            // already in hand at this point.
            _logger.LogWarning(ex, "Could not cache the pharmacy search for {GridKey}", gridKey);
        }
    }

    private static PharmacySearchResponse BuildResponse(
        double latitude,
        double longitude,
        int radius,
        IReadOnlyList<PharmacyResult> results,
        bool fromCache) => new()
    {
        UserLocation = new GeoPoint { Lat = latitude, Lon = longitude },
        RadiusMetres = radius,
        Results = results,
        TotalResults = results.Count,
        FromCache = fromCache
    };

    private static IReadOnlyList<PharmacyResult>? Deserialise(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<List<PharmacyResult>>(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>
    /// Builds the cache key. The radius is part of it because a 2 km search and a 20 km search
    /// from the same spot are different questions and must not share an answer.
    /// </summary>
    internal static string BuildGridCellKey(double latitude, double longitude, int radiusMetres)
    {
        var lat = Math.Round(latitude, GridPrecisionDecimals).ToString("F2", CultureInfo.InvariantCulture);
        var lon = Math.Round(longitude, GridPrecisionDecimals).ToString("F2", CultureInfo.InvariantCulture);

        return $"{lat}:{lon}:{radiusMetres}";
    }

    private static void ValidateCoordinates(double latitude, double longitude)
    {
        if (double.IsNaN(latitude) || latitude is < -90 or > 90)
        {
            throw new BusinessRuleException("Latitude must be between -90 and 90 degrees.");
        }

        if (double.IsNaN(longitude) || longitude is < -180 or > 180)
        {
            throw new BusinessRuleException("Longitude must be between -180 and 180 degrees.");
        }
    }
}
