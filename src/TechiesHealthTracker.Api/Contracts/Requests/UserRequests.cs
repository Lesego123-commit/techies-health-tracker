// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>
/// Body of PUT /api/users/{userId}/settings (section 5.4.1). Every field is optional so the
/// Settings screen can send a single changed preference rather than the whole object; a null
/// property means "leave this as it is" rather than "clear it".
/// </summary>
public class UpdateSettingsRequest
{
    /// <summary>ISO 639-1 code. One of en, zu or af (Requirement 7).</summary>
    [StringLength(5, MinimumLength = 2)]
    public string? PreferredLanguage { get; set; }

    /// <summary>light, dark or system.</summary>
    [StringLength(10)]
    public string? Theme { get; set; }

    public bool? BiometricEnabled { get; set; }

    /// <summary>Master switch for push delivery. Events are still recorded when this is false.</summary>
    public bool? NotificationsEnabled { get; set; }

    /// <summary>How far ahead of a dose the reminder fires. Capped at 24 hours.</summary>
    [Range(0, 1440)]
    public int? ReminderLeadTimeMinutes { get; set; }

    /// <summary>How many days of stock left before a refill alert is raised.</summary>
    [Range(1, 90)]
    public int? LowStockThresholdDays { get; set; }

    /// <summary>Start of the do-not-disturb window in HH:mm, or null to clear it.</summary>
    public string? QuietHoursStart { get; set; }

    /// <summary>End of the do-not-disturb window in HH:mm, or null to clear it.</summary>
    public string? QuietHoursEnd { get; set; }

    /// <summary>Firebase Cloud Messaging device token, refreshed by the client on each launch.</summary>
    [StringLength(255)]
    public string? FcmToken { get; set; }
}
