// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using System.ComponentModel.DataAnnotations;

namespace TechiesHealthTracker.Api.Contracts.Requests;

/// <summary>
/// The multipart form body of POST /api/dependants/{id}/documents (section 5.4.9).
///
/// The three fields are bound as a single model rather than as separate [FromForm] parameters:
/// Swashbuckle cannot describe a file upload declared the second way, and the Swagger page is
/// how the Android side discovers this endpoint.
/// </summary>
public class UploadDocumentRequest
{
    /// <summary>The prescription photo or lab result. Maximum 10 MB.</summary>
    [Required]
    public IFormFile File { get; set; } = null!;

    /// <summary>User-supplied title shown in the Documents list.</summary>
    [Required, StringLength(150, MinimumLength = 1)]
    public string Title { get; set; } = string.Empty;

    /// <summary>prescription, lab_result, medical_report or other.</summary>
    [Required, StringLength(30)]
    public string DocumentType { get; set; } = string.Empty;
}
