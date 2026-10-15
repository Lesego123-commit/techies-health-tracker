// ----------------------------------------------------------------------------------------------
//  References
//   Microsoft. 2024. Dependency Injection in ASP.NET Core. [Online]. Available at:
//    https://learn.microsoft.com/aspnet/core/fundamentals/dependency-injection
//    [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 5.1 (Service Layer).
// ----------------------------------------------------------------------------------------------

using Microsoft.EntityFrameworkCore;
using TechiesHealthTracker.Api.Common;
using TechiesHealthTracker.Api.Contracts.Responses;
using TechiesHealthTracker.Api.Data;
using TechiesHealthTracker.Api.Data.Repositories;
using TechiesHealthTracker.Api.Domain.Entities;
using TechiesHealthTracker.Api.Services.Abstractions;
using TechiesHealthTracker.Api.Services.Mapping;

namespace TechiesHealthTracker.Api.Services.Implementations;

/// <summary>
/// Provisions and returns the local profile for an authenticated Firebase user.
/// See <see cref="IAuthService"/> for the contract.
/// </summary>
public class AuthService : IAuthService
{
    private readonly AppDbContext _context;
    private readonly IUnitOfWork _unitOfWork;
    private readonly ICurrentUserService _currentUser;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<AuthService> _logger;

    public AuthService(
        AppDbContext context,
        IUnitOfWork unitOfWork,
        ICurrentUserService currentUser,
        IDateTimeProvider clock,
        ILogger<AuthService> logger)
    {
        _context = context;
        _unitOfWork = unitOfWork;
        _currentUser = currentUser;
        _clock = clock;
        _logger = logger;
    }

    public async Task<UserResponse> LoginAsync(string? fcmToken, CancellationToken cancellationToken = default)
    {
        var firebaseUid = _currentUser.FirebaseUid;

        if (string.IsNullOrWhiteSpace(firebaseUid))
        {
            // Reaching here means the token passed signature validation but carried no subject,
            // which should be impossible for a genuine Firebase token.
            throw new ForbiddenException("The identity token does not contain a user identifier.");
        }

        var user = await _context.Users.FirstOrDefaultAsync(
            u => u.FirebaseUid == firebaseUid, cancellationToken);

        var isNewUser = user is null;

        if (user is null)
        {
            user = CreateProfileFromToken(firebaseUid);
            await _unitOfWork.Users.AddAsync(user, cancellationToken);

            _logger.LogInformation(
                "Provisioned new profile {UserId} for Firebase user {FirebaseUid}",
                user.UserId, firebaseUid);
        }
        else
        {
            // Google is the system of record for the profile fields, so a name or picture change
            // upstream is picked up on the next sign-in rather than drifting out of date.
            RefreshProfileFromToken(user);
        }

        // The device token changes when the app is reinstalled or its data is cleared, so it is
        // refreshed on every login rather than only at registration.
        if (!string.IsNullOrWhiteSpace(fcmToken) && user.FcmToken != fcmToken)
        {
            user.FcmToken = fcmToken;
        }

        if (!isNewUser)
        {
            _unitOfWork.Users.Update(user);
        }

        await _unitOfWork.SaveChangesAsync(cancellationToken);

        var dependants = await LoadDependantsAsync(user.UserId, cancellationToken);

        return user.ToResponse(dependants, isNewUser);
    }

    private User CreateProfileFromToken(string firebaseUid)
    {
        var email = _currentUser.Email;

        if (string.IsNullOrWhiteSpace(email))
        {
            throw new BusinessRuleException(
                "The identity token does not contain an email address, which is required to create a profile.");
        }

        return new User
        {
            FirebaseUid = firebaseUid,
            Email = email,

            // Google accounts without a display name are rare but real; falling back to the local
            // part of the email is friendlier than an empty greeting on the Home screen.
            DisplayName = string.IsNullOrWhiteSpace(_currentUser.DisplayName)
                ? email.Split('@')[0]
                : _currentUser.DisplayName!,

            PhotoUrl = _currentUser.PhotoUrl,
            CreatedAt = _clock.UtcNow,
            UpdatedAt = _clock.UtcNow
        };
    }

    private void RefreshProfileFromToken(User user)
    {
        if (!string.IsNullOrWhiteSpace(_currentUser.DisplayName))
        {
            user.DisplayName = _currentUser.DisplayName!;
        }

        if (!string.IsNullOrWhiteSpace(_currentUser.PhotoUrl))
        {
            user.PhotoUrl = _currentUser.PhotoUrl;
        }

        if (!string.IsNullOrWhiteSpace(_currentUser.Email))
        {
            user.Email = _currentUser.Email!;
        }
    }

    /// <summary>
    /// Loads the caller's dependants with their active medication counts in a single projection,
    /// so that the login response does not fire one extra query per dependant.
    /// </summary>
    private async Task<List<DependantResponse>> LoadDependantsAsync(
        Guid userId, CancellationToken cancellationToken)
    {
        var today = _clock.Today;

        var rows = await _context.Dependants
            .AsNoTracking()
            .Where(d => d.CaregiverUserId == userId && d.IsActive)
            .OrderBy(d => d.FirstName)
            .Select(d => new
            {
                Dependant = d,
                ActiveMedicationCount = d.Medications.Count(m => m.IsActive)
            })
            .ToListAsync(cancellationToken);

        return rows
            .Select(r => r.Dependant.ToResponse(today, r.ActiveMedicationCount))
            .ToList();
    }
}
