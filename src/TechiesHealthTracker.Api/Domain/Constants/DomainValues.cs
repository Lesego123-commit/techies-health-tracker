// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Value Conversions — EF Core. [Online]. Available at:
//    https://learn.microsoft.com/ef/core/modeling/value-conversions [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 6 (controlled vocabularies).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Domain.Constants;

/// <summary>
/// The controlled vocabularies used by the string columns in section 6 of the design document.
/// These live as constants rather than C# enums so that the values stored in PostgreSQL stay
/// readable in a raw query and match the JSON contract the Android client codes against exactly.
/// </summary>
public static class DoseLogStatuses
{
    public const string Taken = "taken";
    public const string Missed = "missed";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Taken, Missed };
}

public static class ScheduleFrequencies
{
    public const string OnceDaily = "once_daily";
    public const string TwiceDaily = "twice_daily";
    public const string ThreeTimesDaily = "three_times_daily";
    public const string Weekly = "weekly";
    public const string Custom = "custom";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { OnceDaily, TwiceDaily, ThreeTimesDaily, Weekly, Custom };

    /// <summary>
    /// How many clock times a frequency is expected to carry. Custom and weekly are open-ended,
    /// so they are validated on Times being non-empty instead of on an exact count.
    /// </summary>
    public static int? ExpectedTimeCount(string frequency) => frequency?.ToLowerInvariant() switch
    {
        OnceDaily => 1,
        TwiceDaily => 2,
        ThreeTimesDaily => 3,
        _ => null
    };
}

public static class MedicationForms
{
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "capsule", "tablet", "liquid", "injection", "inhaler", "drops" };
}

public static class DosageUnits
{
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "mg", "ml", "tablets", "g", "mcg", "iu", "puffs", "drops", "units" };
}

public static class RelationshipTypes
{
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "child", "parent", "spouse", "self", "other" };
}

public static class DocumentTypes
{
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "prescription", "lab_result", "medical_report", "other" };
}

public static class NotificationCategories
{
    public const string DoseReminder = "dose_reminder";
    public const string MissedDose = "missed_dose";
    public const string AppointmentReminder = "appointment_reminder";
    public const string RefillAlert = "refill_alert";
}

public static class SupportedLanguages
{
    public const string English = "en";
    public const string IsiZulu = "zu";
    public const string Afrikaans = "af";

    /// <summary>The three languages promised by Requirement 7 of the design document.</summary>
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { English, IsiZulu, Afrikaans };
}

public static class Themes
{
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "light", "dark", "system" };
}

public static class DaysOfWeekValues
{
    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday" };

    /// <summary>Maps a .NET DayOfWeek onto the lowercase string form stored in DoseSchedules.DaysOfWeek.</summary>
    public static string From(DayOfWeek day) => day.ToString().ToLowerInvariant();
}
