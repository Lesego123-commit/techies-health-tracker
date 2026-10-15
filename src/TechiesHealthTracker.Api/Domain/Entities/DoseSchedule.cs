// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// When a medication should be taken. A medication can carry more than one schedule (for example
/// a morning tablet and an evening tablet with different dosages). NextDoseAt is recomputed by the
/// API on every dose log, so the reminder always reflects the latest entry.
/// Maps to section 6.4 of the Part 1 Planning and Design document.
/// </summary>
public class DoseSchedule
{
    public Guid ScheduleId { get; set; } = Guid.NewGuid();
    public Guid MedicationId { get; set; }

    /// <summary>once_daily, twice_daily, three_times_daily, weekly, custom.</summary>
    public string Frequency { get; set; } = string.Empty;

    /// <summary>Clock times in HH:mm, stored as a Postgres text[] column.</summary>
    public List<string> Times { get; set; } = new();

    /// <summary>Days the schedule applies to, e.g. monday, thursday. Empty means every day.</summary>
    public List<string> DaysOfWeek { get; set; } = new();

    public DateOnly StartDate { get; set; }
    public DateOnly? EndDate { get; set; }

    /// <summary>API-calculated next dose timestamp, never supplied by the client.</summary>
    public DateTime? NextDoseAt { get; set; }

    public bool IsActive { get; set; } = true;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public Medication Medication { get; set; } = null!;
    public ICollection<DoseLog> DoseLogs { get; set; } = new List<DoseLog>();
}
