// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. ASP.NET Core Fundamentals Overview. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/ [Accessed 16 September 2026].
//   SmartBear. 2024. Swashbuckle.AspNetCore — Getting Started. [Online]. Available at:
//    https://github.com/domaindrivendev/Swashbuckle.AspNetCore [Accessed 16 September 2026].
//   Serilog Contributors. 2024. Serilog.AspNetCore. [Online]. Available at:
//    https://github.com/serilog/serilog-aspnetcore [Accessed 16 September 2026].
//   Npgsql Development Team. 2024. Npgsql Entity Framework Core Provider. [Online]. Available
//    at: https://www.npgsql.org/efcore/ [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 5.1, 5.2 and 5.3.
// ----------------------------------------------------------------------------------------------

using System.Reflection;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.OpenApi.Models;
using Serilog;
using TechiesHealthTracker.Api.Authentication;
using TechiesHealthTracker.Api.BackgroundServices;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Middleware;
using TechiesHealthTracker.Api.Services;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.External;
using TechiesHealthTracker.Api.Services.Implementations;

// The composition root for the Techies Health Tracker API. The layering set out in section 5.1 of
// the Part 1 design document — controllers, services, repositories, external integrations — is
// wired up here and nowhere else.

var builder = WebApplication.CreateBuilder(args);

// ---- Logging (Serilog, per the development stack in section 5.2) ----
builder.Host.UseSerilog((context, services, configuration) => configuration
    .ReadFrom.Configuration(context.Configuration)
    .ReadFrom.Services(services)
    .Enrich.FromLogContext()
    .WriteTo.Console()
    .WriteTo.File(
        Path.Combine(AppContext.BaseDirectory, "logs", "api-.log"),
        rollingInterval: RollingInterval.Day,
        retainedFileCountLimit: 14));

// ---- Configuration binding ----
builder.Services.Configure<SchedulingOptions>(
    builder.Configuration.GetSection(SchedulingOptions.SectionName));
builder.Services.Configure<GeoApifyOptions>(
    builder.Configuration.GetSection(GeoApifyOptions.SectionName));
builder.Services.Configure<FileStorageOptions>(
    builder.Configuration.GetSection(FileStorageOptions.SectionName));
builder.Services.Configure<ReminderScanOptions>(
    builder.Configuration.GetSection(ReminderScanOptions.SectionName));

// ---- Data access ----
var connectionString = builder.Configuration.GetConnectionString("Default");

builder.Services.AddDbContext<AppDbContext>(options =>
{
    if (string.IsNullOrWhiteSpace(connectionString))
    {
        // Failing loudly at startup beats a working API that silently has no database behind it.
        throw new InvalidOperationException(
            "ConnectionStrings:Default is not configured. Set it in appsettings, user secrets, "
            + "or the App Service configuration before starting the API.");
    }

    options.UseNpgsql(connectionString, npgsql => npgsql.EnableRetryOnFailure(
        maxRetryCount: 3,
        maxRetryDelay: TimeSpan.FromSeconds(5),

        // Azure Database for PostgreSQL drops idle connections during maintenance, so transient
        // failures are retried rather than surfaced to the handset as a 500.
        errorCodesToAdd: null));

    if (builder.Environment.IsDevelopment())
    {
        options.EnableDetailedErrors();
        options.EnableSensitiveDataLogging();
    }
});

builder.Services.AddScoped<IUnitOfWork, UnitOfWork>();

// ---- Authentication and authorisation (Requirement 1) ----
builder.Services.AddHttpContextAccessor();
builder.Services.AddFirebaseAuthentication(builder.Configuration, builder.Environment);
builder.Services.AddScoped<ICurrentUserService, CurrentUserService>();
builder.Services.AddScoped<IAccessGuard, AccessGuard>();

// ---- Service layer ----
builder.Services.AddSingleton<IDateTimeProvider, SystemDateTimeProvider>();
builder.Services.AddScoped<IAuthService, AuthService>();
builder.Services.AddScoped<IUserSettingsService, UserSettingsService>();
builder.Services.AddScoped<IDependantService, DependantService>();
builder.Services.AddScoped<IMedicationService, MedicationService>();
builder.Services.AddScoped<IDoseScheduleService, DoseScheduleService>();
builder.Services.AddScoped<IDoseLogService, DoseLogService>();
builder.Services.AddScoped<IAdherenceService, AdherenceService>();
builder.Services.AddScoped<IRefillForecastService, RefillForecastService>();
builder.Services.AddScoped<IAppointmentService, AppointmentService>();
builder.Services.AddScoped<IDocumentService, DocumentService>();
builder.Services.AddScoped<IPharmacyService, PharmacyService>();
builder.Services.AddScoped<ISyncService, SyncService>();
builder.Services.AddScoped<INotificationService, NotificationService>();

// ---- External integrations ----

// Document storage is chosen at startup from whether Azure Blob Storage is actually configured,
// the same way push delivery is below. The design document specifies Blob Storage (section 3.9);
// the filesystem implementation behind the same interface is what lets a teammate run the API,
// and the whole test suite, without an Azure subscription.
builder.Services.Configure<BlobStorageOptions>(
    builder.Configuration.GetSection(BlobStorageOptions.SectionName));

var blobStorageConfigured = !string.IsNullOrWhiteSpace(
    builder.Configuration[$"{BlobStorageOptions.SectionName}:ConnectionString"]);

if (blobStorageConfigured)
{
    builder.Services.AddScoped<IFileStorageService, AzureBlobFileStorageService>();
}
else
{
    builder.Services.AddScoped<IFileStorageService, LocalFileStorageService>();
}

// Push delivery is chosen at startup, once, from whether a Firebase service account is actually
// configured. Deciding per request would mean re-reading credentials on every notification, and
// deciding at compile time would mean a developer without a service account could not run the
// API at all. Without credentials the record-only sender keeps the notification history complete
// and only the delivery step is missing.
var firebaseReady = FirebaseAdminSetup.TryInitialise(
    builder.Configuration,
    LoggerFactory.Create(logging => logging.AddConsole()).CreateLogger("FirebaseAdminSetup"));

if (firebaseReady)
{
    builder.Services.AddScoped<IPushNotificationSender, FirebasePushNotificationSender>();
}
else
{
    builder.Services.AddScoped<IPushNotificationSender, RecordOnlyPushNotificationSender>();
}

builder.Services.AddHttpClient<IGeoApifyClient, GeoApifyClient>((provider, client) =>
{
    var options = builder.Configuration
        .GetSection(GeoApifyOptions.SectionName)
        .Get<GeoApifyOptions>() ?? new GeoApifyOptions();

    client.BaseAddress = new Uri(options.BaseUrl);
    client.Timeout = TimeSpan.FromSeconds(options.TimeoutSeconds);
});

// ---- Background jobs (section 5.2) ----
builder.Services.AddHostedService<ReminderScanService>();

// ---- MVC and API behaviour ----
builder.Services.AddControllers();

builder.Services.Configure<ApiBehaviorOptions>(options =>
{
    // Model-binding failures are reshaped to match the error body produced by
    // ExceptionHandlingMiddleware, so the Android client has one error format to parse.
    options.InvalidModelStateResponseFactory = context =>
    {
        var errors = context.ModelState
            .Where(entry => entry.Value?.Errors.Count > 0)
            .ToDictionary(
                entry => entry.Key,
                entry => entry.Value!.Errors.Select(e => e.ErrorMessage).ToArray());

        return new BadRequestObjectResult(new
        {
            error = "validation_failed",
            message = "One or more fields are invalid.",
            status = StatusCodes.Status400BadRequest,
            traceId = context.HttpContext.TraceIdentifier,
            errors
        });
    };
});

// ---- Swagger / OpenAPI (section 5.2) ----
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new OpenApiInfo
    {
        Title = "Techies Health Tracker API",
        Version = "v1",
        Description =
            "REST API for the Techies Health Tracker Android application (PROG7314 POE Part 2). "
            + "Implements the endpoint specification in section 5.4 of the Part 1 Planning and "
            + "Design document. All endpoints require a Firebase identity token unless marked otherwise."
    });

    options.AddSecurityDefinition("Bearer", new OpenApiSecurityScheme
    {
        Name = "Authorization",
        Type = SecuritySchemeType.Http,
        Scheme = "bearer",
        BearerFormat = "JWT",
        In = ParameterLocation.Header,
        Description = "Paste the Firebase identity token. Swagger adds the Bearer prefix itself."
    });

    options.AddSecurityRequirement(new OpenApiSecurityRequirement
    {
        {
            new OpenApiSecurityScheme
            {
                Reference = new OpenApiReference
                {
                    Type = ReferenceType.SecurityScheme,
                    Id = "Bearer"
                }
            },
            Array.Empty<string>()
        }
    });

    // The XML comments on the controllers become the endpoint descriptions in Swagger, so the
    // Android side always has a current reference to code against.
    var xmlFile = $"{Assembly.GetExecutingAssembly().GetName().Name}.xml";
    var xmlPath = Path.Combine(AppContext.BaseDirectory, xmlFile);

    if (File.Exists(xmlPath))
    {
        options.IncludeXmlComments(xmlPath);
    }
});

// ---- CORS ----
const string CorsPolicy = "TechiesCors";

builder.Services.AddCors(options => options.AddPolicy(CorsPolicy, policy =>
{
    var allowedOrigins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>();

    // The Android client is not a browser and is unaffected by CORS. This policy exists for the
    // Swagger page and any future web dashboard, so it stays closed unless origins are configured.
    if (allowedOrigins is { Length: > 0 })
    {
        policy.WithOrigins(allowedOrigins).AllowAnyHeader().AllowAnyMethod();
    }
}));

var app = builder.Build();

// ---- Pipeline ----
// The exception handler goes first so that it wraps everything downstream of it.
app.UseMiddleware<ExceptionHandlingMiddleware>();

app.UseSerilogRequestLogging();

// Swagger is served outside Production by default. It can also be switched on for a deployed
// environment through Swagger:Enabled, because the design document treats the generated
// specification as the contract the Android side codes against, and it is what the demonstration
// video shows the API through.
if (app.Configuration.GetValue("Swagger:Enabled", !app.Environment.IsProduction()))
{
    app.UseSwagger();
    app.UseSwaggerUI(options =>
    {
        options.SwaggerEndpoint("/swagger/v1/swagger.json", "Techies Health Tracker API v1");
        options.DocumentTitle = "Techies Health Tracker API";
    });
}

// HSTS and the HTTPS redirect apply only to the deployed API. On a dev machine HSTS would pin a
// certificate that does not exist, and under the in-memory test host there is no HTTPS port for
// the redirect to point at.
if (app.Environment.IsProduction())
{
    app.UseHsts();
    app.UseHttpsRedirection();
}

app.UseCors(CorsPolicy);
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();

await ApplyMigrationsAsync(app);

app.Logger.LogInformation(
    "Techies Health Tracker API started in {Environment}", app.Environment.EnvironmentName);

app.Run();

// Applies any outstanding EF Core migrations at startup so a fresh App Service deployment comes
// up against a correctly shaped database without a manual step. Failing here is fatal on purpose:
// an API running against a stale schema would corrupt data rather than simply error.
static async Task ApplyMigrationsAsync(WebApplication app)
{
    if (!app.Configuration.GetValue("Database:ApplyMigrationsOnStartup", true))
    {
        return;
    }

    using var scope = app.Services.CreateScope();
    var context = scope.ServiceProvider.GetRequiredService<AppDbContext>();

    try
    {
        var pending = (await context.Database.GetPendingMigrationsAsync()).ToList();

        if (pending.Count == 0)
        {
            return;
        }

        app.Logger.LogInformation("Applying {Count} pending migration(s)", pending.Count);
        await context.Database.MigrateAsync();
        app.Logger.LogInformation("Migrations applied");
    }
    catch (Exception ex)
    {
        app.Logger.LogCritical(ex, "Could not apply database migrations; the API will not start");
        throw;
    }
}

/// <summary>
/// Exposed so the integration tests can reach this assembly through WebApplicationFactory, which
/// a top-level-statements program does not otherwise provide an entry point for.
/// </summary>
public partial class Program;
