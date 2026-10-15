// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Globalization and localization in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/localization [Accessed 25 September 2026].
//   Microsoft. 2024. CultureInfo Class. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/api/system.globalization.cultureinfo
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.7 (Minimum Requirement 7 —
//    Multi-Language Support).
// ----------------------------------------------------------------------------------------------

using System.Globalization;
using TechiesHealthTracker.Api.Domain.Constants;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// The words the API puts in a notification, in the caregiver's own language.
///
/// Section 3.7 is explicit that the language preference exists so that "dose reminders, refill
/// alerts and similar are sent in the correct language". The preference was being stored,
/// validated and shipped to the handset — and then ignored here, so a caregiver who set the app to
/// isiZulu got an isiZulu interface and English push notifications.
///
/// Deliberately not .NET resource files. Four categories in three languages is a table, and a
/// table that can be read in one screen is easier to check for a missing translation than twelve
/// .resx entries spread over three files. If this grows past a dozen messages, resources become
/// the better answer.
///
/// Dates and numbers are formatted with the matching <see cref="CultureInfo"/> rather than the
/// server's, so an Afrikaans reminder does not arrive with an English month name in the middle of
/// it. The server runs in UTC with an invariant culture, which would otherwise decide all of this.
/// </summary>
public static class NotificationText
{
    /// <summary>
    /// The three languages, resolved leniently.
    ///
    /// An unrecognised or missing preference falls back to English rather than throwing. The value
    /// arrives from a client and travels between devices, so a build that predates a language can
    /// still send its code — and a caregiver reading English is a far better outcome than a
    /// background sweep that dies mid-scan and silently stops sending anyone reminders.
    /// </summary>
    public static CultureInfo CultureFor(string? language) => (language?.Trim().ToLowerInvariant()) switch
    {
        SupportedLanguages.IsiZulu => CultureInfo.GetCultureInfo("zu-ZA"),
        SupportedLanguages.Afrikaans => CultureInfo.GetCultureInfo("af-ZA"),
        _ => CultureInfo.GetCultureInfo("en-ZA")
    };

    private static string Normalise(string? language)
    {
        var code = language?.Trim().ToLowerInvariant();
        return SupportedLanguages.All.Contains(code ?? string.Empty) ? code! : SupportedLanguages.English;
    }

    // ---- Dose reminder ----

    public static string DoseReminderTitle(string? language) => Normalise(language) switch
    {
        SupportedLanguages.IsiZulu => "Umuthi ufanele",
        SupportedLanguages.Afrikaans => "Medikasie verskuldig",
        _ => "Medication due"
    };

    /// <summary>"Thabo: Amoxicillin 500 mg is due."</summary>
    public static string DoseReminderBody(
        string? language, string dependantName, string medicationName, string dosage)
        => Normalise(language) switch
        {
            SupportedLanguages.IsiZulu =>
                $"{dependantName}: {medicationName} {dosage} isifanele manje.",

            SupportedLanguages.Afrikaans =>
                $"{dependantName}: {medicationName} {dosage} is nou verskuldig.",

            _ => $"{dependantName}: {medicationName} {dosage} is due."
        };

    // ---- Missed dose ----

    public static string MissedDoseTitle(string? language) => Normalise(language) switch
    {
        SupportedLanguages.IsiZulu => "Umthamo awuqoshwanga",
        SupportedLanguages.Afrikaans => "Dosis nie aangeteken nie",
        _ => "Dose not recorded"
    };

    /// <summary>
    /// Pluralised properly rather than with "dose(s)".
    ///
    /// isiZulu changes the noun itself — umthamo becomes imithamo — so a bracketed s was never
    /// going to work there, and it reads as unfinished in English too.
    /// </summary>
    public static string MissedDoseBody(
        string? language, string dependantName, string medicationName, int missedCount)
        => Normalise(language) switch
        {
            SupportedLanguages.IsiZulu => missedCount == 1
                ? $"{dependantName}: {medicationName} awumakwanga njengothathiwe (umthamo owodwa)."
                : $"{dependantName}: {medicationName} awumakwanga njengothathiwe (imithamo engu-{missedCount}).",

            SupportedLanguages.Afrikaans => missedCount == 1
                ? $"{dependantName}: {medicationName} is nie as geneem gemerk nie (1 dosis)."
                : $"{dependantName}: {medicationName} is nie as geneem gemerk nie ({missedCount} dosisse).",

            _ => missedCount == 1
                ? $"{dependantName}: {medicationName} was not marked as taken (1 dose)."
                : $"{dependantName}: {medicationName} was not marked as taken ({missedCount} doses)."
        };

    // ---- Appointment reminder ----

    public static string AppointmentTitle(string? language) => Normalise(language) switch
    {
        SupportedLanguages.IsiZulu => "I-apoyintimenti iyeza",
        SupportedLanguages.Afrikaans => "Afspraak kom nader",
        _ => "Appointment coming up"
    };

    /// <summary>
    /// The time and date are formatted in the caregiver's culture, so an Afrikaans reminder reads
    /// "om 14:30 op 25 September" rather than switching language halfway through.
    /// </summary>
    public static string AppointmentBody(
        string? language, string dependantName, string title, DateTime appointmentAt)
    {
        var culture = CultureFor(language);
        var when = appointmentAt.ToString("HH:mm", culture);
        var date = appointmentAt.ToString("d MMMM", culture);

        return Normalise(language) switch
        {
            SupportedLanguages.IsiZulu => $"{dependantName}: {title} ngo-{when} mhla ka-{date}.",
            SupportedLanguages.Afrikaans => $"{dependantName}: {title} om {when} op {date}.",
            _ => $"{dependantName}: {title} at {when} on {date}."
        };
    }

    // ---- Refill alert ----

    public static string RefillTitle(string? language) => Normalise(language) switch
    {
        SupportedLanguages.IsiZulu => "Kudingeka ukugcwaliswa",
        SupportedLanguages.Afrikaans => "Hervulling nodig",
        _ => "Refill needed"
    };

    /// <summary>
    /// Two separate sentences rather than one with a clause bolted on: "has run out" and "will run
    /// out in about n days" are different statements, and the day count has to pluralise.
    /// </summary>
    public static string RefillBody(string? language, string medicationName, int? daysUntilRunOut)
    {
        var code = Normalise(language);

        if (daysUntilRunOut is not > 0)
        {
            return code switch
            {
                SupportedLanguages.IsiZulu =>
                    $"U-{medicationName} usuphelile. Hlela isikweletu esiphindwayo.",

                SupportedLanguages.Afrikaans =>
                    $"{medicationName} is op. Kry 'n herhaalvoorskrif.",

                _ => $"{medicationName} has run out. Arrange a repeat prescription."
            };
        }

        var days = daysUntilRunOut.Value;

        return code switch
        {
            SupportedLanguages.IsiZulu => days == 1
                ? $"U-{medicationName} uzophela cishe osukwini olulodwa. Hlela isikweletu esiphindwayo."
                : $"U-{medicationName} uzophela cishe ezinsukwini ezingu-{days}. Hlela isikweletu esiphindwayo.",

            SupportedLanguages.Afrikaans => days == 1
                ? $"{medicationName} raak oor ongeveer 1 dag op. Kry 'n herhaalvoorskrif."
                : $"{medicationName} raak oor ongeveer {days} dae op. Kry 'n herhaalvoorskrif.",

            _ => days == 1
                ? $"{medicationName} will run out in about 1 day. Arrange a repeat prescription."
                : $"{medicationName} will run out in about {days} days. Arrange a repeat prescription."
        };
    }
}
