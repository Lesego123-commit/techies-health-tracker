// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Infrastructure Persistence Layer Design — Repository and Unit of Work.
//    [Online]. Available at: https://learn.microsoft.com/dotnet/architecture/microservices/
//    microservice-ddd-cqrs-patterns/infrastructure-persistence-layer-design
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Data Access Layer).
// ----------------------------------------------------------------------------------------------

using System.Linq.Expressions;
using Microsoft.EntityFrameworkCore;

namespace TechiesHealthTracker.Api.Data.Repositories;

/// <summary>Entity Framework Core implementation of <see cref="IRepository{T}"/>.</summary>
public class Repository<T> : IRepository<T> where T : class
{
    protected readonly AppDbContext Context;
    protected readonly DbSet<T> Set;

    public Repository(AppDbContext context)
    {
        Context = context;
        Set = context.Set<T>();
    }

    public virtual async Task<T?> GetByIdAsync(Guid id, CancellationToken cancellationToken = default)
        => await Set.FindAsync(new object?[] { id }, cancellationToken);

    public virtual async Task<IReadOnlyList<T>> ListAsync(
        Expression<Func<T, bool>>? predicate = null,
        CancellationToken cancellationToken = default)
    {
        // AsNoTracking on reads: nothing here is going to be written back, and skipping the
        // change tracker measurably cuts the cost of the larger list endpoints.
        var query = Set.AsNoTracking();

        if (predicate is not null)
        {
            query = query.Where(predicate);
        }

        return await query.ToListAsync(cancellationToken);
    }

    public virtual async Task<T?> FirstOrDefaultAsync(
        Expression<Func<T, bool>> predicate,
        CancellationToken cancellationToken = default)
        => await Set.FirstOrDefaultAsync(predicate, cancellationToken);

    public virtual async Task<bool> AnyAsync(
        Expression<Func<T, bool>> predicate,
        CancellationToken cancellationToken = default)
        => await Set.AnyAsync(predicate, cancellationToken);

    public virtual async Task<int> CountAsync(
        Expression<Func<T, bool>> predicate,
        CancellationToken cancellationToken = default)
        => await Set.CountAsync(predicate, cancellationToken);

    public virtual async Task AddAsync(T entity, CancellationToken cancellationToken = default)
        => await Set.AddAsync(entity, cancellationToken);

    public virtual async Task AddRangeAsync(IEnumerable<T> entities, CancellationToken cancellationToken = default)
        => await Set.AddRangeAsync(entities, cancellationToken);

    public virtual void Update(T entity) => Set.Update(entity);

    public virtual void Remove(T entity) => Set.Remove(entity);

    public virtual IQueryable<T> Query() => Set.AsQueryable();
}
