// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>A single dose log row (section 5.4.5).</summary>
public record DoseLogResponse
{
    public Guid DoseLogId { get; init; }
    public Guid ScheduleId { get; init; }
    public Guid DependantId { get; init; }
    public Guid MedicationId { get; init; }
    public string MedicationName { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public DateTime ScheduledAt { get; init; }
    public DateTime LoggedAt { get; init; }
    public string? Notes { get; init; }
    public int RowVersion { get; init; }
}

/// <summary>
/// Response of POST /api/doselogs: the log that was written plus a refreshed adherence summary,
/// so the Home screen can update its streak card without a second call.
/// </summary>
public record DoseLogCreatedResponse
{
    public DoseLogResponse DoseLog { get; init; } = new();
    public AdherenceResponse AdherenceSummary { get; init; } = new();

    /// <summary>Stock left after this log, already decremented when the status was "taken".</summary>
    public int RemainingQuantity { get; init; }

    /// <summary>The recalculated next dose for the schedule that was just logged.</summary>
    public DateTime? NextDoseAt { get; init; }

    /// <summary>True when this log pushed the medication under the low-stock threshold.</summary>
    public bool RefillAlertTriggered { get; init; }
}

/// <summary>
/// Response of GET /api/dependants/{id}/adherence (section 5.4.5) — the analytics behind
/// Additional Feature 3, including the plain-language pattern call-out from section 2.2.
/// </summary>
public record AdherenceResponse
{
    public Guid DependantId { get; init; }
    public DateOnly FromDate { get; init; }
    public DateOnly ToDate { get; init; }

    public int TotalScheduledDoses { get; init; }
    public int DosesTaken { get; init; }
    public int DosesMissed { get; init; }

    /// <summary>Taken as a percentage of scheduled, rounded to one decimal place.</summary>
    public double AdherencePercentage { get; init; }

    /// <summary>Consecutive days, counting back from today, with no missed dose.</summary>
    public int CurrentStreakDays { get; init; }

    /// <summary>Per-day breakdown used by the bar chart on the Trends screen.</summary>
    public IReadOnlyList<AdherenceTrendPoint> Trends { get; init; } = Array.Empty<AdherenceTrendPoint>();

    /// <summary>
    /// The pattern insight, e.g. "Evening doses are missed most often". Null when there is not
    /// enough history to say anything useful.
    /// </summary>
    public string? PatternInsight { get; init; }
}

/// <summary>One day of the adherence trend series.</summary>
public record AdherenceTrendPoint
{
    public DateOnly Date { get; init; }
    public int Scheduled { get; init; }
    public int Taken { get; init; }
    public int Missed { get; init; }
    public double AdherencePercentage { get; init; }
}
