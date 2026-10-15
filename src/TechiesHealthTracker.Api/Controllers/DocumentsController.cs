// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Routing to Controller Actions in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/mvc/controllers/routing [Accessed 16 September 2026].
//   Microsoft. 2024. Create Web APIs with ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/web-api/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.4 (Endpoint Specification).
// ----------------------------------------------------------------------------------------------

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.External;
using TechiesHealthTracker.Api.Services.Implementations;

namespace TechiesHealthTracker.Api.Controllers;

/// <summary>
/// Document endpoints (section 5.4.9), backing Additional Feature 2. Uploads and per-dependant
/// listing are nested under a dependant; reads and deletes are addressed by document id.
/// </summary>
[ApiController]
[Route("api")]
[Authorize]
public class DocumentsController : ControllerBase
{
    private readonly IDocumentService _documentService;
    private readonly ILogger<DocumentsController> _logger;

    public DocumentsController(IDocumentService documentService, ILogger<DocumentsController> logger)
    {
        _documentService = documentService;
        _logger = logger;
    }

    /// <summary>
    /// Uploads a prescription photo or lab result against a dependant. The response carries a
    /// time-limited signed download URL; the internal storage path is never returned.
    /// </summary>
    [HttpPost("dependants/{dependantId:guid}/documents")]
    [RequestSizeLimit(DocumentService.MaximumFileSizeBytes)]
    [ProducesResponseType(typeof(DocumentResponse), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [Produces("application/json")]
    [Consumes("multipart/form-data")]
    public async Task<ActionResult<DocumentResponse>> Upload(
        Guid dependantId,
        [FromForm] UploadDocumentRequest request,
        CancellationToken cancellationToken)
    {
        if (request.File is null || request.File.Length == 0)
        {
            throw new BusinessRuleException("No file was supplied.");
        }

        await using var stream = request.File.OpenReadStream();

        var document = await _documentService.UploadAsync(
            dependantId,
            stream,
            request.File.FileName,
            request.File.ContentType,
            request.File.Length,
            request.Title,
            request.DocumentType,
            cancellationToken);

        return CreatedAtAction(nameof(Get), new { documentId = document.DocumentId }, document);
    }

    /// <summary>Lists the documents held for a dependant, newest first.</summary>
    [HttpGet("dependants/{dependantId:guid}/documents")]
    [ProducesResponseType(typeof(IReadOnlyList<DocumentResponse>), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [Produces("application/json")]
    public async Task<ActionResult<IReadOnlyList<DocumentResponse>>> ListForDependant(
        Guid dependantId, CancellationToken cancellationToken)
        => Ok(await _documentService.ListForDependantAsync(dependantId, cancellationToken));

    /// <summary>Returns a document's metadata with a freshly signed download URL.</summary>
    [HttpGet("documents/{documentId:guid}")]
    [ProducesResponseType(typeof(DocumentResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [Produces("application/json")]
    public async Task<ActionResult<DocumentResponse>> Get(
        Guid documentId, CancellationToken cancellationToken)
        => Ok(await _documentService.GetAsync(documentId, cancellationToken));

    /// <summary>Soft-deletes a document and removes the stored file.</summary>
    [HttpDelete("documents/{documentId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid documentId, CancellationToken cancellationToken)
    {
        await _documentService.DeleteAsync(documentId, cancellationToken);
        return NoContent();
    }

    /// <summary>
    /// Serves the file behind a signed URL.
    ///
    /// This is the one endpoint that is deliberately anonymous: the signature and expiry in the
    /// query string are the authorisation, which is what lets an image load in a browser or an
    /// Android image component that cannot attach a bearer token. The signature is verified in
    /// fixed time and the link is short-lived, so an expired or forged one yields nothing.
    /// </summary>
    [HttpGet("documents/download/{storedName}")]
    [AllowAnonymous]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult Download(
        string storedName,
        [FromQuery] long expires,
        [FromQuery] string signature,
        [FromServices] IFileStorageService fileStorage)
    {
        // Only the local implementation can serve bytes directly. Once Azure Blob Storage is in
        // place at POE, its signed URL points at the blob endpoint and this route is bypassed.
        if (fileStorage is not LocalFileStorageService local)
        {
            return NotFound();
        }

        var path = local.ResolveSignedRequest(storedName, expires, signature);

        _logger.LogInformation("Serving stored document {StoredName} through a signed link", storedName);

        return PhysicalFile(path, "application/octet-stream", enableRangeProcessing: true);
    }
}
