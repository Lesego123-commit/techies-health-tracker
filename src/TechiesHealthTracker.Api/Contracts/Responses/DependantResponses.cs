// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>A dependant profile as returned by the /api/dependants endpoints (section 5.4.2).</summary>
public record DependantResponse
{
    public Guid DependantId { get; init; }
    public string FirstName { get; init; } = string.Empty;
    public string LastName { get; init; } = string.Empty;

    /// <summary>Convenience field so the client does not have to concatenate names itself.</summary>
    public string FullName { get; init; } = string.Empty;

    public DateOnly DateOfBirth { get; init; }

    /// <summary>Calculated server-side so every screen shows the same number.</summary>
    public int Age { get; init; }

    public string Relationship { get; init; } = string.Empty;
    public string? MedicalAidNumber { get; init; }
    public string? Allergies { get; init; }

    /// <summary>Count of currently active medications, used for the Dependants list subtitle.</summary>
    public int ActiveMedicationCount { get; init; }

    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public int RowVersion { get; init; }
}
