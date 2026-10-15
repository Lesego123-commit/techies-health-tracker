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
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.External;
using TechiesHealthTracker.Api.Services.Implementations;
using TechiesHealthTracker.Api.Services.Mapping;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for notification decisions, the pharmacy cache key, the Haversine distance calculation
/// and the shared mapping helpers. These are small pieces, but each one is depended on by several
/// endpoints, so a fault in any of them shows up in more than one place.
/// </summary>
public class NotificationAndMappingTests
{
    private static readonly DateTime FixedNow = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

    // ---- Quiet hours ----

    private static User BuildUser(string? quietStart, string? quietEnd) => new()
    {
        DisplayName = "Test",
        Email = "test@example.com",
        QuietHoursStart = quietStart is null ? null : TimeOnly.Parse(quietStart),
        QuietHoursEnd = quietEnd is null ? null : TimeOnly.Parse(quietEnd)
    };

    [Fact]
    public void IsWithinQuietHours_IsFalseWhenNoWindowIsConfigured()
    {
        NotificationService.IsWithinQuietHours(BuildUser(null, null), FixedNow).Should().BeFalse();
    }

    [Theory]
    [InlineData(23, true)]   // inside a window that wraps past midnight
    [InlineData(2, true)]    // still inside, on the far side of midnight
    [InlineData(22, true)]   // the start is inclusive
    [InlineData(6, false)]   // the end is exclusive
    [InlineData(12, false)]  // the middle of the day
    public void IsWithinQuietHours_HandlesAWindowThatWrapsPastMidnight(int hour, bool expected)
    {
        // 22:00 to 06:00 is the usual case and the one an ordinary start-before-end comparison
        // gets wrong, silencing nothing instead of silencing the night.
        var user = BuildUser("22:00", "06:00");
        var instant = new DateTime(2026, 4, 15, hour, 0, 0, DateTimeKind.Utc);

        NotificationService.IsWithinQuietHours(user, instant).Should().Be(expected);
    }

    [Theory]
    [InlineData(14, true)]
    [InlineData(13, false)]
    [InlineData(16, false)]
    public void IsWithinQuietHours_HandlesAWindowInsideOneDay(int hour, bool expected)
    {
        var user = BuildUser("14:00", "16:00");
        var instant = new DateTime(2026, 4, 15, hour, 0, 0, DateTimeKind.Utc);

        NotificationService.IsWithinQuietHours(user, instant).Should().Be(expected);
    }

    // ---- Duplicate suppression ----

    [Fact]
    public async Task RaiseAsync_SuppressesARepeatOfTheSameNotification()
    {
        using var context = TestDatabase.CreateContext();
        var data = await TestDatabase.SeedAsync(context, FixedNow);
        var clock = new TestClock(FixedNow);

        var service = new NotificationService(
            context, new UnitOfWork(context), clock, NullLogger<NotificationService>.Instance);

        var first = await service.RaiseAsync(
            data.Caregiver.UserId, NotificationCategories.RefillAlert, "Refill needed", "Amoxicillin is low.");

        // Without suppression, every dose logged on an already-low medication would raise another
        // identical alert and the Notifications screen would be unusable.
        var second = await service.RaiseAsync(
            data.Caregiver.UserId, NotificationCategories.RefillAlert, "Refill needed", "Amoxicillin is low.");

        first.Should().NotBeNull();
        second.Should().BeNull();
        (await context.NotificationEvents.CountAsync()).Should().Be(1);
    }

    [Fact]
    public async Task RaiseAsync_AllowsTheSameNotificationAgainOnceTheWindowHasPassed()
    {
        using var context = TestDatabase.CreateContext();
        var data = await TestDatabase.SeedAsync(context, FixedNow);
        var clock = new TestClock(FixedNow);

        var service = new NotificationService(
            context, new UnitOfWork(context), clock, NullLogger<NotificationService>.Instance);

        await service.RaiseAsync(
            data.Caregiver.UserId, NotificationCategories.RefillAlert, "Refill needed", "Amoxicillin is low.");

        // Suppression is a 12-hour window, not permanent: a medication that is still low the next
        // day is worth mentioning again.
        clock.Advance(TimeSpan.FromHours(13));

        var later = await service.RaiseAsync(
            data.Caregiver.UserId, NotificationCategories.RefillAlert, "Refill needed", "Amoxicillin is low.");

        later.Should().NotBeNull();
        (await context.NotificationEvents.CountAsync()).Should().Be(2);
    }

    [Fact]
    public async Task RaiseAsync_StillRecordsAnEventRaisedDuringQuietHours()
    {
        using var context = TestDatabase.CreateContext();
        var data = await TestDatabase.SeedAsync(context, FixedNow);

        data.Caregiver.QuietHoursStart = new TimeOnly(22, 0);
        data.Caregiver.QuietHoursEnd = new TimeOnly(6, 0);
        await context.SaveChangesAsync();

        var clock = new TestClock(new DateTime(2026, 4, 15, 23, 30, 0, DateTimeKind.Utc));

        var service = new NotificationService(
            context, new UnitOfWork(context), clock, NullLogger<NotificationService>.Instance);

        var notification = await service.RaiseAsync(
            data.Caregiver.UserId, NotificationCategories.MissedDose, "Dose not recorded", "Evening dose.");

        // Quiet hours delay delivery, they do not discard the event: the caregiver still needs to
        // see it in the Notifications screen in the morning.
        notification.Should().NotBeNull();
        notification!.IsSent.Should().BeFalse();
    }

    [Fact]
    public async Task ListForUserAsync_ReturnsNewestFirstAndClampsTheLimit()
    {
        using var context = TestDatabase.CreateContext();
        var data = await TestDatabase.SeedAsync(context, FixedNow);
        var clock = new TestClock(FixedNow);

        var service = new NotificationService(
            context, new UnitOfWork(context), clock, NullLogger<NotificationService>.Instance);

        for (var i = 0; i < 3; i++)
        {
            await service.RaiseAsync(
                data.Caregiver.UserId, NotificationCategories.DoseReminder, $"Dose {i}", $"Body {i}");

            clock.Advance(TimeSpan.FromMinutes(30));
        }

        var all = await service.ListForUserAsync(data.Caregiver.UserId);
        all.Should().HaveCount(3);
        all.Should().BeInDescendingOrder(n => n.CreatedAt);

        // A nonsensical limit must not produce an empty or unbounded page.
        (await service.ListForUserAsync(data.Caregiver.UserId, limit: 0)).Should().HaveCount(1);
    }

    // ---- Haversine distance ----

    [Fact]
    public void CalculateHaversineKm_IsZeroForTheSamePoint()
    {
        GeoApifyClient.CalculateHaversineKm(-26.2041, 28.0473, -26.2041, 28.0473)
            .Should().BeApproximately(0, 0.0001);
    }

    [Fact]
    public void CalculateHaversineKm_MatchesAKnownDistance()
    {
        // Johannesburg city centre to Pretoria city centre is about 54 km as the crow flies. A
        // tolerance of 2 km is tight enough to catch a formula error — a wrong Earth radius or a
        // missing degrees-to-radians conversion would be out by far more than that.
        var distance = GeoApifyClient.CalculateHaversineKm(-26.2041, 28.0473, -25.7479, 28.2293);

        distance.Should().BeApproximately(54, 2);
    }

    [Fact]
    public void CalculateHaversineKm_IsSymmetric()
    {
        var there = GeoApifyClient.CalculateHaversineKm(-26.2041, 28.0473, -33.9249, 18.4241);
        var back = GeoApifyClient.CalculateHaversineKm(-33.9249, 18.4241, -26.2041, 28.0473);

        there.Should().BeApproximately(back, 0.0001);
    }

    // ---- GeoApify response parsing ----

    [Fact]
    public void ParseResults_MapsFeaturesAndSortsByDistance()
    {
        // A trimmed-down copy of a real GeoApify response, so the parser is exercised against the
        // shape it will actually meet without needing a network call or an API key.
        const string json = """
        {
          "features": [
            {
              "properties": {
                "name": "Far Pharmacy",
                "formatted": "100 Far Road, Sandton",
                "lat": -26.1076,
                "lon": 28.0567,
                "opening_hours": "Mo-Fr 08:00-18:00",
                "phone": "+27 11 000 0000"
              }
            },
            {
              "properties": {
                "name": "Near Pharmacy",
                "formatted": "1 Near Street, Johannesburg",
                "lat": -26.2050,
                "lon": 28.0480
              }
            }
          ]
        }
        """;

        var results = GeoApifyClient.ParseResults(json, -26.2041, 28.0473);

        results.Should().HaveCount(2);
        results[0].Name.Should().Be("Near Pharmacy");
        results[1].Name.Should().Be("Far Pharmacy");
        results.Should().BeInAscendingOrder(r => r.DistanceKm);
        results[1].OpeningHours.Should().Be("Mo-Fr 08:00-18:00");
    }

    [Fact]
    public void ParseResults_SkipsAFeatureWithNoCoordinates()
    {
        // A result with no position cannot be placed on the map or measured, so it is dropped
        // rather than shown at a distance of zero.
        const string json = """
        { "features": [ { "properties": { "name": "Nowhere Pharmacy" } } ] }
        """;

        GeoApifyClient.ParseResults(json, -26.2041, 28.0473).Should().BeEmpty();
    }

    [Fact]
    public void ParseResults_LabelsAnUnnamedPharmacyRatherThanDroppingIt()
    {
        const string json = """
        { "features": [ { "properties": { "lat": -26.2050, "lon": 28.0480 } } ] }
        """;

        var results = GeoApifyClient.ParseResults(json, -26.2041, 28.0473);

        results.Should().ContainSingle();
        results[0].Name.Should().Be("Unnamed pharmacy");
    }

    [Fact]
    public void ParseResults_ReturnsNothingForAnEmptyResponse()
    {
        GeoApifyClient.ParseResults("{}", -26.2041, 28.0473).Should().BeEmpty();
    }

    [Fact]
    public void ParseResults_ReadsAPhoneNumberFromTheNestedContactObject()
    {
        // The shape GeoApify actually returns, taken from a live search around Johannesburg.
        // The fixture above uses a flat "phone" key, which is why this went unnoticed: the parser
        // asked for a property literally named "contact.phone", which no response has ever had,
        // so every pharmacy that does publish a number came back without one and the client's
        // "Call" button never appeared.
        const string json = """
        {
          "features": [
            {
              "properties": {
                "name": "Joburg City Pharmacy",
                "formatted": "1 Example Street, Johannesburg",
                "lat": -26.2050,
                "lon": 28.0480,
                "contact": { "phone": "+27 117252222" }
              }
            }
          ]
        }
        """;

        var results = GeoApifyClient.ParseResults(json, -26.2041, 28.0473);

        results.Should().ContainSingle();
        results[0].Phone.Should().Be("+27 117252222");
    }

    [Fact]
    public void ParseResults_FallsBackToTheOpenStreetMapFieldsWhenGeoApifyHasNotLiftedThem()
    {
        // Some places carry the detail only in the raw OpenStreetMap record GeoApify passes
        // through. Reading it is free, and the alternative is showing nothing for a pharmacy whose
        // number is sitting right there in the response.
        const string json = """
        {
          "features": [
            {
              "properties": {
                "name": "Corner Pharmacy",
                "lat": -26.2050,
                "lon": 28.0480,
                "datasource": {
                  "raw": { "phone": "+27 115550000", "opening_hours": "Mo-Sa 08:00-17:00" }
                }
              }
            }
          ]
        }
        """;

        var results = GeoApifyClient.ParseResults(json, -26.2041, 28.0473);

        results[0].Phone.Should().Be("+27 115550000");
        results[0].OpeningHours.Should().Be("Mo-Sa 08:00-17:00");
    }

    [Fact]
    public void ParseResults_PrefersTheTopLevelFieldOverTheNestedOne()
    {
        // When GeoApify has lifted a field to the top level it has already normalised it, so that
        // copy wins over the raw OpenStreetMap text it came from.
        const string json = """
        {
          "features": [
            {
              "properties": {
                "name": "Both Pharmacy",
                "lat": -26.2050,
                "lon": 28.0480,
                "phone": "+27 110000001",
                "contact": { "phone": "+27 110000002" },
                "datasource": { "raw": { "phone": "+27 110000003" } }
              }
            }
          ]
        }
        """;

        GeoApifyClient.ParseResults(json, -26.2041, 28.0473)[0].Phone.Should().Be("+27 110000001");
    }

    [Fact]
    public void ParseResults_DoesNotThrowWhenANestedFieldIsNotAnObject()
    {
        // Defensive rather than hypothetical: the upstream shape is not ours to control, and a
        // parser that throws on an unexpected type turns one odd pharmacy into a failed search.
        const string json = """
        {
          "features": [
            {
              "properties": {
                "name": "Odd Pharmacy",
                "lat": -26.2050,
                "lon": 28.0480,
                "contact": "not an object"
              }
            }
          ]
        }
        """;

        var results = GeoApifyClient.ParseResults(json, -26.2041, 28.0473);

        results.Should().ContainSingle();
        results[0].Phone.Should().BeNull();
    }

    // ---- Pharmacy cache key ----

    [Fact]
    public void BuildGridCellKey_GroupsNearbyCoordinatesIntoOneCell()
    {
        // Two caregivers a few hundred metres apart should share a cache entry: that is the whole
        // reason the cache lives on the server rather than on each device.
        var a = PharmacyService.BuildGridCellKey(-26.2041, 28.0473, 5000);
        var b = PharmacyService.BuildGridCellKey(-26.2049, 28.0478, 5000);

        a.Should().Be(b);
    }

    [Fact]
    public void BuildGridCellKey_SeparatesDifferentRadii()
    {
        // A 2 km search and a 20 km search from the same spot are different questions and must
        // not share an answer.
        var near = PharmacyService.BuildGridCellKey(-26.2041, 28.0473, 2000);
        var far = PharmacyService.BuildGridCellKey(-26.2041, 28.0473, 20000);

        near.Should().NotBe(far);
    }

    [Fact]
    public void BuildGridCellKey_UsesInvariantFormattingForTheDecimalPoint()
    {
        // On a machine with a comma decimal separator, culture-sensitive formatting would produce
        // a different key for the same place and quietly defeat the cache.
        var key = PharmacyService.BuildGridCellKey(-26.2041, 28.0473, 5000);

        key.Should().Be("-26.20:28.05:5000");
    }

    // ---- Shared mapping helpers ----

    [Theory]
    [InlineData("2014-03-12", "2026-04-15", 12)]  // birthday already passed this year
    [InlineData("2014-06-12", "2026-04-15", 11)]  // birthday still to come
    [InlineData("2014-04-15", "2026-04-15", 12)]  // birthday is today
    public void CalculateAge_CountsCompletedYears(string birth, string today, int expected)
    {
        EntityMappings.CalculateAge(DateOnly.Parse(birth), DateOnly.Parse(today))
            .Should().Be(expected);
    }

    [Fact]
    public void CalculateAge_ReturnsZeroForADateOfBirthInTheFuture()
    {
        // Defensive: the service rejects this on input, but a negative age would be nonsense on
        // any screen that rendered it.
        EntityMappings.CalculateAge(new DateOnly(2027, 1, 1), new DateOnly(2026, 4, 15))
            .Should().Be(0);
    }

    [Fact]
    public void DocumentMapping_NeverExposesTheInternalStoragePath()
    {
        var document = new Document
        {
            Title = "Prescription",
            DocumentType = "prescription",
            FileName = "script.jpg",
            ContentType = "image/jpeg",
            FileSizeBytes = 2048,
            BlobUrl = "internal-storage-path-abc123.jpg"
        };

        var response = document.ToResponse("https://example.com/signed-link", FixedNow.AddMinutes(15));

        // Section 6.7 requires that downloads go through a signed URL and never the raw path.
        response.DownloadUrl.Should().Be("https://example.com/signed-link");
        response.Should().NotBeOfType<Document>();
        typeof(TechiesHealthTracker.Api.Contracts.Responses.DocumentResponse)
            .GetProperty("BlobUrl").Should().BeNull();
    }
}
