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

/// <summary>Column rules for the Documents table (section 6.7).</summary>
public class DocumentConfiguration : IEntityTypeConfiguration<Document>
{
    public void Configure(EntityTypeBuilder<Document> builder)
    {
        builder.ToTable("Documents");
        builder.HasKey(d => d.DocumentId);

        builder.Property(d => d.Title).HasMaxLength(150).IsRequired();
        builder.Property(d => d.DocumentType).HasMaxLength(30).IsRequired();
        builder.Property(d => d.FileName).HasMaxLength(255).IsRequired();
        builder.Property(d => d.ContentType).HasMaxLength(50).IsRequired();
        builder.Property(d => d.FileSizeBytes).IsRequired();
        builder.Property(d => d.BlobUrl).HasMaxLength(500).IsRequired();
        builder.Property(d => d.IsDeleted).IsRequired().HasDefaultValue(false);
        builder.Property(d => d.UploadedAt).IsRequired();

        builder.HasOne(d => d.Dependant)
            .WithMany(dep => dep.Documents)
            .HasForeignKey(d => d.DependantId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(d => new { d.DependantId, d.IsDeleted });
    }
}
