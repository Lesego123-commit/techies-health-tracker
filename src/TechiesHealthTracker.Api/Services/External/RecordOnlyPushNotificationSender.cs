// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Firebase Cloud Messaging. [Online]. Available at:
//    https://firebase.google.com/docs/cloud-messaging [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.6 (Requirement 6).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.External;

/// <summary>
/// The Part 2 implementation of <see cref="IPushNotificationSender"/>.
///
/// Live Firebase Cloud Messaging delivery is a POE-phase requirement, so nothing is dispatched
/// here. The event is still recorded and logged, which means the Notifications screen works and
/// the decision logic that raises events is fully exercised and testable now. Switching delivery
/// on at POE is a matter of registering a Firebase-backed sender in place of this one.
/// </summary>
public class RecordOnlyPushNotificationSender : IPushNotificationSender
{
    private readonly ILogger<RecordOnlyPushNotificationSender> _logger;

    public RecordOnlyPushNotificationSender(ILogger<RecordOnlyPushNotificationSender> logger)
        => _logger = logger;

    public Task<bool> SendAsync(
        NotificationEvent notification,
        string? deviceToken,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(deviceToken))
        {
            // No device token means the app has not registered for messaging on this account yet.
            // The event stays unsent so a later sweep can pick it up once a token arrives.
            _logger.LogDebug(
                "Notification {NotificationId} not dispatched: user {UserId} has no device token",
                notification.NotificationId, notification.UserId);

            return Task.FromResult(false);
        }

        _logger.LogInformation(
            "Notification {NotificationId} ({Category}) would be pushed to Firebase Cloud Messaging: {Title}",
            notification.NotificationId, notification.Category, notification.Title);

        // Reported as delivered so the retry loop does not build up a backlog during Part 2.
        return Task.FromResult(true);
    }
}
