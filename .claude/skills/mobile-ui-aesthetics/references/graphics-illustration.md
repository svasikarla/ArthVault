# Graphics & Illustration

Imagery, illustration, decorative graphics, data viz, and branding — used in service of
the visual direction, never as random decoration.

## Photography & imagery
- **Consistency beats quantity.** All photos should share a treatment — similar lighting,
  saturation, and mood. A unifying brand-tinted overlay (a faint primary-color gradient)
  makes mixed-source images feel like one set.
- Crop intentionally with a consistent aspect ratio per context (e.g. 16:9 hero, 1:1 thumb,
  4:3 cards). Don't mix ratios in one grid.
- Ensure text-over-image legibility with a scrim/gradient behind text; test the lightest
  image. Never place body text directly on a busy photo.
- Optimize: serve appropriately sized images; use Coil for async loading with crossfade,
  placeholder, and error drawables (a graceful fallback, not a broken icon).
- Respect rounded-corner language on image containers; clip to the same radius as cards.

## Illustration
- A cohesive illustration set gives personality where photos can't (onboarding, empty states,
  errors, success). Keep ONE illustration style: line weight, color palette (pull from your
  theme), level of detail, and perspective all consistent.
- Use illustration to soften "dead" moments — empty states, permission requests, errors —
  turning friction into brand warmth.
- Ship as vector (SVG→vector drawable / `ImageVector`) so they scale and can theme-tint where
  monochrome. Multicolor illustrations: keep palette aligned to the theme; don't tint.
- Don't overuse — too many illustrations everywhere reads as childish for serious apps. Match
  density to the brand direction.

## Decorative graphics (depth without clutter)
- **Subtle gradients / mesh** behind heroes and empty states add richness; keep them low-
  contrast so foreground stays readable.
- **Blur / translucency** (frosted surfaces) can add depth for overlays — but verify contrast
  and don't overdo (performance + legibility cost).
- **Texture/noise** at very low opacity can stop large flat areas feeling sterile.
- Decorative shapes (blobs, arcs) using brand tones can frame content — keep them behind, not
  competing with, the content.
- Rule: decoration must never reduce contrast of, or distract from, the primary content.

## Data visualization (if the app has charts)
- Use your theme palette; the primary series in the accent, others in neutrals/secondary —
  don't rainbow every series.
- Sequential data → single-hue light→dark ramp; categorical → distinct but harmonious hues;
  diverging → two-hue scale around a neutral midpoint.
- Minimal chrome: drop heavy gridlines/borders; label directly where possible; enough contrast
  for small marks. Animate value changes for readability, not flash.
- Ensure colorblind-safe encoding — never rely on color alone (add labels/patterns/shapes).

## Shape & visual language
- Commit to a shape personality: rounded (friendly), sharp (precise/technical), or mixed with
  intent. Echo it across cards, buttons, inputs, icons, and illustration.
- Reuse a small set of motifs (a corner cut, an arc, a dot grid) to build recognizable identity.

## App icon & branding
- The app icon is the first impression: simple, recognizable at small sizes, strong silhouette,
  works on any wallpaper. Provide an **adaptive icon** (foreground + background layers) and a
  **monochrome layer** for themed icons. Test on light/dark launchers and at small sizes.
- Splash via the official Splash Screen API (icon on brand background) — not a full-bleed
  legacy splash image.
- Keep brand assets (logo, wordmark, safe-area) consistent across icon, splash, onboarding,
  and store listing.

## Pitfalls
- Inconsistent photo treatment / mixed aspect ratios; text on busy images without a scrim.
- Mixed illustration styles; over-illustrating serious apps.
- Decorative gradients/blurs that kill foreground contrast or performance.
- Rainbow charts; color-only encoding (fails for colorblind users).
- App icon with fine detail that disappears at launcher size; no monochrome/adaptive layers.
- Raster graphics that blur across densities or don't theme.
