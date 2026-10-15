# AI Usage Write-Up — Part 2

**Module:** PROG7314 — Programming 3D
**Group:** Techies
**Component:** Part 2, App Prototype Development
**Author:** Lesego Masike (ST10058999), Backend Lead
**Word count:** 598

---

## Scope of this declaration

This write-up covers **Part 2 only** — the implementation phase.

**Part 1 was completed without any AI assistance.** The application concept, the user research,
the feature set, the screen designs and wireframes, the database schema and the endpoint contract
were produced by the group before implementation began. Nothing in this declaration applies to
that work.

## Tool used

Anthropic's Claude (Claude Code) was used during Part 2 implementation. No other generative AI
tool was used.

## How it was used

**Locating and applying documentation.** Much of the value was in finding the right source
quickly and applying it correctly. Credential Manager for Google sign-in, Npgsql's rules for
timestamp handling, Retrofit's serialization behaviour and Compose navigation each have
documentation that is easy to find but easy to misapply. Every source file in this submission
carries a reference header citing the documentation it was built from, and those citations came
out of that process.

**Generating code against a contract the group had already fixed.** Section 5.4 of the Part 1
design document specified every endpoint, and section 6 specified every table. Working from
those, the AI produced the entity classes, EF Core configurations, request and response DTOs,
the service layer and the controllers. Because the contract was settled in advance, this was
largely transcription of a design the group had already made — and it was checked field by field
against section 6, with column types, lengths, nullability and defaults verified to match.

The same applied to the Android client: the twelve screens follow the wireframes in section 4 of
the design document, and the navigation structure follows Figure 2.

**Test design.** The AI produced the test suites — 194 backend tests and 49 on the Android side —
including the fixed-clock test double that made the scheduling logic testable at all. These
earned their place: they caught a 12-hour format specifier that would have stored every 20:00
dose as 08:00, and a duplicate-row bug that would have permanently blocked a device's offline
sync queue.

**Diagnosing faults that no documentation covered.** Several defects only appeared once the app
was run against the deployed API, and these were the cases where documentation alone was not
enough:

- `DateOnly.ToDateTime` returns `Kind=Unspecified`, which PostgreSQL rejects but the in-memory
  test provider accepts — so the adherence endpoint passed every test and failed only in
  production.
- Screens behind a form displayed stale data, because a ViewModel scoped to a navigation entry
  survives that entry being covered.
- A stored dark-mode preference was never passed to the theme.
- Swagger generation failed at request time on the multipart document upload.

Each was found by running the application, diagnosed with AI assistance, fixed, and pinned with a
regression test.

## What was not delegated

The design decisions were the group's and predate any AI involvement: the seven-day refill
threshold, the five-minute minimum reminder lead time, last-write-wins conflict resolution, and
measuring adherence from the schedule rather than the log table all come from Part 1.

Two architectural choices were adopted during implementation after discussion and were accepted
deliberately rather than by default: concentrating ownership checks in a single `IAccessGuard`
instead of repeating them per service, and injecting `IDateTimeProvider` rather than calling
`DateTime.UtcNow` directly.

Every file was reviewed before it was accepted. Generated code that was wrong was corrected, and
code that was merely adequate was rewritten. The application was deployed to Azure, run on a
device against the live API, and verified screen by screen. The author can explain any part of
the submitted code.

## Citation

Anthropic. 2026. *Claude* (Claude Code). [Large language model]. Available at:
https://claude.ai/code [Accessed 20 September 2026].
