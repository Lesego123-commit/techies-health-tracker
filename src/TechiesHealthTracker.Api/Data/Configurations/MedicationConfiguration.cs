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

/// <summary>Column rules for the Medications table (section 6.3).</summary>
public class MedicationConfiguration : IEntityTypeConfiguration<Medication>
{
    public void Configure(EntityTypeBuilder<Medication> builder)
    {
        builder.ToTable("Medications");
        builder.HasKey(m => m.MedicationId);

        builder.Property(m => m.Name).HasMaxLength(100).IsRequired();
        builder.Property(m => m.DosageAmount).HasMaxLength(20).IsRequired();
        builder.Property(m => m.DosageUnit).HasMaxLength(20).IsRequired();
        builder.Property(m => m.Form).HasMaxLength(30).IsRequired();
        builder.Property(m => m.TotalQuantity).IsRequired();
        builder.Property(m => m.RemainingQuantity).IsRequired();
        builder.Property(m => m.PrescribedBy).HasMaxLength(100);
        builder.Property(m => m.StartDate).IsRequired();
        builder.Property(m => m.Instructions).HasMaxLength(500);
        builder.Property(m => m.IsActive).IsRequired().HasDefaultValue(true);
        builder.Property(m => m.RowVersion).IsRequired().HasDefaultValue(0).IsConcurrencyToken();

        builder.HasOne(m => m.Dependant)
            .WithMany(d => d.Medications)
            .HasForeignKey(m => m.DependantId)
            .OnDelete(DeleteBehavior.Cascade);

        // GET /api/dependants/{id}/medications only ever wants the active rows for one dependant.
        builder.HasIndex(m => new { m.DependantId, m.IsActive });
    }
}
