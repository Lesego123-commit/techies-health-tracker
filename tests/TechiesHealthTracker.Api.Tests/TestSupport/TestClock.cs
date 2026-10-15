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

using TechiesHealthTracker.Api.Services.Abstractions;

namespace TechiesHealthTracker.Api.Tests.TestSupport;

/// <summary>
/// A clock the tests control.
///
/// Every calculation in this API that involves time — next dose, adherence windows, refill
/// run-out dates, quiet hours — is tested against a fixed instant rather than DateTime.UtcNow.
/// Without this, a test asserting "the next dose is at 08:00 tomorrow" would pass or fail
/// depending on what time of day the suite happened to run, which is exactly the kind of
/// intermittent failure that makes a CI pipeline get ignored.
/// </summary>
public class TestClock : IDateTimeProvider
{
    public TestClock(DateTime utcNow) => UtcNow = DateTime.SpecifyKind(utcNow, DateTimeKind.Utc);

    /// <summary>Convenience overload for building a fixed instant inline.</summary>
    public TestClock(int year, int month, int day, int hour = 0, int minute = 0)
        : this(new DateTime(year, month, day, hour, minute, 0, DateTimeKind.Utc)) { }

    public DateTime UtcNow { get; private set; }

    /// <summary>Moves the clock forward, for tests that need to observe behaviour over time.</summary>
    public void Advance(TimeSpan by) => UtcNow = UtcNow.Add(by);

    /// <summary>Jumps the clock to a specific instant.</summary>
    public void Set(DateTime utcNow) => UtcNow = DateTime.SpecifyKind(utcNow, DateTimeKind.Utc);
}
