// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// A full history of every notification the API has raised, not just a debugging aid: the
/// Notifications screen reads straight off this table, and IsSent lets the background service
/// retry anything Firebase Cloud Messaging failed to deliver.
/// Maps to section 6.8 of the Part 1 Planning and Design document.
/// </summary>
public class NotificationEvent
{
    public Guid NotificationId { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }

    /// <summary>Null for notifications that are not about a specific dependant.</summary>
    public Guid? DependantId { get; set; }

    /// <summary>dose_reminder, missed_dose, appointment_reminder or refill_alert.</summary>
    public string Category { get; set; } = string.Empty;

    public string Title { get; set; } = string.Empty;
    public string Body { get; set; } = string.Empty;

    /// <summary>In-app navigation URI used to deep-link the notification to the right screen.</summary>
    public string? DeepLink { get; set; }

    public bool IsSent { get; set; }
    public DateTime? SentAt { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    public User User { get; set; } = null!;
    public Dependant? Dependant { get; set; }
}
