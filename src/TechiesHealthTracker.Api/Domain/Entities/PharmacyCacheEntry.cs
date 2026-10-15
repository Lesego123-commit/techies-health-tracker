// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// A cached GeoApify Places response, keyed by coordinate grid cell and held for 24 hours.
/// Caching server-side rather than on the device keeps us inside GeoApify free-tier rate limits
/// even when several caregivers search from the same suburb.
/// Maps to section 6.9 of the Part 1 Planning and Design document.
/// </summary>
public class PharmacyCacheEntry
{
    public Guid CacheId { get; set; } = Guid.NewGuid();

    /// <summary>Coordinate grid-cell identifier such as -26.20:28.04:5000. Indexed for lookup.</summary>
    public string GridCellKey { get; set; } = string.Empty;

    /// <summary>The raw GeoApify response, stored verbatim so an upstream schema change cannot corrupt the cache.</summary>
    public string ResponseJson { get; set; } = string.Empty;

    public DateTime CachedAt { get; set; } = DateTime.UtcNow;
    public DateTime ExpiresAt { get; set; }
}
