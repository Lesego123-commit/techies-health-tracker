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

/// <summary>Column rules for the DoseLogs table (section 6.5).</summary>
public class DoseLogConfiguration : IEntityTypeConfiguration<DoseLog>
{
    public void Configure(EntityTypeBuilder<DoseLog> builder)
    {
        builder.ToTable("DoseLogs");
        builder.HasKey(l => l.DoseLogId);

        builder.Property(l => l.Status).HasMaxLength(10).IsRequired();
        builder.Property(l => l.ScheduledAt).IsRequired();
        builder.Property(l => l.LoggedAt).IsRequired();
        builder.Property(l => l.Notes).HasMaxLength(255);
        builder.Property(l => l.PendingSync).IsRequired().HasDefaultValue(false);
        builder.Property(l => l.RowVersion).IsRequired().HasDefaultValue(0).IsConcurrencyToken();

        builder.HasOne(l => l.Schedule)
            .WithMany(s => s.DoseLogs)
            .HasForeignKey(l => l.ScheduleId)
            .OnDelete(DeleteBehavior.Cascade);

        // Restrict rather than Cascade on the two extra foreign keys: a dose log already
        // disappears through its schedule, and a second cascade path is rejected by PostgreSQL.
        builder.HasOne(l => l.Dependant)
            .WithMany(d => d.DoseLogs)
            .HasForeignKey(l => l.DependantId)
            .OnDelete(DeleteBehavior.Restrict);

        builder.HasOne(l => l.Medication)
            .WithMany(m => m.DoseLogs)
            .HasForeignKey(l => l.MedicationId)
            .OnDelete(DeleteBehavior.Restrict);

        // Adherence statistics are always "one dependant over a date range", which is exactly
        // what this composite index serves.
        builder.HasIndex(l => new { l.DependantId, l.ScheduledAt });
        builder.HasIndex(l => l.MedicationId);

        // A schedule can only be logged once for a given slot. This is what makes the offline
        // sync in section 3.5 idempotent: replaying the same queued entry cannot double-count.
        builder.HasIndex(l => new { l.ScheduleId, l.ScheduledAt }).IsUnique();
    }
}
