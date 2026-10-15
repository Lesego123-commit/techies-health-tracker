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

/// <summary>Column rules for the Users table (section 6.1).</summary>
public class UserConfiguration : IEntityTypeConfiguration<User>
{
    public void Configure(EntityTypeBuilder<User> builder)
    {
        builder.ToTable("Users");
        builder.HasKey(u => u.UserId);

        builder.Property(u => u.FirebaseUid).HasMaxLength(128).IsRequired();
        builder.Property(u => u.DisplayName).HasMaxLength(100).IsRequired();
        builder.Property(u => u.Email).HasMaxLength(255).IsRequired();
        builder.Property(u => u.PhotoUrl).HasMaxLength(500);
        builder.Property(u => u.PreferredLanguage).HasMaxLength(5).IsRequired().HasDefaultValue("en");
        builder.Property(u => u.Theme).HasMaxLength(10).IsRequired().HasDefaultValue("light");
        builder.Property(u => u.BiometricEnabled).IsRequired().HasDefaultValue(false);
        builder.Property(u => u.NotificationsEnabled).IsRequired().HasDefaultValue(true);
        builder.Property(u => u.ReminderLeadTimeMinutes).IsRequired().HasDefaultValue(30);
        builder.Property(u => u.LowStockThresholdDays).IsRequired().HasDefaultValue(7);
        builder.Property(u => u.FcmToken).HasMaxLength(255);
        builder.Property(u => u.CreatedAt).IsRequired();
        builder.Property(u => u.UpdatedAt).IsRequired();
        builder.Property(u => u.RowVersion).IsRequired().HasDefaultValue(0).IsConcurrencyToken();

        // The Firebase UID is how every authenticated request finds its row, so it carries a
        // unique index; email is unique as well because Google accounts map one-to-one to users.
        builder.HasIndex(u => u.FirebaseUid).IsUnique();
        builder.HasIndex(u => u.Email).IsUnique();
    }
}
