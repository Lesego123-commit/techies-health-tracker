// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Schedule Tasks with WorkManager. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/workmanager
//    [Accessed 16 September 2026]. Client half of this contract.
//   Microsoft. 2024. Handling Concurrency Conflicts — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/saving/concurrency [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.5 (Requirement 5: offline sync, last-write-wins).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Reconciles the dose logs a device captured while offline (Requirement 5, section 3.5).
///
/// Three rules make this safe to retry, which matters because WorkManager will replay a batch
/// whenever it is not certain the last attempt landed:
///   1. A slot that already holds an identical entry is reported as a duplicate, not written again.
///   2. A slot that already holds a different entry is a conflict, settled by last-write-wins on
///      the LoggedAt timestamp rather than on arrival order.
///   3. An item that fails validation is rejected individually; the rest of the batch still lands.
/// See <see cref="ISyncService"/> for the contract.
/// </summary>
public class SyncService : ISyncService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly ICurrentUserService _currentUser;
    private readonly IDoseScheduleService _scheduleService;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<SyncService> _logger;

    /// <summary>Upper bound on one batch, so a corrupt client queue cannot tie up the server.</summary>
    private const int MaximumBatchSize = 500;

    public SyncService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        ICurrentUserService currentUser,
        IDoseScheduleService scheduleService,
        IDateTimeProvider clock,
        ILogger<SyncService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _currentUser = currentUser;
        _scheduleService = scheduleService;
        _clock = clock;
        _logger = logger;
    }

    public async Task<SyncBatchResponse> SyncDoseLogsAsync(
        SyncBatchRequest request, CancellationToken cancellationToken = default)
    {
        if (request.DoseLogs.Count > MaximumBatchSize)
        {
            throw new BusinessRuleException(
                $"A sync batch may contain at most {MaximumBatchSize} dose logs. Split the queue and retry.");
        }

        var userId = await _currentUser.GetRequiredUserIdAsync(cancellationToken);
        var results = new List<SyncItemResult>(request.DoseLogs.Count);

        // The caller's schedules are loaded once up front rather than per item: a batch built up
        // over a morning offline is mostly the same few medications repeated.
        var scheduleIds = request.DoseLogs.Select(i => i.ScheduleId).Distinct().ToList();

        var schedules = await _context.DoseSchedules
            .Include(s => s.Medication)
            .ThenInclude(m => m.Dependant)
            .Where(s => scheduleIds.Contains(s.ScheduleId))
            .ToDictionaryAsync(s => s.ScheduleId, cancellationToken);

        var touchedSchedules = new HashSet<Guid>();

        // Rows added during this batch are not visible to a query until the single SaveChanges at
        // the end, so they are tracked here as well. Without this, two queued entries for the same
        // slot would both be written: the unique index on (ScheduleId, ScheduledAt) would then
        // reject the whole batch on commit, and a caregiver whose queue contained one such pair
        // could never sync anything again.
        var pendingBySlot = new Dictionary<(Guid ScheduleId, DateTime ScheduledAt), DoseLog>();

        // Items are processed oldest first so that last-write-wins genuinely reflects the order
        // the caregiver tapped, not the order the client happened to queue them in.
        foreach (var item in request.DoseLogs.OrderBy(i => i.LoggedAt))
        {
            var result = await ProcessItemAsync(
                item, userId, schedules, touchedSchedules, pendingBySlot, cancellationToken);

            results.Add(result);
        }

        // Every schedule that received a log has its next dose recalculated once at the end,
        // rather than after each of what may be dozens of entries for the same medication.
        foreach (var scheduleId in touchedSchedules)
        {
            await _scheduleService.RefreshNextDoseAsync(schedules[scheduleId], cancellationToken);
        }

        await _unitOfWork.SaveChangesAsync(cancellationToken);

        var response = new SyncBatchResponse
        {
            SyncedCount = results.Count(r => r.Status is SyncItemStatuses.Synced or SyncItemStatuses.Duplicate),
            ConflictCount = results.Count(r => r.Status == SyncItemStatuses.Conflict),
            RejectedCount = results.Count(r => r.Status == SyncItemStatuses.Rejected),
            Results = results
        };

        _logger.LogInformation(
            "Offline sync for user {UserId}: {Synced} synced, {Conflicts} conflicts, {Rejected} rejected",
            userId, response.SyncedCount, response.ConflictCount, response.RejectedCount);

        return response;
    }

    private async Task<SyncItemResult> ProcessItemAsync(
        SyncDoseLogItem item,
        Guid userId,
        IReadOnlyDictionary<Guid, DoseSchedule> schedules,
        ISet<Guid> touchedSchedules,
        IDictionary<(Guid ScheduleId, DateTime ScheduledAt), DoseLog> pendingBySlot,
        CancellationToken cancellationToken)
    {
        // A single bad row must not fail the whole batch, or a caregiver with one corrupt entry
        // could never sync anything again.
        try
        {
            if (!schedules.TryGetValue(item.ScheduleId, out var schedule))
            {
                return Rejected(item, "The schedule no longer exists.");
            }

            // Ownership is re-checked here rather than relying on the client having sent its own
            // records: the batch endpoint takes dependant ids straight from the request body.
            if (schedule.Medication.Dependant.CaregiverUserId != userId)
            {
                _logger.LogWarning(
                    "Sync item for schedule {ScheduleId} rejected: not owned by user {UserId}",
                    item.ScheduleId, userId);

                return Rejected(item, "This schedule belongs to another caregiver.");
            }

            if (schedule.Medication.MedicationId != item.MedicationId
                || schedule.Medication.DependantId != item.DependantId)
            {
                return Rejected(item, "The medication or dependant does not match the schedule.");
            }

            var status = item.Status?.Trim().ToLowerInvariant() ?? string.Empty;

            if (!DoseLogStatuses.All.Contains(status))
            {
                return Rejected(item, $"Status {item.Status} is not valid.");
            }

            if (item.LoggedAt > _clock.UtcNow.AddMinutes(10))
            {
                return Rejected(item, "The entry is dated in the future.");
            }

            var scheduledAt = ResolveSlot(schedule, item);

            if (scheduledAt is null)
            {
                return Rejected(item, "No dose was due near the time recorded on the device.");
            }

            var slotKey = (schedule.ScheduleId, scheduledAt.Value);

            // An entry added earlier in this same batch is checked first, because it is not yet
            // queryable. Either way the same last-write-wins rule is applied.
            if (pendingBySlot.TryGetValue(slotKey, out var pending))
            {
                return Reconcile(pending, item, status, schedule.Medication, alreadyPersisted: false);
            }

            var existing = await _context.DoseLogs.FirstOrDefaultAsync(
                l => l.ScheduleId == schedule.ScheduleId && l.ScheduledAt == scheduledAt.Value,
                cancellationToken);

            if (existing is not null)
            {
                return Reconcile(existing, item, status, schedule.Medication, alreadyPersisted: true);
            }

            var log = new DoseLog
            {
                ScheduleId = schedule.ScheduleId,
                DependantId = schedule.Medication.DependantId,
                MedicationId = schedule.Medication.MedicationId,
                Status = status,
                ScheduledAt = scheduledAt.Value,
                LoggedAt = item.LoggedAt,
                Notes = string.IsNullOrWhiteSpace(item.Notes) ? null : item.Notes.Trim(),

                // The entry has now reached the server, so it is settled: the flag exists to tell
                // the client which of its own rows are still outstanding.
                PendingSync = false,
                RowVersion = 1
            };

            await _unitOfWork.DoseLogs.AddAsync(log, cancellationToken);
            pendingBySlot[slotKey] = log;

            if (string.Equals(status, DoseLogStatuses.Taken, StringComparison.OrdinalIgnoreCase))
            {
                ApplyStockChange(schedule.Medication);
            }

            touchedSchedules.Add(schedule.ScheduleId);

            return new SyncItemResult
            {
                LocalId = item.LocalId,
                ServerId = log.DoseLogId,
                Status = SyncItemStatuses.Synced,
                ServerRowVersion = log.RowVersion
            };
        }
        catch (Exception ex)
        {
            _logger.LogError(
                ex, "Sync item {LocalId} failed unexpectedly and was rejected", item.LocalId);

            return Rejected(item, "The entry could not be processed.");
        }
    }

    /// <summary>
    /// Settles an offline entry against one the server already holds for the same slot.
    /// Identical entries are duplicates from a replayed batch. Different ones are real conflicts,
    /// where the most recent tap wins: the caregiver's latest correction is what they meant.
    /// </summary>
    /// <param name="alreadyPersisted">
    /// False when the entry being reconciled against was added earlier in this same batch and is
    /// still only tracked as Added. Calling Update on it would flip it to Modified and fail on
    /// commit, because there is no row to update yet.
    /// </param>
    private SyncItemResult Reconcile(
        DoseLog existing,
        SyncDoseLogItem item,
        string status,
        Medication medication,
        bool alreadyPersisted)
    {
        if (string.Equals(existing.Status, status, StringComparison.OrdinalIgnoreCase))
        {
            return new SyncItemResult
            {
                LocalId = item.LocalId,
                ServerId = existing.DoseLogId,
                Status = SyncItemStatuses.Duplicate,
                ServerRowVersion = existing.RowVersion
            };
        }

        if (item.LoggedAt <= existing.LoggedAt)
        {
            return new SyncItemResult
            {
                LocalId = item.LocalId,
                ServerId = existing.DoseLogId,
                Status = SyncItemStatuses.Conflict,
                ServerRowVersion = existing.RowVersion,
                Message = $"The server holds a newer entry for this dose, recorded as {existing.Status}."
            };
        }

        _logger.LogInformation(
            "Dose log {DoseLogId} overwritten by a newer offline entry: {OldStatus} to {NewStatus}",
            existing.DoseLogId, existing.Status, status);

        // The stock count has to follow the correction. Flipping a dose from missed to taken
        // consumes a unit that was never deducted; flipping it back returns one that should not
        // have been. Leaving this out would let a caregiver who corrects a few entries drift the
        // refill forecast permanently out of step with the pack in their hand.
        ApplyStockCorrection(medication, from: existing.Status, to: status);

        existing.Status = status;
        existing.LoggedAt = item.LoggedAt;
        existing.Notes = string.IsNullOrWhiteSpace(item.Notes) ? existing.Notes : item.Notes.Trim();
        existing.PendingSync = false;

        // Set explicitly so the DbContext stamp leaves it alone; the client stores this value and
        // sends it back on the next sync.
        existing.RowVersion = existing.RowVersion + 1;

        if (alreadyPersisted)
        {
            _unitOfWork.DoseLogs.Update(existing);
        }

        return new SyncItemResult
        {
            LocalId = item.LocalId,
            ServerId = existing.DoseLogId,
            Status = SyncItemStatuses.Synced,
            ServerRowVersion = existing.RowVersion,
            Message = "A newer offline entry replaced the value held on the server."
        };
    }

    private DateTime? ResolveSlot(DoseSchedule schedule, SyncDoseLogItem item)
    {
        if (item.ScheduledAt.HasValue)
        {
            return DateTime.SpecifyKind(item.ScheduledAt.Value, DateTimeKind.Utc);
        }

        var candidates = _scheduleService.EnumerateSlots(
            schedule, item.LoggedAt.AddDays(-1), item.LoggedAt.AddDays(1));

        if (candidates.Count == 0)
        {
            return null;
        }

        return candidates
            .OrderBy(slot => Math.Abs((slot - item.LoggedAt).Ticks))
            .First();
    }

    /// <summary>
    /// Adjusts the stock count when a reconciled entry changes a dose between taken and missed.
    /// </summary>
    private void ApplyStockCorrection(Medication medication, string from, string to)
    {
        var wasTaken = string.Equals(from, DoseLogStatuses.Taken, StringComparison.OrdinalIgnoreCase);
        var isTaken = string.Equals(to, DoseLogStatuses.Taken, StringComparison.OrdinalIgnoreCase);

        if (wasTaken == isTaken)
        {
            return;
        }

        if (isTaken)
        {
            ApplyStockChange(medication);
            return;
        }

        // Returning a unit, but never above what was actually prescribed.
        medication.RemainingQuantity = Math.Min(medication.RemainingQuantity + 1, medication.TotalQuantity);
        _unitOfWork.Medications.Update(medication);
    }

    private void ApplyStockChange(Medication medication)
    {
        if (medication.RemainingQuantity <= 0)
        {
            medication.RemainingQuantity = 0;
            return;
        }

        medication.RemainingQuantity -= 1;
        _unitOfWork.Medications.Update(medication);
    }

    private static SyncItemResult Rejected(SyncDoseLogItem item, string message) => new()
    {
        LocalId = item.LocalId,
        ServerId = null,
        Status = SyncItemStatuses.Rejected,
        Message = message
    };
}
