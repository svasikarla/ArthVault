# Motion & Microinteractions

Motion is the difference between an app that feels alive and one that feels static.
Done right it explains relationships and confirms actions; done wrong it slows people
down. Every animation must earn its place.

## Principles
- **Motion has a job:** show *where things come from / go to*, confirm an action, direct
  attention, or express brand personality. If it does none of these, cut it.
- **Fast and subtle wins.** UI transitions live in ~150–400ms. Too slow feels sluggish;
  instant feels broken. Microfeedback (taps) ~100ms; screen transitions ~300ms; large/
  expressive moments up to ~500ms — rarely more.
- **Natural easing, not linear.** Use M3 easing/standard curves: *decelerate* for entering
  elements (ease-out), *accelerate* for exiting (ease-in), standard for moving-through.
  Linear motion feels robotic. Springs (gentle, low-bounce) feel physical and modern.
- **Choreography:** related elements move together; stagger lists subtly (small per-item
  delay) so content "arrives" rather than snapping. Don't animate everything at once.

## Where to use it (high-value spots)
- **State feedback:** button press state layer/ripple, toggle/switch transitions, checkbox
  check, pull-to-refresh — instant confirmation the tap registered.
- **Navigation transitions:** shared-element / container transforms between list→detail give
  spatial continuity (the tapped card *becomes* the detail). Use M3 predictive-back +
  shared-element transitions in Compose.
- **Content loading:** skeleton shimmer; crossfade images in (Coil crossfade); animate content
  in once loaded instead of popping.
- **Value changes:** animate number/progress changes (`animate*AsState`) so users see the
  delta. Animate list add/remove/reorder (`animateItem`) so changes are legible.
- **Empty→filled, error→retry:** soft transitions make state changes feel intentional.
- **Delight (sparingly):** a success checkmark draw, a confetti moment on a milestone — rare,
  on-brand, skippable.

## Microinteractions anatomy
Trigger → feedback → result. Each meaningful action should give immediate, proportional
feedback: a tap depresses, a toggle slides, a like animates, a refresh spins then settles.
Keep them short and consistent — the same gesture should feel the same everywhere.

## Compose toolkit (what to reach for)
- `animate*AsState` (float/color/dp) for simple property animation.
- `AnimatedVisibility` / `Crossfade` for enter/exit and swapping content.
- `updateTransition` for coordinating multiple properties off one state.
- `animateItem()` in `LazyColumn` for list add/remove/move.
- `rememberInfiniteTransition` for shimmer/looping (use sparingly).
- Shared-element transitions + predictive-back for navigation continuity.
- Prefer spring specs for natural feel; tween with M3 easing for precise timing.

## Performance & restraint
- Keep animations at 60fps+ (don't animate layout-thrashing properties; prefer graphicsLayer
  transforms — translate/scale/alpha — over re-laying-out).
- Don't block interaction during transitions; let users tap through.
- **Respect "reduce motion"** accessibility settings — drop or simplify nonessential motion
  when the user has requested it. Never gate critical feedback behind a long animation.
- One motion *language* across the app: consistent durations, easing, and direction (e.g.
  forward navigation always slides/transforms the same way).

## Pitfalls
- Decorative motion that adds delay with no meaning; everything bouncing.
- Slow transitions (>500ms) that make the app feel laggy; linear easing.
- Animating on every keystroke/scroll frame (jank, battery).
- Inconsistent durations/directions across screens.
- Ignoring reduce-motion settings; blocking input mid-animation.
- Popping content in with no transition right after a skeleton (jarring).
