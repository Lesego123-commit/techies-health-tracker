// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// A person in a caregiver's care. CaregiverUserId is the ownership boundary for the whole
/// data model — every medication, appointment and document check walks back to this column.
/// Maps to section 6.2 of the Part 1 Planning and Design document.
/// </summary>
public class Dependant
{
    public Guid DependantId { get; set; } = Guid.NewGuid();

    /// <summary>Owning caregiver. Indexed, because every authorisation check filters on it.</summary>
    public Guid CaregiverUserId { get; set; }

    public string FirstName { get; set; } = string.Empty;
    public string LastName { get; set; } = string.Empty;
    public DateOnly DateOfBirth { get; set; }

    /// <summary>child, parent, spouse, other — see <see cref="Constants.RelationshipTypes"/>.</summary>
    public string Relationship { get; set; } = string.Empty;

    public string? MedicalAidNumber { get; set; }
    public string? Allergies { get; set; }

    /// <summary>Soft-delete flag: DELETE /api/dependants/{id} clears this rather than removing the row.</summary>
    public bool IsActive { get; set; } = true;

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public int RowVersion { get; set; }

    public User Caregiver { get; set; } = null!;
    public ICollection<Medication> Medications { get; set; } = new List<Medication>();
    public ICollection<Appointment> Appointments { get; set; } = new List<Appointment>();
    public ICollection<Document> Documents { get; set; } = new List<Document>();
    public ICollection<DoseLog> DoseLogs { get; set; } = new List<DoseLog>();
}
