// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>
/// Response of POST /api/sync/batch (section 5.4.10). Every submitted item comes back with a
/// verdict, so WorkManager can clear exactly the RoomDB rows that landed and leave the rest.
/// </summary>
public record SyncBatchResponse
{
    /// <summary>Items written or accepted as already-present.</summary>
    public int SyncedCount { get; init; }

    /// <summary>Items where the server already held a newer entry for the same slot.</summary>
    public int ConflictCount { get; init; }

    /// <summary>Items rejected outright, e.g. an unknown schedule or a dependant that is not yours.</summary>
    public int RejectedCount { get; init; }

    public IReadOnlyList<SyncItemResult> Results { get; init; } = Array.Empty<SyncItemResult>();
}

/// <summary>The outcome for a single queued offline dose log.</summary>
public record SyncItemResult
{
    /// <summary>The RoomDB key exactly as it was submitted.</summary>
    public string LocalId { get; init; } = string.Empty;

    /// <summary>The server-side DoseLogId, or null when the item was rejected.</summary>
    public Guid? ServerId { get; init; }

    /// <summary>
    /// synced when the entry was written, duplicate when the same slot was already recorded
    /// identically, conflict when the server kept a newer entry, rejected when it was invalid.
    /// </summary>
    public string Status { get; init; } = string.Empty;

    /// <summary>The authoritative RowVersion the device should now store.</summary>
    public int? ServerRowVersion { get; init; }

    /// <summary>Human-readable reason, populated for conflict and rejected outcomes.</summary>
    public string? Message { get; init; }
}

/// <summary>Statuses a <see cref="SyncItemResult"/> can carry.</summary>
public static class SyncItemStatuses
{
    public const string Synced = "synced";
    public const string Duplicate = "duplicate";
    public const string Conflict = "conflict";
    public const string Rejected = "rejected";
}
