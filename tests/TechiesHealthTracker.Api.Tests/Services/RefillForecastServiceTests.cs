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
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Implementations;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Moq;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for the low-stock refill prediction (Additional Feature 4).
///
/// The arithmetic matters more here than in most places: a forecast that over-states how much
/// stock is left is the failure that actually reaches a patient, because the caregiver is told
/// they have time to spare when they do not.
/// </summary>
public class RefillForecastServiceTests : IDisposable
{
    private static readonly DateTime FixedNow = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);
    private static readonly DateOnly Today = new(2026, 4, 15);

    private readonly AppDbContext _context = TestDatabase.CreateContext();
    private readonly TestClock _clock = new(FixedNow);

    private RefillForecastService CreateService()
    {
        var scheduleService = new DoseScheduleService(
            Mock.Of<TechiesHealthTracker.Api.Data.Repositories.IUnitOfWork>(),
            Mock.Of<IAccessGuard>(),
            _clock,
            Options.Create(new SchedulingOptions()),
            NullLogger<DoseScheduleService>.Instance);

        return new RefillForecastService(
            _context,
            Mock.Of<IAccessGuard>(),
            scheduleService,
            new FakeCurrentUserService(),
            _clock,
            NullLogger<RefillForecastService>.Instance);
    }

    private static Medication BuildMedication(int remaining, string name = "Amoxicillin") => new()
    {
        Name = name,
        DosageAmount = "500",
        DosageUnit = "mg",
        Form = "capsule",
        TotalQuantity = 100,
        RemainingQuantity = remaining,
        StartDate = new DateOnly(2026, 4, 1),
        IsActive = true
    };

    private static DoseSchedule BuildSchedule(
        List<string>? times = null, List<string>? daysOfWeek = null, bool isActive = true) => new()
    {
        Frequency = ScheduleFrequencies.TwiceDaily,
        Times = times ?? new List<string> { "08:00", "20:00" },
        DaysOfWeek = daysOfWeek ?? new List<string>(),
        StartDate = new DateOnly(2026, 4, 1),
        IsActive = isActive
    };

    [Fact]
    public void Forecast_DividesStockByTheDailyConsumptionRate()
    {
        var service = CreateService();

        // 20 capsules at two a day is ten days of supply.
        var result = service.Forecast(BuildMedication(20), new[] { BuildSchedule() }, lowStockThresholdDays: 7);

        result.DailyConsumptionRate.Should().Be(2.0);
        result.DaysUntilRunOut.Should().Be(10);
        result.EstimatedRunOutDate.Should().Be(Today.AddDays(10));
        result.RefillAlertTriggered.Should().BeFalse();
        result.Severity.Should().Be("ok");
    }

    [Fact]
    public void Forecast_RaisesTheAlertOnceStockFallsInsideTheThreshold()
    {
        var service = CreateService();

        // 12 capsules at two a day is six days, which is inside the seven-day threshold.
        var result = service.Forecast(BuildMedication(12), new[] { BuildSchedule() }, lowStockThresholdDays: 7);

        result.DaysUntilRunOut.Should().Be(6);
        result.RefillAlertTriggered.Should().BeTrue();
        result.Severity.Should().Be("low");
    }

    [Fact]
    public void Forecast_TreatsTheThresholdAsInclusive()
    {
        var service = CreateService();

        // Exactly seven days left against a seven-day threshold must alert. Being one day out
        // here is the difference between a warning and running out unannounced.
        var result = service.Forecast(BuildMedication(14), new[] { BuildSchedule() }, lowStockThresholdDays: 7);

        result.DaysUntilRunOut.Should().Be(7);
        result.RefillAlertTriggered.Should().BeTrue();
    }

    [Fact]
    public void Forecast_RoundsDownRatherThanToNearest()
    {
        var service = CreateService();

        // 21 capsules at two a day is ten and a half days. Half a day of stock is no day at all,
        // and rounding up would tell the caregiver they have supply they do not have.
        var result = service.Forecast(BuildMedication(21), new[] { BuildSchedule() }, lowStockThresholdDays: 7);

        result.DaysUntilRunOut.Should().Be(10);
    }

    [Fact]
    public void Forecast_ReportsDepletedWhenNothingIsLeft()
    {
        var service = CreateService();

        var result = service.Forecast(BuildMedication(0), new[] { BuildSchedule() }, lowStockThresholdDays: 7);

        result.RemainingQuantity.Should().Be(0);
        result.DaysUntilRunOut.Should().Be(0);
        result.RefillAlertTriggered.Should().BeTrue();
        result.Severity.Should().Be("depleted");
    }

    [Fact]
    public void Forecast_ReturnsNoRunOutDateWhenNothingIsBeingConsumed()
    {
        var service = CreateService();

        // With no active schedule there is no consumption rate, so a run-out date would be a
        // fabrication. Null is reported instead, and the client shows no countdown.
        var result = service.Forecast(BuildMedication(30), Array.Empty<DoseSchedule>(), lowStockThresholdDays: 7);

        result.DailyConsumptionRate.Should().Be(0);
        result.EstimatedRunOutDate.Should().BeNull();
        result.DaysUntilRunOut.Should().BeNull();
        result.RefillAlertTriggered.Should().BeFalse();
        result.Severity.Should().Be("ok");
    }

    [Fact]
    public void Forecast_StillFlagsDepletedStockWithNoActiveSchedule()
    {
        var service = CreateService();

        var result = service.Forecast(BuildMedication(0), Array.Empty<DoseSchedule>(), lowStockThresholdDays: 7);

        result.RefillAlertTriggered.Should().BeTrue();
        result.Severity.Should().Be("depleted");
    }

    [Fact]
    public void Forecast_SumsTheRateAcrossSeveralSchedules()
    {
        var service = CreateService();

        // A morning tablet and a separate evening tablet on the same medication consume three a
        // day between them.
        var schedules = new[]
        {
            BuildSchedule(times: new List<string> { "08:00", "14:00" }),
            BuildSchedule(times: new List<string> { "20:00" })
        };

        var result = service.Forecast(BuildMedication(30), schedules, lowStockThresholdDays: 7);

        result.DailyConsumptionRate.Should().Be(3.0);
        result.DaysUntilRunOut.Should().Be(10);
    }

    [Fact]
    public void Forecast_StretchesTheEstimateForAScheduleThatOnlyRunsOnCertainDays()
    {
        var service = CreateService();

        // Two doses on two days a week is four sevenths of a dose a day, so 20 tablets last
        // 35 days rather than the ten a daily schedule would give.
        var schedule = BuildSchedule(daysOfWeek: new List<string> { "monday", "thursday" });

        var result = service.Forecast(BuildMedication(20), new[] { schedule }, lowStockThresholdDays: 7);

        result.DaysUntilRunOut.Should().Be(35);
        result.RefillAlertTriggered.Should().BeFalse();
    }

    [Fact]
    public void Forecast_TreatsANegativeStockCountAsEmpty()
    {
        var service = CreateService();

        // Defensive: the column should never go negative, but a forecast built on a negative
        // count would produce a run-out date in the past and a nonsensical countdown.
        var result = service.Forecast(BuildMedication(-5), new[] { BuildSchedule() }, lowStockThresholdDays: 7);

        result.RemainingQuantity.Should().Be(0);
        result.Severity.Should().Be("depleted");
    }

    [Fact]
    public void Forecast_RespectsAThresholdTheUserHasChanged()
    {
        var service = CreateService();

        // Same stock, different settings: a caregiver who set a 30-day threshold is warned where
        // one on the 7-day default is not. The threshold is echoed back for the Refill screen.
        var medication = BuildMedication(40);
        var schedules = new[] { BuildSchedule() };

        service.Forecast(medication, schedules, lowStockThresholdDays: 7)
            .RefillAlertTriggered.Should().BeFalse();

        var generous = service.Forecast(medication, schedules, lowStockThresholdDays: 30);

        generous.RefillAlertTriggered.Should().BeTrue();
        generous.LowStockThresholdDays.Should().Be(30);
    }

    public void Dispose() => _context.Dispose();
}
