// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Configure JWT Bearer Authentication in ASP.NET Core. [Online]. Available
//    at: https://learn.microsoft.com/aspnet/core/security/authentication/configure-jwt-bearer-authentication
//    [Accessed 16 September 2026].
//   Google. 2024. Verify ID Tokens — Firebase Authentication. [Online]. Available at:
//    https://firebase.google.com/docs/auth/admin/verify-id-tokens [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.1 (Requirement 1: SSO).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Authentication;

/// <summary>
/// Firebase settings bound from the Firebase section of appsettings.json. Only the project id is
/// needed to validate an identity token: the issuer, audience and signing keys all derive from it.
/// </summary>
public class FirebaseAuthOptions
{
    public const string SectionName = "Firebase";

    /// <summary>The Firebase project id, e.g. techies-health-tracker.</summary>
    public string ProjectId { get; set; } = string.Empty;

    /// <summary>
    /// Path to the service-account JSON used by the Admin SDK to send Cloud Messaging pushes.
    /// Left empty during Part 2, where notifications are recorded but not yet dispatched.
    /// </summary>
    public string? ServiceAccountPath { get; set; }

    /// <summary>
    /// Bypasses Firebase and accepts a locally signed development token instead. Guarded so it
    /// can only ever be switched on outside Production; see Program.cs.
    /// </summary>
    public bool UseDevelopmentTokens { get; set; }

    /// <summary>Symmetric signing key for development tokens. Ignored unless UseDevelopmentTokens is true.</summary>
    public string? DevelopmentSigningKey { get; set; }

    public string Issuer => $"https://securetoken.google.com/{ProjectId}";
}
