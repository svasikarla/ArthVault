---
name: mobile-ui-aesthetics
description: Design-craft guidance for building professional, polished, aesthetically rich mobile UI in Jetpack Compose / Material 3 — opinionated direction for color theming, typography, iconography, spacing, components (cards, menus, sheets, inputs, nav), graphics/illustration, and motion. Use when designing a new screen's look-and-feel, defining a visual identity/theme, or elevating UI from functional to beautiful. Pairs with compose-m3-conventions (which covers mechanics); this covers taste. Reference files hold deep per-topic catalogs.
---

# Mobile UI Aesthetics

This skill is about **taste and polish** — turning correct UI into something that
looks designed. `compose-m3-conventions` covers the mechanics (stability, a11y,
adaptivity); follow both. Principles are platform-agnostic; implementation notes
target Compose + Material 3.

## The one rule: be intentional
Generic UI looks generic because every choice is a default. Professional UI looks
designed because every choice — one accent color, one type scale, 8dp not 7dp — is
deliberate and repeated. Pick a direction and apply it ruthlessly. Consistency reads
as quality more than any single flourish.

## Pick a visual direction first (before any screen)
Choose ONE adjective set and let it drive every later decision:
- e.g. *calm / editorial / trustworthy* → muted palette, generous whitespace, serif-ish display, soft elevation.
- e.g. *energetic / playful / bold* → saturated accent, tight punchy type, rounded shapes, springy motion.
- e.g. *premium / minimal / focused* → near-monochrome + one accent, high contrast, restrained motion.
Write the direction down. Every reference file below is applied *in service of it*.

## The craft checklist (what separates pro from amateur)
1. **One accent, used sparingly.** A single dominant brand color carries the identity;
   everything else is neutral. Color earns attention — don't spend it everywhere.
2. **A real spacing system.** All spacing on a 4dp grid (4/8/12/16/24/32/48). Never
   eyeball one-off paddings. Rhythm is invisible but felt.
3. **A type scale, not random sizes.** A small set of roles on a ratio; strong size
   contrast between display and body. (see references/typography.md)
4. **Generous whitespace.** Crowding is the #1 amateur tell. Let primary content breathe;
   group with proximity, not boxes and dividers.
5. **Consistent corner radius + elevation language.** One radius family, one elevation
   strategy (M3 tonal surfaces). Mixed radii/shadows look unfinished.
6. **Visual hierarchy on every screen.** Exactly one primary action, one focal element.
   If everything is bold, nothing is. Use size, weight, color, and space to rank.
7. **Intentional empty / loading / error states.** Designed empty states and skeletons
   (not spinners) are where polish shows most.
8. **Purposeful motion.** Motion explains relationships and gives feedback; never decorate.
   (see references/motion-microinteractions.md)
9. **Pixel-level alignment.** Optical alignment, consistent icon sizes, baselines that
   line up. Sloppy alignment undoes everything else.
10. **Accessibility is part of beauty.** 4.5:1 text contrast, 48dp targets, legible
    sizes. Inaccessible UI is unprofessional UI, full stop.

## How to use the references
Load the file for the decision you're making; each is a focused, opinionated catalog:
- `references/color-systems.md` — building a rich, harmonious palette + M3 tonal roles, dark mode.
- `references/typography.md` — type scale, pairing, optical sizing, Compose setup.
- `references/iconography.md` — icon style, sizing, sourcing, custom vector guidance.
- `references/component-catalog.md` — cards, menus, sheets, inputs, nav, chips, dialogs,
  lists, FABs, banners — anatomy + aesthetic do/don't for each.
- `references/graphics-illustration.md` — imagery, gradients, illustration, charts,
  decorative graphics, app icon/branding.
- `references/motion-microinteractions.md` — transitions, feedback, choreography, easing.

## Workflow when designing a screen
1. Restate the visual direction. 2. Establish hierarchy (what's primary?). 3. Lay out on
the spacing grid with generous whitespace. 4. Apply type roles, then the single accent,
then neutrals. 5. Choose components from the catalog; keep radius/elevation consistent.
6. Design empty/loading/error, not just the happy path. 7. Add restrained motion.
8. Run the a11y + alignment pass. 9. Preview light + dark, compact + expanded.

## Top pitfalls (the amateur tells)
- Many competing accent colors; pure black `#000` text on pure white (use near-black/near-white).
- Inconsistent padding and mixed corner radii.
- Cramped layouts; dividers and boxes everywhere instead of whitespace grouping.
- Tiny type, weak hierarchy, everything the same weight.
- Default spinners instead of skeletons; no empty-state design.
- Drop shadows fighting M3 tonal elevation (pick one elevation language).
- Decorative motion that delays the user; gradients/blurs with poor contrast.
- Stock-photo clutter; mismatched icon sets (outline + filled mixed randomly).
