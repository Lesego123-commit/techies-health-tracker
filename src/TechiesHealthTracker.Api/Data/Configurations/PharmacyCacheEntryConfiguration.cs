// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Fluent API — Configuring a Model in EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/entity-types [Accessed 16 September 2026].
//   Microsoft. 2024. Indexes — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/indexes [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6.
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Data.Configurations;

/// <summary>Column rules for the PharmacyCache table (section 6.9).</summary>
public class PharmacyCacheEntryConfiguration : IEntityTypeConfiguration<PharmacyCacheEntry>
{
    public void Configure(EntityTypeBuilder<PharmacyCacheEntry> builder)
    {
        builder.ToTable("PharmacyCache");
        builder.HasKey(p => p.CacheId);

        builder.Property(p => p.GridCellKey).HasMaxLength(30).IsRequired();
        builder.Property(p => p.ResponseJson).HasColumnType("text").IsRequired();
        builder.Property(p => p.CachedAt).IsRequired();
        builder.Property(p => p.ExpiresAt).IsRequired();

        // Lookups are always "give me the live entry for this grid cell", so the index covers
        // both halves of that question and lets an expired row be skipped without a second read.
        builder.HasIndex(p => new { p.GridCellKey, p.ExpiresAt });
    }
}
