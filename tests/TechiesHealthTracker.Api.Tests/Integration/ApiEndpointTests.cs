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

using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Integration;

/// <summary>
/// End-to-end tests that drive the real HTTP pipeline: routing, model binding, authorisation,
/// the exception middleware and every service behind them.
///
/// The unit tests prove each piece of business logic in isolation; these prove the pieces are
/// actually wired together — that a route exists, reaches the right service, and returns the
/// status code and body the Android client is being written against.
/// </summary>
public class ApiEndpointTests : IAsyncLifetime
{
    private readonly ApiFactory _factory;
    private readonly HttpClient _client;

    /// <summary>
    /// A fresh API and database per test rather than a shared class fixture. Sharing one would
    /// make the suite order-dependent: a test that changes the caregiver's language would leak
    /// into the next test's assertions about their defaults, and the failure would only appear
    /// when the tests happened to run in a particular order.
    /// </summary>
    public ApiEndpointTests()
    {
        _factory = new ApiFactory { IsAuthenticated = true };
        _client = _factory.CreateClient();
    }

    /// <summary>Every test starts from a provisioned profile, exactly as the app does on launch.</summary>
    public async Task InitializeAsync() => await _client.PostAsync("/api/auth/login", null);

    public Task DisposeAsync()
    {
        _client.Dispose();
        _factory.Dispose();
        return Task.CompletedTask;
    }

    private static async Task<T> ReadAsync<T>(HttpResponseMessage response)
    {
        var value = await response.Content.ReadFromJsonAsync<T>();
        value.Should().NotBeNull();
        return value!;
    }

    /// <summary>Creates a dependant and returns it, for tests that need something to hang data off.</summary>
    private async Task<DependantResponse> CreateDependantAsync(string firstName = "Thandi")
    {
        var response = await _client.PostAsJsonAsync("/api/dependants", new CreateDependantRequest
        {
            FirstName = firstName,
            LastName = "Masike",
            DateOfBirth = new DateOnly(2014, 3, 12),
            Relationship = "child"
        });

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        return await ReadAsync<DependantResponse>(response);
    }

    // ---- Health ----

    [Fact]
    public async Task Health_IsReachableWithoutAToken()
    {
        // Azure App Service probes this before the instance is put into rotation, so it cannot
        // require authentication.
        _factory.IsAuthenticated = false;

        var response = await _client.GetAsync("/api/health");

        response.StatusCode.Should().Be(HttpStatusCode.OK);

        _factory.IsAuthenticated = true;
    }

    // ---- Authentication (Requirement 1) ----

    [Fact]
    public async Task Login_ProvisionsAProfileAndReturnsIt()
    {
        var response = await _client.PostAsync("/api/auth/login", null);

        response.StatusCode.Should().Be(HttpStatusCode.OK);

        var user = await ReadAsync<UserResponse>(response);
        user.Email.Should().Be(ApiFactory.TestEmail);
        user.DisplayName.Should().Be(ApiFactory.TestDisplayName);
        user.Settings.PreferredLanguage.Should().Be(SupportedLanguages.English);
    }

    [Fact]
    public async Task Login_IsIdempotentAcrossRepeatedLaunches()
    {
        var first = await ReadAsync<UserResponse>(await _client.PostAsync("/api/auth/login", null));
        var second = await ReadAsync<UserResponse>(await _client.PostAsync("/api/auth/login", null));

        // The client calls this on every launch, so a second call must return the same profile
        // rather than creating another one.
        second.UserId.Should().Be(first.UserId);
        second.IsNewUser.Should().BeFalse();

        using var context = _factory.CreateDbContext();
        context.Users.Count(u => u.FirebaseUid == ApiFactory.TestFirebaseUid).Should().Be(1);
    }

    [Fact]
    public async Task ProtectedEndpoints_RefuseAnUnauthenticatedRequest()
    {
        _factory.IsAuthenticated = false;

        try
        {
            var response = await _client.GetAsync("/api/dependants");

            response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
        }
        finally
        {
            _factory.IsAuthenticated = true;
        }
    }

    // ---- Settings (Requirement 3) ----

    [Fact]
    public async Task Settings_RoundTripThroughTheApi()
    {
        var user = await ReadAsync<UserResponse>(await _client.PostAsync("/api/auth/login", null));

        var update = await _client.PutAsJsonAsync(
            $"/api/users/{user.UserId}/settings",
            new UpdateSettingsRequest
            {
                PreferredLanguage = SupportedLanguages.IsiZulu,
                Theme = "dark",
                ReminderLeadTimeMinutes = 45,
                QuietHoursStart = "22:00",
                QuietHoursEnd = "06:00"
            });

        update.StatusCode.Should().Be(HttpStatusCode.OK);

        var settings = await ReadAsync<SettingsResponse>(update);
        settings.PreferredLanguage.Should().Be("zu");
        settings.Theme.Should().Be("dark");

        // 22:00 must come back as 22:00, not as 10:00 on a 12-hour clock.
        settings.QuietHoursStart.Should().Be("22:00");

        // The preference has to survive the round trip, since the whole point of storing it
        // server-side is that it outlives a reinstall.
        var reread = await ReadAsync<SettingsResponse>(
            await _client.GetAsync($"/api/users/{user.UserId}/settings"));

        reread.PreferredLanguage.Should().Be("zu");
        reread.ReminderLeadTimeMinutes.Should().Be(45);
    }

    [Fact]
    public async Task Settings_RejectAnUnsupportedLanguageWithAReadableError()
    {
        var user = await ReadAsync<UserResponse>(await _client.PostAsync("/api/auth/login", null));

        var response = await _client.PutAsJsonAsync(
            $"/api/users/{user.UserId}/settings",
            new UpdateSettingsRequest { PreferredLanguage = "fr" });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);

        // The middleware turns the business rule into the same error envelope every other
        // failure uses, so the Android client has one shape to parse.
        var body = await response.Content.ReadAsStringAsync();
        body.Should().Contain("invalid_request");
        body.Should().Contain("not supported");
    }

    [Fact]
    public async Task Settings_RefuseToChangeAnotherUsersPreferences()
    {
        var response = await _client.PutAsJsonAsync(
            $"/api/users/{Guid.NewGuid()}/settings",
            new UpdateSettingsRequest { Theme = "dark" });

        response.StatusCode.Should().Be(HttpStatusCode.Forbidden);
    }

    // ---- Dependants (Additional Feature 5) ----

    [Fact]
    public async Task Dependants_CanBeCreatedListedAndRetrieved()
    {
        var created = await CreateDependantAsync("Sipho");

        created.FullName.Should().Be("Sipho Masike");

        // The age is calculated server-side so every screen shows the same number.
        created.Age.Should().BeGreaterThan(0);

        var list = await ReadAsync<List<DependantResponse>>(await _client.GetAsync("/api/dependants"));
        list.Should().Contain(d => d.DependantId == created.DependantId);

        var fetched = await ReadAsync<DependantResponse>(
            await _client.GetAsync($"/api/dependants/{created.DependantId}"));

        fetched.FirstName.Should().Be("Sipho");
    }

    [Fact]
    public async Task Dependants_SupportSeveralUnderOneAccount()
    {
        // This is Additional Feature 5 in one assertion: one caregiver account, several people
        // in their care, each with their own records.
        await CreateDependantAsync("Nomsa");
        await CreateDependantAsync("Bongani");

        var list = await ReadAsync<List<DependantResponse>>(await _client.GetAsync("/api/dependants"));

        list.Should().HaveCountGreaterThanOrEqualTo(2);
    }

    [Fact]
    public async Task Dependants_RejectAFutureDateOfBirth()
    {
        var response = await _client.PostAsJsonAsync("/api/dependants", new CreateDependantRequest
        {
            FirstName = "Future",
            LastName = "Child",
            DateOfBirth = DateOnly.FromDateTime(DateTime.UtcNow.AddYears(1)),
            Relationship = "child"
        });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Dependants_RejectAMissingRequiredFieldWithFieldLevelErrors()
    {
        // Model-binding failures are reshaped by Program.cs to match the same envelope, and must
        // name the offending field so the Android form can highlight it.
        var response = await _client.PostAsJsonAsync("/api/dependants", new { lastName = "Masike" });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);

        var body = await response.Content.ReadAsStringAsync();
        body.Should().Contain("validation_failed");
    }

    [Fact]
    public async Task Dependants_ReturnNotFoundForAnUnknownId()
    {
        var response = await _client.GetAsync($"/api/dependants/{Guid.NewGuid()}");

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Dependants_DisappearFromTheListOnceDeleted()
    {
        var dependant = await CreateDependantAsync("Temporary");

        var delete = await _client.DeleteAsync($"/api/dependants/{dependant.DependantId}");
        delete.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await ReadAsync<List<DependantResponse>>(await _client.GetAsync("/api/dependants"));
        list.Should().NotContain(d => d.DependantId == dependant.DependantId);

        // Soft delete: the row survives for the sake of the medical history.
        using var context = _factory.CreateDbContext();
        context.Dependants.Single(d => d.DependantId == dependant.DependantId).IsActive.Should().BeFalse();
    }

    // ---- The full medication journey ----

    [Fact]
    public async Task MedicationJourney_FromPrescriptionToDoseLogToAdherenceAndRefill()
    {
        // This single test walks the path the demonstration video follows: add a dependant, add
        // their medication, set a schedule, log a dose, and read back the analytics. It is the
        // clearest proof that the API does the heavy lifting the design document promised.
        var dependant = await CreateDependantAsync("Journey");

        // 1. Add the medication.
        var medicationResponse = await _client.PostAsJsonAsync(
            $"/api/dependants/{dependant.DependantId}/medications",
            new CreateMedicationRequest
            {
                Name = "Amoxicillin",
                DosageAmount = "500",
                DosageUnit = "mg",
                Form = "capsule",
                TotalQuantity = 28,
                StartDate = DateOnly.FromDateTime(DateTime.UtcNow.AddDays(-1))
            });

        medicationResponse.StatusCode.Should().Be(HttpStatusCode.Created);
        var medication = await ReadAsync<MedicationResponse>(medicationResponse);

        // Remaining stock defaults to the full pack when the client does not state it.
        medication.RemainingQuantity.Should().Be(28);
        medication.DosageDisplay.Should().Be("500 mg");

        // 2. Set a twice-daily schedule. The API calculates the next dose; the client never does.
        var scheduleResponse = await _client.PostAsJsonAsync(
            $"/api/medications/{medication.MedicationId}/schedules",
            new CreateDoseScheduleRequest
            {
                Frequency = ScheduleFrequencies.TwiceDaily,
                Times = new List<string> { "08:00", "20:00" },
                StartDate = DateOnly.FromDateTime(DateTime.UtcNow.AddDays(-1))
            });

        scheduleResponse.StatusCode.Should().Be(HttpStatusCode.Created);
        var schedule = await ReadAsync<DoseScheduleResponse>(scheduleResponse);

        schedule.NextDoseAt.Should().NotBeNull();
        schedule.Times.Should().Equal("08:00", "20:00");

        // 3. Log a dose as taken, against yesterday's morning slot.
        //
        // The slot is named outright, as the Android client does, rather than left to the server
        // to infer. That is not only what the app does — it is what keeps this test honest: a slot
        // inferred from "now" moves with the wall clock, so the assertions below would pass or
        // fail depending on the hour the suite happened to run.
        //
        // 08:00 in Africa/Johannesburg is 06:00 UTC, and the zone has no daylight saving, so this
        // is a fixed offset rather than a seasonal guess.
        var yesterdayMorningSlot = DateTime.UtcNow.Date.AddDays(-1).AddHours(6);

        var logResponse = await _client.PostAsJsonAsync("/api/doselogs", new CreateDoseLogRequest
        {
            ScheduleId = schedule.ScheduleId,
            DependantId = dependant.DependantId,
            MedicationId = medication.MedicationId,
            Status = DoseLogStatuses.Taken,
            ScheduledAt = yesterdayMorningSlot
        });

        logResponse.StatusCode.Should().Be(HttpStatusCode.Created);
        var logged = await ReadAsync<DoseLogCreatedResponse>(logResponse);

        // One request, four outcomes: the log, the stock, the next dose and the adherence summary.
        logged.RemainingQuantity.Should().Be(27);
        logged.DoseLog.Status.Should().Be(DoseLogStatuses.Taken);
        logged.DoseLog.ScheduledAt.Should().Be(yesterdayMorningSlot);

        // The slot has come due, so it scores. Only whole slots already past are counted, which
        // is why the dose is logged against yesterday rather than against a time still ahead.
        logged.AdherenceSummary.DosesTaken.Should().Be(1);

        // 4. Read the adherence analytics (Additional Feature 3).
        var adherence = await ReadAsync<AdherenceResponse>(
            await _client.GetAsync($"/api/dependants/{dependant.DependantId}/adherence"));

        adherence.DependantId.Should().Be(dependant.DependantId);
        adherence.Trends.Should().NotBeEmpty();

        // 5. Read the refill forecast (Additional Feature 4).
        var forecast = await ReadAsync<RefillForecastResponse>(
            await _client.GetAsync($"/api/medications/{medication.MedicationId}/refill-forecast"));

        forecast.DailyConsumptionRate.Should().Be(2.0);
        forecast.DaysUntilRunOut.Should().Be(13);
        forecast.RefillAlertTriggered.Should().BeFalse();
    }

    [Fact]
    public async Task Medications_RejectAnUnknownForm()
    {
        var dependant = await CreateDependantAsync("Validation");

        var response = await _client.PostAsJsonAsync(
            $"/api/dependants/{dependant.DependantId}/medications",
            new CreateMedicationRequest
            {
                Name = "Something",
                DosageAmount = "1",
                DosageUnit = "mg",
                Form = "powder",
                TotalQuantity = 10,
                StartDate = DateOnly.FromDateTime(DateTime.UtcNow)
            });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await response.Content.ReadAsStringAsync()).Should().Contain("not recognised");
    }

    // ---- Appointments ----

    [Fact]
    public async Task Appointments_CanBeBookedListedUpdatedAndCancelled()
    {
        var dependant = await CreateDependantAsync("Appointments");

        var createResponse = await _client.PostAsJsonAsync(
            $"/api/dependants/{dependant.DependantId}/appointments",
            new CreateAppointmentRequest
            {
                Title = "Paediatrician follow-up",
                AppointmentDateTime = DateTime.UtcNow.AddDays(7),
                Location = "Sandton Mediclinic",
                ReminderMinutesBefore = 120
            });

        createResponse.StatusCode.Should().Be(HttpStatusCode.Created);
        var appointment = await ReadAsync<AppointmentResponse>(createResponse);

        appointment.IsUpcoming.Should().BeTrue();
        appointment.ReminderAt.Should().BeCloseTo(
            appointment.AppointmentDateTime.AddMinutes(-120), TimeSpan.FromSeconds(1));

        var update = await _client.PutAsJsonAsync(
            $"/api/appointments/{appointment.AppointmentId}",
            new UpdateAppointmentRequest { Title = "Rescheduled follow-up" });

        (await ReadAsync<AppointmentResponse>(update)).Title.Should().Be("Rescheduled follow-up");

        var cancel = await _client.DeleteAsync($"/api/appointments/{appointment.AppointmentId}");
        cancel.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var list = await ReadAsync<List<AppointmentResponse>>(
            await _client.GetAsync($"/api/dependants/{dependant.DependantId}/appointments"));

        // Cancelled rather than deleted: the visit history survives.
        list.Single(a => a.AppointmentId == appointment.AppointmentId).IsCancelled.Should().BeTrue();
    }

    [Fact]
    public async Task Appointments_RejectAReminderShorterThanTheMinimum()
    {
        var dependant = await CreateDependantAsync("ReminderValidation");

        var response = await _client.PostAsJsonAsync(
            $"/api/dependants/{dependant.DependantId}/appointments",
            new CreateAppointmentRequest
            {
                Title = "Check-up",
                AppointmentDateTime = DateTime.UtcNow.AddDays(3),
                ReminderMinutesBefore = 1
            });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    // ---- Offline sync (Requirement 5) ----

    [Fact]
    public async Task SyncBatch_AcceptsQueuedOfflineLogsAndReportsOnEachOne()
    {
        var dependant = await CreateDependantAsync("OfflineSync");

        var medication = await ReadAsync<MedicationResponse>(await _client.PostAsJsonAsync(
            $"/api/dependants/{dependant.DependantId}/medications",
            new CreateMedicationRequest
            {
                Name = "Metformin",
                DosageAmount = "850",
                DosageUnit = "mg",
                Form = "tablet",
                TotalQuantity = 60,
                StartDate = DateOnly.FromDateTime(DateTime.UtcNow.AddDays(-2))
            }));

        var schedule = await ReadAsync<DoseScheduleResponse>(await _client.PostAsJsonAsync(
            $"/api/medications/{medication.MedicationId}/schedules",
            new CreateDoseScheduleRequest
            {
                Frequency = ScheduleFrequencies.OnceDaily,
                Times = new List<string> { "08:00" },
                StartDate = DateOnly.FromDateTime(DateTime.UtcNow.AddDays(-2))
            }));

        var batch = new SyncBatchRequest
        {
            DoseLogs = new List<SyncDoseLogItem>
            {
                new()
                {
                    LocalId = "room-1",
                    ScheduleId = schedule.ScheduleId,
                    DependantId = dependant.DependantId,
                    MedicationId = medication.MedicationId,
                    Status = DoseLogStatuses.Taken,
                    LoggedAt = DateTime.UtcNow.AddHours(-1),
                    ScheduledAt = DateTime.UtcNow.AddHours(-1)
                },
                new()
                {
                    LocalId = "room-2",
                    ScheduleId = Guid.NewGuid(),
                    DependantId = dependant.DependantId,
                    MedicationId = medication.MedicationId,
                    Status = DoseLogStatuses.Taken,
                    LoggedAt = DateTime.UtcNow.AddHours(-2)
                }
            }
        };

        var response = await _client.PostAsJsonAsync("/api/sync/batch", batch);
        response.StatusCode.Should().Be(HttpStatusCode.OK);

        var result = await ReadAsync<SyncBatchResponse>(response);

        // The good entry lands and the bad one is reported on its own, which is what stops a
        // single corrupt row in the device queue from blocking everything behind it.
        result.Results.Should().HaveCount(2);
        result.Results.Single(r => r.LocalId == "room-1").Status.Should().Be(SyncItemStatuses.Synced);
        result.Results.Single(r => r.LocalId == "room-2").Status.Should().Be(SyncItemStatuses.Rejected);
    }

    [Fact]
    public async Task SyncBatch_RejectsAnEmptyBatch()
    {
        var response = await _client.PostAsJsonAsync(
            "/api/sync/batch", new SyncBatchRequest { DoseLogs = new List<SyncDoseLogItem>() });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    // ---- Notifications ----

    [Fact]
    public async Task Notifications_ReturnTheCallersHistory()
    {
        var response = await _client.GetAsync("/api/notifications");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await ReadAsync<List<NotificationResponse>>(response)).Should().NotBeNull();
    }

    // ---- Pharmacy finder ----

    [Fact]
    public async Task Pharmacies_RejectImpossibleCoordinates()
    {
        // Validated before the upstream call, so a bad request never costs a GeoApify lookup.
        var response = await _client.GetAsync("/api/pharmacies/nearby?lat=999&lon=28.05&radius=5000");

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await response.Content.ReadAsStringAsync()).Should().Contain("Latitude");
    }

    [Fact]
    public async Task Pharmacies_RejectAnOversizedRadius()
    {
        var response = await _client.GetAsync("/api/pharmacies/nearby?lat=-26.20&lon=28.05&radius=500000");

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Pharmacies_ReportUpstreamUnavailableWhenNoApiKeyIsConfigured()
    {
        // No GeoApify key is set in the test configuration. The failure must surface as a clean
        // 502 rather than an unhandled exception leaking a stack trace to the handset.
        var response = await _client.GetAsync("/api/pharmacies/nearby?lat=-26.20&lon=28.05&radius=5000");

        response.StatusCode.Should().Be(HttpStatusCode.BadGateway);
        (await response.Content.ReadAsStringAsync()).Should().Contain("upstream_unavailable");
    }
}
