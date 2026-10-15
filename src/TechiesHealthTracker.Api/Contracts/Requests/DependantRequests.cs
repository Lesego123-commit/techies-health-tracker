// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>Body of POST /api/dependants (section 5.4.2).</summary>
public class CreateDependantRequest
{
    [Required, StringLength(50, MinimumLength = 1)]
    public string FirstName { get; set; } = string.Empty;

    [Required, StringLength(50, MinimumLength = 1)]
    public string LastName { get; set; } = string.Empty;

    [Required]
    public DateOnly DateOfBirth { get; set; }

    /// <summary>child, parent, spouse, self or other.</summary>
    [Required, StringLength(30)]
    public string Relationship { get; set; } = string.Empty;

    [StringLength(50)]
    public string? MedicalAidNumber { get; set; }

    [StringLength(500)]
    public string? Allergies { get; set; }
}

/// <summary>
/// Body of PUT /api/dependants/{id} (section 5.4.2). Partial by design: the client sends only
/// the fields the caregiver actually edited.
/// </summary>
public class UpdateDependantRequest
{
    [StringLength(50, MinimumLength = 1)]
    public string? FirstName { get; set; }

    [StringLength(50, MinimumLength = 1)]
    public string? LastName { get; set; }

    public DateOnly? DateOfBirth { get; set; }

    [StringLength(30)]
    public string? Relationship { get; set; }

    [StringLength(50)]
    public string? MedicalAidNumber { get; set; }

    [StringLength(500)]
    public string? Allergies { get; set; }
}
