// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>
/// Body of POST /api/sync/batch (section 5.4.10). WorkManager posts everything RoomDB has
/// flagged as pending in one call rather than one request per row, so a caregiver who spent the
/// morning offline does not fire twenty requests the moment signal returns.
/// </summary>
public class SyncBatchRequest
{
    [Required, MinLength(1)]
    public List<SyncDoseLogItem> DoseLogs { get; set; } = new();
}

/// <summary>One queued offline dose log.</summary>
public class SyncDoseLogItem
{
    /// <summary>
    /// The RoomDB primary key. Echoed back untouched in the response so the client knows which
    /// local row to mark as synced, and so a replayed batch stays idempotent.
    /// </summary>
    [Required]
    public string LocalId { get; set; } = string.Empty;

    [Required]
    public Guid ScheduleId { get; set; }

    [Required]
    public Guid DependantId { get; set; }

    [Required]
    public Guid MedicationId { get; set; }

    [Required, StringLength(10)]
    public string Status { get; set; } = string.Empty;

    [Required]
    public DateTime LoggedAt { get; set; }

    /// <summary>The slot this entry belongs to. Resolved server-side when the client omits it.</summary>
    public DateTime? ScheduledAt { get; set; }

    [StringLength(255)]
    public string? Notes { get; set; }

    /// <summary>
    /// The RowVersion the device last saw. Drives the last-write-wins comparison in section 3.5:
    /// a stale value means another device already logged this slot.
    /// </summary>
    public int RowVersion { get; set; }
}
