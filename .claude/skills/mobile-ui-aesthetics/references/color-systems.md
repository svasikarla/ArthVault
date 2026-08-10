# Color Systems

Building a rich, harmonious palette that looks designed — and wiring it into
Material 3's tonal role system for light and dark.

## Build the palette (in this order)
1. **Pick one brand/seed color.** This is your identity. Choose for meaning + contrast
   ability, not just looks. Everything derives from it.
2. **Generate a tonal palette** (13 tones, 0–100 luminance) from the seed. M3's tonal
   approach does this; or use the Material Theme Builder. You'll pull specific tones for
   light vs dark roles.
3. **Choose a harmony for secondary/accent** if you need more than one hue:
   - *Analogous* (neighbors on the wheel) → calm, cohesive.
   - *Complementary* (opposite) → vivid, high-energy; use the second hue tiny.
   - *Triadic* → playful, balanced; hardest to keep tasteful.
   Default to a near-monochrome palette + ONE accent. More hues = more ways to look cheap.
4. **Design the neutrals deliberately.** Most of the screen is neutral. Tint your greys
   slightly toward the brand hue (a "warm" or "cool" neutral) — pure grey looks lifeless.
5. **Define semantic colors:** success / warning / error / info. Keep them distinct from
   the brand accent so status never reads as branding.

## The 60-30-10 rule
Roughly 60% dominant neutral (backgrounds/surfaces), 30% secondary (surface variants,
containers), 10% accent (primary actions, key highlights). The accent's scarcity is what
makes it powerful. If your accent covers 40% of the screen, it's no longer an accent.

## Map to Material 3 roles (don't freelance colors in components)
Pull from `MaterialTheme.colorScheme`; never hardcode hex in UI. Key roles:
- `primary` / `onPrimary` — main actions, active states. `primaryContainer` — softer accent fills.
- `secondary` / `tertiary` — supporting accents; tertiary for contrast moments.
- `surface` + `surfaceContainerLowest/Low/.../Highest` — the elevation-by-tone ladder.
- `surfaceVariant`, `outline`, `outlineVariant` — subtle separation without hard dividers.
- `error` / `errorContainer` — destructive + error states.
- `onSurface` (primary text), `onSurfaceVariant` (secondary text) — DON'T use pure black.

### M3 elevation = tone, not just shadow
In M3, raised surfaces get a lighter tonal fill (`surfaceContainerHigh` etc.), not just a
shadow. Use the tonal ladder for hierarchy; reserve real shadow for transient surfaces
(menus, dialogs). Mixing heavy custom shadows with tonal elevation looks muddy — pick one.

## Dark theme (design it, don't invert)
- Use dark grey surfaces, NOT pure black `#000` (except true-OLED modes by choice). Pure
  black causes harsh edges and smearing on OLED scroll.
- **Desaturate** accents in dark mode — fully saturated colors vibrate on dark backgrounds.
  M3 dark roles already shift toward lighter, less saturated tones; honor them.
- Maintain 4.5:1 text contrast in BOTH themes — re-check, don't assume.
- Elevation in dark mode reads via lighter surface tones (higher container = lighter).

## Dynamic color (Material You, API 31+)
Offer dynamic color (`dynamicLightColorScheme`/`dynamicDarkColorScheme`) so the app
harmonizes with the user's wallpaper — but ALWAYS keep a hand-tuned branded fallback scheme
for branding moments, older devices, and screenshots. Test your layouts against a few wild
wallpaper palettes so nothing breaks when the user's accent is, say, hot pink.

## Gradients & tints (use sparingly, on purpose)
- Subtle gradients between two *close* tones of one hue look premium; rainbow gradients look
  cheap. Keep contrast of overlaid text in check (test the darkest text spot).
- Mesh/soft radial gradients work well behind hero areas and empty states.
- A faint brand-tinted overlay unifies photography of mixed quality.

## Compose setup sketch
```kotlin
// Define light/dark ColorSchemes from your tonal palette (Theme Builder output),
// then choose dynamic vs branded at runtime:
val colors = when {
    dynamicColor && Build.VERSION.SDK_INT >= 31 ->
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    dark -> BrandDarkColors
    else -> BrandLightColors
}
MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes) { … }
```

## Pitfalls
- More than one strong accent; accent used on too much of the screen.
- Pure-black text/background; fully saturated colors in dark mode.
- Grey neutrals with zero hue (lifeless); status colors that clash with branding.
- Hardcoding hex in components instead of `colorScheme` roles.
- Custom drop shadows fighting M3 tonal elevation.
- Low-contrast text over gradients/images (always test the worst-case spot).
