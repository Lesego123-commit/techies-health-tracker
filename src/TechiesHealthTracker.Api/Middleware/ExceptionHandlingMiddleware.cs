// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Write Custom ASP.NET Core Middleware. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/middleware/write [Accessed 16 September 2026].
//   Internet Engineering Task Force. 2016. RFC 7807: Problem Details for HTTP APIs. [Online].
//    Available at: https://www.rfc-editor.org/rfc/rfc7807 [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using System.Text.Json;
using TechiesHealthTracker.Api.Common;

namespace TechiesHealthTracker.Api.Middleware;

/// <summary>
/// Converts every unhandled exception into a consistent JSON error body so the Android client
/// never has to parse an HTML error page. Expected failures (<see cref="AppException"/>) are
/// logged at warning level with their status code; anything else is logged as an error and
/// reported as a generic 500, so internal details never leak to the device.
/// </summary>
public class ExceptionHandlingMiddleware
{
    private readonly RequestDelegate _next;
    private readonly ILogger<ExceptionHandlingMiddleware> _logger;
    private readonly IHostEnvironment _environment;

    public ExceptionHandlingMiddleware(
        RequestDelegate next,
        ILogger<ExceptionHandlingMiddleware> logger,
        IHostEnvironment environment)
    {
        _next = next;
        _logger = logger;
        _environment = environment;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        try
        {
            await _next(context);
        }
        catch (AppException ex)
        {
            _logger.LogWarning(
                "Handled {ErrorCode} on {Method} {Path}: {Message}",
                ex.ErrorCode, context.Request.Method, context.Request.Path, ex.Message);

            await WriteAsync(context, (int)ex.StatusCode, ex.ErrorCode, ex.Message);
        }
        catch (Exception ex)
        {
            _logger.LogError(
                ex, "Unhandled exception on {Method} {Path}", context.Request.Method, context.Request.Path);

            var detail = _environment.IsDevelopment()
                ? ex.Message
                : "An unexpected error occurred. Please try again.";

            await WriteAsync(context, StatusCodes.Status500InternalServerError, "server_error", detail);
        }
    }

    private static async Task WriteAsync(HttpContext context, int statusCode, string errorCode, string message)
    {
        // Something further down the pipeline may already have started writing; overwriting the
        // headers at that point would throw a second, more confusing exception.
        if (context.Response.HasStarted)
        {
            return;
        }

        context.Response.Clear();
        context.Response.StatusCode = statusCode;
        context.Response.ContentType = "application/json";

        var payload = new
        {
            error = errorCode,
            message,
            status = statusCode,
            traceId = context.TraceIdentifier
        };

        await context.Response.WriteAsync(JsonSerializer.Serialize(payload));
    }
}
