---
name: android-architecture-conventions
description: Architecture rules for this Kotlin + Jetpack Compose project — MVVM with strict unidirectional data flow, Clean Architecture layering (data/domain/presentation), feature-based modularization, Hilt DI, and StateFlow-based state management. Use when designing architecture, scaffolding modules, or implementing/reviewing ViewModels, repositories, DI, navigation, or error handling.
---

# Android Architecture Conventions

## Architecture: MVVM + strict UDF (MVI-flavored)
- ViewModel exposes ONE immutable `UiState` via `StateFlow`.
- ViewModel receives user intents through ONE entry point: `onEvent(event)`.
- This gives a single source of truth (MVI) without a heavyweight reducer framework.
- The ViewModel is a pure function of (state, event): trivially unit-testable and restorable.

## Clean Architecture layering (dependency rule: inward only)
- **data** — repository *implementations*, data sources (Retrofit/Room/DataStore), DTOs, mappers.
- **domain** — pure Kotlin, NO Android deps: entities, repository *interfaces*, use cases. Optional for small apps.
- **presentation** — ViewModels, `UiState`, Compose UI.
- Domain depends on nothing. Data and presentation depend on domain.
- Map DTO -> domain -> UI model at each boundary so a backend change never reaches the UI.

## Modularization (hybrid: feature isolation + shared cores)
```
:app              -> DI wiring, NavHost, Application class
:core:ui          -> design system / theme
:core:common      -> utilities, Result types
:core:network     -> Retrofit/OkHttp setup
:core:database    -> Room setup
:core:model       -> shared models
:core:testing     -> test fakes/rules
:domain           -> entities, repo interfaces, use cases (pure Kotlin)
:feature:<name>   -> self-contained: UI + ViewModel + feature data wiring
```
- **Rule: a feature module NEVER depends on another feature module.** Route through
  navigation + domain interfaces instead.
- Use Gradle convention plugins (`build-logic`) to centralize config; keep module build files tiny.

## Dependency Injection: Hilt (default)
- Compile-time validated; first-class ViewModel/WorkManager/Navigation/Compose integration.
- ViewModels: `@HiltViewModel` + `hiltViewModel()`.
- Use `@ApplicationContext`, not raw `Context`.
- Scope deliberately — do NOT make everything `@Singleton`.
- Choose Koin only for Kotlin Multiplatform or to avoid KSP build cost.

## State management
- `val uiState: StateFlow<UiState> = ...stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`.
- Collect in UI with `collectAsStateWithLifecycle()` (lifecycle-aware).
- Model `UiState` as an immutable `data class` (overlapping flags) OR a `sealed interface`
  (Loading/Success/Error when mutually exclusive).
- One-shot events (navigation, snackbars) go through a `Channel`/`SharedFlow` "effects" stream,
  NOT in `UiState` (otherwise they re-fire on rotation).

## Error handling
- Repositories return a sealed result, not thrown exceptions for expected failures:
  `sealed interface DataResult<out T> { data class Success<T>(val data: T); data class Error(val cause: AppError) }`.
- Define `sealed class AppError` (Network, NotFound, Unauthorized, Unknown, ...).
- Map exceptions to `AppError` at the data boundary (catch `IOException`/`HttpException`).
- ALWAYS rethrow `CancellationException` — never swallow it.
- UI renders error states from `UiState`; raw exceptions never reach Compose.

## Navigation
- Navigation Compose with type-safe routes (`@Serializable` route classes/objects).
- Nested graph per feature; expose only the start route to the parent.
- Pass IDs as nav args, not large objects — load from the repository on the destination.
- Handle system back / predictive back.

## Pitfalls
- Leaking Android types (`Context`, `Cursor`) into domain.
- Feature-to-feature dependencies.
- A god `:core` module everything depends on (kills build parallelism).
- Exposing `MutableStateFlow`/mutable state publicly.
- `SharingStarted.Eagerly` wasting resources; `collectAsState()` instead of the lifecycle variant.
- One-to-one "use case per repo method" with no logic — skip use cases then.
