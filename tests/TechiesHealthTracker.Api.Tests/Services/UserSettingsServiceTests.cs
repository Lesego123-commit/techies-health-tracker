// ----------------------------------------------------------------------------------------------
//  References
//   .NET Foundation. 2024. Unit Testing in .NET with xUnit. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/core/testing/unit-testing-with-dotnet-test
//    [Accessed 16 September 2026].
//   Fluent Assertions. 2024. Documentation. [Online]. Available at:
//    https://fluentassertions.com/introduction [Accessed 16 September 2026].
//   Moq. 2024. Quickstart. [Online]. Available at:
//    https://github.com/devlooped/moq/wiki/Quickstart [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Services.Implementations;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for the Settings screen endpoints (Requirement 3). Preferences are stored server-side so
/// they survive a reinstall, which makes validating them the API's responsibility rather than
/// something the Android form can be trusted to have done.
/// </summary>
public class UserSettingsServiceTests : IAsyncLifetime
{
    private static readonly DateTime FixedNow = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

    private readonly AppDbContext _context = TestDatabase.CreateContext();
    private readonly FakeCurrentUserService _currentUser = new();
    private TestDatabase.SeededData _data = null!;
    private UserSettingsService _service = null!;

    public async Task InitializeAsync()
    {
        _data = await TestDatabase.SeedAsync(_context, FixedNow);
        _currentUser.SignInAs(_data.Caregiver);

        _service = new UserSettingsService(
            new UnitOfWork(_context), _currentUser, NullLogger<UserSettingsService>.Instance);
    }

    public Task DisposeAsync()
    {
        _context.Dispose();
        return Task.CompletedTask;
    }

    private Task<TechiesHealthTracker.Api.Contracts.Responses.SettingsResponse> UpdateAsync(
        UpdateSettingsRequest request)
        => _service.UpdateSettingsAsync(_data.Caregiver.UserId, request, CancellationToken.None);

    [Fact]
    public async Task UpdateSettingsAsync_ChangesOnlyTheFieldsSupplied()
    {
        // The Settings screen sends a single toggled preference, so everything left null must be
        // preserved rather than reset to its default.
        var result = await UpdateAsync(new UpdateSettingsRequest { Theme = "dark" });

        result.Theme.Should().Be("dark");
        result.PreferredLanguage.Should().Be(SupportedLanguages.English);
        result.ReminderLeadTimeMinutes.Should().Be(30);
        result.LowStockThresholdDays.Should().Be(7);
    }

    [Theory]
    [InlineData("zu")]
    [InlineData("af")]
    [InlineData("en")]
    public async Task UpdateSettingsAsync_AcceptsEachSupportedLanguage(string language)
    {
        // The three languages promised by Requirement 7.
        var result = await UpdateAsync(new UpdateSettingsRequest { PreferredLanguage = language });

        result.PreferredLanguage.Should().Be(language);
    }

    [Fact]
    public async Task UpdateSettingsAsync_NormalisesTheCaseOfAControlledValue()
    {
        // ZU and zu must not end up as two different values in the database.
        var result = await UpdateAsync(new UpdateSettingsRequest { PreferredLanguage = "ZU", Theme = "DARK" });

        result.PreferredLanguage.Should().Be("zu");
        result.Theme.Should().Be("dark");
    }

    [Fact]
    public async Task UpdateSettingsAsync_RejectsAnUnsupportedLanguage()
    {
        var act = () => UpdateAsync(new UpdateSettingsRequest { PreferredLanguage = "fr" });

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*not supported*");
    }

    [Fact]
    public async Task UpdateSettingsAsync_RejectsAnUnsupportedTheme()
    {
        var act = () => UpdateAsync(new UpdateSettingsRequest { Theme = "neon" });

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*not supported*");
    }

    [Fact]
    public async Task UpdateSettingsAsync_StoresAQuietHoursWindow()
    {
        var result = await UpdateAsync(new UpdateSettingsRequest
        {
            QuietHoursStart = "22:00",
            QuietHoursEnd = "06:00"
        });

        result.QuietHoursStart.Should().Be("22:00");
        result.QuietHoursEnd.Should().Be("06:00");
    }

    [Fact]
    public async Task UpdateSettingsAsync_ClearsQuietHoursWhenEitherEndIsBlanked()
    {
        await UpdateAsync(new UpdateSettingsRequest { QuietHoursStart = "22:00", QuietHoursEnd = "06:00" });

        var result = await UpdateAsync(new UpdateSettingsRequest { QuietHoursStart = "" });

        // Clearing one end clears both: a window with only a start is something the notification
        // service could not evaluate.
        result.QuietHoursStart.Should().BeNull();
        result.QuietHoursEnd.Should().BeNull();
    }

    [Fact]
    public async Task UpdateSettingsAsync_RejectsHalfAQuietHoursWindow()
    {
        var act = () => UpdateAsync(new UpdateSettingsRequest { QuietHoursStart = "22:00" });

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*both a start and an end*");
    }

    [Fact]
    public async Task UpdateSettingsAsync_RejectsAQuietHoursWindowOfZeroLength()
    {
        var act = () => UpdateAsync(new UpdateSettingsRequest
        {
            QuietHoursStart = "22:00",
            QuietHoursEnd = "22:00"
        });

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*same time*");
    }

    [Fact]
    public async Task UpdateSettingsAsync_RejectsAMalformedTime()
    {
        var act = () => UpdateAsync(new UpdateSettingsRequest
        {
            QuietHoursStart = "10pm",
            QuietHoursEnd = "06:00"
        });

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*HH:mm*");
    }

    [Fact]
    public async Task UpdateSettingsAsync_RefusesToChangeAnotherUsersSettings()
    {
        // The route carries a userId to match the documented contract, but the identity always
        // comes from the token: passing somebody else's id must not work.
        var act = () => _service.UpdateSettingsAsync(
            _data.OtherCaregiver.UserId, new UpdateSettingsRequest { Theme = "dark" }, CancellationToken.None);

        (await act.Should().ThrowAsync<ForbiddenException>()).WithMessage("*your own settings*");
    }

    [Fact]
    public async Task GetSettingsAsync_RefusesToReadAnotherUsersSettings()
    {
        var act = () => _service.GetSettingsAsync(_data.OtherCaregiver.UserId, CancellationToken.None);

        await act.Should().ThrowAsync<ForbiddenException>();
    }

    [Fact]
    public async Task UpdateSettingsAsync_StoresTheThresholdsThatDriveRemindersAndRefills()
    {
        var result = await UpdateAsync(new UpdateSettingsRequest
        {
            ReminderLeadTimeMinutes = 120,
            LowStockThresholdDays = 14,
            BiometricEnabled = true
        });

        result.ReminderLeadTimeMinutes.Should().Be(120);
        result.LowStockThresholdDays.Should().Be(14);
        result.BiometricEnabled.Should().BeTrue();
    }
}
