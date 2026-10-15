// ----------------------------------------------------------------------------------------------
//  References
//   Npgsql. 2024. Date and Time Handling. [Online]. Available at:
//    https://www.npgsql.org/doc/types/datetime.html [Accessed 16 September 2026].
//   Fluent Assertions. 2024. Documentation. [Online]. Available at:
//    https://fluentassertions.com/introduction [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

using FluentAssertions;
using TechiesHealthTracker.Api.Common;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Guards the conversion that every date-range query goes through.
///
/// This is deliberately a test of Kind rather than of value. The adherence and dose-history
/// endpoints both build their window from a DateOnly, and `DateOnly.ToDateTime` hands back a
/// DateTime marked Unspecified, which Npgsql refuses against a `timestamp with time zone`
/// parameter. Because the suite runs on the in-memory provider, which ignores Kind altogether,
/// nothing else in this project can catch that — it surfaced only once the app was talking to the
/// real database.
/// </summary>
public class DateOnlyExtensionsTests
{
    [Fact]
    public void ToUtcStartOfDay_MarksTheResultAsUtc()
    {
        var date = new DateOnly(2026, 9, 16);

        var result = date.ToUtcStartOfDay();

        result.Kind.Should().Be(DateTimeKind.Utc);
    }

    [Fact]
    public void ToUtcStartOfDay_KeepsTheDateAndStartsAtMidnight()
    {
        var result = new DateOnly(2026, 9, 16).ToUtcStartOfDay();

        result.Should().Be(new DateTime(2026, 9, 16, 0, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public void ToUtcStartOfDay_DoesNotShiftTheDateForTheMachineTimeZone()
    {
        // A conversion that went through local time would move the date for anyone east or west
        // of UTC. The window a caregiver asks for must not depend on where the server sits.
        var result = new DateOnly(2026, 1, 1).ToUtcStartOfDay();

        result.Date.Should().Be(new DateTime(2026, 1, 1));
    }
}
