# Iconography

Icons are a visual language. Consistency in style, weight, and size is what makes a
set look professional; a mismatched set is an instant amateur tell.

## Pick ONE icon style and never mix
Choose a single style and apply it everywhere:
- **Outlined** — light, modern, calm (good default for content-heavy apps).
- **Filled/solid** — bold, high-emphasis (good for tab bars, compact UI).
- **Rounded / Sharp** — match your shape language (rounded corners → rounded icons).
- **Two-tone / duotone** — expressive, brand-forward; harder to keep consistent.

Mixing outlined and filled randomly is the most common mistake. A valid pattern: outlined
for inactive, filled for the *active/selected* state of the same icon (tabs, nav).

## Sourcing
- **Material Symbols** (the modern variable icon font) is the safe default for Android —
  one family, adjustable weight/fill/grade/optical-size axes, consistent by construction.
  Note: since Material3 1.4, `material-icons-core` isn't pulled transitively — add it or use
  Material Symbols explicitly.
- Third-party sets (Phosphor, Lucide, Tabler, Feather) are excellent and cohesive — pick one
  set and stick to it for everything custom.
- Avoid grabbing single icons from many sources; they won't share stroke weight/grid.

## Sizing & alignment
- Standard sizes on the grid: **24dp** (default UI icons), 20dp (dense/inline), 40–48dp
  (feature/avatars). Keep to your scale; don't use arbitrary sizes.
- The **touch target** is 48dp even when the icon glyph is 24dp — pad it; never shrink the
  hit area to the glyph.
- Optical alignment > mathematical: a triangle "play" icon often needs nudging to look
  centered. Trust the eye.
- Match stroke weight across the set (e.g. all 2dp strokes). With Material Symbols, lock the
  weight/grade axes so icons match your type weight.
- Align icon weight to text weight beside it — a heavy icon next to light text looks off.

## Color & state
- Default icons use `onSurfaceVariant`; active/primary icons use `primary` or `onSurface`.
- Don't color every icon with the brand accent — icons are mostly neutral; accent marks state.
- Provide clear inactive/active/disabled states (color + optionally fill).

## Custom vector icons (Compose)
- Ship as `ImageVector` (programmatic) or vector drawables (`<vector>`); never raster PNGs
  for UI icons — vectors stay crisp at all densities and themes.
- Keep custom icons on the same grid (24dp canvas, consistent padding/stroke) as your set.
- Tint via `tint = ...` so they adapt to theme; design them monochrome unless intentionally duotone.
- For brand/illustrative marks that must keep multiple colors, use a multicolor vector and
  don't tint it.

## Accessibility
- Meaningful icons need a `contentDescription`; purely decorative ones take `null` so
  TalkBack skips them.
- Icon-only buttons MUST have a description (and ideally a tooltip/long-press label).
- Don't rely on an icon alone to convey critical meaning — pair with a label where stakes are high.

## Pitfalls
- Mixing outlined + filled (or two icon sets) across the app.
- Inconsistent sizes/stroke weights; raster icons that blur or don't theme.
- Shrinking the touch target to the glyph.
- Every icon tinted with the brand color; no active/inactive distinction.
- Missing content descriptions on icon-only actions.
