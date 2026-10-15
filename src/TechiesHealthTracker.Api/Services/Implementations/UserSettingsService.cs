// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Reads and updates the preferences behind the Settings screen (Requirement 3).
/// See <see cref="IUserSettingsService"/> for the contract.
/// </summary>
public class UserSettingsService : IUserSettingsService
{
    private readonly IUnitOfWork _unitOfWork;
    private readonly ICurrentUserService _currentUser;
    private readonly ILogger<UserSettingsService> _logger;

    public UserSettingsService(
        IUnitOfWork unitOfWork,
        ICurrentUserService currentUser,
        ILogger<UserSettingsService> logger)
    {
        _unitOfWork = unitOfWork;
        _currentUser = currentUser;
        _logger = logger;
    }

    public async Task<SettingsResponse> GetSettingsAsync(
        Guid userId, CancellationToken cancellationToken = default)
        => (await LoadOwnProfileAsync(userId, cancellationToken)).ToSettingsResponse();

    public async Task<SettingsResponse> UpdateSettingsAsync(
        Guid userId,
        UpdateSettingsRequest request,
        CancellationToken cancellationToken = default)
    {
        var user = await LoadOwnProfileAsync(userId, cancellationToken);

        ApplyLanguage(user, request.PreferredLanguage);
        ApplyTheme(user, request.Theme);

        if (request.BiometricEnabled.HasValue)
        {
            user.BiometricEnabled = request.BiometricEnabled.Value;
        }

        if (request.NotificationsEnabled.HasValue)
        {
            user.NotificationsEnabled = request.NotificationsEnabled.Value;
        }

        if (request.ReminderLeadTimeMinutes.HasValue)
        {
            user.ReminderLeadTimeMinutes = request.ReminderLeadTimeMinutes.Value;
        }

        if (request.LowStockThresholdDays.HasValue)
        {
            user.LowStockThresholdDays = request.LowStockThresholdDays.Value;
        }

        ApplyQuietHours(user, request);

        if (!string.IsNullOrWhiteSpace(request.FcmToken))
        {
            user.FcmToken = request.FcmToken;
        }

        _unitOfWork.Users.Update(user);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        _logger.LogInformation(
            "Settings updated for user {UserId}: language {Language}, theme {Theme}, biometric {Biometric}",
            user.UserId, user.PreferredLanguage, user.Theme, user.BiometricEnabled);

        return user.ToSettingsResponse();
    }

    /// <summary>
    /// Loads the profile only if the id in the route is the caller's own. The route carries a
    /// userId to match the documented contract, but it is never trusted as the identity: that
    /// always comes from the validated token.
    /// </summary>
    private async Task<User> LoadOwnProfileAsync(Guid userId, CancellationToken cancellationToken)
    {
        var user = await _currentUser.GetRequiredUserAsync(cancellationToken);

        if (user.UserId != userId)
        {
            throw new ForbiddenException("You can only read or change your own settings.");
        }

        return user;
    }

    private static void ApplyLanguage(User user, string? language)
    {
        if (string.IsNullOrWhiteSpace(language))
        {
            return;
        }

        var normalised = language.Trim().ToLowerInvariant();

        if (!SupportedLanguages.All.Contains(normalised))
        {
            throw new BusinessRuleException(
                $"Language {language} is not supported. Supported languages are: "
                + string.Join(", ", SupportedLanguages.All) + ".");
        }

        user.PreferredLanguage = normalised;
    }

    private static void ApplyTheme(User user, string? theme)
    {
        if (string.IsNullOrWhiteSpace(theme))
        {
            return;
        }

        var normalised = theme.Trim().ToLowerInvariant();

        if (!Themes.All.Contains(normalised))
        {
            throw new BusinessRuleException(
                $"Theme {theme} is not supported. Supported themes are: "
                + string.Join(", ", Themes.All) + ".");
        }

        user.Theme = normalised;
    }

    /// <summary>
    /// Quiet hours are all-or-nothing: a start without an end would leave the notification
    /// service with a window it cannot evaluate. Sending an empty string for either clears both.
    /// </summary>
    private static void ApplyQuietHours(User user, UpdateSettingsRequest request)
    {
        var startProvided = request.QuietHoursStart is not null;
        var endProvided = request.QuietHoursEnd is not null;

        if (!startProvided && !endProvided)
        {
            return;
        }

        var clearingStart = startProvided && string.IsNullOrWhiteSpace(request.QuietHoursStart);
        var clearingEnd = endProvided && string.IsNullOrWhiteSpace(request.QuietHoursEnd);

        if (clearingStart || clearingEnd)
        {
            user.QuietHoursStart = null;
            user.QuietHoursEnd = null;
            return;
        }

        var start = startProvided ? ParseTime(request.QuietHoursStart!, nameof(request.QuietHoursStart)) : user.QuietHoursStart;
        var end = endProvided ? ParseTime(request.QuietHoursEnd!, nameof(request.QuietHoursEnd)) : user.QuietHoursEnd;

        if (start is null || end is null)
        {
            throw new BusinessRuleException(
                "Quiet hours need both a start and an end time. Send both, or send an empty value to clear them.");
        }

        if (start == end)
        {
            throw new BusinessRuleException(
                "Quiet hours cannot start and end at the same time, as that would silence every notification.");
        }

        // A window that wraps past midnight (22:00 to 06:00) is normal and is handled by the
        // notification service, so no ordering check is applied here.
        user.QuietHoursStart = start;
        user.QuietHoursEnd = end;
    }

    private static TimeOnly ParseTime(string value, string fieldName)
    {
        if (!TimeOnly.TryParseExact(value.Trim(), "HH:mm", out var parsed))
        {
            throw new BusinessRuleException($"{fieldName} must be a 24-hour HH:mm value, for example 22:00.");
        }

        return parsed;
    }
}
