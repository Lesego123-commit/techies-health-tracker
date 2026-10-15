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
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
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
/// Tests for adherence analytics (Additional Feature 3).
///
/// The property these tests exist to protect is that doses are counted from the schedule, not
/// from the dose-log table. A dose the caregiver never responded to leaves no row behind, so
/// counting rows would report perfect adherence for someone taking nothing at all — the single
/// most misleading thing this API could tell a caregiver.
/// </summary>
public class AdherenceServiceTests : IAsyncLifetime
{
    /// <summary>Friday 17 April 2026, 22:00 UTC — midnight local, so the day is fully complete.</summary>
    private static readonly DateTime FixedNow = new(2026, 4, 17, 22, 0, 0, DateTimeKind.Utc);

    private readonly AppDbContext _context = TestDatabase.CreateContext();
    private readonly TestClock _clock = new(FixedNow);
    private TestDatabase.SeededData _data = null!;
    private AdherenceService _service = null!;
    private DoseScheduleService _scheduleService = null!;

    public async Task InitializeAsync()
    {
        _data = await TestDatabase.SeedAsync(_context, FixedNow);

        _scheduleService = new DoseScheduleService(
            new UnitOfWork(_context),
            Mock.Of<IAccessGuard>(),
            _clock,
            Options.Create(new SchedulingOptions()),
            NullLogger<DoseScheduleService>.Instance);

        // The guard is stubbed to allow access: ownership is covered exhaustively in
        // AccessGuardTests, and repeating it here would only obscure the arithmetic under test.
        var guard = new Mock<IAccessGuard>();
        guard.Setup(g => g.EnsureDependantAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
            .ReturnsAsync(_data.Dependant);

        _service = new AdherenceService(
            _context, guard.Object, _scheduleService, _clock, NullLogger<AdherenceService>.Instance);
    }

    public Task DisposeAsync()
    {
        _context.Dispose();
        return Task.CompletedTask;
    }

    /// <summary>Records a dose as taken against a specific slot.</summary>
    private async Task LogTakenAsync(DateTime scheduledAtUtc)
    {
        _context.DoseLogs.Add(new DoseLog
        {
            ScheduleId = _data.Schedule.ScheduleId,
            DependantId = _data.Dependant.DependantId,
            MedicationId = _data.Medication.MedicationId,
            Status = DoseLogStatuses.Taken,
            ScheduledAt = scheduledAtUtc,
            LoggedAt = scheduledAtUtc.AddMinutes(2),
            RowVersion = 1
        });

        await _context.SaveChangesAsync();
    }

    /// <summary>The 08:00 and 20:00 local slots for a given date, as UTC instants.</summary>
    private static (DateTime Morning, DateTime Evening) SlotsFor(int day)
        => (new DateTime(2026, 4, day, 6, 0, 0, DateTimeKind.Utc),
            new DateTime(2026, 4, day, 18, 0, 0, DateTimeKind.Utc));

    [Fact]
    public async Task CalculateAsync_CountsAnUnansweredDoseAsMissed()
    {
        var from = new DateOnly(2026, 4, 16);
        var to = new DateOnly(2026, 4, 16);

        var (morning, _) = SlotsFor(16);
        await LogTakenAsync(morning);

        var result = await _service.CalculateAsync(_data.Dependant.DependantId, from, to);

        // Two doses were due that day and only one was answered. The evening dose left no row at
        // all, and it must still be counted against the caregiver.
        result.TotalScheduledDoses.Should().Be(2);
        result.DosesTaken.Should().Be(1);
        result.DosesMissed.Should().Be(1);
        result.AdherencePercentage.Should().Be(50.0);
    }

    [Fact]
    public async Task CalculateAsync_ReportsFullAdherenceWhenEveryDoseWasTaken()
    {
        var (morning, evening) = SlotsFor(16);
        await LogTakenAsync(morning);
        await LogTakenAsync(evening);

        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 16), new DateOnly(2026, 4, 16));

        result.DosesTaken.Should().Be(2);
        result.DosesMissed.Should().Be(0);
        result.AdherencePercentage.Should().Be(100.0);
    }

    [Fact]
    public async Task CalculateAsync_ReportsZeroWhenNothingWasAnswered()
    {
        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 16), new DateOnly(2026, 4, 16));

        result.TotalScheduledDoses.Should().Be(2);
        result.DosesTaken.Should().Be(0);
        result.AdherencePercentage.Should().Be(0);
    }

    [Fact]
    public async Task CalculateAsync_DoesNotCountDosesThatAreNotDueYet()
    {
        // The clock is at midnight local on the 18th, so the 18th has nothing due yet. Counting
        // the day ahead would show every caregiver as behind for the whole of each day.
        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 18), new DateOnly(2026, 4, 18));

        result.TotalScheduledDoses.Should().Be(0);
        result.DosesMissed.Should().Be(0);
    }

    [Fact]
    public async Task CalculateAsync_ReturnsAPointForEveryDayIncludingEmptyOnes()
    {
        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 14), new DateOnly(2026, 4, 17));

        // The bar chart on the Trends screen needs an unbroken x-axis, so days with nothing
        // scheduled still get a point rather than being skipped.
        result.Trends.Should().HaveCount(4);
        result.Trends.Select(t => t.Date).Should().BeInAscendingOrder();
    }

    [Fact]
    public async Task CalculateAsync_DefaultsToTheLastThirtyDays()
    {
        var result = await _service.CalculateAsync(_data.Dependant.DependantId);

        result.ToDate.Should().Be(new DateOnly(2026, 4, 17));
        result.FromDate.Should().Be(new DateOnly(2026, 3, 19));
        result.Trends.Should().HaveCount(30);
    }

    [Fact]
    public async Task CalculateAsync_CountsTheStreakOfDaysWithNoMissedDose()
    {
        // Every dose on the 16th and 17th was taken, so the streak is two days.
        foreach (var day in new[] { 16, 17 })
        {
            var (morning, evening) = SlotsFor(day);
            await LogTakenAsync(morning);
            await LogTakenAsync(evening);
        }

        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 15), new DateOnly(2026, 4, 17));

        result.CurrentStreakDays.Should().Be(2);
    }

    [Fact]
    public async Task CalculateAsync_BreaksTheStreakOnAMissedDose()
    {
        var (morning17, _) = SlotsFor(17);
        await LogTakenAsync(morning17);

        // The evening dose on the 17th was never answered, so there is no clean day to count.
        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 15), new DateOnly(2026, 4, 17));

        result.CurrentStreakDays.Should().Be(0);
    }

    [Fact]
    public async Task CalculateAsync_NamesThePartOfDayThatIsMissedMostOften()
    {
        // Three mornings taken, three evenings left unanswered: a clear and genuine pattern.
        foreach (var day in new[] { 15, 16, 17 })
        {
            var (morning, _) = SlotsFor(day);
            await LogTakenAsync(morning);
        }

        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 15), new DateOnly(2026, 4, 17));

        result.PatternInsight.Should().NotBeNull();

        // 20:00 local is 18:00 UTC, which the service describes as an evening dose.
        result.PatternInsight.Should().Contain("Evening");
    }

    [Fact]
    public async Task CalculateAsync_SaysNothingWhenThereIsTooLittleHistoryToJudge()
    {
        // One or two misses is a forgetful evening, not a pattern. Claiming otherwise would be a
        // confident-sounding insight drawn from noise.
        var (morning, _) = SlotsFor(17);
        await LogTakenAsync(morning);

        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 17), new DateOnly(2026, 4, 17));

        result.PatternInsight.Should().BeNull();
    }

    [Fact]
    public async Task CalculateAsync_StillCountsDosesFromAScheduleThatHasSinceBeenRetired()
    {
        // A course that has ended still produced real doses while it was running. Dropping them
        // would rewrite the caregiver's history every time a prescription finished.
        _data.Schedule.IsActive = false;
        await _context.SaveChangesAsync();

        var result = await _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 16), new DateOnly(2026, 4, 16));

        result.TotalScheduledDoses.Should().Be(2);
    }

    [Fact]
    public async Task CalculateAsync_RejectsAnInvertedDateRange()
    {
        var act = () => _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 17), new DateOnly(2026, 4, 10));

        (await act.Should().ThrowAsync<BusinessRuleException>())
            .WithMessage("*cannot fall after its end*");
    }

    [Fact]
    public async Task CalculateAsync_RejectsARangeLongerThanAYear()
    {
        var act = () => _service.CalculateAsync(
            _data.Dependant.DependantId, new DateOnly(2020, 1, 1), new DateOnly(2026, 4, 17));

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*at most 365 days*");
    }
}
