// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>
/// Response of POST /api/auth/login (section 5.4.1): the user profile together with their
/// dependants, so the Android client can render the Home screen from one round trip on launch.
/// </summary>
public record UserResponse
{
    public Guid UserId { get; init; }
    public string DisplayName { get; init; } = string.Empty;
    public string Email { get; init; } = string.Empty;
    public string? PhotoUrl { get; init; }
    public SettingsResponse Settings { get; init; } = new();
    public IReadOnlyList<DependantResponse> Dependants { get; init; } = Array.Empty<DependantResponse>();

    /// <summary>True when this call created the profile rather than returning an existing one.</summary>
    public bool IsNewUser { get; init; }

    public DateTime CreatedAt { get; init; }
}

/// <summary>
/// Response of PUT /api/users/{userId}/settings, and the Settings block nested in
/// <see cref="UserResponse"/>. Mirrors the Settings screen field for field (Requirement 3).
/// </summary>
public record SettingsResponse
{
    public string PreferredLanguage { get; init; } = "en";
    public string Theme { get; init; } = "light";
    public bool BiometricEnabled { get; init; }
    public bool NotificationsEnabled { get; init; } = true;
    public int ReminderLeadTimeMinutes { get; init; } = 30;
    public int LowStockThresholdDays { get; init; } = 7;

    /// <summary>HH:mm, or null when no quiet period is configured.</summary>
    public string? QuietHoursStart { get; init; }

    /// <summary>HH:mm, or null when no quiet period is configured.</summary>
    public string? QuietHoursEnd { get; init; }

    public DateTime UpdatedAt { get; init; }
    public int RowVersion { get; init; }
}
