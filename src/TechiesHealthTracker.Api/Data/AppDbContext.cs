// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. DbContext Configuration and Initialisation — EF Core. [Online]. Available
//    at: https://learn.microsoft.com/ef/core/dbcontext-configuration/ [Accessed 16 September 2026].
//   Microsoft. 2024. Handling Concurrency Conflicts — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/saving/concurrency [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 3.5 and 6.
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Data;

/// <summary>
/// The Entity Framework Core context for the Techies Health Tracker database (section 6 of the
/// Part 1 Planning and Design document). Schema changes are applied through EF Core migrations
/// rather than hand-written SQL, so the C# model stays the single source of truth.
/// </summary>
public class AppDbContext : DbContext
{
    public AppDbContext(DbContextOptions<AppDbContext> options) : base(options) { }

    public DbSet<User> Users => Set<User>();
    public DbSet<Dependant> Dependants => Set<Dependant>();
    public DbSet<Medication> Medications => Set<Medication>();
    public DbSet<DoseSchedule> DoseSchedules => Set<DoseSchedule>();
    public DbSet<DoseLog> DoseLogs => Set<DoseLog>();
    public DbSet<Appointment> Appointments => Set<Appointment>();
    public DbSet<Document> Documents => Set<Document>();
    public DbSet<NotificationEvent> NotificationEvents => Set<NotificationEvent>();
    public DbSet<PharmacyCacheEntry> PharmacyCache => Set<PharmacyCacheEntry>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        // Each entity has its own IEntityTypeConfiguration in Data/Configurations, which keeps
        // this class small and puts the column rules next to the table they describe.
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(AppDbContext).Assembly);
    }

    /// <summary>
    /// Stamps UpdatedAt and advances the integer RowVersion concurrency tokens on save.
    /// PostgreSQL will not do this for us because RowVersion is a plain integer rather than an
    /// xmin system column; keeping it as an integer is deliberate, as the Android client has to
    /// be able to read, store and send the same value back through POST /api/sync/batch.
    /// </summary>
    public override Task<int> SaveChangesAsync(CancellationToken cancellationToken = default)
    {
        ApplyAuditAndConcurrencyStamps();
        return base.SaveChangesAsync(cancellationToken);
    }

    public override int SaveChanges()
    {
        ApplyAuditAndConcurrencyStamps();
        return base.SaveChanges();
    }

    private void ApplyAuditAndConcurrencyStamps()
    {
        var now = DateTime.UtcNow;

        foreach (var entry in ChangeTracker.Entries())
        {
            if (entry.State is not (EntityState.Added or EntityState.Modified))
            {
                continue;
            }

            // UpdatedAt is present on most but not all entities, so it is set by name rather than
            // through a shared base class, which would otherwise force it onto DoseLog and Document.
            if (entry.Metadata.FindProperty("UpdatedAt") is not null)
            {
                entry.Property("UpdatedAt").CurrentValue = now;
            }

            if (entry.State == EntityState.Modified && entry.Metadata.FindProperty("RowVersion") is not null)
            {
                var current = entry.Property("RowVersion").CurrentValue as int? ?? 0;
                var original = entry.Property("RowVersion").OriginalValue as int? ?? 0;

                // Only bump the token when the caller has not set it deliberately. The sync
                // service assigns an explicit RowVersion when it resolves an offline conflict.
                if (current == original)
                {
                    entry.Property("RowVersion").CurrentValue = original + 1;
                }
            }
        }
    }
}
