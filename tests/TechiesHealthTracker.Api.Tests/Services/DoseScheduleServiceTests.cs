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

using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Implementations;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Moq;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for the dose-scheduling engine — the piece of business logic the whole app rests on.
/// If the next dose is calculated wrongly, reminders fire at the wrong time, adherence is
/// measured against the wrong denominator and the refill forecast drifts, so this is the most
/// thoroughly covered class in the suite.
///
/// All of these run against a fixed clock and the Africa/Johannesburg time zone the app is
/// configured for, so the UTC instants asserted below are two hours behind the local dose times.
/// </summary>
public class DoseScheduleServiceTests
{
    /// <summary>Wednesday 15 April 2026, 06:00 UTC, which is 08:00 in Johannesburg.</summary>
    private static readonly DateTime FixedNow = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

    private static DoseScheduleService CreateService(DateTime? now = null, string timeZoneId = "Africa/Johannesburg")
    {
        var options = Options.Create(new SchedulingOptions
        {
            TimeZoneId = timeZoneId,
            NextDoseSearchHorizonDays = 14,
            MissedDoseGraceMinutes = 120
        });

        return new DoseScheduleService(
            Mock.Of<IUnitOfWork>(),
            Mock.Of<IAccessGuard>(),
            new TestClock(now ?? FixedNow),
            options,
            NullLogger<DoseScheduleService>.Instance);
    }

    private static DoseSchedule BuildSchedule(
        string frequency = ScheduleFrequencies.TwiceDaily,
        List<string>? times = null,
        List<string>? daysOfWeek = null,
        DateOnly? startDate = null,
        DateOnly? endDate = null,
        bool isActive = true) => new()
    {
        Frequency = frequency,
        Times = times ?? new List<string> { "08:00", "20:00" },
        DaysOfWeek = daysOfWeek ?? new List<string>(),
        StartDate = startDate ?? new DateOnly(2026, 4, 1),
        EndDate = endDate,
        IsActive = isActive
    };

    // ---- CalculateNextDoseAt ----

    [Fact]
    public void CalculateNextDoseAt_ReturnsTheNextSlotLaterToday()
    {
        var service = CreateService();
        var schedule = BuildSchedule();

        // 06:00 UTC is 08:00 local, so the 08:00 dose has just passed and the 20:00 one is next.
        // 20:00 local on the 15th is 18:00 UTC on the 15th.
        var next = service.CalculateNextDoseAt(schedule, FixedNow);

        next.Should().Be(new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public void CalculateNextDoseAt_AdvancesPastASlotFallingExactlyOnTheCurrentInstant()
    {
        var service = CreateService();
        var schedule = BuildSchedule();

        // This is the situation right after a dose is logged on time: RefreshNextDoseAsync asks
        // for the next dose at the exact instant of the slot just recorded. Returning that same
        // slot would leave the schedule stuck, reminding the caregiver about a dose they have
        // already taken, so "next" must mean strictly later.
        var exactlyAtTheEightAmSlot = new DateTime(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

        var next = service.CalculateNextDoseAt(schedule, exactlyAtTheEightAmSlot);

        next.Should().Be(new DateTime(2026, 4, 15, 18, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public void CalculateNextDoseAt_RollsOverToTomorrowOnceTodaysDosesHavePassed()
    {
        var service = CreateService();
        var schedule = BuildSchedule();

        // 21:00 local on the 15th is 19:00 UTC; both of today's doses are behind us.
        var after = new DateTime(2026, 4, 15, 19, 0, 0, DateTimeKind.Utc);

        var next = service.CalculateNextDoseAt(schedule, after);

        next.Should().Be(new DateTime(2026, 4, 16, 6, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public void CalculateNextDoseAt_SkipsDaysTheScheduleDoesNotApplyTo()
    {
        var service = CreateService();

        // A Monday-and-Thursday schedule, asked on a Wednesday afternoon. The answer must be
        // Thursday, not "tomorrow" for its own sake.
        var schedule = BuildSchedule(
            frequency: ScheduleFrequencies.Weekly,
            times: new List<string> { "09:00" },
            daysOfWeek: new List<string> { "monday", "thursday" });

        var next = service.CalculateNextDoseAt(schedule, FixedNow);

        next.Should().Be(new DateTime(2026, 4, 16, 7, 0, 0, DateTimeKind.Utc));
        next!.Value.DayOfWeek.Should().Be(DayOfWeek.Thursday);
    }

    [Fact]
    public void CalculateNextDoseAt_ReturnsNullOnceTheCourseHasEnded()
    {
        var service = CreateService();
        var schedule = BuildSchedule(endDate: new DateOnly(2026, 4, 10));

        service.CalculateNextDoseAt(schedule, FixedNow).Should().BeNull();
    }

    [Fact]
    public void CalculateNextDoseAt_ReturnsNullForARetiredSchedule()
    {
        var service = CreateService();
        var schedule = BuildSchedule(isActive: false);

        service.CalculateNextDoseAt(schedule, FixedNow).Should().BeNull();
    }

    [Fact]
    public void CalculateNextDoseAt_ReturnsTheFirstDoseWhenTheCourseStartsInFuture()
    {
        var service = CreateService();
        var schedule = BuildSchedule(
            times: new List<string> { "08:00" },
            startDate: new DateOnly(2026, 4, 20));

        var next = service.CalculateNextDoseAt(schedule, FixedNow);

        next.Should().Be(new DateTime(2026, 4, 20, 6, 0, 0, DateTimeKind.Utc));
    }

    [Fact]
    public void CalculateNextDoseAt_ReturnsNullWhenNoDayMatchesInsideTheSearchHorizon()
    {
        var service = CreateService();

        // A schedule whose only day falls after the 14-day horizon has nothing to report, and
        // must return null rather than looping indefinitely looking for one.
        var schedule = BuildSchedule(
            frequency: ScheduleFrequencies.Weekly,
            times: new List<string> { "08:00" },
            daysOfWeek: new List<string> { "monday" },
            startDate: new DateOnly(2026, 6, 1));

        service.CalculateNextDoseAt(schedule, FixedNow).Should().BeNull();
    }

    [Fact]
    public void CalculateNextDoseAt_HandlesALateEveningDoseThatCrossesTheUtcDayBoundary()
    {
        var service = CreateService();

        // 23:00 in Johannesburg is 21:00 UTC the same day. The slot must not be dropped or
        // shifted by the day-boundary handling in EnumerateSlots.
        var schedule = BuildSchedule(
            frequency: ScheduleFrequencies.OnceDaily,
            times: new List<string> { "23:00" });

        var next = service.CalculateNextDoseAt(schedule, FixedNow);

        next.Should().Be(new DateTime(2026, 4, 15, 21, 0, 0, DateTimeKind.Utc));
    }

    // ---- EnumerateSlots ----

    [Fact]
    public void EnumerateSlots_ReturnsEverySlotInTheWindowInOrder()
    {
        var service = CreateService();
        var schedule = BuildSchedule();

        var from = new DateTime(2026, 4, 13, 0, 0, 0, DateTimeKind.Utc);
        var to = new DateTime(2026, 4, 16, 0, 0, 0, DateTimeKind.Utc);

        var slots = service.EnumerateSlots(schedule, from, to);

        // Three full days at two doses a day.
        slots.Should().HaveCount(6);
        slots.Should().BeInAscendingOrder();
        slots.Should().OnlyContain(s => s >= from && s < to);
    }

    [Fact]
    public void EnumerateSlots_ExcludesTheUpperBound()
    {
        var service = CreateService();
        var schedule = BuildSchedule(times: new List<string> { "08:00" });

        var slot = new DateTime(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

        // A half-open interval matters: adherence windows are chained day after day, and an
        // inclusive upper bound would count the boundary dose in two days at once.
        var slots = service.EnumerateSlots(schedule, slot.AddHours(-1), slot);

        slots.Should().BeEmpty();
    }

    [Fact]
    public void EnumerateSlots_ReturnsNothingWhenTheWindowIsInverted()
    {
        var service = CreateService();
        var schedule = BuildSchedule();

        var slots = service.EnumerateSlots(schedule, FixedNow, FixedNow.AddDays(-1));

        slots.Should().BeEmpty();
    }

    [Fact]
    public void EnumerateSlots_StopsAtTheCourseEndDate()
    {
        var service = CreateService();
        var schedule = BuildSchedule(
            times: new List<string> { "08:00" },
            endDate: new DateOnly(2026, 4, 16));

        var slots = service.EnumerateSlots(
            schedule,
            new DateTime(2026, 4, 15, 0, 0, 0, DateTimeKind.Utc),
            new DateTime(2026, 4, 20, 0, 0, 0, DateTimeKind.Utc));

        slots.Should().HaveCount(2);
        slots.Last().Should().Be(new DateTime(2026, 4, 16, 6, 0, 0, DateTimeKind.Utc));
    }

    // ---- CalculateDailyDoseRate ----

    [Fact]
    public void CalculateDailyDoseRate_CountsOneUnitPerScheduledTime()
    {
        var service = CreateService();

        service.CalculateDailyDoseRate(BuildSchedule()).Should().Be(2.0);
    }

    [Fact]
    public void CalculateDailyDoseRate_ProratesAScheduleLimitedToCertainDays()
    {
        var service = CreateService();

        var schedule = BuildSchedule(
            frequency: ScheduleFrequencies.Weekly,
            times: new List<string> { "08:00", "20:00" },
            daysOfWeek: new List<string> { "monday", "thursday" });

        // Two doses on two days a week averages to four sevenths of a dose a day. Getting this
        // wrong would have the refill forecast predicting a run-out date more than twice too soon.
        service.CalculateDailyDoseRate(schedule).Should().BeApproximately(4.0 / 7.0, 0.0001);
    }

    [Fact]
    public void CalculateDailyDoseRate_ReturnsZeroForARetiredSchedule()
    {
        var service = CreateService();

        service.CalculateDailyDoseRate(BuildSchedule(isActive: false)).Should().Be(0);
    }

    // ---- Validation ----

    [Fact]
    public async Task CreateAsync_RejectsAFrequencyThatDoesNotMatchTheNumberOfTimes()
    {
        var service = CreateService();

        var request = new CreateDoseScheduleRequest
        {
            Frequency = ScheduleFrequencies.TwiceDaily,
            Times = new List<string> { "08:00" },
            StartDate = new DateOnly(2026, 4, 1)
        };

        var act = () => service.CreateAsync(Guid.NewGuid(), request, CancellationToken.None);

        (await act.Should().ThrowAsync<BusinessRuleException>())
            .WithMessage("*expects exactly 2 time(s)*");
    }

    [Theory]
    [InlineData("25:00")]
    [InlineData("8am")]
    [InlineData("08.00")]
    [InlineData("")]
    public async Task CreateAsync_RejectsAMalformedTime(string time)
    {
        var service = CreateService();

        var request = new CreateDoseScheduleRequest
        {
            Frequency = ScheduleFrequencies.OnceDaily,
            Times = new List<string> { time },
            StartDate = new DateOnly(2026, 4, 1)
        };

        var act = () => service.CreateAsync(Guid.NewGuid(), request, CancellationToken.None);

        await act.Should().ThrowAsync<BusinessRuleException>();
    }

    [Fact]
    public async Task CreateAsync_RejectsAWeeklyScheduleWithNoDays()
    {
        var service = CreateService();

        var request = new CreateDoseScheduleRequest
        {
            Frequency = ScheduleFrequencies.Weekly,
            Times = new List<string> { "08:00" },
            DaysOfWeek = new List<string>(),
            StartDate = new DateOnly(2026, 4, 1)
        };

        var act = () => service.CreateAsync(Guid.NewGuid(), request, CancellationToken.None);

        (await act.Should().ThrowAsync<BusinessRuleException>())
            .WithMessage("*at least one day of the week*");
    }

    [Fact]
    public async Task CreateAsync_RejectsAnEndDateBeforeTheStartDate()
    {
        var service = CreateService();

        var request = new CreateDoseScheduleRequest
        {
            Frequency = ScheduleFrequencies.OnceDaily,
            Times = new List<string> { "08:00" },
            StartDate = new DateOnly(2026, 4, 10),
            EndDate = new DateOnly(2026, 4, 1)
        };

        var act = () => service.CreateAsync(Guid.NewGuid(), request, CancellationToken.None);

        (await act.Should().ThrowAsync<BusinessRuleException>())
            .WithMessage("*cannot fall before its start date*");
    }

    [Fact]
    public async Task CreateAsync_RejectsDuplicateTimes()
    {
        var service = CreateService();

        var request = new CreateDoseScheduleRequest
        {
            Frequency = ScheduleFrequencies.TwiceDaily,
            Times = new List<string> { "08:00", "08:00" },
            StartDate = new DateOnly(2026, 4, 1)
        };

        var act = () => service.CreateAsync(Guid.NewGuid(), request, CancellationToken.None);

        (await act.Should().ThrowAsync<BusinessRuleException>()).WithMessage("*must be unique*");
    }

    [Fact]
    public async Task CreateAsync_StoresEveningTimesOnATwentyFourHourClock()
    {
        // Regression guard. Normalising the times through a 12-hour format specifier would store
        // the 20:00 evening dose as 08:00, silently moving it to the morning: every reminder for
        // that medication would then fire twelve hours early and the caregiver would have no way
        // to see why. Times are sorted on the way in, so the pair also proves the ordering is
        // still done on the 24-hour value.
        var captured = new List<DoseSchedule>();

        var unitOfWork = new Mock<IUnitOfWork>();
        var repository = new Mock<IRepository<DoseSchedule>>();

        repository
            .Setup(r => r.AddAsync(It.IsAny<DoseSchedule>(), It.IsAny<CancellationToken>()))
            .Callback<DoseSchedule, CancellationToken>((s, _) => captured.Add(s))
            .Returns(Task.CompletedTask);

        unitOfWork.SetupGet(u => u.DoseSchedules).Returns(repository.Object);

        var medicationId = Guid.NewGuid();
        var guard = new Mock<IAccessGuard>();

        guard.Setup(g => g.EnsureMedicationAsync(medicationId, It.IsAny<CancellationToken>()))
            .ReturnsAsync(new Medication { MedicationId = medicationId });

        var service = new DoseScheduleService(
            unitOfWork.Object,
            guard.Object,
            new TestClock(FixedNow),
            Options.Create(new SchedulingOptions()),
            NullLogger<DoseScheduleService>.Instance);

        await service.CreateAsync(
            medicationId,
            new CreateDoseScheduleRequest
            {
                Frequency = ScheduleFrequencies.TwiceDaily,
                Times = new List<string> { "20:00", "08:00" },
                StartDate = new DateOnly(2026, 4, 1)
            },
            CancellationToken.None);

        captured.Single().Times.Should().Equal("08:00", "20:00");
    }

    // ---- Resilience ----

    [Fact]
    public void Constructor_FallsBackToUtcWhenTheConfiguredTimeZoneIsUnavailable()
    {
        // A host with a trimmed time-zone database must not take the whole API down. The service
        // logs a warning and treats schedule times as UTC instead.
        var service = CreateService(timeZoneId: "Not/ARealZone");

        var schedule = BuildSchedule(times: new List<string> { "08:00" });

        var next = service.CalculateNextDoseAt(
            schedule, new DateTime(2026, 4, 15, 0, 0, 0, DateTimeKind.Utc));

        next.Should().Be(new DateTime(2026, 4, 15, 8, 0, 0, DateTimeKind.Utc));
    }
}
