// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. HMACSHA256 Class. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/api/system.security.cryptography.hmacsha256
//    [Accessed 16 September 2026]. Used to sign time-limited download URLs.
//   Microsoft. 2024. Grant Limited Access to Data with Shared Access Signatures. [Online].
//    Available at: https://learn.microsoft.com/azure/storage/common/storage-sas-overview
//    [Accessed 16 September 2026]. Pattern this implementation stands in for until the POE.
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 3.9 and 6.7.
// ----------------------------------------------------------------------------------------------

using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Services.External;

/// <summary>Settings for <see cref="LocalFileStorageService"/>.</summary>
public class FileStorageOptions
{
    public const string SectionName = "FileStorage";

    /// <summary>Directory the uploaded files are written to, relative to the content root.</summary>
    public string RootPath { get; set; } = "App_Data/documents";

    /// <summary>
    /// Secret used to sign download URLs. It must be set to something unguessable before the API
    /// is exposed, otherwise a signed URL could be forged for any stored document.
    /// </summary>
    public string SigningKey { get; set; } = string.Empty;

    /// <summary>Public base URL the signed link is built against.</summary>
    public string PublicBaseUrl { get; set; } = string.Empty;
}

/// <summary>
/// Filesystem-backed document storage for the Part 2 prototype.
///
/// The design document targets Azure Blob Storage, which is provisioned in the POE phase. This
/// implementation sits behind <see cref="IFileStorageService"/> and reproduces the two properties
/// that actually matter to the rest of the system: files are addressed by an opaque internal path,
/// and downloads go through a time-limited signed URL rather than that path. Swapping in the Azure
/// implementation later is a one-line registration change in Program.cs.
/// </summary>
public class LocalFileStorageService : IFileStorageService
{
    private readonly FileStorageOptions _options;
    private readonly string _rootPath;
    private readonly ILogger<LocalFileStorageService> _logger;

    public LocalFileStorageService(
        IOptions<FileStorageOptions> options,
        IHostEnvironment environment,
        ILogger<LocalFileStorageService> logger)
    {
        _options = options.Value;
        _logger = logger;

        _rootPath = Path.IsPathRooted(_options.RootPath)
            ? _options.RootPath
            : Path.Combine(environment.ContentRootPath, _options.RootPath);

        Directory.CreateDirectory(_rootPath);
    }

    public async Task<string> UploadAsync(
        Stream content,
        string fileName,
        string contentType,
        CancellationToken cancellationToken = default)
    {
        // The stored name is generated rather than taken from the upload: the original file name
        // is attacker-controlled, and keeping it out of the path removes any traversal risk while
        // also stopping two prescriptions called scan.jpg from colliding.
        var extension = Path.GetExtension(fileName);
        var storedName = $"{Guid.NewGuid():N}{SanitiseExtension(extension)}";
        var fullPath = Path.Combine(_rootPath, storedName);

        await using (var target = File.Create(fullPath))
        {
            await content.CopyToAsync(target, cancellationToken);
        }

        _logger.LogInformation(
            "Stored document {StoredName} ({ContentType}) for original file {FileName}",
            storedName, contentType, fileName);

        return storedName;
    }

    public Task<(string Url, DateTime ExpiresAt)> GetSignedUrlAsync(
        string blobUrl, TimeSpan validFor, CancellationToken cancellationToken = default)
    {
        var expiresAt = DateTime.UtcNow.Add(validFor);
        var expiresUnix = new DateTimeOffset(expiresAt).ToUnixTimeSeconds();
        var signature = ComputeSignature(blobUrl, expiresUnix);

        var baseUrl = string.IsNullOrWhiteSpace(_options.PublicBaseUrl)
            ? string.Empty
            : _options.PublicBaseUrl.TrimEnd('/');

        var url = $"{baseUrl}/api/documents/download/{blobUrl}?expires={expiresUnix}&signature={signature}";

        return Task.FromResult((url, expiresAt));
    }

    public Task DeleteAsync(string blobUrl, CancellationToken cancellationToken = default)
    {
        var fullPath = ResolveStoredPath(blobUrl);

        if (File.Exists(fullPath))
        {
            File.Delete(fullPath);
            _logger.LogInformation("Deleted stored document {StoredName}", blobUrl);
        }
        else
        {
            // The metadata row is the source of truth, so a file that has already gone is not an
            // error; the caller still gets to mark the document deleted.
            _logger.LogWarning("Stored document {StoredName} was already absent", blobUrl);
        }

        return Task.CompletedTask;
    }

    /// <summary>
    /// Validates a signed URL and returns the file path it points at. Checks the signature before
    /// the expiry so that a forged link is refused outright rather than leaking whether the token
    /// would otherwise have been valid.
    /// </summary>
    public string ResolveSignedRequest(string blobUrl, long expiresUnix, string signature)
    {
        var expected = ComputeSignature(blobUrl, expiresUnix);

        // Fixed-time comparison: a plain string equality check leaks, through its timing, how much
        // of a guessed signature was correct.
        if (!CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(signature)))
        {
            throw new ForbiddenException("This download link is not valid.");
        }

        if (DateTimeOffset.FromUnixTimeSeconds(expiresUnix).UtcDateTime < DateTime.UtcNow)
        {
            throw new ForbiddenException("This download link has expired. Request the document again.");
        }

        var fullPath = ResolveStoredPath(blobUrl);

        if (!File.Exists(fullPath))
        {
            throw new NotFoundException("The stored file could not be found.");
        }

        return fullPath;
    }

    private string ComputeSignature(string blobUrl, long expiresUnix)
    {
        if (string.IsNullOrWhiteSpace(_options.SigningKey))
        {
            throw new InvalidOperationException(
                "FileStorage:SigningKey is not configured, so download links cannot be signed.");
        }

        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(_options.SigningKey));
        var payload = Encoding.UTF8.GetBytes($"{blobUrl}:{expiresUnix}");

        return Convert.ToHexString(hmac.ComputeHash(payload)).ToLowerInvariant();
    }

    /// <summary>
    /// Maps a stored name onto a path, refusing anything that is not a bare file name. Upload
    /// generates these names, but this method is also reached from a signed download request, so
    /// the value is treated as untrusted.
    /// </summary>
    private string ResolveStoredPath(string blobUrl)
    {
        if (string.IsNullOrWhiteSpace(blobUrl)
            || blobUrl.Contains("..", StringComparison.Ordinal)
            || Path.GetFileName(blobUrl) != blobUrl)
        {
            throw new ForbiddenException("This download link is not valid.");
        }

        var fullPath = Path.GetFullPath(Path.Combine(_rootPath, blobUrl));

        // Belt and braces: even after the checks above, confirm the resolved path has not escaped
        // the storage directory.
        if (!fullPath.StartsWith(Path.GetFullPath(_rootPath), StringComparison.OrdinalIgnoreCase))
        {
            throw new ForbiddenException("This download link is not valid.");
        }

        return fullPath;
    }

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
