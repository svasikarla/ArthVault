# Typography

Type carries more of the "professional" feeling than any other element. Get the
scale, pairing, and rhythm right and the UI instantly looks considered.

## Build a type scale (not random sizes)
Use a small set of roles on a modular ratio so sizes relate harmonically. A common
mobile ratio is ~1.2–1.25 between steps. Map to M3 type roles rather than inventing sizes:
- **Display** (large/medium/small) — hero numbers, splash, big moments. Rare.
- **Headline** (l/m/s) — screen titles, section headers.
- **Title** (l/m/s) — card titles, app bar, list headers.
- **Body** (l/m/s) — paragraphs, primary reading text (body is your workhorse).
- **Label** (l/m/s) — buttons, chips, captions, overlines.

Strong contrast between display/headline and body is what creates hierarchy. Timid
scales (everything 14–18sp) look flat and amateur.

## Choose & pair fonts
- **One family well-used beats two paired badly.** A good variable font with multiple
  weights (e.g. a clean grotesque) can carry an entire app.
- If pairing: one display/heading font + one highly legible body font. Pair by *contrast*
  (serif display + sans body) or stay in one superfamily. Don't pair two similar sans —
  it looks accidental.
- Body font must be screen-legible at small sizes with clear letterforms and good spacing.
  Avoid condensed/decorative fonts for body.
- Limit to 2 families, ~3–4 weights total. More weights/families = visual noise + bigger app.

## Weight, size & color for hierarchy
Rank text with (in order of subtlety): **size → weight → color → spacing**. Prefer
adjusting weight and color (e.g. `onSurfaceVariant` for secondary text) over piling on
sizes. Reserve bold for genuinely primary text; if everything's bold, nothing stands out.

## Readability rules
- **Line length:** ~40–60 characters per line for paragraphs; full-bleed wide text tires the eye.
- **Line height:** ~1.4–1.6× font size for body. M3 roles set sensible defaults — keep them.
- **Letter spacing:** slightly positive for ALL-CAPS labels/overlines; near-zero for body.
  Negative tracking on large display text tightens it nicely.
- **Alignment:** left-align body (LTR). Avoid justified text on mobile (rivers of whitespace).
  Center only short headings/empty-state copy.
- **Minimum size:** body ≥ 14sp (16sp ideal); never below 12sp for meaningful text.
- Use `sp` (scales with user font settings) for text, `dp` for layout. Respect large-font
  accessibility settings — test at 200% font scale; don't truncate critical text.

## Optical & numeric details (the pro touches)
- **Optical sizing:** variable fonts with an optical axis render display vs body at their
  intended weight balance — use it where available.
- **Tabular figures** for tables, timers, prices, and anything that changes in place, so
  digits don't jitter (enable tabular/monospaced numerals).
- **Avoid orphans/widows** in headings; balance two-line titles.
- Curly quotes, real en/em dashes, and proper ellipsis (…) signal craft.

## Compose setup sketch
```kotlin
val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    titleMedium  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge    = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    labelLarge   = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    // …fill the rest of the roles
)
// Use via MaterialTheme.typography.titleMedium — never inline fontSize in screens.
```
Bundle fonts as resources or use Downloadable Fonts to keep APK size down. Prefer variable
fonts to ship many weights cheaply.

## Pitfalls
- Flat scale with weak size contrast; too many sizes with no system.
- Two clashing or near-identical font families; too many weights.
- Pure-black body text; tiny line height; justified paragraphs.
- Hardcoding `fontSize` in composables instead of typography roles.
- Proportional figures jittering in tables/timers.
- Truncating important text instead of supporting large font scales.
