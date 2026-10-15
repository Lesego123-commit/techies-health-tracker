// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using TechiesHealthTracker.Api.Contracts.Requests;
using TechiesHealthTracker.Api.Contracts.Responses;

namespace TechiesHealthTracker.Api.Services.Abstractions;

/// <summary>
/// Offline synchronisation (Requirement 5, section 3.5). Takes the batch of dose logs WorkManager
/// queued while the device was offline and reconciles each one against what the server already
/// holds, applying a last-write-wins rule based on the RowVersion concurrency token.
/// </summary>
public interface ISyncService
{
    Task<SyncBatchResponse> SyncDoseLogsAsync(
        SyncBatchRequest request, CancellationToken cancellationToken = default);
}
