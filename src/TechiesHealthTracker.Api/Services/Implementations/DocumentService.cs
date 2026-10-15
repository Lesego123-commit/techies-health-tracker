// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Stores prescriptions and lab results against a dependant (Additional Feature 2, section 3.9).
/// See <see cref="IDocumentService"/> for the contract.
/// </summary>
public class DocumentService : IDocumentService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly IAccessGuard _accessGuard;
    private readonly IFileStorageService _fileStorage;
    private readonly ILogger<DocumentService> _logger;

    /// <summary>Upload ceiling, matching the 10 MB stated in the endpoint specification (section 5.4.9).</summary>
    public const long MaximumFileSizeBytes = 10 * 1024 * 1024;

    /// <summary>
    /// Only the formats a phone camera or a clinic actually produces are accepted. An allow-list
    /// is used rather than a block-list so that an unexpected type is refused by default.
    /// </summary>
    private static readonly IReadOnlySet<string> AllowedContentTypes =
        new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "image/jpeg", "image/png", "image/heic", "image/webp", "application/pdf"
        };

    /// <summary>How long a generated download link stays valid.</summary>
    private static readonly TimeSpan SignedUrlLifetime = TimeSpan.FromMinutes(15);

    public DocumentService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        IAccessGuard accessGuard,
        IFileStorageService fileStorage,
        ILogger<DocumentService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _accessGuard = accessGuard;
        _fileStorage = fileStorage;
        _logger = logger;
    }

    public async Task<DocumentResponse> UploadAsync(
        Guid dependantId,
        Stream content,
        string fileName,
        string contentType,
        long fileSizeBytes,
        string title,
        string documentType,
        CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        if (fileSizeBytes <= 0)
        {
            throw new BusinessRuleException("The uploaded file is empty.");
        }

        if (fileSizeBytes > MaximumFileSizeBytes)
        {
            throw new BusinessRuleException(
                $"Files may be at most {MaximumFileSizeBytes / (1024 * 1024)} MB.");
        }

        if (!AllowedContentTypes.Contains(contentType))
        {
            throw new BusinessRuleException(
                $"Files of type {contentType} are not accepted. Upload a JPEG, PNG, HEIC, WebP or PDF.");
        }

        var normalisedType = documentType?.Trim().ToLowerInvariant() ?? string.Empty;

        if (!DocumentTypes.All.Contains(normalisedType))
        {
            throw new BusinessRuleException(
                $"Document type {documentType} is not recognised. Use one of: "
                + string.Join(", ", DocumentTypes.All) + ".");
        }

        if (string.IsNullOrWhiteSpace(title))
        {
            throw new BusinessRuleException("A document title is required.");
        }

        var blobUrl = await _fileStorage.UploadAsync(content, fileName, contentType, cancellationToken);

        var document = new Document
        {
            DependantId = dependantId,
            Title = title.Trim(),
            DocumentType = normalisedType,
            FileName = fileName,
            ContentType = contentType,
            FileSizeBytes = fileSizeBytes,
            BlobUrl = blobUrl,
            IsDeleted = false
        };

        try
        {
            await _unitOfWork.Documents.AddAsync(document, cancellationToken);
            await _unitOfWork.SaveChangesAsync(cancellationToken);
        }
        catch
        {
            // The file is already on disk at this point. If the metadata row fails to save there
            // would be nothing left pointing at it, so it is removed rather than orphaned.
            await _fileStorage.DeleteAsync(blobUrl, CancellationToken.None);
            throw;
        }

        _logger.LogInformation(
            "Document {DocumentId} ({DocumentType}, {Bytes} bytes) uploaded for dependant {DependantId}",
            document.DocumentId, normalisedType, fileSizeBytes, dependantId);

        return await BuildResponseAsync(document, cancellationToken);
    }

    public async Task<IReadOnlyList<DocumentResponse>> ListForDependantAsync(
        Guid dependantId, CancellationToken cancellationToken = default)
    {
        await _accessGuard.EnsureDependantAsync(dependantId, cancellationToken);

        var documents = await _context.Documents
            .AsNoTracking()
            .Where(d => d.DependantId == dependantId && !d.IsDeleted)
            .OrderByDescending(d => d.UploadedAt)
            .ToListAsync(cancellationToken);

        var responses = new List<DocumentResponse>(documents.Count);

        foreach (var document in documents)
        {
            responses.Add(await BuildResponseAsync(document, cancellationToken));
        }

        return responses;
    }

    public async Task<DocumentResponse> GetAsync(
        Guid documentId, CancellationToken cancellationToken = default)
    {
        var document = await _accessGuard.EnsureDocumentAsync(documentId, cancellationToken);
        return await BuildResponseAsync(document, cancellationToken);
    }

    public async Task DeleteAsync(Guid documentId, CancellationToken cancellationToken = default)
    {
        var document = await _accessGuard.EnsureDocumentAsync(documentId, cancellationToken);

        document.IsDeleted = true;
        _unitOfWork.Documents.Update(document);
        await _unitOfWork.SaveChangesAsync(cancellationToken);

        // The metadata row is kept as a soft delete for the audit trail, but the file itself is
        // removed: there is no reason to hold medical images the caregiver has discarded.
        await _fileStorage.DeleteAsync(document.BlobUrl, cancellationToken);

        _logger.LogInformation("Document {DocumentId} deleted", documentId);
    }

    private async Task<DocumentResponse> BuildResponseAsync(
        Document document, CancellationToken cancellationToken)
    {
        var (url, expiresAt) = await _fileStorage.GetSignedUrlAsync(
            document.BlobUrl, SignedUrlLifetime, cancellationToken);

        return document.ToResponse(url, expiresAt);
    }
}
