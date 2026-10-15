# Techies Health Tracker — REST API

**PROG7314 Portfolio of Evidence — Part 2 (App Prototype Development)**
Backend component: hosted REST API and PostgreSQL database.

| | |
| --- | --- |
| **Group** | Techies |
| **Module** | PROG7314 — Programming 3D |
| **Component** | Backend — REST API and database |
| **Author** | Lesego Masike (ST10058999) — Backend Lead |
| **Stack** | ASP.NET Core 8 · Entity Framework Core 8 · PostgreSQL 16 · Firebase Authentication |
| **Builds on** | `PROG7314_Part1_Planning and Design.pdf`, sections 5 and 6 |
| **Live API** | https://techies-health-api.azurewebsites.net |
| **Swagger** | https://techies-health-api.azurewebsites.net/swagger |

## Deployed environment

The API is live on Azure App Service, backed by Azure Database for PostgreSQL Flexible Server —
the hosting platform specified in section 5.3 of the design document.

| | |
| --- | --- |
| Subscription | Azure for Students |
| Resource group | `techies-health-tracker` (South Africa North) |
| App Service | `techies-health-api` — Linux B1, always-on, HTTPS-only, TLS 1.2 minimum |
| Database | `techies-db-a06513` — PostgreSQL 16, Burstable B1ms, 32 GB |
| Firewall | Azure services only; not open to the public internet |

Verified on deployment: `/api/health` returns `{"status":"healthy","database":"reachable"}`,
protected endpoints return `401` without a token, Swagger lists all 22 routes, and plain HTTP
redirects to HTTPS.

Redeploy after a code change with:

```powershell
.\deploy\deploy-azure.ps1 -DeployOnly
```

The full script (no flag) is idempotent and safe to rerun; it skips anything that already exists.

---

## 1. What this is

The Techies Health Tracker helps caregivers manage medication schedules, appointments and
medical documents for one or more dependants. This repository holds **the server side of that
application**: the hosted REST API and the database behind it.

The guiding principle from the Part 1 design document is that *the API does the heavy lifting*.
The Android client renders and captures input; everything that requires a decision happens here:

- working out when the next dose is actually due, from a schedule the user typed in;
- deciding how many doses were due over a period, and therefore how many were missed;
- predicting when a medication will run out and when to raise a refill alert;
- reconciling dose logs captured offline against what the server already holds;
- deciding what is worth notifying a caregiver about, and when to stay quiet.

A phone that has been switched off for two days still gets the right answer when it reconnects,
because none of that reasoning depends on the phone having been awake.

---

## 2. Part 2 scope

The assignment brief separates prototype features from POE-phase features. The API implements the
**full endpoint contract** from section 5.4 of the design document, so the Android side is never
blocked waiting on a backend route — but the features marked *POE* below are the ones the Android
client will wire up in the final phase.

### Minimum requirements

| # | Requirement | Phase | Status in this API |
| --- | --- | --- | --- |
| 1 | Registration and login via SSO | Part 2 | **Done** — Firebase identity tokens validated on every request; `POST /api/auth/login` provisions the profile |
| 2 | Biometric authentication | POE | Client-side concern; the `biometricEnabled` preference is already stored and served |
| 3 | User settings | Part 2 | **Done** — `PUT /api/users/{id}/settings`, stored server-side so preferences survive a reinstall |
| 4 | Hosted REST API with a database | Part 2 | **Done** — 22 routes over 9 PostgreSQL tables |
| 5 | Offline mode with sync | POE | **API side done** — `POST /api/sync/batch` with last-write-wins conflict resolution |
| 6 | Real-time notifications | POE | **Decision logic done** — events are raised and recorded; Firebase dispatch is a POE step (see §7) |
| 7 | Multi-language support | POE | Language preference stored and validated for `en`, `zu`, `af` |

### User-defined features

The Part 2 rubric marks three user-defined features. All three are server-side, which is why they
are complete in this component:

| # | Feature | Where it lives |
| --- | --- | --- |
| 1 | **Multiple dependants per caregiver** (Additional Feature 5) | `DependantService`, and the ownership model enforced by `AccessGuard` |
| 2 | **Medication adherence analytics** (Additional Feature 3) | `AdherenceService` — percentages, daily trends, streaks and a plain-language pattern insight |
| 3 | **Low-stock refill prediction** (Additional Feature 4) | `RefillForecastService` — consumption rate, run-out date and threshold alerting |

Pharmacy search (GeoApify) and document management are also implemented, ahead of their POE phase.

---

## 3. Architecture

The layering follows section 5.1 of the design document.

```
┌─────────────────────────────────────────────────────────────┐
│  Android client (Kotlin / Jetpack Compose)                  │
│  Retrofit over HTTPS, Firebase identity token on every call │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  Presentation — Controllers/                                │
│  Routing, model binding, status codes. No business logic.   │
├─────────────────────────────────────────────────────────────┤
│  Service layer — Services/                                  │
│  Scheduling · adherence · refill forecasting · sync ·       │
│  notification decisions · ownership rules (AccessGuard)     │
├─────────────────────────────────────────────────────────────┤
│  Data access — Data/                                        │
│  Repository + Unit of Work over EF Core 8                   │
├─────────────────────────────────────────────────────────────┤
│  External — Services/External/                              │
│  GeoApify · blob storage · push delivery (behind interfaces)│
└───────────────────────────┬─────────────────────────────────┘
                            │
                  ┌─────────▼──────────┐
                  │  PostgreSQL 16     │
                  │  9 tables          │
                  └────────────────────┘
```

### Why it is put together this way

**Ownership is enforced in one place.** Every dependant-scoped request goes through
`IAccessGuard`, which loads the record, walks the relationship back to its owning caregiver and
refuses anything that belongs to somebody else. Concentrating it means a new endpoint cannot
quietly forget to apply the rule — and it makes the security boundary directly testable, which
is why `AccessGuardTests` covers every entity type including two-hop paths like
schedule → medication → dependant.

**Time is injected, never read.** Every calculation that involves time depends on
`IDateTimeProvider` rather than `DateTime.UtcNow`. Without that, a test asserting "the next dose
is at 08:00 tomorrow" would pass or fail depending on what time of day CI happened to run.

**Schedule times are local, instants are UTC.** A caregiver means "08:00, with breakfast", so
times are stored as wall-clock values and converted through `Africa/Johannesburg` only when a
concrete instant is needed. The service falls back to UTC with a warning if the host has no
time-zone database, rather than failing every request at startup.

**POE integrations sit behind seams, not stubs scattered through the code.** `IFileStorageService`
and `IPushNotificationSender` have working Part 2 implementations (local filesystem; record-only).
Switching to Azure Blob Storage and Firebase Cloud Messaging is a registration change in
`Program.cs` — see §7.

---

## 4. Running it locally

### Prerequisites

- .NET 8 SDK
- PostgreSQL 16 (or Docker)

### Start a database

```bash
docker run --name techies-db -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=techies_health_tracker -p 5432:5432 -d postgres:16
```

### Run the API

```bash
dotnet run --project src/TechiesHealthTracker.Api
```

Migrations are applied automatically at startup. Swagger is then at
`https://localhost:7005/swagger`.

### Run the tests

```bash
dotnet test
```

The suite needs no database and no network — see §6.

### Local authentication without Firebase

`appsettings.Development.json` enables `Firebase:UseDevelopmentTokens`, which accepts a locally
signed JWT instead of a real Firebase token so the API can be exercised through Swagger. This is
**refused outright in Production** regardless of configuration (`AuthenticationSetup.cs`),
because a symmetric signing key sitting in a config file would otherwise be a way in.

---

## 5. Configuration

No secret is committed. `appsettings.json` holds empty placeholders; real values come from user
secrets locally and from the Azure App Service configuration in production.

| Setting | Purpose |
| --- | --- |
| `ConnectionStrings:Default` | PostgreSQL connection string |
| `Firebase:ProjectId` | Firebase project, from which the token issuer and audience are derived |
| `Scheduling:TimeZoneId` | Time zone the schedule clock times are expressed in (`Africa/Johannesburg`) |
| `Scheduling:MissedDoseGraceMinutes` | How late a dose can be before it counts as missed |
| `ReminderScan:Enabled` / `IntervalMinutes` | The background reminder sweep |
| `GeoApify:ApiKey` | Pharmacy search. Held server-side so the key never ships in the APK |
| `BlobStorage:ConnectionString` | Azure Blob Storage for uploaded documents. Supplying it switches the API from the local filesystem implementation to Blob Storage |
| `BlobStorage:ContainerName` | The private container documents are written to (`documents`) |
| `FileStorage:SigningKey` | Signs time-limited document download URLs, used only by the local filesystem implementation |
| `Swagger:Enabled` | Serves the OpenAPI page on a deployed environment |

```bash
dotnet user-secrets set "GeoApify:ApiKey" "<key>" --project src/TechiesHealthTracker.Api
```

Document storage picks its implementation at startup from whether `BlobStorage:ConnectionString`
is set. Leave it empty and uploads go to the local filesystem, which is what lets the API and
the whole test suite run without an Azure subscription; set it and the same code writes to
Blob Storage instead. Nothing else changes — the controller, the service and the stored rows
are already the final shape.

---

## 6. Testing

**186 tests, no external dependencies.** The suite runs against an in-memory database and a fixed
clock, so it behaves identically on a laptop and in GitHub Actions.

| Area | What it covers |
| --- | --- |
| `DoseScheduleServiceTests` | Next-dose calculation, slot enumeration, day-of-week rules, time-zone boundaries, validation |
| `RefillForecastServiceTests` | Consumption rate, run-out dates, threshold alerting, edge cases |
| `AdherenceServiceTests` | Scheduled-vs-taken counting, streaks, pattern insights, date-range guards |
| `AccessGuardTests` | The ownership boundary — every entity, including multi-hop paths and soft deletes |
| `SyncServiceTests` | Replay safety, conflict resolution, per-item rejection, stock corrections |
| `DoseLogServiceTests` | The full write fan-out: log, stock, schedule advance, refill alert |
| `UserSettingsServiceTests` | Preference validation, quiet hours, cross-user refusal |
| `ApiEndpointTests` | The real HTTP pipeline end to end, including the full medication journey |
| `SwaggerDocumentTests` | That the OpenAPI document generates and describes every section 5.4 endpoint |

### Bugs the suite caught during development

These are worth recording, because each one is invisible to a passing compiler and would have
reached the demonstration:

1. **`hh` instead of `HH` in a time format.** .NET's `hh` specifier is a 12-hour clock, so a
   20:00 evening dose was normalised and stored as `08:00` — every reminder for that medication
   would have fired twelve hours early. Same bug in the quiet-hours response.
2. **Next dose not advancing.** `CalculateNextDoseAt` used an inclusive lower bound, so logging a
   dose exactly on time returned that same slot as "next" and the schedule stuck.
3. **Duplicate rows inside one sync batch.** Two queued entries for the same slot were both
   written, because the duplicate check only queried saved rows. In PostgreSQL the unique index
   would have rejected the whole batch on commit, permanently blocking that device's queue.
4. **Stock not following a corrected dose.** Reconciling a dose from missed to taken left the
   stock count untouched, drifting the refill forecast out of step with the actual pack.
5. **Swagger failing at request time.** The multipart upload compiled fine but could not be
   described by Swashbuckle, breaking the page the Android side codes against. Now pinned by a test.

---

## 7. What changed at POE

The three switches Part 2 left behind interfaces are now made. None of them touched the database
schema, the endpoint contract or any consuming service, which was the point of putting them behind
interfaces in the first place:

| POE requirement | Part 2 | Now |
| --- | --- | --- |
| Real-time notifications | `RecordOnlyPushNotificationSender` recorded events and logged them | `FirebasePushNotificationSender`, registered when a service account is configured |
| Document storage | `LocalFileStorageService` wrote to disk with HMAC-signed URLs | `AzureBlobFileStorageService`, registered when a connection string is configured |
| Multi-language notifications | Preference stored and validated, then ignored when composing | `NotificationText` composes every category in the caregiver's own language |

Each is chosen at startup from whether its credentials are actually present, so the API and the
full test suite still run with none of them — a teammate does not need a Firebase service account,
an Azure subscription or a GeoApify key to work on the rest of the system.

That last row was a real gap rather than a planned switch. The language preference was being
stored, validated and shipped to the handset, and then the server composed its notifications in
hard-coded English — so a caregiver who set the app to isiZulu got an isiZulu interface and English
push messages. `Services/Implementations/NotificationText.cs` closes it, including per-language
pluralisation and dates formatted in the reader's own culture.

---

## 8. Repository layout

```
part 2/
├── .github/workflows/backend-ci.yml   Build + test on every push
├── src/TechiesHealthTracker.Api/
│   ├── Controllers/                   11 controllers, 22 routes
│   ├── Services/
│   │   ├── Abstractions/              18 interfaces
│   │   ├── Implementations/           Business logic
│   │   ├── External/                  GeoApify, storage, push
│   │   └── Mapping/                   Entity → response
│   ├── Data/
│   │   ├── AppDbContext.cs
│   │   ├── Configurations/            9 EF configurations
│   │   ├── Repositories/              Repository + Unit of Work
│   │   └── Migrations/                InitialSchema — 9 tables, 15 indexes
│   ├── Domain/Entities/               9 entities (design doc §6)
│   ├── Contracts/                     Request and response DTOs (§5.4)
│   ├── Authentication/                Firebase token validation
│   ├── BackgroundServices/            Reminder sweep
│   └── Middleware/                    Consistent JSON error envelope
└── tests/TechiesHealthTracker.Api.Tests/
    ├── Services/                      Unit tests
    ├── Integration/                   End-to-end HTTP tests
    └── TestSupport/                   Fixed clock, fakes, in-memory host
```

---

## 9. API reference

Full request and response schemas are in Swagger. Every route requires an
`Authorization: Bearer <firebase-token>` header unless noted.

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/login` | Validate the token; create or return the profile |
| `GET` | `/api/auth/me` | The caller's profile |
| `GET` `PUT` | `/api/users/{userId}/settings` | Read and update preferences |
| `POST` `GET` | `/api/dependants` | Create and list dependants |
| `GET` `PUT` `DELETE` | `/api/dependants/{id}` | Read, update, soft-delete a dependant |
| `POST` `GET` | `/api/dependants/{id}/medications` | Add and list medications |
| `GET` `PUT` | `/api/medications/{id}` | Read and update a medication |
| `POST` | `/api/medications/{id}/schedules` | Create a dose schedule |
| `GET` | `/api/medications/{id}/refill-forecast` | Refill prediction |
| `GET` | `/api/dependants/{id}/refill-forecast` | All forecasts, ranked by urgency |
| `POST` | `/api/doselogs` | Log a dose taken or missed |
| `GET` | `/api/dependants/{id}/doselogs` | Dose history |
| `GET` | `/api/dependants/{id}/adherence` | Adherence statistics and trends |
| `POST` `GET` | `/api/dependants/{id}/appointments` | Book and list appointments |
| `PUT` `DELETE` | `/api/appointments/{id}` | Update and cancel an appointment |
| `POST` `GET` | `/api/dependants/{id}/documents` | Upload and list documents |
| `GET` `DELETE` | `/api/documents/{id}` | Read metadata, soft-delete |
| `GET` | `/api/documents/download/{name}` | Signed download (anonymous by signature) |
| `GET` | `/api/pharmacies/nearby` | Pharmacy search via GeoApify |
| `POST` | `/api/sync/batch` | Reconcile offline dose logs |
| `GET` | `/api/notifications` | Notification history |
| `GET` | `/api/health` | Liveness probe (anonymous) |

---

## 10. Continuous integration

`.github/workflows/backend-ci.yml` runs on every push and pull request:

1. restore, with NuGet caching;
2. build in Release — the API compiles with `TreatWarningsAsErrors`, so any warning fails the run;
3. run all 186 tests, publishing results and coverage as artefacts;
4. on `main`, publish a deployable artefact for Azure App Service.

---

## 11. References

- Microsoft. 2024a. *ASP.NET Core Documentation*. https://learn.microsoft.com/aspnet/core/
- Microsoft. 2024b. *Entity Framework Core Documentation*. https://learn.microsoft.com/ef/core/
- Microsoft. 2024c. *Azure App Service Documentation*. https://learn.microsoft.com/azure/app-service/
- PostgreSQL Global Development Group. 2024. *PostgreSQL 16 Documentation*. https://www.postgresql.org/docs/16/
- Google. 2024a. *Firebase Authentication Documentation*. https://firebase.google.com/docs/auth/
- Google. 2024e. *Firebase Cloud Messaging*. https://firebase.google.com/docs/cloud-messaging/
- Geoapify GmbH. 2024. *Places API Documentation*. https://apidocs.geoapify.com/docs/places/
- SmartBear. 2024. *Swashbuckle.AspNetCore*. https://github.com/domaindrivendev/Swashbuckle.AspNetCore
- Serilog Contributors. 2024. *Serilog Documentation*. https://serilog.net/
