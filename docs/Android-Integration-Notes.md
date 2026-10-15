# Android Integration Notes

For Senamiso and Sameer. This is what the Android client needs to know to talk to the API, and
the handful of places where the API's behaviour affects how a screen should be built.

Swagger is the authoritative reference for schemas (`/swagger`). This document covers the things
Swagger cannot tell you.

---

## 1. Every call carries a Firebase token

```
Authorization: Bearer <firebase-id-token>
```

Get it from `FirebaseAuth.getInstance().currentUser?.getIdToken(false)`. Tokens expire after an
hour, so fetch it per request rather than caching it — the Firebase SDK refreshes transparently.

A missing or expired token gives `401`. Refresh and retry once; if it still fails, send the user
back to sign-in.

**Call `POST /api/auth/login` on every app launch.** It is idempotent and it is what provisions
the user's profile on first sign-in. Every other endpoint returns `404` until it has run once.
The response carries the profile, the settings and the dependants list, so the Home screen can
render from this one call.

Pass the FCM token as a query parameter when you have one:
`POST /api/auth/login?fcmToken=<token>`.

---

## 2. Errors have one shape

Every failure — validation, business rule, authorisation, unexpected — returns the same envelope:

```json
{
  "error": "invalid_request",
  "message": "Frequency twice_daily expects exactly 2 time(s) but 1 were supplied.",
  "status": 400,
  "traceId": "0HN7G2K9..."
}
```

Field-level validation failures add an `errors` object keyed by field name, which is what you
want for highlighting a form.

| `error` | Status | What to do |
| --- | --- | --- |
| `validation_failed` | 400 | Highlight the named fields |
| `invalid_request` | 400 | Show `message` — it is written to be read by a user |
| `forbidden` | 403 | Should not happen in normal use; log it |
| `not_found` | 404 | The record is gone; refresh the list |
| `concurrency_conflict` | 409 | Another device changed it; re-fetch |
| `upstream_unavailable` | 502 | GeoApify is down; show a retry |
| `server_error` | 500 | Generic retry |

`message` is safe to display. It never contains internal detail.

---

## 3. Let the API do the work

This is the part that saves the most client code.

**Do not calculate the next dose.** `nextDoseAt` comes back on every schedule and is recalculated
server-side whenever a dose is logged. Just render it.

**Do not calculate adherence, streaks or run-out dates.** `GET /api/dependants/{id}/adherence`
returns the percentage, a per-day series ready to plot, the current streak and a plain-language
insight string. `refill-forecast` returns `daysUntilRunOut` and a `severity` of `ok` / `low` /
`depleted` — colour the Refill screen straight off that field rather than deciding thresholds
in the app.

**Do not calculate ages or format dosages.** `DependantResponse.age`, `fullName` and
`MedicationResponse.dosageDisplay` ("500 mg") are computed server-side so every screen agrees.

**Logging a dose needs almost nothing.** `POST /api/doselogs` accepts just the three ids and a
status; the server resolves which scheduled slot it belongs to. The response carries the updated
adherence summary, the remaining stock and the new `nextDoseAt`, so the Home screen updates from
one round trip.

---

## 4. Offline sync (POE phase)

Queue dose logs in RoomDB with a `pendingSync` flag, then post them in one batch via
`POST /api/sync/batch` when WorkManager runs.

Send your RoomDB primary key as `localId`. It comes back untouched in the response so you can
match results to rows.

Each item gets its own verdict:

| `status` | Meaning | What to do |
| --- | --- | --- |
| `synced` | Written | Clear the pending flag |
| `duplicate` | Already on the server, identical | Clear the pending flag |
| `conflict` | The server holds a newer entry | Clear the flag, adopt `serverRowVersion` |
| `rejected` | Invalid — see `message` | Drop it and log; retrying will not help |

**Replaying a batch is safe.** If you are not sure the last attempt landed, send it again — an
entry already on the server comes back as `duplicate`, not a second row.

Conflicts resolve by last-write-wins on `loggedAt`, not on arrival order, so the caregiver's most
recent tap wins regardless of which device reached the server first.

Batches are capped at 500 items.

---

## 5. Settings

`PUT /api/users/{userId}/settings` is a partial update — send only what changed. The `userId` must
be the signed-in user's own; anything else gives `403`.

- `preferredLanguage`: `en`, `zu` or `af` only
- `theme`: `light`, `dark` or `system`
- Quiet hours are all-or-nothing. Send both `quietHoursStart` and `quietHoursEnd` in 24-hour
  `HH:mm`, or send an empty string for either to clear both.

Preferences are stored server-side, so mirror them into DataStore for offline reading but treat
the API as the source of truth on launch.

---

## 6. Controlled vocabularies

The API rejects anything outside these lists, case-insensitively:

| Field | Accepted values |
| --- | --- |
| Dose log `status` | `taken`, `missed` |
| Schedule `frequency` | `once_daily`, `twice_daily`, `three_times_daily`, `weekly`, `custom` |
| Medication `form` | `capsule`, `tablet`, `liquid`, `injection`, `inhaler`, `drops` |
| `dosageUnit` | `mg`, `ml`, `tablets`, `g`, `mcg`, `iu`, `puffs`, `drops`, `units` |
| Dependant `relationship` | `child`, `parent`, `spouse`, `self`, `other` |
| Document `documentType` | `prescription`, `lab_result`, `medical_report`, `other` |

Two rules worth building into the form:

- `once_daily` needs exactly one time, `twice_daily` exactly two, `three_times_daily` exactly three.
- `weekly` needs at least one entry in `daysOfWeek` (lowercase English day names).

---

## 7. Dates and times

- **Instants** (`loggedAt`, `appointmentDateTime`, `nextDoseAt`) are UTC ISO-8601 —
  `2026-04-15T18:00:00Z`. Convert for display.
- **Schedule times** (`times`) are local wall-clock strings in 24-hour `HH:mm`. "08:00" means
  08:00 to the caregiver; the server converts through `Africa/Johannesburg`.
- **Dates** (`dateOfBirth`, `startDate`) are `yyyy-MM-dd` with no time component.

Retrofit with a Gson/Moshi adapter for `Instant` handles the first case; treat the other two as
strings.

---

## 8. Documents

Upload is `multipart/form-data` with `file`, `title` and `documentType`. Maximum 10 MB; JPEG,
PNG, HEIC, WebP and PDF only.

Responses carry a **time-limited signed `downloadUrl`**, valid for 15 minutes, along with
`downloadUrlExpiresAt`. The URL needs no `Authorization` header, so you can hand it straight to
Coil or Glide. Do not cache it past its expiry — re-fetch the document metadata for a fresh one.

---

## 9. Local development

The API runs at `https://localhost:7005`. From the Android emulator that is
`https://10.0.2.2:7005`, and you will need a network security config to trust the dev certificate.

`Firebase:UseDevelopmentTokens` is enabled in `appsettings.Development.json`, which lets you
exercise the API through Swagger without a live Firebase project. It is refused in Production.

Anything unclear or missing — tell me and I will add the endpoint or adjust the shape. The API
should fit the screens, not the other way round.

— Lesego
