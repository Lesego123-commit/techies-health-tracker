// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Integration Tests in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/test/integration-tests [Accessed 16 September 2026].
//   Microsoft. 2024. Testing with the EF Core In-Memory Database Provider. [Online]. Available
//    at: https://learn.microsoft.com/ef/core/testing/testing-without-the-database
//    [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Tests.TestSupport;

/// <summary>
/// Boots the real API in memory for the integration tests.
///
/// Only two things are swapped out: PostgreSQL becomes an in-memory store, and Firebase token
/// validation becomes a test authentication handler. Everything else — routing, model binding,
/// the exception middleware, the authorisation policies and every service — is the production
/// wiring, which is the point: these tests prove the pipeline itself works, not just the services
/// underneath it.
/// </summary>
public class ApiFactory : WebApplicationFactory<Program>
{
    /// <summary>The Firebase UID the test handler signs requests in as, when authenticated.</summary>
    public const string TestFirebaseUid = "integration-test-user";
    public const string TestEmail = "integration@example.com";
    public const string TestDisplayName = "Integration Test User";

    private readonly string _databaseName = $"api-tests-{Guid.NewGuid()}";

    /// <summary>
    /// When false, requests are left unauthenticated so that the tests can check an endpoint
    /// really is protected rather than assuming it.
    /// </summary>
    public bool IsAuthenticated { get; set; } = true;

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");

        builder.ConfigureAppConfiguration((_, config) =>
        {
            config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                // A connection string is required at startup, but the provider below replaces the
                // real one, so nothing ever connects to it.
                ["ConnectionStrings:Default"] = "Host=localhost;Database=unused",
                ["Database:ApplyMigrationsOnStartup"] = "false",

                // The background sweep would otherwise write notification rows underneath the
                // assertions and make the suite flaky.
                ["ReminderScan:Enabled"] = "false",

                ["FileStorage:SigningKey"] = "integration-test-signing-key-at-least-32-chars",
                ["FileStorage:RootPath"] = Path.Combine(Path.GetTempPath(), "techies-tests", Guid.NewGuid().ToString("N")),
                ["Firebase:ProjectId"] = "techies-health-tracker-test"
            });
        });

        builder.ConfigureServices(services =>
        {
            // ---- Swap PostgreSQL for an in-memory store ----
            services.RemoveAll<DbContextOptions<AppDbContext>>();
            services.RemoveAll<AppDbContext>();

            services.AddDbContext<AppDbContext>(options => options
                .UseInMemoryDatabase(_databaseName)
                .ConfigureWarnings(w => w.Ignore(
                    Microsoft.EntityFrameworkCore.Diagnostics.InMemoryEventId.TransactionIgnoredWarning)));

            // ---- Swap Firebase token validation for a test handler ----
            services.AddAuthentication(TestAuthHandler.SchemeName)
                .AddScheme<AuthenticationSchemeOptions, TestAuthHandler>(TestAuthHandler.SchemeName, _ => { });

            services.AddSingleton(this);

            // The hosted reminder sweep is removed outright as well as disabled, so it cannot
            // start even if the configuration above is overridden.
            services.RemoveAll<IHostedService>();
        });

        builder.ConfigureLogging(logging => logging.SetMinimumLevel(LogLevel.Warning));
    }

    /// <summary>Gives a test direct access to the same in-memory store the API is using.</summary>
    public AppDbContext CreateDbContext()
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase(_databaseName)
            .ConfigureWarnings(w => w.Ignore(
                Microsoft.EntityFrameworkCore.Diagnostics.InMemoryEventId.TransactionIgnoredWarning))
            .Options;

        return new AppDbContext(options);
    }
}

/// <summary>
/// Signs requests in as a fixed test user, standing in for a validated Firebase identity token.
/// The claims it issues are exactly the ones <see cref="ICurrentUserService"/> reads, so the
/// production code path is unchanged.
/// </summary>
public class TestAuthHandler : AuthenticationHandler<AuthenticationSchemeOptions>
{
    public const string SchemeName = "TestScheme";

    private readonly ApiFactory _factory;

    public TestAuthHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder,
        ApiFactory factory)
        : base(options, logger, encoder)
    {
        _factory = factory;
    }

    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        if (!_factory.IsAuthenticated)
        {
            return Task.FromResult(AuthenticateResult.Fail("No token supplied."));
        }

        var claims = new[]
        {
            new Claim("user_id", ApiFactory.TestFirebaseUid),
            new Claim(ClaimTypes.NameIdentifier, ApiFactory.TestFirebaseUid),
            new Claim(ClaimTypes.Email, ApiFactory.TestEmail),
            new Claim("name", ApiFactory.TestDisplayName)
        };

        var identity = new ClaimsIdentity(claims, SchemeName);
        var ticket = new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName);

        return Task.FromResult(AuthenticateResult.Success(ticket));
    }
}
