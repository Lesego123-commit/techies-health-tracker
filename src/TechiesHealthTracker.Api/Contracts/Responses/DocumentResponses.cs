// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Model Validation in ASP.NET Core MVC. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/models/validation [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Contracts.Responses;

/// <summary>
/// Document metadata as returned by the /api/documents endpoints (section 5.4.9). The internal
/// BlobUrl is deliberately absent: the client only ever receives a time-limited signed URL.
/// </summary>
public record DocumentResponse
{
    public Guid DocumentId { get; init; }
    public Guid DependantId { get; init; }
    public string Title { get; init; } = string.Empty;
    public string DocumentType { get; init; } = string.Empty;
    public string FileName { get; init; } = string.Empty;
    public string ContentType { get; init; } = string.Empty;
    public long FileSizeBytes { get; init; }

    /// <summary>Time-limited signed URL, regenerated on every read.</summary>
    public string DownloadUrl { get; init; } = string.Empty;

    /// <summary>When the signed URL stops working and the client must ask for a fresh one.</summary>
    public DateTime DownloadUrlExpiresAt { get; init; }

    public DateTime UploadedAt { get; init; }
}
