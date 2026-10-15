// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// Caregiver account. Profile fields (DisplayName, Email, PhotoUrl) are populated from the
/// Google profile carried on the Firebase identity token the first time a user signs in;
/// the settings fields are filled in as the user customises the app.
/// Maps to section 6.1 of the Part 1 Planning and Design document.
/// </summary>
public class User
{
    public Guid UserId { get; set; } = Guid.NewGuid();

    /// <summary>Firebase Authentication UID — the stable link between our row and the SSO provider.</summary>
    public string FirebaseUid { get; set; } = string.Empty;

    public string DisplayName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string? PhotoUrl { get; set; }

    // ---- Settings (exposed through PUT /api/users/{userId}/settings) ----
    public string PreferredLanguage { get; set; } = "en";
    public string Theme { get; set; } = "light";
    public bool BiometricEnabled { get; set; }

    /// <summary>
    /// Master switch for push notifications, exposed on the Settings screen (section 4.13).
    /// Distinct from quiet hours: this silences delivery entirely, whereas quiet hours only
    /// defers it. Events are still recorded either way, so the in-app Notifications history
    /// stays complete regardless of what the caregiver has chosen here.
    /// </summary>
    public bool NotificationsEnabled { get; set; } = true;
    public int ReminderLeadTimeMinutes { get; set; } = 30;
    public int LowStockThresholdDays { get; set; } = 7;
    public TimeOnly? QuietHoursStart { get; set; }
    public TimeOnly? QuietHoursEnd { get; set; }

    /// <summary>Firebase Cloud Messaging device token, refreshed by the client on every launch.</summary>
    public string? FcmToken { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>Concurrency token used by the offline-sync conflict resolution in section 3.5.</summary>
    public int RowVersion { get; set; }

    public ICollection<Dependant> Dependants { get; set; } = new List<Dependant>();
    public ICollection<NotificationEvent> NotificationEvents { get; set; } = new List<NotificationEvent>();
}
