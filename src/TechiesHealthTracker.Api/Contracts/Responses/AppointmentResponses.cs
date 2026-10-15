// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>An appointment as returned by the /api/appointments endpoints (section 5.4.7).</summary>
public record AppointmentResponse
{
    public Guid AppointmentId { get; init; }
    public Guid DependantId { get; init; }
    public string Title { get; init; } = string.Empty;
    public DateTime AppointmentDateTime { get; init; }
    public string? Location { get; init; }
    public string? Notes { get; init; }
    public int ReminderMinutesBefore { get; init; }
    public bool IsCancelled { get; init; }

    /// <summary>
    /// True when the appointment is still ahead of now and not cancelled. Decided server-side so
    /// that a device with a skewed clock cannot file an appointment under the wrong heading.
    /// </summary>
    public bool IsUpcoming { get; init; }

    /// <summary>When the reminder will fire, i.e. AppointmentDateTime less the lead time.</summary>
    public DateTime ReminderAt { get; init; }

    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
}
