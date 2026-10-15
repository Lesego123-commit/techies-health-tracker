// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Blob storage for uploaded prescriptions and lab results (section 3.9).
///
/// The design document targets Azure Blob Storage, which is provisioned in the POE phase. Part 2
/// registers a local filesystem implementation behind this same interface, so the controller,
/// service and database rows are already the final shape and only the registration changes later.
/// </summary>
public interface IFileStorageService
{
    /// <summary>Stores the file and returns the internal storage path recorded in Documents.BlobUrl.</summary>
    Task<string> UploadAsync(
        Stream content,
        string fileName,
        string contentType,
        CancellationToken cancellationToken = default);

    /// <summary>
    /// Produces a time-limited URL for the stored file. Section 6.7 requires that the client
    /// never sees the raw storage path, so this is regenerated on every read rather than stored.
    /// </summary>
    Task<(string Url, DateTime ExpiresAt)> GetSignedUrlAsync(
        string blobUrl, TimeSpan validFor, CancellationToken cancellationToken = default);

    Task DeleteAsync(string blobUrl, CancellationToken cancellationToken = default);
}
