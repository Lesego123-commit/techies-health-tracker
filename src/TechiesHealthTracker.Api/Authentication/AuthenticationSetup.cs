// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Configure JWT Bearer Authentication in ASP.NET Core. [Online]. Available
//    at: https://learn.microsoft.com/aspnet/core/security/authentication/configure-jwt-bearer-authentication
//    [Accessed 16 September 2026].
//   Google. 2024. Verify ID Tokens — Firebase Authentication. [Online]. Available at:
//    https://firebase.google.com/docs/auth/admin/verify-id-tokens [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.1 (Requirement 1: SSO).
// ----------------------------------------------------------------------------------------------

using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;

namespace TechiesHealthTracker.Api.Authentication;

/// <summary>
/// Wires up Firebase identity-token validation (Requirement 1 of the design document).
/// </summary>
public static class AuthenticationSetup
{
    public static IServiceCollection AddFirebaseAuthentication(
        this IServiceCollection services,
        IConfiguration configuration,
        IHostEnvironment environment)
    {
        var options = configuration.GetSection(FirebaseAuthOptions.SectionName).Get<FirebaseAuthOptions>()
                      ?? new FirebaseAuthOptions();

        services.Configure<FirebaseAuthOptions>(configuration.GetSection(FirebaseAuthOptions.SectionName));

        // The development token path exists so the API can be run and tested without a live
        // Firebase project. It is refused outright in Production regardless of configuration,
        // because a symmetric key in appsettings would otherwise be a way in.
        var useDevelopmentTokens = options.UseDevelopmentTokens && !environment.IsProduction();

        services
            .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(jwt =>
            {
                if (useDevelopmentTokens)
                {
                    var key = options.DevelopmentSigningKey;

                    if (string.IsNullOrWhiteSpace(key) || key.Length < 32)
                    {
                        throw new InvalidOperationException(
                            "Firebase:DevelopmentSigningKey must be at least 32 characters when " +
                            "Firebase:UseDevelopmentTokens is enabled.");
                    }

                    jwt.TokenValidationParameters = new TokenValidationParameters
                    {
                        ValidateIssuer = true,
                        ValidIssuer = options.Issuer,
                        ValidateAudience = true,
                        ValidAudience = options.ProjectId,
                        ValidateLifetime = true,
                        ValidateIssuerSigningKey = true,
                        IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(key)),
                        ClockSkew = TimeSpan.FromMinutes(1)
                    };

                    return;
                }

                // Production path. Setting Authority lets the handler fetch and cache Google's
                // public signing keys itself and rotate them when Google does, which is why the
                // Firebase Admin SDK is not needed just to authenticate a request.
                jwt.Authority = options.Issuer;
                jwt.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidIssuer = options.Issuer,
                    ValidateAudience = true,
                    ValidAudience = options.ProjectId,
                    ValidateLifetime = true,
                    ValidateIssuerSigningKey = true,
                    ClockSkew = TimeSpan.FromMinutes(1)
                };

                jwt.Events = new JwtBearerEvents
                {
                    OnAuthenticationFailed = context =>
                    {
                        var logger = context.HttpContext.RequestServices
                            .GetRequiredService<ILoggerFactory>()
                            .CreateLogger("Firebase.Auth");

                        // Logged at warning, not error: an expired token is routine, and the
                        // client is expected to refresh and retry.
                        logger.LogWarning(
                            "Firebase token rejected for {Path}: {Reason}",
                            context.HttpContext.Request.Path,
                            context.Exception.Message);

                        return Task.CompletedTask;
                    }
                };
            });

        services.AddAuthorization();
        return services;
    }
}
