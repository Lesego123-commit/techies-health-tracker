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

/// <summary>Column rules for the Appointments table (section 6.6).</summary>
public class AppointmentConfiguration : IEntityTypeConfiguration<Appointment>
{
    public void Configure(EntityTypeBuilder<Appointment> builder)
    {
        builder.ToTable("Appointments");
        builder.HasKey(a => a.AppointmentId);

        builder.Property(a => a.Title).HasMaxLength(150).IsRequired();
        builder.Property(a => a.AppointmentDateTime).IsRequired();
        builder.Property(a => a.Location).HasMaxLength(255);
        builder.Property(a => a.Notes).HasMaxLength(500);
        builder.Property(a => a.ReminderMinutesBefore).IsRequired().HasDefaultValue(60);
        builder.Property(a => a.IsCancelled).IsRequired().HasDefaultValue(false);

        builder.HasOne(a => a.Dependant)
            .WithMany(d => d.Appointments)
            .HasForeignKey(a => a.DependantId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(a => new { a.DependantId, a.AppointmentDateTime });
    }
}
