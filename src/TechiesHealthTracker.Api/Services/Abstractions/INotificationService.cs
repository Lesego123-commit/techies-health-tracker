// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Raises and reads notification events (Requirement 6). The API decides what is worth telling a
/// caregiver about and records it; actually pushing the message to the handset is the separate
/// concern of <see cref="IPushNotificationSender"/>.
/// </summary>
public interface INotificationService
{
    /// <summary>
    /// Records an event for later delivery. Respects the user's quiet hours, and will not create
    /// a second event for something the caregiver has already been told about today.
    /// </summary>
    Task<NotificationEvent?> RaiseAsync(
        Guid userId,
        string category,
        string title,
        string body,
        Guid? dependantId = null,
        string? deepLink = null,
        CancellationToken cancellationToken = default);

    /// <summary>Notification history for the caller, newest first.</summary>
    Task<IReadOnlyList<NotificationResponse>> ListForUserAsync(
        Guid userId, int limit = 50, CancellationToken cancellationToken = default);
}
