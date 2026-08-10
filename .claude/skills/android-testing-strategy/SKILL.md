---
name: android-testing-strategy
description: Testing strategy and conventions for this Kotlin + Jetpack Compose project — the test pyramid, ViewModel/Flow unit tests with Turbine and MockK, Compose UI tests, Robolectric, screenshot tests, and Maestro E2E. Use when writing, generating, or reviewing tests, or deciding what level of test a change needs.
---

# Android Testing Strategy

## Test pyramid (target mix)
- ~70% fast JVM unit tests (domain, ViewModels, mappers, repositories with fakes).
- ~20% integration (Room with in-memory DB, repository + DAO, Robolectric).
- ~10% UI/E2E (Compose UI tests, Maestro flows on critical paths).
- Write the cheapest test that gives confidence; don't push logic-only checks up to UI.

## Unit tests
- Use `kotlinx-coroutines-test`: `runTest`, `StandardTestDispatcher`, `advanceUntilIdle()`.
- Inject dispatchers (a `DispatcherProvider`) so tests control time — never call `Dispatchers.IO` directly.
- Assert `StateFlow`/`Flow` emissions with **Turbine** (`flow.test { awaitItem() ... }`).
- Mock collaborators with **MockK** (Kotlin-native: objects, coroutines, relaxed mocks).
- Prefer hand-written fakes for repositories/data sources over mocks where behavior matters.

## ViewModel test pattern
1. Arrange fakes + `StandardTestDispatcher`.
2. Construct the ViewModel.
3. `viewModel.uiState.test { ... }` — assert initial, send `onEvent(...)`, assert transitions.
4. Verify one-shot effects on the effects channel separately.

## Integration tests
- Room: `Room.inMemoryDatabaseBuilder`, test DAO queries + migrations (`MigrationTestHelper`).
- Repository: real DAO + fake remote, assert mapping and caching.
- Robolectric runs Android-dependent tests on the JVM (fast, no device).

## Compose UI tests
- `createComposeRule()` / `createAndroidComposeRule<Activity>()`.
- Find nodes by semantics (`onNodeWithText`, `onNodeWithContentDescription`, test tags).
- Test states (loading/empty/error/content) by feeding `UiState`, plus key interactions.
- Use `mainClock` for animation control; prefer semantics matchers over brittle tag soup.

## Screenshot tests
- **Paparazzi** (JVM, no device) or Compose preview screenshot testing for theming/layout regressions.
- Cover light/dark and compact/expanded for shared components.

## E2E
- **Maestro** YAML flows for the few business-critical journeys (login, primary task, checkout).
- Keep E2E small and stable; they are the most expensive to maintain.

## CI gates
- PR: lint + detekt + unit + screenshot must pass.
- Nightly/pre-release: instrumented + Maestro on an emulator matrix.

## Pitfalls
- Calling real `Dispatchers.*` in tests (flaky) — inject dispatchers.
- Swallowing `CancellationException` in code under test.
- Over-mocking until tests assert mocks, not behavior.
- Brittle UI tests bound to exact strings/structure instead of semantics.
- No migration tests — schema changes crash in production instead of CI.
