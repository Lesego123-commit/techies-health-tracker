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

/// <summary>Column rules for the DoseSchedules table (section 6.4).</summary>
public class DoseScheduleConfiguration : IEntityTypeConfiguration<DoseSchedule>
{
    public void Configure(EntityTypeBuilder<DoseSchedule> builder)
    {
        builder.ToTable("DoseSchedules");
        builder.HasKey(s => s.ScheduleId);

        builder.Property(s => s.Frequency).HasMaxLength(20).IsRequired();

        // Times and DaysOfWeek map onto native PostgreSQL text[] columns rather than a joined
        // table: they are small, always read as a whole, and never queried element by element.
        builder.Property(s => s.Times).HasColumnType("text[]").IsRequired();
        builder.Property(s => s.DaysOfWeek).HasColumnType("text[]").IsRequired();

        builder.Property(s => s.StartDate).IsRequired();
        builder.Property(s => s.IsActive).IsRequired().HasDefaultValue(true);

        builder.HasOne(s => s.Medication)
            .WithMany(m => m.DoseSchedules)
            .HasForeignKey(s => s.MedicationId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(s => s.MedicationId);

        // The reminder background service sweeps for schedules whose next dose has come due.
        builder.HasIndex(s => s.NextDoseAt);
    }
}
