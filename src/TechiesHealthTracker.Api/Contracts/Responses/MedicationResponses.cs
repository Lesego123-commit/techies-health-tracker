// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>A medication as returned by the /api/medications endpoints (section 5.4.3).</summary>
public record MedicationResponse
{
    public Guid MedicationId { get; init; }
    public Guid DependantId { get; init; }
    public string Name { get; init; } = string.Empty;
    public string DosageAmount { get; init; } = string.Empty;
    public string DosageUnit { get; init; } = string.Empty;

    /// <summary>Pre-formatted for display, e.g. "500 mg". Built server-side so every screen agrees.</summary>
    public string DosageDisplay { get; init; } = string.Empty;

    public string Form { get; init; } = string.Empty;
    public int TotalQuantity { get; init; }
    public int RemainingQuantity { get; init; }
    public string? PrescribedBy { get; init; }
    public DateOnly StartDate { get; init; }
    public DateOnly? EndDate { get; init; }
    public string? Instructions { get; init; }
    public bool IsActive { get; init; }

    public IReadOnlyList<DoseScheduleResponse> Schedules { get; init; } = Array.Empty<DoseScheduleResponse>();

    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public int RowVersion { get; init; }
}

/// <summary>A dose schedule as returned by POST /api/medications/{id}/schedules (section 5.4.4).</summary>
public record DoseScheduleResponse
{
    public Guid ScheduleId { get; init; }
    public Guid MedicationId { get; init; }
    public string Frequency { get; init; } = string.Empty;
    public IReadOnlyList<string> Times { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> DaysOfWeek { get; init; } = Array.Empty<string>();
    public DateOnly StartDate { get; init; }
    public DateOnly? EndDate { get; init; }

    /// <summary>Calculated by the API, never supplied by the client (section 6.4).</summary>
    public DateTime? NextDoseAt { get; init; }

    public bool IsActive { get; init; }
}

/// <summary>
/// Response of GET /api/medications/{id}/refill-forecast (section 5.4.6) — the output of the
/// low-stock prediction engine described in section 3.11.
/// </summary>
public record RefillForecastResponse
{
    public Guid MedicationId { get; init; }
    public string MedicationName { get; init; } = string.Empty;
    public int RemainingQuantity { get; init; }

    /// <summary>Units consumed per day, derived from the active dose schedules.</summary>
    public double DailyConsumptionRate { get; init; }

    /// <summary>Null when the medication has no active schedule, so nothing is being consumed.</summary>
    public DateOnly? EstimatedRunOutDate { get; init; }

    /// <summary>Null for the same reason as EstimatedRunOutDate.</summary>
    public int? DaysUntilRunOut { get; init; }

    /// <summary>True once DaysUntilRunOut falls inside the user's LowStockThresholdDays setting.</summary>
    public bool RefillAlertTriggered { get; init; }

    /// <summary>The threshold the alert was judged against, echoed back for the Refill screen.</summary>
    public int LowStockThresholdDays { get; init; }

    /// <summary>ok, low or depleted. Drives the colour coding on the Refill and Low-Stock screen.</summary>
    public string Severity { get; init; } = "ok";
}
