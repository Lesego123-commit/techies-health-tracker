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

/// <summary>Column rules for the NotificationEvents table (section 6.8).</summary>
public class NotificationEventConfiguration : IEntityTypeConfiguration<NotificationEvent>
{
    public void Configure(EntityTypeBuilder<NotificationEvent> builder)
    {
        builder.ToTable("NotificationEvents");
        builder.HasKey(n => n.NotificationId);

        builder.Property(n => n.Category).HasMaxLength(30).IsRequired();
        builder.Property(n => n.Title).HasMaxLength(150).IsRequired();
        builder.Property(n => n.Body).HasMaxLength(500).IsRequired();
        builder.Property(n => n.DeepLink).HasMaxLength(255);
        builder.Property(n => n.IsSent).IsRequired().HasDefaultValue(false);
        builder.Property(n => n.CreatedAt).IsRequired();

        builder.HasOne(n => n.User)
            .WithMany(u => u.NotificationEvents)
            .HasForeignKey(n => n.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasOne(n => n.Dependant)
            .WithMany()
            .HasForeignKey(n => n.DependantId)
            .OnDelete(DeleteBehavior.SetNull);

        builder.HasIndex(n => new { n.UserId, n.CreatedAt });

        // The delivery worker polls for anything Firebase Cloud Messaging has not taken yet.
        builder.HasIndex(n => n.IsSent);
    }
}
