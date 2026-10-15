// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// A doctor visit or check-up for a dependant. ReminderMinutesBefore defaults to 60 and is
/// adjustable through the API with a five-minute minimum.
/// Maps to section 6.6 of the Part 1 Planning and Design document.
/// </summary>
public class Appointment
{
    public Guid AppointmentId { get; set; } = Guid.NewGuid();
    public Guid DependantId { get; set; }

    public string Title { get; set; } = string.Empty;
    public DateTime AppointmentDateTime { get; set; }
    public string? Location { get; set; }
    public string? Notes { get; set; }

    public int ReminderMinutesBefore { get; set; } = 60;

    /// <summary>DELETE /api/appointments/{id} cancels rather than deletes, so the history survives.</summary>
    public bool IsCancelled { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public Dependant Dependant { get; set; } = null!;
}
