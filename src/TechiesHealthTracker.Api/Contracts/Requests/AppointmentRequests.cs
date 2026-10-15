// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>Body of POST /api/dependants/{id}/appointments (section 5.4.7).</summary>
public class CreateAppointmentRequest
{
    [Required, StringLength(150, MinimumLength = 1)]
    public string Title { get; set; } = string.Empty;

    [Required]
    public DateTime AppointmentDateTime { get; set; }

    [StringLength(255)]
    public string? Location { get; set; }

    [StringLength(500)]
    public string? Notes { get; set; }

    /// <summary>
    /// Lead time for the reminder. Defaults to 60 minutes with a five-minute floor, as specified
    /// in section 6.6 of the design document.
    /// </summary>
    [Range(5, 10080)]
    public int ReminderMinutesBefore { get; set; } = 60;
}

/// <summary>Body of PUT /api/appointments/{id} (section 5.4.7). Partial update.</summary>
public class UpdateAppointmentRequest
{
    [StringLength(150, MinimumLength = 1)]
    public string? Title { get; set; }

    public DateTime? AppointmentDateTime { get; set; }

    [StringLength(255)]
    public string? Location { get; set; }

    [StringLength(500)]
    public string? Notes { get; set; }

    [Range(5, 10080)]
    public int? ReminderMinutesBefore { get; set; }
}
