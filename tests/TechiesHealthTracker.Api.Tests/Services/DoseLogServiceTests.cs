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
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Services;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Implementations;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Moq;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for logging a dose — the widest-reaching write in the API. One tap records the log,
/// decrements the stock, advances the schedule and can raise a refill alert, so these tests check
/// that the whole chain moves together rather than just that a row appeared.
/// </summary>
public class DoseLogServiceTests : IAsyncLifetime
{
    /// <summary>15 April 2026, 06:05 UTC — five minutes after the 08:00 local dose.</summary>
    private static readonly DateTime FixedNow = new(2026, 4, 15, 6, 5, 0, DateTimeKind.Utc);
    private static readonly DateTime MorningSlot = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

    private readonly AppDbContext _context = TestDatabase.CreateContext();
    private readonly FakeCurrentUserService _currentUser = new();
    private readonly TestClock _clock = new(FixedNow);
    private TestDatabase.SeededData _data = null!;
    private DoseLogService _service = null!;

    public async Task InitializeAsync()
    {
        _data = await TestDatabase.SeedAsync(_context, FixedNow);
        _currentUser.SignInAs(_data.Caregiver);

        var unitOfWork = new UnitOfWork(_context);

        var guard = new Mock<IAccessGuard>();

        guard.Setup(g => g.EnsureScheduleAsync(_data.Schedule.ScheduleId, It.IsAny<CancellationToken>()))
            .ReturnsAsync(() => _context.DoseSchedules
                .Include(s => s.Medication)
                .First(s => s.ScheduleId == _data.Schedule.ScheduleId));

        guard.Setup(g => g.EnsureMedicationAsync(_data.Medication.MedicationId, It.IsAny<CancellationToken>()))
            .ReturnsAsync(() => _context.Medications.First(m => m.MedicationId == _data.Medication.MedicationId));

        guard.Setup(g => g.EnsureDependantAsync(_data.Dependant.DependantId, It.IsAny<CancellationToken>()))
            .ReturnsAsync(_data.Dependant);

        var scheduleService = new DoseScheduleService(
            unitOfWork, guard.Object, _clock,
            Options.Create(new SchedulingOptions()), NullLogger<DoseScheduleService>.Instance);

        var adherenceService = new AdherenceService(
            _context, guard.Object, scheduleService, _clock, NullLogger<AdherenceService>.Instance);

        var refillService = new RefillForecastService(
            _context, guard.Object, scheduleService, _currentUser, _clock,
            NullLogger<RefillForecastService>.Instance);

        var notificationService = new NotificationService(
            _context, unitOfWork, _clock, NullLogger<NotificationService>.Instance);

        _service = new DoseLogService(
            _context, unitOfWork, guard.Object, scheduleService, adherenceService,
            refillService, notificationService, _currentUser, _clock,
            NullLogger<DoseLogService>.Instance);
    }

    public Task DisposeAsync()
    {
        _context.Dispose();
        return Task.CompletedTask;
    }

    private CreateDoseLogRequest BuildRequest(
        string status = DoseLogStatuses.Taken,
        DateTime? loggedAt = null,
        DateTime? scheduledAt = null,
        Guid? medicationId = null,
        Guid? dependantId = null) => new()
    {
        ScheduleId = _data.Schedule.ScheduleId,
        DependantId = dependantId ?? _data.Dependant.DependantId,
        MedicationId = medicationId ?? _data.Medication.MedicationId,
        Status = status,
        LoggedAt = loggedAt,
        ScheduledAt = scheduledAt
    };

    [Fact]
    public async Task CreateAsync_RecordsTheDoseAndDeductsOneUnit()
    {
        var result = await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        result.DoseLog.Status.Should().Be(DoseLogStatuses.Taken);
        result.RemainingQuantity.Should().Be(27);
        result.DoseLog.MedicationName.Should().Be("Amoxicillin");

        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(27);
    }

    [Fact]
    public async Task CreateAsync_AdvancesTheScheduleToTheNextDose()
    {
        var result = await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        // The morning dose is done, so the next one is 20:00 local, i.e. 18:00 UTC.
        result.NextDoseAt.Should().Be(new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc));
        (await _context.DoseSchedules.SingleAsync()).NextDoseAt.Should().Be(result.NextDoseAt);
    }

    [Fact]
    public async Task CreateAsync_ReturnsARefreshedAdherenceSummary()
    {
        // The Home screen updates its streak card straight from this, so it has to come back on
        // the same response rather than needing a second call.
        var result = await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        result.AdherenceSummary.Should().NotBeNull();
        result.AdherenceSummary.DosesTaken.Should().Be(1);
    }

    [Fact]
    public async Task CreateAsync_LeavesStockAloneForAMissedDose()
    {
        await _service.CreateAsync(BuildRequest(status: DoseLogStatuses.Missed, scheduledAt: MorningSlot));

        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(28);
    }

    [Fact]
    public async Task CreateAsync_ResolvesTheSlotFromTheScheduleWhenNotSupplied()
    {
        // This is what lets the Android client post a dose log carrying nothing but a schedule id.
        var result = await _service.CreateAsync(BuildRequest());

        result.DoseLog.ScheduledAt.Should().Be(MorningSlot);
    }

    [Fact]
    public async Task CreateAsync_FilesAnAfternoonTapAgainstTheMorningSlotNotTonights()
    {
        // 12:05 UTC is 14:05 local: nearer to tonight's 20:00 dose than to this morning's 08:00.
        // Nearness is the wrong measure — tonight's dose has not happened yet, so a tap now can
        // only be this morning's, logged late.
        _clock.Set(new DateTime(2026, 4, 15, 12, 5, 0, DateTimeKind.Utc));

        var result = await _service.CreateAsync(BuildRequest());

        result.DoseLog.ScheduledAt.Should().Be(MorningSlot);

        // And because the slot has come due, it counts. Filing it forward scored it as nothing
        // while marking the morning down as missed.
        result.AdherenceSummary.DosesTaken.Should().Be(1);
    }

    [Fact]
    public async Task CreateAsync_AllowsADoseTakenShortlyBeforeItIsDue()
    {
        // 17:45 UTC is 19:45 local — fifteen minutes before the 20:00 dose. Someone on their way
        // out taking it early means tonight's slot, not this morning's.
        _clock.Set(new DateTime(2026, 4, 15, 17, 45, 0, DateTimeKind.Utc));

        var result = await _service.CreateAsync(BuildRequest());

        result.DoseLog.ScheduledAt.Should().Be(new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public async Task CreateAsync_FilesAJustAfterMidnightTapAgainstLastNightsDose()
    {
        // 00:30 UTC is 02:30 local. Someone logging a dose at that hour has taken last night's
        // 20:00, not this morning's 08:00, which is still five and a half hours away.
        _clock.Set(new DateTime(2026, 4, 15, 0, 30, 0, DateTimeKind.Utc));

        var result = await _service.CreateAsync(BuildRequest());

        result.DoseLog.ScheduledAt.Should().Be(new DateTime(2026, 4, 14, 18, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public async Task CreateAsync_AcceptsAnExplicitSlotStillInTheFuture()
    {
        // What the Android client sends when the caregiver marks tonight's dose taken early in
        // the day. It is recorded against the slot it anticipates and scores once that slot is due.
        var eveningSlot = new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc);

        var result = await _service.CreateAsync(BuildRequest(scheduledAt: eveningSlot));

        result.DoseLog.ScheduledAt.Should().Be(eveningSlot);
    }

    [Fact]
    public async Task CreateAsync_StoresEveryTimestampAsUtc()
    {
        // PostgreSQL rejects a DateTime whose Kind is Unspecified against `timestamp with time
        // zone`. The in-memory provider these tests run on does not, so Kind has to be asserted
        // explicitly or a value that only fails in production passes here.
        await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        var stored = await _context.DoseLogs.SingleAsync();

        stored.ScheduledAt.Kind.Should().Be(DateTimeKind.Utc);
        stored.LoggedAt.Kind.Should().Be(DateTimeKind.Utc);
    }

    [Fact]
    public async Task CreateAsync_RefusesASecondLogForTheSameSlot()
    {
        await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        // A double tap must not take two capsules off the count.
        var act = () => _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*already logged*");

        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(27);
    }

    [Fact]
    public async Task CreateAsync_RejectsAMedicationThatDoesNotMatchTheSchedule()
    {
        // The client sends all three ids, so a mismatch means the app has muddled two medications
        // and the log would land on the wrong dependant's history.
        var act = () => _service.CreateAsync(BuildRequest(medicationId: Guid.NewGuid()));

        (await act.Should().ThrowAsync<BusinessRuleException>())
            .WithMessage("*does not match the schedule*");
    }

    [Fact]
    public async Task CreateAsync_RejectsADependantThatDoesNotMatchTheMedication()
    {
        var act = () => _service.CreateAsync(BuildRequest(dependantId: Guid.NewGuid()));

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*does not match*");
    }

    [Fact]
    public async Task CreateAsync_RejectsAnInvalidStatus()
    {
        var act = () => _service.CreateAsync(BuildRequest(status: "skipped"));

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*not valid*");
    }

    [Fact]
    public async Task CreateAsync_RejectsATimestampInTheFuture()
    {
        // A handset with a badly skewed clock would otherwise corrupt the adherence figures.
        var act = () => _service.CreateAsync(
            BuildRequest(loggedAt: FixedNow.AddHours(3), scheduledAt: MorningSlot));

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*future*");
    }

    [Fact]
    public async Task CreateAsync_AllowsASmallAmountOfClockSkew()
    {
        // A few minutes of drift between the phone and the server is normal and must not be
        // treated as an error.
        var result = await _service.CreateAsync(
            BuildRequest(loggedAt: FixedNow.AddMinutes(5), scheduledAt: MorningSlot));

        result.DoseLog.Should().NotBeNull();
    }

    [Fact]
    public async Task CreateAsync_RejectsATimestampTooFarInThePast()
    {
        var act = () => _service.CreateAsync(
            BuildRequest(loggedAt: FixedNow.AddDays(-60), scheduledAt: MorningSlot));

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*30 days in the past*");
    }

    [Fact]
    public async Task CreateAsync_RaisesARefillAlertOnceStockRunsLow()
    {
        // Two capsules left against a twice-daily schedule is one day of supply, well inside the
        // caregiver's seven-day threshold.
        _data.Medication.RemainingQuantity = 2;
        await _context.SaveChangesAsync();

        var result = await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        result.RefillAlertTriggered.Should().BeTrue();

        var notification = await _context.NotificationEvents.SingleAsync();
        notification.Category.Should().Be(NotificationCategories.RefillAlert);
        notification.UserId.Should().Be(_data.Caregiver.UserId);
        notification.Body.Should().Contain("Amoxicillin");
    }

    [Fact]
    public async Task CreateAsync_DoesNotRaiseARefillAlertWhileStockIsHealthy()
    {
        var result = await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        result.RefillAlertTriggered.Should().BeFalse();
        (await _context.NotificationEvents.CountAsync()).Should().Be(0);
    }

    [Fact]
    public async Task CreateAsync_NeverDrivesStockBelowZero()
    {
        _data.Medication.RemainingQuantity = 0;
        await _context.SaveChangesAsync();

        // The dose genuinely happened, most likely because the caregiver refilled without telling
        // the app, so it is recorded. The count simply cannot go negative.
        var result = await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        result.RemainingQuantity.Should().Be(0);
        (await _context.DoseLogs.CountAsync()).Should().Be(1);
    }

    [Fact]
    public async Task GetHistoryAsync_ReturnsLogsNewestFirst()
    {
        await _service.CreateAsync(BuildRequest(scheduledAt: MorningSlot));

        _clock.Set(new DateTime(2026, 4, 15, 18, 5, 0, DateTimeKind.Utc));
        await _service.CreateAsync(
            BuildRequest(scheduledAt: new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc)));

        var history = await _service.GetHistoryAsync(_data.Dependant.DependantId);

        history.Should().HaveCount(2);
        history.Should().BeInDescendingOrder(h => h.ScheduledAt);
        history.First().MedicationName.Should().Be("Amoxicillin");
    }

    [Fact]
    public async Task GetHistoryAsync_RejectsAnInvertedDateRange()
    {
        var act = () => _service.GetHistoryAsync(
            _data.Dependant.DependantId, new DateOnly(2026, 4, 17), new DateOnly(2026, 4, 10));

        (await act.Should().ThrowAsync<BusinessRuleException>())
            .WithMessage("*cannot fall after its end*");
    }
}
