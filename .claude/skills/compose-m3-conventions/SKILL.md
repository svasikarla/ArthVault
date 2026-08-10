---
name: compose-m3-conventions
description: Material 3 and Jetpack Compose conventions for this project — theming and dynamic color, state hoisting, slot APIs, recomposition stability, accessibility, and adaptive layouts for phone/tablet/foldable. Use when generating or reviewing any Compose UI code. See references/CHECKLIST.md for the full review checklist.
---

# Compose + Material 3 Conventions

## Theming
- Pull ALL colors, typography, and shape from `MaterialTheme` — never hardcode hex or dp font sizes.
- Use semantic color roles (`primary`, `surface`, `surfaceContainer*`), not `surface` everywhere.
- Support dynamic color on API 31+ (`dynamicLightColorScheme`/`dynamicDarkColorScheme`) with a
  hand-tuned branded fallback `ColorScheme` for older devices and for branding.
- Provide a complete dark `ColorScheme` (not just inverted colors).
- `darkTheme = isSystemInDarkTheme()`; call `enableEdgeToEdge()` and handle `WindowInsets`
  (status/navigation/IME). Android 15+ enforces edge-to-edge; 16+ removes opt-outs on large screens.
- Since Material3 1.4.0, `material-icons-core` is NOT pulled transitively — add it explicitly.

## State
- Composables are STATELESS: take `value` + `onEvent` lambda; hoist state to the lowest common owner (often a ViewModel).
- Collect with `collectAsStateWithLifecycle()`.
- Do heavy work in `LaunchedEffect`, not in composition.

## Components & recomposition stability
- Build reusable components with slot APIs (`content: @Composable () -> Unit`), not many boolean params.
- Prefer immutable/stable parameter types; mark models `@Immutable`/`@Stable` when the compiler can't infer it.
- Use `ImmutableList` (kotlinx.collections.immutable) or `remember` for collections/lambdas passed down.
- Use stable `key` in `LazyColumn` items; `derivedStateOf` for computed state.
- Defer state reads with lambda modifiers (e.g., `Modifier.offset { }`) to skip recomposition.

## Accessibility
- Minimum touch target 48x48dp (`Modifier.minimumInteractiveComponentSize()` or explicit sizing).
- `contentDescription` on meaningful icons/images; `null` for purely decorative ones.
- Merge semantics on clickable `Row`s/`Column`s (`Modifier.semantics(mergeDescendants = true)`) so
  TalkBack reads coherent units; use `heading()`, `stateDescription`, custom actions.
- Target 4.5:1 contrast for body text; never use color alone to convey state.
- Test with TalkBack + Accessibility Scanner.

## Adaptive layouts (phone / tablet / foldable)
- Drive layout off `WindowSizeClass` (Compact/Medium/Expanded), NOT raw `Configuration.screenWidthDp`.
- Use canonical layouts: `ListDetailPaneScaffold`, `SupportingPaneScaffold`, feed.
- Use `NavigationSuiteScaffold` to switch bottom bar (compact) <-> rail/drawer (medium/expanded).
- Apps targeting API 36/37 cannot opt out of resize/orientation on screens >=600dp — adaptive is mandatory.
- Don't lock orientation (ignored on large screens in API 36+).

## Navigation (UI side)
- Type-safe routes; nested graph per feature; deep links via `navDeepLink` + verified App Links.
- Scope ViewModels to the back-stack entry via `hiltViewModel()`.

## Previews
- Always add `@Preview` for light/dark and compact/expanded width.

## Pitfalls
- Passing unstable `List`/lambda recreated each recomposition.
- Reading `MutableState` too high in the tree (wide recomposition).
- Content drawing under system bars because insets aren't consumed.
- Icon-only buttons with no description; clickable rows that don't merge semantics.
- Branching on raw screen width; losing state on fold/rotation because it isn't hoisted/saved.
