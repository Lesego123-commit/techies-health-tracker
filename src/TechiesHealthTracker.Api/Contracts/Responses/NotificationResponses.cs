// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>
/// A notification as shown on the Notifications screen (section 4.12), read straight off the
/// NotificationEvents table so the in-app history and what Firebase delivered cannot drift apart.
/// </summary>
public record NotificationResponse
{
    public Guid NotificationId { get; init; }
    public Guid? DependantId { get; init; }

    /// <summary>dose_reminder, missed_dose, appointment_reminder or refill_alert.</summary>
    public string Category { get; init; } = string.Empty;

    public string Title { get; init; } = string.Empty;
    public string Body { get; init; } = string.Empty;
    public string? DeepLink { get; init; }
    public bool IsSent { get; init; }
    public DateTime? SentAt { get; init; }
    public DateTime CreatedAt { get; init; }
}
