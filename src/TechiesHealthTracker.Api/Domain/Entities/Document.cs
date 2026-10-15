// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Creating and Configuring a Model — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (Data Models and Schema Definitions).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Entities;

/// <summary>
/// Metadata for an uploaded prescription photo or lab result. The file itself lives in blob
/// storage; BlobUrl is internal and is never returned to the client, so downloads always go
/// through a time-limited signed URL generated per request.
/// Maps to section 6.7 of the Part 1 Planning and Design document.
/// </summary>
public class Document
{
    public Guid DocumentId { get; set; } = Guid.NewGuid();
    public Guid DependantId { get; set; }

    public string Title { get; set; } = string.Empty;

    /// <summary>prescription, lab_result, medical_report or other.</summary>
    public string DocumentType { get; set; } = string.Empty;

    public string FileName { get; set; } = string.Empty;
    public string ContentType { get; set; } = string.Empty;
    public long FileSizeBytes { get; set; }

    /// <summary>Internal storage path. Never serialised to the client.</summary>
    public string BlobUrl { get; set; } = string.Empty;

    public bool IsDeleted { get; set; }
    public DateTime UploadedAt { get; set; } = DateTime.UtcNow;

    public Dependant Dependant { get; set; } = null!;
}
