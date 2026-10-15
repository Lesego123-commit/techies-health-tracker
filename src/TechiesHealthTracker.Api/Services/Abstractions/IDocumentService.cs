// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>The /api/documents endpoints (section 5.4.9), backing Additional Feature 2.</summary>
public interface IDocumentService
{
    Task<DocumentResponse> UploadAsync(
        Guid dependantId,
        Stream content,
        string fileName,
        string contentType,
        long fileSizeBytes,
        string title,
        string documentType,
        CancellationToken cancellationToken = default);

    Task<IReadOnlyList<DocumentResponse>> ListForDependantAsync(
        Guid dependantId, CancellationToken cancellationToken = default);

    /// <summary>Metadata plus a freshly signed download URL.</summary>
    Task<DocumentResponse> GetAsync(Guid documentId, CancellationToken cancellationToken = default);

    /// <summary>Soft-deletes the metadata row and removes the underlying file.</summary>
    Task DeleteAsync(Guid documentId, CancellationToken cancellationToken = default);
}
