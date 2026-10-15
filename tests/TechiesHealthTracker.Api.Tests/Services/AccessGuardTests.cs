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
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Implementations;
using TechiesHealthTracker.Api.Tests.TestSupport;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for the ownership rules that separate one caregiver's records from another's.
///
/// This is the security boundary of the whole API: section 3.12 of the design document requires
/// that a caregiver reaches only their own dependants and everything hanging off them. Because
/// every service routes its authorisation through <see cref="AccessGuard"/>, proving it correct
/// here covers the rule everywhere it is applied.
/// </summary>
public class AccessGuardTests : IAsyncLifetime
{
    private static readonly DateTime FixedNow = new(2026, 4, 15, 6, 0, 0, DateTimeKind.Utc);

    private readonly AppDbContext _context = TestDatabase.CreateContext();
    private readonly FakeCurrentUserService _currentUser = new();
    private TestDatabase.SeededData _data = null!;
    private AccessGuard _guard = null!;

    public async Task InitializeAsync()
    {
        _data = await TestDatabase.SeedAsync(_context, FixedNow);
        _currentUser.SignInAs(_data.Caregiver);
        _guard = new AccessGuard(_context, _currentUser, NullLogger<AccessGuard>.Instance);
    }

    public Task DisposeAsync()
    {
        _context.Dispose();
        return Task.CompletedTask;
    }

    // ---- The owner is allowed through ----

    [Fact]
    public async Task EnsureDependantAsync_ReturnsTheRecordForItsOwner()
    {
        var dependant = await _guard.EnsureDependantAsync(_data.Dependant.DependantId);

        dependant.DependantId.Should().Be(_data.Dependant.DependantId);
    }

    [Fact]
    public async Task EnsureMedicationAsync_ReturnsTheRecordForItsOwner()
    {
        var medication = await _guard.EnsureMedicationAsync(_data.Medication.MedicationId);

        medication.MedicationId.Should().Be(_data.Medication.MedicationId);
    }

    [Fact]
    public async Task EnsureScheduleAsync_ReturnsTheRecordForItsOwner()
    {
        var schedule = await _guard.EnsureScheduleAsync(_data.Schedule.ScheduleId);

        schedule.ScheduleId.Should().Be(_data.Schedule.ScheduleId);
    }

    // ---- Another caregiver is refused ----

    [Fact]
    public async Task EnsureDependantAsync_RefusesAnotherCaregiver()
    {
        _currentUser.SignInAs(_data.OtherCaregiver);

        var act = () => _guard.EnsureDependantAsync(_data.Dependant.DependantId);

        await act.Should().ThrowAsync<ForbiddenException>();
    }

    [Fact]
    public async Task EnsureMedicationAsync_RefusesAnotherCaregiverThroughTheDependantLink()
    {
        // The medication carries no caregiver column of its own, so this proves the guard walks
        // the relationship back to the owning dependant rather than trusting the id it was given.
        _currentUser.SignInAs(_data.OtherCaregiver);

        var act = () => _guard.EnsureMedicationAsync(_data.Medication.MedicationId);

        await act.Should().ThrowAsync<ForbiddenException>();
    }

    [Fact]
    public async Task EnsureScheduleAsync_RefusesAnotherCaregiverTwoRelationshipsDown()
    {
        // A schedule is two hops from its caregiver: schedule to medication to dependant. This is
        // the deepest ownership walk in the model and the easiest one to get wrong.
        _currentUser.SignInAs(_data.OtherCaregiver);

        var act = () => _guard.EnsureScheduleAsync(_data.Schedule.ScheduleId);

        await act.Should().ThrowAsync<ForbiddenException>();
    }

    [Fact]
    public async Task EnsureAppointmentAsync_RefusesAnotherCaregiver()
    {
        var appointment = new Appointment
        {
            DependantId = _data.Dependant.DependantId,
            Title = "Paediatrician follow-up",
            AppointmentDateTime = FixedNow.AddDays(3),
            ReminderMinutesBefore = 60
        };

        _context.Appointments.Add(appointment);
        await _context.SaveChangesAsync();

        _currentUser.SignInAs(_data.OtherCaregiver);

        var act = () => _guard.EnsureAppointmentAsync(appointment.AppointmentId);

        await act.Should().ThrowAsync<ForbiddenException>();
    }

    [Fact]
    public async Task EnsureDocumentAsync_RefusesAnotherCaregiver()
    {
        var document = new Document
        {
            DependantId = _data.Dependant.DependantId,
            Title = "Prescription",
            DocumentType = "prescription",
            FileName = "script.jpg",
            ContentType = "image/jpeg",
            FileSizeBytes = 1024,
            BlobUrl = "abc123.jpg"
        };

        _context.Documents.Add(document);
        await _context.SaveChangesAsync();

        _currentUser.SignInAs(_data.OtherCaregiver);

        var act = () => _guard.EnsureDocumentAsync(document.DocumentId);

        await act.Should().ThrowAsync<ForbiddenException>();
    }

    // ---- Missing and soft-deleted records ----

    [Fact]
    public async Task EnsureDependantAsync_ReportsNotFoundForAnUnknownId()
    {
        var act = () => _guard.EnsureDependantAsync(Guid.NewGuid());

        await act.Should().ThrowAsync<NotFoundException>();
    }

    [Fact]
    public async Task EnsureDependantAsync_TreatsASoftDeletedDependantAsGone()
    {
        _data.Dependant.IsActive = false;
        await _context.SaveChangesAsync();

        // The row survives for the sake of the medical history, but it must no longer be
        // reachable through the API.
        var act = () => _guard.EnsureDependantAsync(_data.Dependant.DependantId);

        await act.Should().ThrowAsync<NotFoundException>();
    }

    [Fact]
    public async Task EnsureDocumentAsync_TreatsASoftDeletedDocumentAsGone()
    {
        var document = new Document
        {
            DependantId = _data.Dependant.DependantId,
            Title = "Old prescription",
            DocumentType = "prescription",
            FileName = "old.pdf",
            ContentType = "application/pdf",
            FileSizeBytes = 2048,
            BlobUrl = "old123.pdf",
            IsDeleted = true
        };

        _context.Documents.Add(document);
        await _context.SaveChangesAsync();

        var act = () => _guard.EnsureDocumentAsync(document.DocumentId);

        await act.Should().ThrowAsync<NotFoundException>();
    }

    [Fact]
    public async Task EnsureDependantAsync_RefusesAnUnauthenticatedCaller()
    {
        _currentUser.SignOut();

        var act = () => _guard.EnsureDependantAsync(_data.Dependant.DependantId);

        await act.Should().ThrowAsync<ForbiddenException>();
    }
}
