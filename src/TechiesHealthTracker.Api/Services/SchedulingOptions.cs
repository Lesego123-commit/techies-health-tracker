// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Services;

/// <summary>
/// Settings for the dose-scheduling engine, bound from the Scheduling section of appsettings.json.
/// </summary>
public class SchedulingOptions
{
    public const string SectionName = "Scheduling";

    /// <summary>
    /// The time zone the HH:mm values in DoseSchedules.Times are expressed in. Schedule times are
    /// wall-clock times to the caregiver ("08:00 with breakfast"), so they are stored as local
    /// times and converted to UTC only when a concrete instant is needed. The app is aimed at
    /// South African users, hence the default.
    /// </summary>
    public string TimeZoneId { get; set; } = "Africa/Johannesburg";

    /// <summary>
    /// How far ahead the next-dose search will look before giving up. Fourteen days comfortably
    /// covers a weekly schedule while bounding the loop for a schedule with no matching days.
    /// </summary>
    public int NextDoseSearchHorizonDays { get; set; } = 14;

    /// <summary>
    /// How long after a scheduled slot a dose is considered missed rather than merely late.
    /// Used by the background service that raises missed-dose notifications.
    /// </summary>
    public int MissedDoseGraceMinutes { get; set; } = 120;
}
