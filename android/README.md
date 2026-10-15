# Techies Health Tracker — Android Client

**PROG7314 POE Part 2** · Kotlin · Jetpack Compose · Retrofit

A working Android client: seven screens over the deployed API, with the full network layer
underneath. Firebase configuration is the only thing still outstanding before sign-in works.

| | |
| --- | --- |
| **Package** | `za.co.techies.healthtracker` |
| **minSdk / targetSdk** | 26 / 36 |
| **Language** | Kotlin 2.2.20, Jetpack Compose |
| **Networking** | Retrofit 2.11 · OkHttp 4.12 · kotlinx.serialization |
| **Auth** | Firebase Authentication (Google SSO) |
| **Status** | 7 screens, 44 unit tests passing, debug and release APKs verified |
| **API** | Live at https://techies-health-api.azurewebsites.net |

---

## 1. What is already here

### Screens

| Screen | Design doc | Rubric |
| --- | --- | --- |
| Sign In — Google SSO | 4.1 | SSO [10] |
| Home — doses due, one-tap logging | 4.3 | UI [10] |
| Dependants — switcher and add form | 4.4 | User-defined 1 [10] |
| Add medication — with its dose schedule | 4.6 | — |
| Trends — adherence chart, streak, pattern insight | 4.7 | User-defined 2 [10] |
| Refill — run-out forecasts by urgency | 4.8 | User-defined 3 [10] |
| Settings — language, theme, reminders, thresholds | 4.13 | Settings [10] |

Five of them sit behind a bottom navigation bar; Sign In is outside it, and the back stack is
cleared on entry so the hardware back button cannot return to it.

### Network layer

**All 22 API endpoints, tested.** DTOs that match the server contracts field for field, automatic
Firebase token attachment, and one error type for every kind of failure.

| Area | File |
| --- | --- |
| Endpoint definitions | `data/remote/TechiesApi.kt` |
| Request and response DTOs | `data/remote/dto/` |
| Repositories | `data/repository/Repositories.kt` |
| Token attachment and 401 retry | `core/network/AuthInterceptor.kt` |
| Error normalisation | `core/network/ApiCall.kt`, `ApiError.kt` |
| Date handling | `core/network/Serializers.kt` |
| Dependency graph | `di/AppContainer.kt` |

Two more pieces worth knowing about: `core/session/Session.kt` holds the signed-in caregiver and
the dependant currently in view — shared state, because Home, Trends and Refill all have to agree
on which person they are showing — and `ui/components/` carries the shared loading, error and
empty states so every screen fails the same way.

## 2. Getting it running

### Open and build

```bash
cd android
./gradlew assembleDebug
```

Android Studio writes `local.properties` with your SDK path on first open. That file is
gitignored deliberately — a hard-coded path from someone else's laptop is a common reason a
cloned Android project will not build.

### Point it at the API

The base URL comes from `local.properties`, so you can change it without touching a tracked file:

```properties
apiBaseUrl=http://10.0.2.2:5192/
```

| Where you are running | Use |
| --- | --- |
| **Deployed API (current default)** | `https://techies-health-api.azurewebsites.net/` |
| Emulator, API on this machine | `http://10.0.2.2:5192/` |
| Physical phone, API on this machine | `http://<your-LAN-ip>:5192/` and add that IP to `res/xml/network_security_config.xml` |

**The API is live on Azure**, and `local.properties` already points at it. That means you can run
the app on any phone, anywhere, without anyone's laptop being switched on — which is what lets all
three of us work against the same backend. Comment the `apiBaseUrl` line out to go back to a
locally running API.

Cleartext HTTP is permitted only for `10.0.2.2` and `localhost`. Everything else is HTTPS-only —
the app carries medical records, so blanket `usesCleartextTraffic` was not a trade worth making.

### Set up Firebase (needed for sign-in)

In the Firebase console, under **Authentication > Sign-in method**, enable **both** providers:

1. **Google** — the one-tap route.
2. **Email/Password** — the alternative for anyone without a Google account. Leave "Email link"
   off; only the password option is used.

Then:

3. Register the Android app with package name `za.co.techies.healthtracker`.
4. Add this **SHA-1 fingerprint** — one value covers all of us, see below:

   ```
   96:EE:C7:01:8F:4B:3B:34:59:10:1E:81:12:63:B3:84:D8:92:EA:6D
   ```

5. Download `google-services.json` into `app/`.

Email/Password is the one that is easy to forget, because nothing warns you until someone tries
it. If it is off, the Sign In screen says so directly rather than showing an SDK error:

> Email sign-in is not enabled for this project yet. Enable Email/Password under Authentication
> in the Firebase console, then try again.

### If Google sign-in says the SHA-1 is not registered

That message means the build was signed with a fingerprint Firebase does not know. In order of
likelihood:

1. **The shared keystore is missing from your copy.** Check `keystore/techies-debug.keystore`
   exists, then confirm what your build is actually signing with:

   ```bash
   ./gradlew signingReport
   ```

   The `debug` variant must report `96:EE:C7:01:...:EA:6D`. Anything else means Gradle fell back
   to a per-machine key — the build log carries a warning to that effect.

2. **You are on an emulator without Google Play.** Credential Manager needs Play Services, so an
   AOSP system image cannot do Google sign-in at all. Use an image labelled *Google Play* or
   *Google APIs*, or use the email and password route instead.

3. **No Google account on the device.** Add one under Settings, or use email and password.

The email and password route depends on none of this — no SHA-1, no Play Services, no Google
account — so it is also the quickest way to confirm the rest of the app works.

The project builds and runs without this file — the Google Services plugin is applied only when
it is present, and the connection screen says plainly that Firebase is unconfigured. Without it,
every API call is unauthenticated and comes back `401`.

### Why there is a keystore in the repository

`keystore/techies-debug.keystore` is committed deliberately, and `.gitignore` carries a single
explicit exception for it.

Google Sign-In authorises an app by the SHA-1 of the certificate that signed it. Left alone, the
Android Gradle plugin generates a debug keystore **per machine** — so Lesego's builds, Senamiso's
builds and every CI run would each have a different fingerprint, and only whichever one happened
to be registered in Firebase could sign in. The others fail with `ApiException: 10`, which names
nothing useful and is genuinely unpleasant to diagnose.

Sharing one debug keystore means a single registered SHA-1 covers every developer *and* the APK
that CI produces — which is what makes it possible to hand a CI-built APK to whoever is recording
the demonstration video and have sign-in simply work.

This is safe for a **debug** key: it signs nothing that can install over a real release, and it
uses the well-known Android default password. A **release** keystore is a different matter and
must never be committed; `.gitignore` still excludes every other `*.jks` and `*.keystore`.

If the file ever goes missing the build still succeeds, falling back to a per-machine key — but it
logs a loud warning, because otherwise the only symptom would be sign-in mysteriously breaking.


---

## 3. Using the layer

```kotlin
val container = (application as TechiesApplication).container

// Call this on every launch. Every other endpoint returns 404 until it has run once.
container.authRepository.login()
    .onSuccess { user -> /* user.dependants is already populated */ }
    .onFailure { error -> /* error is an ApiException; error.message is safe to display */ }

// Logging a dose needs three ids. The API resolves which slot the tap belongs to.
container.doseRepository.markTaken(scheduleId, dependantId, medicationId)
    .onSuccess { result ->
        // One response: the log, the new stock figure, the next dose and a fresh adherence summary
        result.remainingQuantity
        result.nextDoseAt
        result.adherenceSummary.currentStreakDays
    }
```

**Do not recompute what the API already sends.** `age`, `fullName`, `dosageDisplay` ("500 mg"),
`nextDoseAt`, `adherencePercentage`, `daysUntilRunOut` and a `severity` of `ok`/`low`/`depleted`
all arrive ready to render. Re-deriving any of them creates a second source of truth that can
disagree with the server.

### Errors

Every failure arrives as `ApiException` with a `code`, a displayable `message`, a `fieldErrors`
map for form validation, and `isRetryable` / `requiresReauthentication` flags. One `onFailure`
branch handles the lot.

### Dates

Three different shapes, kept distinct on purpose:

- `Instant` — a moment in time (`nextDoseAt`, `loggedAt`). Convert to local for display.
- `LocalDate` — a calendar date (`dateOfBirth`, `startDate`). Treating one as an instant shifts it
  across a day boundary for anyone east of UTC, which is everyone here.
- `String` — schedule clock times (`"08:00"`). A wall-clock time, not a moment at all.

---

## 4. Testing

44 unit tests, no device or network needed:

```bash
./gradlew testDebugUnitTest
```

| Suite | Covers |
| --- | --- |
| `AuthInterceptorTest` | Token attachment, the 401 refresh-and-retry, when *not* to retry, signed download URLs left alone |
| `DtoSerializationTest` | Real API payloads parsing into the DTOs, partial-update encoding, unknown-field tolerance |
| `ApiErrorHandlingTest` | Every failure path normalising to `ApiException`, including unparseable bodies and dropped connections |
| `SessionTest` | The selected-dependant rules — falling back when one is deleted, surviving a re-login, clearing on sign-out |
| `FormattingTest` | Relative dose phrasing and overdue detection, the only display logic the client owns |

They run against MockWebServer, so they assert on exactly what left the device.

---

## 5. What is left

**Firebase configuration is the blocker.** Until `google-services.json` is in `app/`, the Sign In
screen says so plainly and nothing behind it can be reached — every API call comes back 401. See
section 2.

After that:

- **Appointments** — the API endpoints exist (`/api/dependants/{id}/appointments`) and the DTOs and
  repository are written; only the screen is missing. It is not on the Part 2 rubric, so it was
  left until the marked screens were done.
- **Editing** — medications and dependants can be created but not yet edited. `PUT` endpoints and
  repository methods are ready.
- **POE phase** — Pharmacy Finder, Documents, Notifications, Biometric Unlock, offline sync with
  RoomDB, and isiZulu/Afrikaans string resources. The API side of the first three is already live.

### The pattern to follow

Every screen is the same shape, so a new one has a worked example to copy:

- a `ViewModel` holding **one immutable `UiState`**, exposed as a `StateFlow` — making
  "loading and failed at once" unrepresentable, which is what stops a spinner that never clears
- dependencies through the constructor, with a `factory(container)` in the companion object
- a stateless `@Composable` taking the state and callbacks, so it can be previewed and tested
- shared `LoadingState` / `ErrorState` / `EmptyState` from `ui/components`

`RefillViewModel` is the smallest complete example; `HomeViewModel` shows the same pattern with a
write path in it.

---

## 6. Notes on two decisions

**Manual dependency injection instead of Hilt.** Hilt needs KSP, whose version must match Kotlin
exactly — the most common way a shared Android project stops compiling when somebody bumps a
version. `AppContainer` keeps the whole graph visible in one file and cannot break the build.
Every class still takes its dependencies through its constructor, so swapping in Hilt later means
annotating those same constructors and deleting one file.

**Strings live in `res/values/strings.xml`.** Multi-language support is a POE requirement
(English plus two South African languages). Extracting hard-coded strings from a dozen finished
screens is far more work than starting this way; isiZulu and Afrikaans go in `values-zu/` and
`values-af/`.

---

## 7. Reference

- [`../docs/Android-Integration-Notes.md`](../docs/Android-Integration-Notes.md) — API behaviour, controlled vocabularies, offline sync contract
- [`../README.md`](../README.md) — the backend
- Swagger — the authoritative schema reference, at `/swagger` on the running API
