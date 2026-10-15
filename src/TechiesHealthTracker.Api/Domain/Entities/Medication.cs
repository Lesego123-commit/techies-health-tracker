// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// A medication prescribed to a dependant. RemainingQuantity is decremented every time a dose
/// is logged as taken and is the input to the refill-prediction engine (section 3.11).
/// Maps to section 6.3 of the Part 1 Planning and Design document.
/// </summary>
public class Medication
{
    public Guid MedicationId { get; set; } = Guid.NewGuid();
    public Guid DependantId { get; set; }

    public string Name { get; set; } = string.Empty;

    /// <summary>Dosage per intake, e.g. "500". Stored as text because prescriptions use values like "1/2".</summary>
    public string DosageAmount { get; set; } = string.Empty;

    /// <summary>mg, ml, tablets — see <see cref="Constants.DosageUnits"/>.</summary>
    public string DosageUnit { get; set; } = string.Empty;

    /// <summary>capsule, tablet, liquid, injection, inhaler, drops — see <see cref="Constants.MedicationForms"/>.</summary>
    public string Form { get; set; } = string.Empty;

    public int TotalQuantity { get; set; }
    public int RemainingQuantity { get; set; }
    public string? PrescribedBy { get; set; }

    public DateOnly StartDate { get; set; }

    /// <summary>Null means the course is ongoing.</summary>
    public DateOnly? EndDate { get; set; }

    public string? Instructions { get; set; }
    public bool IsActive { get; set; } = true;

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public int RowVersion { get; set; }

    public Dependant Dependant { get; set; } = null!;
    public ICollection<DoseSchedule> DoseSchedules { get; set; } = new List<DoseSchedule>();
    public ICollection<DoseLog> DoseLogs { get; set; } = new List<DoseLog>();
}
