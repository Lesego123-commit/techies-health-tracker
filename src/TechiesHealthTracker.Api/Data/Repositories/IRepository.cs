// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Infrastructure Persistence Layer Design — Repository and Unit of Work.
//    [Online]. Available at: https://learn.microsoft.com/dotnet/architecture/microservices/
//    microservice-ddd-cqrs-patterns/infrastructure-persistence-layer-design
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Data Access Layer).
// ----------------------------------------------------------------------------------------------

using System.Linq.Expressions;

namespace TechiesHealthTracker.Api.Data.Repositories;

/// <summary>
/// The generic half of the Repository pattern described in section 5.1 of the design document.
/// Services depend on this rather than on DbContext, which is what lets every service in
/// Services/Implementations be unit tested against an in-memory store.
/// </summary>
public interface IRepository<T> where T : class
{
    Task<T?> GetByIdAsync(Guid id, CancellationToken cancellationToken = default);

    Task<IReadOnlyList<T>> ListAsync(
        Expression<Func<T, bool>>? predicate = null,
        CancellationToken cancellationToken = default);

    Task<T?> FirstOrDefaultAsync(
        Expression<Func<T, bool>> predicate,
        CancellationToken cancellationToken = default);

    Task<bool> AnyAsync(Expression<Func<T, bool>> predicate, CancellationToken cancellationToken = default);

    Task<int> CountAsync(Expression<Func<T, bool>> predicate, CancellationToken cancellationToken = default);

    Task AddAsync(T entity, CancellationToken cancellationToken = default);

    Task AddRangeAsync(IEnumerable<T> entities, CancellationToken cancellationToken = default);

    void Update(T entity);

    void Remove(T entity);

    /// <summary>
    /// Escape hatch for the handful of reads that need projection, paging or a join. Returning
    /// IQueryable keeps those queries in the service that owns them without forcing a bespoke
    /// repository method for every variation.
    /// </summary>
    IQueryable<T> Query();
}
