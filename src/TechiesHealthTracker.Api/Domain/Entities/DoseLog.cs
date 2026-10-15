// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// One row per "taken" or "missed" tap. Rows created on the device while offline arrive later
/// through POST /api/sync/batch; PendingSync stays true on the client until that push succeeds.
/// Maps to section 6.5 of the Part 1 Planning and Design document.
/// </summary>
public class DoseLog
{
    public Guid DoseLogId { get; set; } = Guid.NewGuid();
    public Guid ScheduleId { get; set; }
    public Guid DependantId { get; set; }
    public Guid MedicationId { get; set; }

    /// <summary>Either "taken" or "missed".</summary>
    public string Status { get; set; } = string.Empty;

    public DateTime ScheduledAt { get; set; }
    public DateTime LoggedAt { get; set; }
    public string? Notes { get; set; }

    /// <summary>True if the entry was captured offline and has not yet been reconciled server-side.</summary>
    public bool PendingSync { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>Concurrency token; the last-write-wins rule in section 3.5 compares against this.</summary>
    public int RowVersion { get; set; }

    public DoseSchedule Schedule { get; set; } = null!;
    public Dependant Dependant { get; set; } = null!;
    public Medication Medication { get; set; } = null!;
}
