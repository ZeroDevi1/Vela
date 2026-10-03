# Vela Material Design 3 system

This file is the visual source of truth for Android Phone and TV UI. Page-specific exceptions belong in `pages/` and must explain why the shared tokens are insufficient.

## Product direction

Vela is a dark-first personal media client. The interface should feel cinematic without hiding hierarchy or controls: content art is expressive, while navigation, settings, forms, dialogs, and feedback use standard Material 3 behavior.

## Color roles

- Brand source: `branding/vela/` (`#1565C0`, `#64B5F6`, `#7C4DFF`, near-black `#05070B`).
- Runtime source: `shared/src/androidMain/com/vela/shared/ui/theme/Color.kt`.
- Pages consume `MaterialTheme.colorScheme`; do not add local near-duplicate background, card, text, or accent constants.
- Use `primary` for the main action and focus, `secondaryContainer` for selected navigation, `tertiaryContainer` for non-blocking warnings, and `error` only for destructive or failed states.
- Express elevation with `surfaceContainerLowest` through `surfaceContainerHighest`, not arbitrary black/gray values.

## Typography and shape

- Runtime sources: `Type.kt` and `Shape.kt` beside the color tokens.
- Use the Material type role that matches meaning; do not override `fontSize` when a role already fits.
- Touch controls must expose at least a 48 dp target. Standard card radius is 16 dp, prominent panels 24 dp, and dialogs 32 dp.

## Layout and navigation

- Use `Scaffold` as the owner of system insets and bottom navigation padding. Nested screens must not reserve guessed bottom-bar heights.
- Compact windows use the M3 Expressive `ShortNavigationBar` (64 dp, pill indicator, default item colors); larger layouts may move to adaptive navigation when the existing navigation stack is migrated.
- Keep page content edge-to-edge only when imagery benefits from it. Text and controls use a 16 dp horizontal baseline and 8 dp spacing rhythm.
- Back behavior and navigation history remain platform-standard.

## Motion and accessibility

- Runtime source: `shared/src/androidMain/com/vela/shared/ui/theme/Motion.kt`.
- Motion follows M3 Expressive springs (`VelaMotionScheme`): effects specs (alpha, color) are critically damped; spatial specs (position, scale, bounds) allow a slight overshoot. Pages must not introduce a second set of timing constants.
- Material3 1.4.0 keeps `MotionScheme` internal, so M3 components still use library motion; switch `VelaMotionScheme` to implement `MotionScheme` once it is public.
- Phone navigation uses a restrained fade-through with a slight scale; TV navigation keeps focus motion dominant and avoids large page slides.
- Decorative brand motion plays once on entry. Infinite animation is reserved for active loading or playback status.
- Do not rely on color alone for selection, connectivity, loading, or errors.
- Icons need localized content descriptions unless adjacent text already supplies the accessible name.
- Keep body text contrast at least 4.5:1 and preserve visible pressed, focused, disabled, loading, and selected states.
- Media playback overlays are exempt from light-surface styling, but still use theme semantic roles and 48 dp touch targets.

## Player overlay

- Readability comes from top and bottom gradient scrims, not per-control glass containers. Only the center transport buttons and gesture pills carry a translucent black fill.
- Keep device telemetry (battery, clock, raw network speed) out of the chrome; network rate appears only while buffering. Low-frequency toggles (hardware decoding, VR) live in the overflow menu.
- The seek bar uses the Expressive slider shape: vertical handle, gaps around the handle, track split at chapter marks.
- Single-choice panels (tracks, quality, projection) slide in from the end edge in landscape and rise from the bottom in portrait; selection is a `secondaryContainer` row plus a check, never a per-type accent color.
- Every search field submits through the IME Search action; single-line fields declare `ImeAction.Next` until the last field of a form.

## Review checklist

- No legacy `Jelly*` visual tokens or page-local copies of shared semantic colors.
- No content hidden behind system bars or navigation bars.
- Main shell, servers, federated content/search, settings, detail, and player surfaces are visually checked on an installed release build.
- `git diff --check` and the relevant module-level Release compile task pass before delivery.
