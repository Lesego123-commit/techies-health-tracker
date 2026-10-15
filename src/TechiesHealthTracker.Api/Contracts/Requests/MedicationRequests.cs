// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>Body of POST /api/dependants/{id}/medications (section 5.4.3).</summary>
public class CreateMedicationRequest
{
    [Required, StringLength(100, MinimumLength = 1)]
    public string Name { get; set; } = string.Empty;

    /// <summary>Dosage per intake, e.g. 500. Text rather than a number because prescriptions use values like 1/2.</summary>
    [Required, StringLength(20)]
    public string DosageAmount { get; set; } = string.Empty;

    /// <summary>mg, ml, tablets and so on.</summary>
    [Required, StringLength(20)]
    public string DosageUnit { get; set; } = string.Empty;

    /// <summary>capsule, tablet, liquid, injection, inhaler or drops.</summary>
    [Required, StringLength(30)]
    public string Form { get; set; } = string.Empty;

    [Range(1, 100000)]
    public int TotalQuantity { get; set; }

    /// <summary>
    /// Stock on hand right now. Left null on a brand-new prescription, in which case the API
    /// seeds it from TotalQuantity.
    /// </summary>
    [Range(0, 100000)]
    public int? RemainingQuantity { get; set; }

    [StringLength(100)]
    public string? PrescribedBy { get; set; }

    [Required]
    public DateOnly StartDate { get; set; }

    /// <summary>Null means the course is ongoing.</summary>
    public DateOnly? EndDate { get; set; }

    [StringLength(500)]
    public string? Instructions { get; set; }
}

/// <summary>Body of PUT /api/medications/{id} (section 5.4.3). Partial update.</summary>
public class UpdateMedicationRequest
{
    [StringLength(100, MinimumLength = 1)]
    public string? Name { get; set; }

    [StringLength(20)]
    public string? DosageAmount { get; set; }

    [StringLength(20)]
    public string? DosageUnit { get; set; }

    [StringLength(30)]
    public string? Form { get; set; }

    [Range(1, 100000)]
    public int? TotalQuantity { get; set; }

    [Range(0, 100000)]
    public int? RemainingQuantity { get; set; }

    [StringLength(100)]
    public string? PrescribedBy { get; set; }

    public DateOnly? StartDate { get; set; }

    public DateOnly? EndDate { get; set; }

    [StringLength(500)]
    public string? Instructions { get; set; }

    /// <summary>Set false to retire a medication without losing its dose history.</summary>
    public bool? IsActive { get; set; }
}
