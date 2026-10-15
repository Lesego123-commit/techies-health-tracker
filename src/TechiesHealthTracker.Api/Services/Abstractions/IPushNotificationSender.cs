// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Delivers a recorded notification to a device through Firebase Cloud Messaging.
///
/// Live push delivery is a POE-phase requirement, so the Part 2 build registers a no-op
/// implementation that marks events as handled without calling Firebase. Keeping the seam here
/// means switching it on at POE is a registration change in Program.cs rather than a rewrite of
/// the services that raise the events.
/// </summary>
public interface IPushNotificationSender
{
    /// <summary>
    /// Attempts delivery. Returns true when the message was accepted by the transport, which is
    /// what lets the caller set IsSent and stop retrying.
    /// </summary>
    Task<bool> SendAsync(
        NotificationEvent notification, string? deviceToken, CancellationToken cancellationToken = default);
}
