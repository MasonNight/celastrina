# Coding standards

Read during review. These are judgement calls; anything mechanical is enforced by `./gradlew check` and CI instead.

## Domain language

- Name domain types, functions and tests with `GLOSSARY.md` terms. Flag a name that collides with a glossary term meaning something else (e.g. a `Kind` that isn't a **Source Kind**), and flag a domain concept the glossary lacks so it can go through `/domain-modeling`.

## Core and adapters

- The Core reaches the outside only through its ports (`dev.celastrina.core.port`). Each external service's wire format (TMDB, Jellyfin, Stremio, Internet Archive JSON) stays private to the adapter that reads it; domain types never carry wire field names or shapes.
- Failures cross the Core's API as typed reasons (like `CatalogueUnavailable.Reason`), so screens and the configuration ContentProvider can say something specific.

## Tests

- Core tests replay **real recorded** responses (`core/src/testFixtures/resources/recorded/`), captured from the live service and stripped of keys and tokens. A hand-written fixture is acceptable only for a response the live service can't be made to produce, and says so in a comment.
- A recorded fake picks its recording from every part of the request the adapter varies (each ID scheme in a query, each path), so every branch the adapter can take has a test that goes red without it. A fake keyed on one field leaves the other branches untested.
- Tests read like Household or agent scenarios and assert through the Core's public API, per the spec's Testing Decisions (#20).

## Scope

- Each ticket implements its own acceptance criteria. Behaviour that a sibling ticket owns (an on-screen message, a setting, a gate) is left for that ticket, or flagged in the PR when a stopgap is unavoidable.
