// ----------------------------------------------------------------------------------------------
//  References
//   Npgsql. 2024. Date and Time Handling. [Online]. Available at:
//    https://www.npgsql.org/doc/types/datetime.html [Accessed 16 September 2026].
//   Microsoft. 2024. DateOnly.ToDateTime Method. [Online]. Available at:
//    https://learn.microsoft.com/dotnet/api/system.dateonly.todatetime [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.3 (Data Model).
// ----------------------------------------------------------------------------------------------

namespace TechiesHealthTracker.Api.Common;

/// <summary>
/// Turns a calendar date into the UTC instant its day begins at.
/// </summary>
public static class DateOnlyExtensions
{
    /// <summary>
    /// The start of this date as a UTC instant.
    ///
    /// Exists because <see cref="DateOnly.ToDateTime(TimeOnly)"/> returns a DateTime whose Kind is
    /// <see cref="DateTimeKind.Unspecified"/>, and Npgsql refuses to write or parameterise one
    /// against a <c>timestamp with time zone</c> column — it throws rather than guessing a zone.
    ///
    /// That combination is genuinely dangerous on this project, because the in-memory provider the
    /// tests run against ignores Kind entirely. A date-range query built the obvious way therefore
    /// passes every test and then fails only against the real database, which is exactly what
    /// happened to the adherence endpoint. Naming the conversion is what stops it recurring.
    /// </summary>
    public static DateTime ToUtcStartOfDay(this DateOnly date) =>
        DateTime.SpecifyKind(date.ToDateTime(TimeOnly.MinValue), DateTimeKind.Utc);
}
