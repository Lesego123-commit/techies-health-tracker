// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Infrastructure Persistence Layer Design — Repository and Unit of Work.
//    [Online]. Available at: https://learn.microsoft.com/dotnet/architecture/microservices/
//    microservice-ddd-cqrs-patterns/infrastructure-persistence-layer-design
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Data Access Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Data.Repositories;

/// <summary>
/// Groups the repositories behind one commit boundary. Several operations touch more than one
/// table in a single request — logging a dose writes a DoseLog, decrements Medication.RemainingQuantity,
/// recalculates DoseSchedule.NextDoseAt and can raise a NotificationEvent — and all of that has
/// to land or fail together.
/// </summary>
public interface IUnitOfWork
{
    IRepository<User> Users { get; }
    IRepository<Dependant> Dependants { get; }
    IRepository<Medication> Medications { get; }
    IRepository<DoseSchedule> DoseSchedules { get; }
    IRepository<DoseLog> DoseLogs { get; }
    IRepository<Appointment> Appointments { get; }
    IRepository<Document> Documents { get; }
    IRepository<NotificationEvent> NotificationEvents { get; }
    IRepository<PharmacyCacheEntry> PharmacyCache { get; }

    /// <summary>Commits everything tracked in this request. Returns the number of affected rows.</summary>
    Task<int> SaveChangesAsync(CancellationToken cancellationToken = default);
}
