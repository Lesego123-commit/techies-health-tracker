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

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Domain.Entities;

namespace TechiesHealthTracker.Api.Tests.TestSupport;

/// <summary>
/// Builds an isolated in-memory database per test, plus the seed data most tests need.
///
/// The EF Core in-memory provider is used rather than a real PostgreSQL instance so the suite
/// runs on any machine and inside GitHub Actions with no external dependency. It does not enforce
/// relational constraints, so the tests here deliberately assert on the behaviour the services
/// implement themselves — ownership checks, duplicate detection, stock arithmetic — rather than
/// on constraints the database would enforce in production.
/// </summary>
public static class TestDatabase
{
    /// <summary>Creates an empty context backed by a uniquely named in-memory store.</summary>
    public static AppDbContext CreateContext()
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase($"techies-tests-{Guid.NewGuid()}")

            // The in-memory provider warns that transactions are ignored. That is expected here,
            // so the warning is suppressed rather than left to fail every test that saves twice.
            .ConfigureWarnings(w => w.Ignore(
                Microsoft.EntityFrameworkCore.Diagnostics.InMemoryEventId.TransactionIgnoredWarning))
            .Options;

        return new AppDbContext(options);
    }

    /// <summary>
    /// A caregiver with one dependant, one medication and one twice-daily schedule. This is the
    /// shape most tests need, so it is built once here rather than repeated in every test.
    /// </summary>
    public static async Task<SeededData> SeedAsync(AppDbContext context, DateTime utcNow)
    {
        var caregiver = new User
        {
            FirebaseUid = "firebase-caregiver-1",
            DisplayName = "Lesego Masike",
            Email = "caregiver@example.com",
            PreferredLanguage = SupportedLanguages.English,
            ReminderLeadTimeMinutes = 30,
            LowStockThresholdDays = 7,
            CreatedAt = utcNow,
            UpdatedAt = utcNow
        };

        // A second caregiver, so that the ownership tests have somebody else's records to be
        // refused access to.
        var otherCaregiver = new User
        {
            FirebaseUid = "firebase-caregiver-2",
            DisplayName = "Another Caregiver",
            Email = "other@example.com",
            CreatedAt = utcNow,
            UpdatedAt = utcNow
        };

        var dependant = new Dependant
        {
            CaregiverUserId = caregiver.UserId,
            FirstName = "Thandi",
            LastName = "Masike",
            DateOfBirth = new DateOnly(2014, 3, 12),
            Relationship = "child",
            IsActive = true,
            CreatedAt = utcNow,
            UpdatedAt = utcNow
        };

        var medication = new Medication
        {
            DependantId = dependant.DependantId,
            Name = "Amoxicillin",
            DosageAmount = "500",
            DosageUnit = "mg",
            Form = "capsule",
            TotalQuantity = 28,
            RemainingQuantity = 28,
            StartDate = DateOnly.FromDateTime(utcNow).AddDays(-7),
            IsActive = true,
            CreatedAt = utcNow,
            UpdatedAt = utcNow
        };

        var schedule = new DoseSchedule
        {
            MedicationId = medication.MedicationId,
            Frequency = ScheduleFrequencies.TwiceDaily,
            Times = new List<string> { "08:00", "20:00" },
            DaysOfWeek = new List<string>(),
            StartDate = DateOnly.FromDateTime(utcNow).AddDays(-7),
            IsActive = true,
            CreatedAt = utcNow,
            UpdatedAt = utcNow
        };

        context.Users.AddRange(caregiver, otherCaregiver);
        context.Dependants.Add(dependant);
        context.Medications.Add(medication);
        context.DoseSchedules.Add(schedule);

        await context.SaveChangesAsync();

        return new SeededData(caregiver, otherCaregiver, dependant, medication, schedule);
    }

    /// <summary>The records created by <see cref="SeedAsync"/>.</summary>
    public record SeededData(
        User Caregiver,
        User OtherCaregiver,
        Dependant Dependant,
        Medication Medication,
        DoseSchedule Schedule);
}
