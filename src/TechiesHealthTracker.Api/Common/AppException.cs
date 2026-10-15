// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Handle Errors in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/error-handling [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using System.Net;

namespace TechiesHealthTracker.Api.Common;

/// <summary>
/// Base class for the errors the API raises deliberately. Services throw these instead of
/// returning status codes so that the business logic stays free of MVC types and can be unit
/// tested without an HTTP context; <see cref="Middleware.ExceptionHandlingMiddleware"/> turns
/// them into RFC 7807 problem responses at the edge.
/// </summary>
public abstract class AppException : Exception
{
    protected AppException(string message, HttpStatusCode statusCode) : base(message)
    {
        StatusCode = statusCode;
    }

    public HttpStatusCode StatusCode { get; }

    /// <summary>Short machine-readable code the Android client can branch on.</summary>
    public abstract string ErrorCode { get; }
}

/// <summary>The requested record does not exist, or has been soft-deleted.</summary>
public class NotFoundException : AppException
{
    public NotFoundException(string message) : base(message, HttpStatusCode.NotFound) { }

    public NotFoundException(string resource, Guid id)
        : base($"{resource} '{id}' was not found.", HttpStatusCode.NotFound) { }

    public override string ErrorCode => "not_found";
}

/// <summary>
/// The caller is authenticated but the record belongs to another caregiver. Raised by the
/// ownership checks that enforce Additional Feature 5 in the design document.
/// </summary>
public class ForbiddenException : AppException
{
    public ForbiddenException(string message = "You do not have access to this record.")
        : base(message, HttpStatusCode.Forbidden) { }

    public override string ErrorCode => "forbidden";
}

/// <summary>The request was understood but its contents break a business rule.</summary>
public class BusinessRuleException : AppException
{
    public BusinessRuleException(string message) : base(message, HttpStatusCode.BadRequest) { }

    public override string ErrorCode => "invalid_request";
}

/// <summary>
/// A concurrent update was rejected. The offline-sync path catches this and applies the
/// last-write-wins rule from section 3.5 rather than surfacing it to the client.
/// </summary>
public class ConcurrencyConflictException : AppException
{
    public ConcurrencyConflictException(string message = "The record was modified by another device.")
        : base(message, HttpStatusCode.Conflict) { }

    public override string ErrorCode => "concurrency_conflict";
}

/// <summary>An upstream service (GeoApify, Firebase, blob storage) failed or timed out.</summary>
public class ExternalServiceException : AppException
{
    public ExternalServiceException(string message) : base(message, HttpStatusCode.BadGateway) { }

    public override string ErrorCode => "upstream_unavailable";
}
