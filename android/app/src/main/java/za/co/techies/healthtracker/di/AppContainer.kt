// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Manual Dependency Injection on Android. [Online]. Available at:
//    https://developer.android.com/training/dependency-injection/manual
//    [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import za.co.techies.healthtracker.BuildConfig
import za.co.techies.healthtracker.core.auth.FirebaseTokenProvider
import za.co.techies.healthtracker.core.auth.TokenProvider
import za.co.techies.healthtracker.core.auth.BiometricGate
import za.co.techies.healthtracker.core.location.AndroidLocationProvider
import za.co.techies.healthtracker.core.location.LocationProvider
import za.co.techies.healthtracker.core.network.AndroidConnectivityObserver
import za.co.techies.healthtracker.core.notifications.FirebasePushTokenProvider
import za.co.techies.healthtracker.core.notifications.NoPushTokenProvider
import za.co.techies.healthtracker.core.notifications.PushTokenProvider
import za.co.techies.healthtracker.core.network.ConnectivityObserver
import za.co.techies.healthtracker.data.local.TechiesDatabase
import za.co.techies.healthtracker.data.repository.SyncEngine
import za.co.techies.healthtracker.core.auth.EmailPasswordAuthClient
import za.co.techies.healthtracker.core.auth.GoogleAuthClient
import za.co.techies.healthtracker.core.auth.UnconfiguredTokenProvider
import za.co.techies.healthtracker.core.network.NetworkFactory
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.data.remote.TechiesApi
import za.co.techies.healthtracker.data.repository.AppointmentRepository
import za.co.techies.healthtracker.data.repository.AuthRepository
import za.co.techies.healthtracker.data.repository.DependantRepository
import za.co.techies.healthtracker.data.repository.DocumentRepository
import za.co.techies.healthtracker.data.repository.DoseRepository
import za.co.techies.healthtracker.data.repository.MedicationRepository
import za.co.techies.healthtracker.data.repository.NotificationRepository
import za.co.techies.healthtracker.data.repository.PharmacyRepository
import za.co.techies.healthtracker.data.repository.SettingsRepository

/**
 * The dependency graph, wired by hand.
 *
 * Hilt was the obvious alternative and is the more conventional choice. This project uses manual
 * constructor injection instead, for two reasons that matter more here than convention does:
 *
 *  1. **It cannot break the build.** Hilt needs KSP, whose version has to match the Kotlin version
 *     exactly. That coupling is the most common way an Android project stops compiling when
 *     somebody bumps a version, and this repository is shared across three people's machines.
 *  2. **The whole graph is visible in one file.** For a team picking this up mid-project, that is
 *     worth more than annotation-driven wiring spread across a dozen modules.
 *
 * Nothing about the rest of the codebase assumes this choice. Every class takes its dependencies
 * through its constructor, which is what makes them testable — swapping in Hilt later means
 * annotating those same constructors and deleting this file.
 *
 * Everything is created lazily, so opening the app does not build a Retrofit instance before the
 * first screen needs one.
 */
class AppContainer(private val context: Context) {

    /**
     * Firebase is only reachable once `google-services.json` is present. Without it the app still
     * runs — it simply has no session, and the sign-in screen says so rather than crashing on
     * launch with an uninitialised-FirebaseApp error that tells a newcomer nothing.
     */
    val tokenProvider: TokenProvider by lazy {
        if (BuildConfig.FIREBASE_CONFIGURED) {
            FirebaseTokenProvider(FirebaseAuth.getInstance())
        } else {
            UnconfiguredTokenProvider
        }
    }

    val isFirebaseConfigured: Boolean get() = BuildConfig.FIREBASE_CONFIGURED

    /** Google single sign-on. Reports itself unconfigured when google-services.json is absent. */
    val googleAuthClient: GoogleAuthClient by lazy {
        GoogleAuthClient(
            context = context,
            firebaseAuth = if (BuildConfig.FIREBASE_CONFIGURED) FirebaseAuth.getInstance() else null
        )
    }

    /**
     * The device's push registration token, sent to the API on every login.
     *
     * Falls back to a no-op provider when Firebase is not configured, so a build without
     * google-services.json still signs in rather than crashing on a missing FirebaseApp.
     */
    val pushTokenProvider: PushTokenProvider by lazy {
        if (BuildConfig.FIREBASE_CONFIGURED) FirebasePushTokenProvider() else NoPushTokenProvider
    }

    /** For components that genuinely need a Context, such as WorkManager requests. */
    val applicationContext: Context get() = context

    /**
     * The on-device database and the offline queue built on it.
     *
     * Opened lazily, so a build with no Firebase configuration still starts: nothing here
     * touches the network, and the queue is useful even before anyone signs in.
     */
    val database: TechiesDatabase by lazy { TechiesDatabase.create(context) }

    val connectivity: ConnectivityObserver by lazy { AndroidConnectivityObserver(context) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            dao = database.pendingDoseLogs(),
            sender = { request -> doseRepository.syncOfflineLogs(request) },
            connectivity = connectivity
        )
    }

    /** Biometric unlock. Reports its own availability, so callers never guess. */
    val biometricGate: BiometricGate by lazy { BiometricGate(context) }

    val locationProvider: LocationProvider by lazy { AndroidLocationProvider(context) }

    /** Email and password registration and sign-in, the alternative to Google SSO. */
    val emailPasswordAuthClient: EmailPasswordAuthClient by lazy {
        EmailPasswordAuthClient(
            firebaseAuth = if (BuildConfig.FIREBASE_CONFIGURED) FirebaseAuth.getInstance() else null
        )
    }

    /**
     * App-wide state: the signed-in caregiver and the dependant currently in view.
     *
     * A singleton for the process lifetime, because the selected dependant has to be the same on
     * Home, Trends and Refill. Scoping it to any one ViewModel would reset it on navigation.
     */
    val session: Session by lazy { Session() }

    /** The API base URL, set per build type and overridable in local.properties. */
    val apiBaseUrl: String get() = BuildConfig.API_BASE_URL

    private val okHttpClient by lazy {
        NetworkFactory.createOkHttpClient(
            tokenProvider = tokenProvider,

            // Request and response bodies carry medical data, so full logging is debug-only.
            enableLogging = BuildConfig.DEBUG
        )
    }

    val api: TechiesApi by lazy { NetworkFactory.createApi(apiBaseUrl, okHttpClient) }

    // ---- Repositories ----

    val authRepository by lazy { AuthRepository(api) }
    val settingsRepository by lazy { SettingsRepository(api) }
    val dependantRepository by lazy { DependantRepository(api) }
    val medicationRepository by lazy { MedicationRepository(api) }
    val doseRepository by lazy { DoseRepository(api) }
    val appointmentRepository by lazy { AppointmentRepository(api) }
    val documentRepository by lazy { DocumentRepository(api, context.contentResolver) }
    val notificationRepository by lazy { NotificationRepository(api) }
    val pharmacyRepository by lazy { PharmacyRepository(api) }
}
