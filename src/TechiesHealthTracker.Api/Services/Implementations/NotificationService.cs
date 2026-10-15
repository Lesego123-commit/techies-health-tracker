// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Decides what a caregiver should be told about and records it (Requirement 6).
/// See <see cref="INotificationService"/> for the contract and how this relates to delivery.
/// </summary>
public class NotificationService : INotificationService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<NotificationService> _logger;

    /// <summary>
    /// How long the same notification is suppressed for. Without this, every dose logged on a
    /// medication that is already low would raise another refill alert.
    /// </summary>
    private static readonly TimeSpan DuplicateSuppressionWindow = TimeSpan.FromHours(12);

    public NotificationService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        IDateTimeProvider clock,
        ILogger<NotificationService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _clock = clock;
        _logger = logger;
    }

    public async Task<NotificationEvent?> RaiseAsync(
        Guid userId,
        string category,
        string title,
        string body,
        Guid? dependantId = null,
        string? deepLink = null,
        CancellationToken cancellationToken = default)
    {
        var user = await _context.Users.FirstOrDefaultAsync(u => u.UserId == userId, cancellationToken);

        if (user is null)
        {
            _logger.LogWarning(
                "Notification {Category} skipped: user {UserId} no longer exists", category, userId);

            return null;
        }

        var now = _clock.UtcNow;
        var since = now - DuplicateSuppressionWindow;

        var alreadyRaised = await _context.NotificationEvents.AnyAsync(
            n => n.UserId == userId
                 && n.Category == category
                 && n.DependantId == dependantId
                 && n.Title == title
                 && n.Body == body
                 && n.CreatedAt >= since,
            cancellationToken);

        if (alreadyRaised)
        {
            _logger.LogDebug(
                "Notification {Category} for user {UserId} suppressed as a recent duplicate", category, userId);

            return null;
        }

        var notification = new NotificationEvent
        {
            UserId = userId,
            DependantId = dependantId,
            Category = category,
            Title = title,
            Body = body,
            DeepLink = deepLink,
            IsSent = false,
            CreatedAt = now
        };

        await _unitOfWork.NotificationEvents.AddAsync(notification, cancellationToken);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        // Quiet hours delay delivery, they do not discard the event: the caregiver still sees it
        // in the Notifications screen, and the sender picks it up once the window has passed.
        if (IsWithinQuietHours(user, now))
        {
            _logger.LogInformation(
                "Notification {NotificationId} recorded but held: user {UserId} is inside quiet hours",
                notification.NotificationId, userId);
        }
        else
        {
            _logger.LogInformation(
                "Notification {NotificationId} ({Category}) raised for user {UserId}",
                notification.NotificationId, category, userId);
        }

        return notification;
    }

    public async Task<IReadOnlyList<NotificationResponse>> ListForUserAsync(
        Guid userId, int limit = 50, CancellationToken cancellationToken = default)
    {
        var take = Math.Clamp(limit, 1, 200);

        var notifications = await _context.NotificationEvents
            .AsNoTracking()
            .Where(n => n.UserId == userId)
            .OrderByDescending(n => n.CreatedAt)
            .Take(take)
            .ToListAsync(cancellationToken);

        return notifications.Select(n => n.ToResponse()).ToList();
    }

    /// <summary>
    /// Whether the given instant falls inside the user's do-not-disturb window. Handles a window
    /// that wraps past midnight, which is the usual case: 22:00 to 06:00.
    /// </summary>
    internal static bool IsWithinQuietHours(User user, DateTime utcNow)
    {
        if (user.QuietHoursStart is null || user.QuietHoursEnd is null)
        {
            return false;
        }

        var start = user.QuietHoursStart.Value;
        var end = user.QuietHoursEnd.Value;
        var current = TimeOnly.FromDateTime(utcNow);

        return start <= end
            ? current >= start && current < end
            : current >= start || current < end;
    }
}
