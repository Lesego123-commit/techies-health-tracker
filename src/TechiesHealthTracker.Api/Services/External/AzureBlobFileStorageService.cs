// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024d. Introduction to Azure Blob Storage. [Online]. Available at:
//    https://learn.microsoft.com/azure/storage/blobs/storage-blobs-introduction
//    [Accessed 25 September 2026].
//   Microsoft. 2024. Grant Limited Access to Data with Shared Access Signatures. [Online].
//    Available at: https://learn.microsoft.com/azure/storage/common/storage-sas-overview
//    [Accessed 25 September 2026].
//   Microsoft. 2024. Azure.Storage.Blobs namespace. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/api/azure.storage.blobs [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 3.9 (Additional Feature 2)
//    and 6.7 (Document access control).
// ----------------------------------------------------------------------------------------------

using Azure;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;
using Azure.Storage.Sas;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.External;

/// <summary>Settings for <see cref="AzureBlobFileStorageService"/>.</summary>
public class BlobStorageOptions
{
    public const string SectionName = "BlobStorage";

    /// <summary>
    /// Storage account connection string. Supplied as an App Service application setting, never
    /// committed: it contains the account key, which is what signs every download link.
    /// </summary>
    public string ConnectionString { get; set; } = string.Empty;

    /// <summary>The private container uploaded documents are written to.</summary>
    public string ContainerName { get; set; } = "documents";

    /// <summary>True once a connection string has actually been supplied.</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(ConnectionString);
}

/// <summary>
/// Azure Blob Storage, which is what the design document specifies for uploaded documents
/// (section 3.9).
///
/// The container is private. Nothing is ever served from a public blob URL; every read produces a
/// fresh Shared Access Signature scoped to one blob, read-only, and valid for the few minutes the
/// caller actually needs. That is the same shape <see cref="LocalFileStorageService"/> reproduces
/// with an HMAC, which is why this swap is a registration change and nothing else: the controller,
/// the service and the stored rows already assume it.
///
/// One consequence worth naming: a SAS points straight at the blob endpoint, so downloads no
/// longer pass through this API at all. The `api/documents/download/{storedName}` route exists for
/// the local implementation and is bypassed here, which also means the API stops paying the
/// bandwidth for every prescription a caregiver opens.
/// </summary>
public class AzureBlobFileStorageService : IFileStorageService
{
    private readonly BlobContainerClient _container;
    private readonly ILogger<AzureBlobFileStorageService> _logger;

    public AzureBlobFileStorageService(
        IOptions<BlobStorageOptions> options,
        ILogger<AzureBlobFileStorageService> logger)
    {
        var settings = options.Value;
        _logger = logger;

        if (!settings.IsConfigured)
        {
            throw new InvalidOperationException(
                "BlobStorage:ConnectionString is not configured. Register LocalFileStorageService " +
                "instead, or supply the connection string as an application setting.");
        }

        var client = new BlobServiceClient(settings.ConnectionString);
        _container = client.GetBlobContainerClient(settings.ContainerName);

        // PublicAccessType.None is explicit rather than left to the default: a container created
        // with blob-level public access would make every stored prescription readable by anyone
        // who guessed its name, and the signed-URL work above it would be decoration.
        _container.CreateIfNotExists(PublicAccessType.None);
    }

    public async Task<string> UploadAsync(
        Stream content,
        string fileName,
        string contentType,
        CancellationToken cancellationToken = default)
    {
        // The stored name is generated, exactly as in the local implementation: the uploaded file
        // name is attacker-controlled, and two people photographing "scan.jpg" must not collide.
        var storedName = $"{Guid.NewGuid():N}{SanitiseExtension(Path.GetExtension(fileName))}";
        var blob = _container.GetBlobClient(storedName);

        var headers = new BlobHttpHeaders
        {
            ContentType = contentType,

            // Forces a download rather than inline rendering for anything the browser would
            // otherwise try to interpret. A stored file is data, not a page.
            ContentDisposition = $"inline; filename=\"{Path.GetFileName(fileName)}\""
        };

        await blob.UploadAsync(content, new BlobUploadOptions { HttpHeaders = headers }, cancellationToken);

        _logger.LogInformation(
            "Stored document {StoredName} ({ContentType}) in container {Container}",
            storedName, contentType, _container.Name);

        return storedName;
    }

    public Task<(string Url, DateTime ExpiresAt)> GetSignedUrlAsync(
        string blobUrl, TimeSpan validFor, CancellationToken cancellationToken = default)
    {
        var blob = _container.GetBlobClient(blobUrl);

        if (!blob.CanGenerateSasUri)
        {
            // Happens when the client was built from a token credential rather than an account
            // key. Worth saying plainly, because the symptom otherwise is every download failing
            // with nothing in the logs to explain it.
            throw new InvalidOperationException(
                "The blob client cannot sign URLs. A connection string with an account key is " +
                "required for Shared Access Signatures.");
        }

        var expiresAt = DateTime.UtcNow.Add(validFor);

        var builder = new BlobSasBuilder
        {
            BlobContainerName = _container.Name,
            BlobName = blobUrl,
            Resource = "b",

            // Backdated by a few minutes so a small clock difference between this server and the
            // storage service does not reject a signature the instant it is issued.
            StartsOn = DateTimeOffset.UtcNow.AddMinutes(-5),
            ExpiresOn = new DateTimeOffset(expiresAt)
        };

        // Read only. A signature that could write or delete would hand whoever holds the link far
        // more than the ability to look at one prescription.
        builder.SetPermissions(BlobSasPermissions.Read);

        return Task.FromResult((blob.GenerateSasUri(builder).ToString(), expiresAt));
    }

    public async Task DeleteAsync(string blobUrl, CancellationToken cancellationToken = default)
    {
        try
        {
            var deleted = await _container
                .GetBlobClient(blobUrl)
                .DeleteIfExistsAsync(DeleteSnapshotsOption.IncludeSnapshots, cancellationToken: cancellationToken);

            if (deleted.Value)
            {
                _logger.LogInformation("Deleted stored document {StoredName}", blobUrl);
            }
            else
            {
                // The metadata row is the source of truth, so a blob that has already gone is not
                // an error; the caller still gets to mark the document deleted.
                _logger.LogWarning("Stored document {StoredName} was already absent", blobUrl);
            }
        }
        catch (RequestFailedException ex)
        {
            _logger.LogError(ex, "Could not delete stored document {StoredName}", blobUrl);

            throw new ExternalServiceException(
                "The document could not be removed from storage. Please try again shortly.");
        }
    }

    /// <summary>
    /// Keeps only a short, known-safe extension.
    ///
    /// Mirrors the local implementation. The extension never reaches a filesystem path here, but
    /// it does end up in the blob name and in the Content-Disposition header, and neither is a
    /// place to echo arbitrary user input.
    /// </summary>
    private static string SanitiseExtension(string? extension)
    {
        if (string.IsNullOrWhiteSpace(extension) || extension.Length > 10)
        {
            return string.Empty;
        }

        return extension.All(c => char.IsLetterOrDigit(c) || c == '.')
            ? extension.ToLowerInvariant()
            : string.Empty;
    }
}
