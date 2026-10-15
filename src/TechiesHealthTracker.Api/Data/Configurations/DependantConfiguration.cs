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

/// <summary>Column rules for the Dependants table (section 6.2).</summary>
public class DependantConfiguration : IEntityTypeConfiguration<Dependant>
{
    public void Configure(EntityTypeBuilder<Dependant> builder)
    {
        builder.ToTable("Dependants");
        builder.HasKey(d => d.DependantId);

        builder.Property(d => d.FirstName).HasMaxLength(50).IsRequired();
        builder.Property(d => d.LastName).HasMaxLength(50).IsRequired();
        builder.Property(d => d.DateOfBirth).IsRequired();
        builder.Property(d => d.Relationship).HasMaxLength(30).IsRequired();
        builder.Property(d => d.MedicalAidNumber).HasMaxLength(50);
        builder.Property(d => d.Allergies).HasMaxLength(500);
        builder.Property(d => d.IsActive).IsRequired().HasDefaultValue(true);
        builder.Property(d => d.RowVersion).IsRequired().HasDefaultValue(0).IsConcurrencyToken();

        builder.HasOne(d => d.Caregiver)
            .WithMany(u => u.Dependants)
            .HasForeignKey(d => d.CaregiverUserId)
            .OnDelete(DeleteBehavior.Cascade);

        // Every authorisation check in the API filters dependants by their caregiver, so this
        // index is the single most important one in the schema.
        builder.HasIndex(d => d.CaregiverUserId);
    }
}
