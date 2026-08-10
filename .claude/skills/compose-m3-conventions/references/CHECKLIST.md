# Compose UI Review Checklist

A pass/fail checklist for reviewing any Compose screen or component. Load this
when doing detailed UI review; the parent SKILL.md has the conventions.

## Theming
- [ ] No hardcoded colors — all from `MaterialTheme.colorScheme`.
- [ ] No hardcoded text styles — all from `MaterialTheme.typography`.
- [ ] Semantic color roles used correctly (not `surface` for everything).
- [ ] Dynamic color on API 31+ with a branded fallback scheme.
- [ ] Complete, hand-checked dark scheme.
- [ ] `enableEdgeToEdge()` + insets handled (status/nav/IME).

## State & recomposition
- [ ] Composables stateless where possible (value + onEvent hoisted).
- [ ] `collectAsStateWithLifecycle()` (not `collectAsState()`).
- [ ] Side effects in `LaunchedEffect`/`rememberCoroutineScope`, not composition.
- [ ] Lists passed as `ImmutableList` or remembered; lambdas remembered.
- [ ] `LazyColumn`/`LazyRow` items have stable `key`s.
- [ ] Models marked `@Immutable`/`@Stable` where compiler can't infer.
- [ ] No obvious wide-recomposition (state read too high in tree).

## Components
- [ ] Reusable components use slot APIs, not boolean-flag explosions.
- [ ] No business logic inside composables.

## Accessibility
- [ ] Touch targets >= 48x48dp.
- [ ] Meaningful icons/images have `contentDescription`; decorative ones `null`.
- [ ] Clickable rows merge semantics for coherent TalkBack output.
- [ ] Headings/state described via semantics.
- [ ] Text contrast >= 4.5:1; state never conveyed by color alone.

## Adaptive
- [ ] Layout driven by `WindowSizeClass`, not raw width.
- [ ] Canonical scaffold used where appropriate (list-detail / supporting pane).
- [ ] `NavigationSuiteScaffold` for nav surface adaptation.
- [ ] No orientation lock; survives fold/rotation (state hoisted/saved).

## Previews
- [ ] `@Preview` for light + dark.
- [ ] `@Preview` for compact + expanded width.
