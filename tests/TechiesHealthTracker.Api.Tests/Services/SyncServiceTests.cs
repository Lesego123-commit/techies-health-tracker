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
using TechiesHealthTracker.Api.Contracts.Responses;
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
/// Tests for offline synchronisation (Requirement 5).
///
/// WorkManager replays a batch whenever it cannot confirm the last attempt landed, so the
/// properties proven here are the ones that make that safe: replaying is harmless, one bad entry
/// cannot block the rest, and a genuine clash between two devices resolves the same way whichever
/// order the entries happen to arrive in.
/// </summary>
public class SyncServiceTests : IAsyncLifetime
{
    /// <summary>Wednesday 15 April 2026, 18:00 UTC, which is 20:00 in Johannesburg.</summary>
    private static readonly DateTime FixedNow = new(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc);

    /// <summary>The 08:00 local slot on 15 April, i.e. 06:00 UTC.</summary>
    private static readonly DateTime MorningSlot = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

    private readonly AppDbContext _context = TestDatabase.CreateContext();
    private readonly FakeCurrentUserService _currentUser = new();
    private readonly TestClock _clock = new(FixedNow);
    private TestDatabase.SeededData _data = null!;
    private SyncService _service = null!;

    public async Task InitializeAsync()
    {
        _data = await TestDatabase.SeedAsync(_context, FixedNow);
        _currentUser.SignInAs(_data.Caregiver);

        var unitOfWork = new UnitOfWork(_context);

        var scheduleService = new DoseScheduleService(
            unitOfWork,
            Mock.Of<IAccessGuard>(),
            _clock,
            Options.Create(new SchedulingOptions()),
            NullLogger<DoseScheduleService>.Instance);

        _service = new SyncService(
            _context,
            unitOfWork,
            _currentUser,
            scheduleService,
            _clock,
            NullLogger<SyncService>.Instance);
    }

    public Task DisposeAsync()
    {
        _context.Dispose();
        return Task.CompletedTask;
    }

    private SyncDoseLogItem BuildItem(
        string localId = "room-1",
        string status = DoseLogStatuses.Taken,
        DateTime? loggedAt = null,
        DateTime? scheduledAt = null,
        Guid? scheduleId = null,
        Guid? dependantId = null,
        Guid? medicationId = null) => new()
    {
        LocalId = localId,
        ScheduleId = scheduleId ?? _data.Schedule.ScheduleId,
        DependantId = dependantId ?? _data.Dependant.DependantId,
        MedicationId = medicationId ?? _data.Medication.MedicationId,
        Status = status,
        LoggedAt = loggedAt ?? MorningSlot.AddMinutes(5),
        ScheduledAt = scheduledAt ?? MorningSlot
    };

    private Task<SyncBatchResponse> SyncAsync(params SyncDoseLogItem[] items)
        => _service.SyncDoseLogsAsync(new SyncBatchRequest { DoseLogs = items.ToList() });

    // ---- The happy path ----

    [Fact]
    public async Task SyncDoseLogsAsync_WritesAQueuedEntryAndDecrementsStock()
    {
        var response = await SyncAsync(BuildItem());

        response.SyncedCount.Should().Be(1);
        response.ConflictCount.Should().Be(0);
        response.Results.Single().Status.Should().Be(SyncItemStatuses.Synced);
        response.Results.Single().ServerId.Should().NotBeNull();

        var log = await _context.DoseLogs.SingleAsync();
        log.Status.Should().Be(DoseLogStatuses.Taken);
        log.ScheduledAt.Should().Be(MorningSlot);

        // The entry has reached the server, so it is settled from the server's point of view.
        log.PendingSync.Should().BeFalse();

        var medication = await _context.Medications.SingleAsync();
        medication.RemainingQuantity.Should().Be(27);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_EchoesTheLocalIdBackUntouched()
    {
        // The client matches results to its own RoomDB rows by this value, so it has to survive
        // the round trip exactly as sent.
        var response = await SyncAsync(BuildItem(localId: "room-row-42"));

        response.Results.Single().LocalId.Should().Be("room-row-42");
    }

    [Fact]
    public async Task SyncDoseLogsAsync_DoesNotDecrementStockForAMissedDose()
    {
        await SyncAsync(BuildItem(status: DoseLogStatuses.Missed));

        // Only a dose actually taken consumes stock. Counting missed doses would make the refill
        // forecast track the prescription rather than real consumption.
        var medication = await _context.Medications.SingleAsync();
        medication.RemainingQuantity.Should().Be(28);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_ResolvesTheSlotWhenTheClientOmitsIt()
    {
        // The Android client can post a queued entry carrying only a schedule id and the time the
        // user tapped; the server works out which slot that belongs to.
        var response = await SyncAsync(BuildItem(scheduledAt: null, loggedAt: MorningSlot.AddMinutes(20)));

        response.Results.Single().Status.Should().Be(SyncItemStatuses.Synced);

        var log = await _context.DoseLogs.SingleAsync();
        log.ScheduledAt.Should().Be(MorningSlot);
    }

    // ---- Replay safety ----

    [Fact]
    public async Task SyncDoseLogsAsync_ReportsAnIdenticalReplayAsADuplicate()
    {
        await SyncAsync(BuildItem());
        var replay = await SyncAsync(BuildItem());

        replay.Results.Single().Status.Should().Be(SyncItemStatuses.Duplicate);

        // The crucial assertion: a replayed batch must not write a second row or take another
        // capsule off the count.
        (await _context.DoseLogs.CountAsync()).Should().Be(1);
        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(27);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_CountsADuplicateAsSyncedSoTheClientClearsIt()
    {
        await SyncAsync(BuildItem());
        var replay = await SyncAsync(BuildItem());

        // A duplicate means "the server already has this", so the client should stop retrying it.
        replay.SyncedCount.Should().Be(1);
        replay.ConflictCount.Should().Be(0);
    }

    // ---- Conflict resolution: last write wins ----

    [Fact]
    public async Task SyncDoseLogsAsync_LetsANewerEntryOverwriteAnOlderOne()
    {
        // The caregiver first marked the dose missed, then corrected it to taken ten minutes later
        // on another device. The correction is what they meant, so it wins.
        await SyncAsync(BuildItem(status: DoseLogStatuses.Missed, loggedAt: MorningSlot));

        var response = await SyncAsync(
            BuildItem(localId: "room-2", status: DoseLogStatuses.Taken, loggedAt: MorningSlot.AddMinutes(10)));

        response.Results.Single().Status.Should().Be(SyncItemStatuses.Synced);

        var log = await _context.DoseLogs.SingleAsync();
        log.Status.Should().Be(DoseLogStatuses.Taken);

        // The client stores this value and sends it back on the next sync, so it has to advance.
        log.RowVersion.Should().BeGreaterThan(1);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_KeepsTheServerEntryWhenTheOfflineOneIsOlder()
    {
        await SyncAsync(BuildItem(status: DoseLogStatuses.Taken, loggedAt: MorningSlot.AddMinutes(30)));

        // A phone that was offline for hours may deliver a stale entry long after a newer one
        // already landed from another device. Arrival order must not decide the outcome.
        var response = await SyncAsync(
            BuildItem(localId: "room-stale", status: DoseLogStatuses.Missed, loggedAt: MorningSlot));

        response.ConflictCount.Should().Be(1);
        response.Results.Single().Status.Should().Be(SyncItemStatuses.Conflict);
        response.Results.Single().Message.Should().Contain("newer entry");

        (await _context.DoseLogs.SingleAsync()).Status.Should().Be(DoseLogStatuses.Taken);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_SettlesOnTheLatestEntryRegardlessOfBatchOrder()
    {
        // Both entries arrive in one batch, newest first. The service orders by LoggedAt before
        // processing, so the outcome is the same as if they had arrived chronologically.
        var response = await SyncAsync(
            BuildItem(localId: "newer", status: DoseLogStatuses.Taken, loggedAt: MorningSlot.AddMinutes(30)),
            BuildItem(localId: "older", status: DoseLogStatuses.Missed, loggedAt: MorningSlot));

        (await _context.DoseLogs.SingleAsync()).Status.Should().Be(DoseLogStatuses.Taken);
        response.Results.Should().HaveCount(2);

        // Two entries for one slot must leave exactly one row behind. A second row here would be
        // rejected by the unique index on (ScheduleId, ScheduledAt) once this reaches PostgreSQL,
        // failing the whole batch rather than just the one item.
        (await _context.DoseLogs.CountAsync()).Should().Be(1);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_DeductsStockWhenACorrectionTurnsAMissedDoseIntoATakenOne()
    {
        // The dose was first recorded as missed, so no capsule was deducted. Once the caregiver
        // corrects it, the stock count has to catch up or the refill forecast stays permanently
        // optimistic.
        await SyncAsync(
            BuildItem(localId: "newer", status: DoseLogStatuses.Taken, loggedAt: MorningSlot.AddMinutes(30)),
            BuildItem(localId: "older", status: DoseLogStatuses.Missed, loggedAt: MorningSlot));

        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(27);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_ReturnsStockWhenACorrectionTurnsATakenDoseIntoAMissedOne()
    {
        await SyncAsync(BuildItem(status: DoseLogStatuses.Taken, loggedAt: MorningSlot));
        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(27);

        await SyncAsync(BuildItem(
            localId: "correction", status: DoseLogStatuses.Missed, loggedAt: MorningSlot.AddMinutes(15)));

        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(28);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_NeverReturnsStockAboveTheQuantityPrescribed()
    {
        // Defensive: repeated corrections must not inflate the count past the size of the pack.
        _data.Medication.RemainingQuantity = _data.Medication.TotalQuantity;
        await _context.SaveChangesAsync();

        _context.DoseLogs.Add(new DoseLog
        {
            ScheduleId = _data.Schedule.ScheduleId,
            DependantId = _data.Dependant.DependantId,
            MedicationId = _data.Medication.MedicationId,
            Status = DoseLogStatuses.Taken,
            ScheduledAt = MorningSlot,
            LoggedAt = MorningSlot,
            RowVersion = 1
        });

        await _context.SaveChangesAsync();

        await SyncAsync(BuildItem(status: DoseLogStatuses.Missed, loggedAt: MorningSlot.AddMinutes(15)));

        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(28);
    }

    // ---- Rejection of bad entries ----

    [Fact]
    public async Task SyncDoseLogsAsync_RejectsAnEntryForAnotherCaregiversSchedule()
    {
        // The batch endpoint takes ids straight from the request body, so ownership is re-checked
        // here rather than assumed from the fact that the caller is authenticated.
        _currentUser.SignInAs(_data.OtherCaregiver);

        var response = await SyncAsync(BuildItem());

        response.RejectedCount.Should().Be(1);
        response.Results.Single().Status.Should().Be(SyncItemStatuses.Rejected);
        response.Results.Single().Message.Should().Contain("another caregiver");

        (await _context.DoseLogs.CountAsync()).Should().Be(0);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_RejectsAnUnknownSchedule()
    {
        var response = await SyncAsync(BuildItem(scheduleId: Guid.NewGuid()));

        response.RejectedCount.Should().Be(1);
        response.Results.Single().Message.Should().Contain("no longer exists");
    }

    [Fact]
    public async Task SyncDoseLogsAsync_RejectsAnEntryWhoseMedicationDoesNotMatchItsSchedule()
    {
        var response = await SyncAsync(BuildItem(medicationId: Guid.NewGuid()));

        response.RejectedCount.Should().Be(1);
        response.Results.Single().Message.Should().Contain("does not match");
    }

    [Fact]
    public async Task SyncDoseLogsAsync_RejectsAnInvalidStatus()
    {
        var response = await SyncAsync(BuildItem(status: "maybe"));

        response.RejectedCount.Should().Be(1);
        response.Results.Single().Message.Should().Contain("not valid");
    }

    [Fact]
    public async Task SyncDoseLogsAsync_RejectsAnEntryDatedInTheFuture()
    {
        var response = await SyncAsync(BuildItem(loggedAt: FixedNow.AddHours(2)));

        response.RejectedCount.Should().Be(1);
        response.Results.Single().Message.Should().Contain("future");
    }

    [Fact]
    public async Task SyncDoseLogsAsync_LetsGoodEntriesThroughAlongsideABadOne()
    {
        // This is the property that keeps a caregiver from being stuck forever: one corrupt row
        // in the queue must not stop everything behind it from syncing.
        var eveningSlot = new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc);

        var response = await SyncAsync(
            BuildItem(localId: "bad", status: "nonsense"),
            BuildItem(localId: "good", scheduledAt: eveningSlot, loggedAt: eveningSlot.AddMinutes(-5)));

        response.SyncedCount.Should().Be(1);
        response.RejectedCount.Should().Be(1);

        response.Results.Single(r => r.LocalId == "good").Status.Should().Be(SyncItemStatuses.Synced);
        response.Results.Single(r => r.LocalId == "bad").Status.Should().Be(SyncItemStatuses.Rejected);

        (await _context.DoseLogs.CountAsync()).Should().Be(1);
    }

    [Fact]
    public async Task SyncDoseLogsAsync_RefusesAnOversizedBatch()
    {
        var items = Enumerable.Range(0, 501)
            .Select(i => BuildItem(localId: $"room-{i}"))
            .ToList();

        var act = () => _service.SyncDoseLogsAsync(new SyncBatchRequest { DoseLogs = items });

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*at most 500*");
    }

    // ---- Side effects ----

    [Fact]
    public async Task SyncDoseLogsAsync_RecalculatesTheNextDoseOnce()
    {
        var eveningSlot = new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc);

        await SyncAsync(
            BuildItem(localId: "morning"),
            BuildItem(localId: "evening", scheduledAt: eveningSlot, loggedAt: eveningSlot.AddMinutes(-5)));

        // Both doses for 15 April are in, so the next one is 08:00 local on the 16th (06:00 UTC).
        var schedule = await _context.DoseSchedules.SingleAsync();
        schedule.NextDoseAt.Should().Be(new DateTime(2026, 4, 16, 6, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public async Task SyncDoseLogsAsync_NeverDrivesStockBelowZero()
    {
        _data.Medication.RemainingQuantity = 0;
        await _context.SaveChangesAsync();

        await SyncAsync(BuildItem());

        // The dose is still recorded — it genuinely happened — but a negative stock count would
        // corrupt every forecast built on it.
        (await _context.Medications.SingleAsync()).RemainingQuantity.Should().Be(0);
        (await _context.DoseLogs.CountAsync()).Should().Be(1);
    }
}
