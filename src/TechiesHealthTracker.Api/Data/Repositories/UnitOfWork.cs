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
/// Entity Framework Core implementation of <see cref="IUnitOfWork"/>. The repositories are
/// created lazily because most requests only ever touch two or three of the nine tables.
/// </summary>
public class UnitOfWork : IUnitOfWork
{
    private readonly AppDbContext _context;

    private IRepository<User>? _users;
    private IRepository<Dependant>? _dependants;
    private IRepository<Medication>? _medications;
    private IRepository<DoseSchedule>? _doseSchedules;
    private IRepository<DoseLog>? _doseLogs;
    private IRepository<Appointment>? _appointments;
    private IRepository<Document>? _documents;
    private IRepository<NotificationEvent>? _notificationEvents;
    private IRepository<PharmacyCacheEntry>? _pharmacyCache;

    public UnitOfWork(AppDbContext context) => _context = context;

    public IRepository<User> Users => _users ??= new Repository<User>(_context);
    public IRepository<Dependant> Dependants => _dependants ??= new Repository<Dependant>(_context);
    public IRepository<Medication> Medications => _medications ??= new Repository<Medication>(_context);
    public IRepository<DoseSchedule> DoseSchedules => _doseSchedules ??= new Repository<DoseSchedule>(_context);
    public IRepository<DoseLog> DoseLogs => _doseLogs ??= new Repository<DoseLog>(_context);
    public IRepository<Appointment> Appointments => _appointments ??= new Repository<Appointment>(_context);
    public IRepository<Document> Documents => _documents ??= new Repository<Document>(_context);

    public IRepository<NotificationEvent> NotificationEvents
        => _notificationEvents ??= new Repository<NotificationEvent>(_context);

    public IRepository<PharmacyCacheEntry> PharmacyCache
        => _pharmacyCache ??= new Repository<PharmacyCacheEntry>(_context);

    public Task<int> SaveChangesAsync(CancellationToken cancellationToken = default)
        => _context.SaveChangesAsync(cancellationToken);
}
