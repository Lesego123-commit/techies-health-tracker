// ----------------------------------------------------------------------------------------------
//  References
//   .NET Foundation. 2024. Unit Testing in .NET with xUnit. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/core/testing/unit-testing-with-dotnet-test
//    [Accessed 16 September 2026].
//   Fluent Assertions. 2024. Documentation. [Online]. Available at:
//    https://fluentassertions.com/introduction [Accessed 16 September 2026].
//   Moq. 2024. Quickstart. [Online]. Available at:
//    https://github.com/devlooped/moq/wiki/Quickstart [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using System.Net;
using System.Text.Json;
using FluentAssertions;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Integration;

/// <summary>
/// Checks that the OpenAPI document generates and describes the endpoint specification from
/// section 5.4 of the design document.
///
/// This is worth a test rather than a manual glance: Swagger generation fails at request time,
/// not at build time, so a controller signature Swashbuckle cannot describe compiles perfectly
/// and only breaks the page the Android side relies on. That happened once already, with the
/// multipart document upload.
/// </summary>
public class SwaggerDocumentTests : IDisposable
{
    private readonly ApiFactory _factory;
    private readonly HttpClient _client;

    public SwaggerDocumentTests()
    {
        // Swagger is only mapped outside Production, so the factory's Testing environment is fine.
        _factory = new ApiFactory { IsAuthenticated = false };
        _client = _factory.CreateClient();
    }

    public void Dispose()
    {
        _client.Dispose();
        _factory.Dispose();
    }

    private async Task<JsonElement> GetPathsAsync()
    {
        var response = await _client.GetAsync("/swagger/v1/swagger.json");

        response.StatusCode.Should().Be(
            HttpStatusCode.OK,
            "the OpenAPI document must generate, or the Android side has no current API reference");

        var json = await response.Content.ReadAsStringAsync();
        return JsonDocument.Parse(json).RootElement.GetProperty("paths");
    }

    [Fact]
    public async Task SwaggerDocument_Generates()
    {
        var paths = await GetPathsAsync();

        paths.EnumerateObject().Should().NotBeEmpty();
    }

    [Theory]
    // Authentication and users (section 5.4.1)
    [InlineData("/api/auth/login")]
    [InlineData("/api/users/{userId}/settings")]
    // Dependants (section 5.4.2)
    [InlineData("/api/dependants")]
    [InlineData("/api/dependants/{dependantId}")]
    // Medications and schedules (sections 5.4.3 and 5.4.4)
    [InlineData("/api/dependants/{dependantId}/medications")]
    [InlineData("/api/medications/{medicationId}")]
    [InlineData("/api/medications/{medicationId}/schedules")]
    // Dose logs and adherence (section 5.4.5)
    [InlineData("/api/doselogs")]
    [InlineData("/api/dependants/{dependantId}/doselogs")]
    [InlineData("/api/dependants/{dependantId}/adherence")]
    // Refill forecast (section 5.4.6)
    [InlineData("/api/medications/{medicationId}/refill-forecast")]
    // Appointments (section 5.4.7)
    [InlineData("/api/dependants/{dependantId}/appointments")]
    [InlineData("/api/appointments/{appointmentId}")]
    // Pharmacy finder (section 5.4.8)
    [InlineData("/api/pharmacies/nearby")]
    // Documents (section 5.4.9)
    [InlineData("/api/dependants/{dependantId}/documents")]
    [InlineData("/api/documents/{documentId}")]
    // Synchronisation (section 5.4.10)
    [InlineData("/api/sync/batch")]
    public async Task SwaggerDocument_DescribesEveryEndpointFromTheDesignDocument(string path)
    {
        // One case per endpoint group in section 5.4, so a route that goes missing during a
        // refactor is reported by name rather than as a single opaque count mismatch.
        var paths = await GetPathsAsync();

        paths.TryGetProperty(path, out _).Should().BeTrue($"the API must expose {path}");
    }

    [Fact]
    public async Task SwaggerDocument_DescribesTheMultipartDocumentUpload()
    {
        var paths = await GetPathsAsync();

        var upload = paths
            .GetProperty("/api/dependants/{dependantId}/documents")
            .GetProperty("post")
            .GetProperty("requestBody")
            .GetProperty("content");

        upload.TryGetProperty("multipart/form-data", out _).Should().BeTrue();
    }

    [Fact]
    public async Task SwaggerDocument_DeclaresTheBearerSecurityScheme()
    {
        var response = await _client.GetAsync("/swagger/v1/swagger.json");
        var json = await response.Content.ReadAsStringAsync();

        using var document = JsonDocument.Parse(json);

        // Without this, the Swagger page offers no way to paste a Firebase token and every
        // protected endpoint appears broken when tried from the browser.
        document.RootElement
            .GetProperty("components")
            .GetProperty("securitySchemes")
            .TryGetProperty("Bearer", out _)
            .Should().BeTrue();
    }
}
