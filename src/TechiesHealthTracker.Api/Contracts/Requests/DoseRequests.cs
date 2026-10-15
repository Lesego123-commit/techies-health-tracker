// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>Body of POST /api/medications/{id}/schedules (section 5.4.4).</summary>
public class CreateDoseScheduleRequest
{
    /// <summary>once_daily, twice_daily, three_times_daily, weekly or custom.</summary>
    [Required, StringLength(20)]
    public string Frequency { get; set; } = string.Empty;

    /// <summary>Clock times in HH:mm. The count has to agree with the frequency.</summary>
    [Required, MinLength(1)]
    public List<string> Times { get; set; } = new();

    /// <summary>Lowercase day names. Empty means every day.</summary>
    public List<string> DaysOfWeek { get; set; } = new();

    [Required]
    public DateOnly StartDate { get; set; }

    public DateOnly? EndDate { get; set; }
}

/// <summary>Body of POST /api/doselogs (section 5.4.5).</summary>
public class CreateDoseLogRequest
{
    [Required]
    public Guid ScheduleId { get; set; }

    [Required]
    public Guid DependantId { get; set; }

    [Required]
    public Guid MedicationId { get; set; }

    /// <summary>Either taken or missed.</summary>
    [Required, StringLength(10)]
    public string Status { get; set; } = string.Empty;

    /// <summary>When the user actually tapped. Defaults to the time the request arrives.</summary>
    public DateTime? LoggedAt { get; set; }

    /// <summary>
    /// The slot this log belongs to. Optional: when the client omits it the API resolves the
    /// slot from the schedule itself, which is what keeps the client lightweight.
    /// </summary>
    public DateTime? ScheduledAt { get; set; }

    [StringLength(255)]
    public string? Notes { get; set; }
}
