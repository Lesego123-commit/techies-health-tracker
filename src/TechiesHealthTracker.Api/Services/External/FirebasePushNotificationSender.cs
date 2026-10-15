// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Firebase Admin SDK — Send Messages to Multiple Devices. [Online]. Available at:
//    https://firebase.google.com/docs/cloud-messaging/send-message [Accessed 25 September 2026].
//   Google. 2024. Add the Firebase Admin SDK to Your Server. [Online]. Available at:
//    https://firebase.google.com/docs/admin/setup [Accessed 25 September 2026].
//   Google. 2024. FirebaseAdmin.Messaging.FirebaseMessaging (.NET). [Online]. Available at:
//    https://firebase.google.com/docs/reference/admin/dotnet/class/firebase-admin/messaging/firebase-messaging
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.6 (Requirement 6:
//    Real-time notifications).
// ----------------------------------------------------------------------------------------------

using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.External;

/// <summary>
/// Live Firebase Cloud Messaging delivery (Requirement 6).
///
/// Sends **data messages** rather than notification messages. A notification message is drawn by
/// the Android system and only handed to the app when it is in the foreground, which would leave
/// the client unable to choose a channel or a destination for an alert arriving while the phone
/// is locked — precisely when a missed-dose alert matters. A data message is always delivered to
/// the client's messaging service, so one code path builds every notification.
/// </summary>
public class FirebasePushNotificationSender : IPushNotificationSender
{
    private readonly ILogger<FirebasePushNotificationSender> _logger;

    public FirebasePushNotificationSender(ILogger<FirebasePushNotificationSender> logger)
        => _logger = logger;

    public async Task<bool> SendAsync(
        NotificationEvent notification,
        string? deviceToken,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(deviceToken))
        {
            // No token means the app has not registered for messaging on this account yet. The
            // event stays unsent so a later sweep can pick it up once a token arrives.
            _logger.LogDebug(
                "Notification {NotificationId} not dispatched: user {UserId} has no device token",
                notification.NotificationId, notification.UserId);

            return false;
        }

        var message = new Message
        {
            Token = deviceToken,

            // Keys match TechiesMessagingService on the client. Values must be strings: FCM data
            // payloads are string-to-string, and a null here throws rather than being omitted.
            Data = new Dictionary<string, string>
            {
                ["category"] = notification.Category ?? string.Empty,
                ["title"] = notification.Title ?? string.Empty,
                ["body"] = notification.Body ?? string.Empty,
                ["deepLink"] = notification.DeepLink ?? string.Empty,
                ["notificationId"] = notification.NotificationId.ToString()
            },

            Android = new AndroidConfig
            {
                // High priority wakes a dozing device. A dose reminder that arrives an hour late
                // because the phone was idle is worse than useless — the dose is already missed.
                Priority = Priority.High
            }
        };

        try
        {
            var id = await FirebaseMessaging.DefaultInstance.SendAsync(message, cancellationToken);

            _logger.LogInformation(
                "Notification {NotificationId} ({Category}) delivered to Firebase as {MessageId}",
                notification.NotificationId, notification.Category, id);

            return true;
        }
        catch (FirebaseMessagingException e) when (
            e.MessagingErrorCode == MessagingErrorCode.Unregistered ||
            e.MessagingErrorCode == MessagingErrorCode.InvalidArgument)
        {
            // The token belongs to an app that has been uninstalled, or is malformed. Retrying
            // will never succeed, so this is reported as handled rather than left to build up a
            // backlog the sweep would retry forever.
            _logger.LogWarning(
                "Notification {NotificationId} dropped: the device token is no longer valid ({Code})",
                notification.NotificationId, e.MessagingErrorCode);

            return true;
        }
        catch (Exception e)
        {
            // Anything else — a network blip, a quota, Firebase being down — is worth retrying,
            // so the event is left unsent.
            _logger.LogError(
                e, "Notification {NotificationId} could not be delivered",
                notification.NotificationId);

            return false;
        }
    }
}

/// <summary>
/// Sets up the Firebase Admin SDK once per process, and reports whether it is usable.
///
/// Credentials are read from a path or from the raw JSON in configuration, never from a file in
/// the repository. When neither is present the API keeps the record-only sender, so a developer
/// without a service account still gets a working build with a complete notification history —
/// only the delivery step is missing.
/// </summary>
public static class FirebaseAdminSetup
{
    public static bool TryInitialise(IConfiguration configuration, ILogger logger)
    {
        var credentialsPath = configuration["Firebase:ServiceAccountPath"];
        var credentialsJson = configuration["Firebase:ServiceAccountJson"];

        if (string.IsNullOrWhiteSpace(credentialsPath) && string.IsNullOrWhiteSpace(credentialsJson))
        {
            logger.LogInformation(
                "No Firebase service account configured; push notifications will be recorded but not delivered");

            return false;
        }

        try
        {
            if (FirebaseApp.DefaultInstance is not null)
            {
                return true;
            }

            var credential = !string.IsNullOrWhiteSpace(credentialsJson)
                ? GoogleCredential.FromJson(credentialsJson)
                : GoogleCredential.FromFile(credentialsPath!);

            FirebaseApp.Create(new AppOptions
            {
                Credential = credential,
                ProjectId = configuration["Firebase:ProjectId"]
            });

            logger.LogInformation("Firebase Admin SDK initialised; push delivery is live");
            return true;
        }
        catch (Exception e)
        {
            // A bad service account must not stop the API from starting. Every other endpoint
            // works without push, and a dead API is a far worse outcome than silent notifications.
            logger.LogError(e, "Firebase Admin SDK could not be initialised; falling back to record-only");
            return false;
        }
    }
}
