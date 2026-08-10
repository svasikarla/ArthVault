# Component Catalog

Anatomy + aesthetic do/don't for the components a mobile app actually needs. All build
on Material 3; this layer is about making them *look* polished. Keep radius, elevation,
and spacing consistent across every component (see SKILL.md craft checklist).

## Global component rules
- **Radius family:** pick one corner scale (e.g. small 8 / medium 12 / large 16 / xl 28dp)
  and map components to it via `MaterialTheme.shapes`. Don't free-style radii.
- **Elevation language:** prefer M3 tonal surfaces (`surfaceContainer*`) for resting
  hierarchy; reserve shadow for transient surfaces (menus, dialogs, FAB). Don't mix.
- **Padding rhythm:** internal padding on the 4dp grid; 16dp is the standard screen margin.
- **One primary action per screen/surface.** Everything else is secondary/tertiary.
- **States for everything:** default, pressed (ripple/state layer), focused, disabled,
  loading, error, empty. Amateur UIs skip the last four.

## Buttons (emphasis ladder)
Use emphasis to encode importance — never two filled buttons competing:
- **Filled** — the single primary action. Highest emphasis. One per surface.
- **Filled tonal** — important but secondary (uses `secondaryContainer`).
- **Elevated** — needs separation from a busy/colored background.
- **Outlined** — secondary actions.
- **Text** — lowest emphasis, tertiary/dismissive ("Cancel", "Skip").
Do: full-width primary CTAs on mobile for thumb reach; min height 48dp; show a loading
spinner *inside* the button on submit (disable it, keep width stable).
Don't: two filled buttons side by side; tiny buttons; color a destructive action with the
brand accent (use `error`).

## FAB (Floating Action Button)
- For the ONE most common screen action only. Regular / small / large / extended.
- Use **extended FAB** (icon + label) when the action isn't self-evident from an icon.
- Place bottom-end, clear of nav bar insets; let it shrink/hide on scroll-down.
- Don't put multiple FABs or use a FAB for a secondary action.

## Cards
The workhorse container. Three M3 styles:
- **Elevated** — subtle shadow; floats over content.
- **Filled** — tonal fill (`surfaceContainerHighest`); flat, calm.
- **Outlined** — hairline `outlineVariant` border; lightest, good on dense lists.
Anatomy: optional media → content (title, supporting text) → optional actions row.
Do: generous internal padding (16dp), consistent radius, one elevation style per list;
make the whole card tappable if it navigates (with merged semantics + ripple).
Don't: nest cards in cards; cram edge-to-edge; mix elevated and outlined in one list;
add a heavy shadow on top of a tonal fill.

## Lists & list items
- Use list items with leading (icon/avatar/image), headline + supporting text, trailing
  (meta/chevron/switch). Keep leading/trailing slots aligned across rows.
- Group with whitespace and subtle section headers; use `outlineVariant` dividers sparingly
  (only when whitespace alone can't separate). Full-width dividers everywhere = cluttered.
- 1-line / 2-line / 3-line heights — keep consistent within a list.
- Provide skeleton placeholders while loading; a designed **empty state** when there's no data.
- `LazyColumn` with stable keys; consistent item padding; sticky headers for long grouped lists.

## Menus
- **Dropdown menu** (`DropdownMenu`) — contextual actions anchored to a button/overflow.
  Keep items short, icon + label, group with dividers, destructive item last and in `error`.
- **Overflow (⋮)** — secondary actions that don't fit the app bar.
- **Exposed dropdown** — for selection from a known set (acts like a styled picker).
- **Cascading/sub-menus** — only when truly hierarchical; avoid deep nesting on mobile.
Do: anchor near the trigger; animate origin from the anchor; dismiss on outside tap/back.
Don't: stuff 15 items in one menu; put primary actions only in overflow (hidden = unused).

## Navigation (choose by destination count + screen size)
- **Navigation bar (bottom)** — 3–5 top-level destinations on phones. Filled icon + label
  for active, outlined for inactive. The default for most apps.
- **Navigation rail** — medium widths/tablets/landscape (side rail).
- **Navigation drawer** — many destinations or secondary nav; modal on phone, permanent on
  large screens.
- **Tabs** — switching views *within* a screen (not top-level app nav).
- Use `NavigationSuiteScaffold` to switch bar↔rail↔drawer by `WindowSizeClass` automatically.
Do: keep labels always visible for clarity; badge unread counts. Don't: >5 bottom items;
mix bottom nav with tabs doing the same job; hide labels to look "clean" (hurts usability).

## App bars
- **Top app bar** variants: center-aligned (simple titles), small, medium, large
  (collapsing — great for editorial/hero headers that shrink on scroll).
- Keep the title, a nav icon (back/menu), and ≤2–3 actions + overflow. Don't overload.
- Collapsing large/medium top bars add polish on content screens; wire to scroll behavior.

## Bottom sheets
- **Modal bottom sheet** — focused tasks/menus from the bottom; drag handle, scrim behind.
- **Standard bottom sheet** — persistent, co-exists with content (maps, players).
- Do: round only top corners; show a drag handle; size to content but cap height; make it
  scrollable past ~50% height. Don't: bury primary flows in sheets the user can't find.

## Dialogs
- For critical decisions/interruptions only. Title, body, ≤2 actions (confirm/dismiss).
- Confirm action right/end, dismiss left; destructive confirm uses `error` color.
- Don't use dialogs for non-critical info (use a snackbar/inline) or for long forms (use a screen/sheet).

## Inputs & forms
- **Text fields:** filled or outlined — pick ONE style app-wide. Always pair with a label;
  use placeholder as hint, not as the only label. Show helper text + error text + leading/
  trailing icons; animate label to the top on focus.
- **Validation:** validate on blur/submit, not on every keystroke; show clear, specific error
  text in `error` color + an icon; never rely on red color alone.
- Use the right keyboard type (email/number/phone) and IME actions (Next/Done).
- **Selection controls:** checkbox (multi), radio (single, ≤5), switch (instant on/off
  setting). Give 48dp targets; label is tappable.
- **Sliders** for ranges; **date/time pickers** from M3; **search bar** with leading search
  icon + clear action.
- Forms: one column, group related fields, generous vertical spacing, sticky primary CTA,
  show progress for multi-step. Don't crowd fields or right-align labels.

## Chips
- **Assist / Filter / Input / Suggestion.** Filter chips for multi-select facets; input chips
  for entered tokens (with remove ✕). Keep them on one consistent height; wrap, don't scroll
  horizontally forever. Don't use chips as primary buttons.

## Feedback & status
- **Snackbar** — brief, dismissible, one optional action; bottom, above nav/FAB. Default for
  transient feedback. Don't stack snackbars or use for critical errors needing a choice.
- **Banner** — persistent, important message with actions (inline, top of content).
- **Badges** — counts/dots on icons; keep small, don't overuse.
- **Progress:** linear (determinate tasks/top of screen), circular (indeterminate waits).
  Prefer **skeletons** over spinners for content loading — they feel faster and look designed.
- **Tooltips** — plain (info) or rich; pair with icon-only actions.

## Avatars, badges, dividers
- Avatars: circle, consistent sizes (24/32/40/48); fallback to initials on a tinted surface,
  not a broken-image icon.
- Dividers: `outlineVariant`, full-bleed or inset consistently; use whitespace first.

## Empty / loading / error states (where polish lives)
- **Empty:** friendly illustration or icon + one-line explanation + a primary action to fix it
  ("No items yet — Add your first"). Never a blank screen.
- **Loading:** skeleton matching the real layout; shimmer optional and subtle.
- **Error:** clear cause + a retry action; keep tone human, not a stack trace.

## Pitfalls (component-level)
- Multiple high-emphasis buttons competing; destructive actions not in `error`.
- Mixed card/elevation/radius styles in one list.
- >5 bottom-nav items; primary actions hidden in overflow.
- Placeholder-as-label; per-keystroke validation; red-only error signaling.
- Spinners instead of skeletons; missing empty/error states.
- Dividers and boxes everywhere instead of whitespace grouping.
- Sheets/dialogs misused (long forms in dialogs, critical flows hidden in sheets).
