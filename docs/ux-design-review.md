# PeakBud design system UX review

Research date: 17 October 2026. This is a source review and implementation recommendation, not an outdoor usability study. Keep the app focused on identifying peaks, choosing an offline area, and understanding current observation conditions.

## Findings before implementation

- `ui/theme/Theme.kt` supplies only a light palette; the system dark setting has no effect. Shape values are already centralized, but spacing remains scattered through the screens.
- `feature/panorama/PeakBudApp.kt` has useful metadata and Material text roles, mixed with many literal strings, custom dimensions, and camera-specific colors. Its status-bar icon choice assumes every non-camera screen is light.
- `feature/panorama/PanoramaCanvas.kt` hardcodes a daylight sky, terrain colors, label backgrounds, and native canvas text sizes.
- `feature/camera/CameraPanel.kt` intentionally uses white labels on the live image. Native `Paint` text sizes multiply by density instead of using scalable text units; labels currently bypass the user's font-size preference. A white shadow alone cannot guarantee contrast against bright snow.
- `res/values/strings.xml` currently contains only the application name. Camera permission messages, controls, accessibility descriptions, status text, and unit phrases need resource ownership too.

## Recommended implementation

### Color and dark mode

Use the system appearance by default. If an explicit override is added, keep it to one Appearance row in the existing settings sheet: System, Light, Dark. Preserve forest greens, with restrained near-black green surfaces and pale green accents in the dark palette. Use semantic surface/content pairs rather than manually dimming existing colors. Material supports separate light/dark schemes selected by `isSystemInDarkTheme()` and shared typography/shape roles. [Android Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)

Define separate semantic palettes for the drawn panorama and the camera overlay. The drawn sky, ridges, grid, labels, and leader lines should respond to appearance. Keep camera controls and labels consistently legible over the actual image in both modes: white content with a sufficiently opaque dark backing, retaining clear leader lines and peak anchors. This is a project recommendation for variable imagery, not a claim that changing the application theme can darken the live camera scene.

Update status/navigation bar appearance with the actual selected scheme; check startup and dialogs so light flashes do not undermine dark mode.

### Spacing and dimensions

Interpret “distances” here as UI spacing and component dimensions. Establish a small shared scale: 2, 4, 8, 12, 16, 24, 32, 48 dp. Give repeated intent a semantic name, for example screen inset 16, card inset 16, section gap 24, row gap 12, inline gap 8, compact gap 4, minimum touch target 48. Keep a few genuine optical exceptions named near the component rather than inventing a universal token for every pixel value.

Group component geometry separately: floating navigation clearance, panel width, camera label margins/lanes, border widths, marker radii. Convert dp dimensions to pixels only at the canvas boundary. Leave geographic distances, angles, sensor thresholds, and projection equations in their domain units; those are not visual spacing tokens.

### Typography

Use one local system font family and named text roles. Suggested mapping: screen heading `titleLarge`, cards/peak names `titleMedium`, primary settings/body `bodyLarge`, supporting text `bodyMedium`, short metadata `labelMedium`, buttons `labelLarge`. Avoid tiny tracking-heavy all-caps text for important sensor status. Apply shared roles to canvas labels too, including font scaling and measured line height; avoid raw density-based font sizes. Android's guidance calls for scalable pixels, readable text contrast, and adequately sized touch targets. [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)

### Strings and data presentation

Put user-facing copy in `res/values/strings.xml`, including content descriptions and complete formatted phrases. Use positional placeholders and plurals; do not concatenate English fragments around values. Resolve strings in Compose with `stringResource`, and use an appropriate resource resolver for non-composable presentation code. [Resources in Compose](https://developer.android.com/develop/ui/compose/resources)

Keep proper peak/place names in the regional data. Keep machine identifiers, URLs, telemetry keys, and technical diagnostic details outside translation resources. Centralize locale-aware presentation for elevation, distance, coordinates, heading, and field of view; retain the existing metric/imperial preference. Make unknown altitude and stale/unavailable GPS explicit instead of presenting plausible-looking zero values.

## Verification checklist

- Inspect Home, Camera, Peaks, Regions, settings, and permission states in light and dark appearance, including changing system appearance while the app is open.
- Check portrait and landscape, large system fonts, long peak names, and full-screen camera metadata/exit behavior.
- Check normal text at least 4.5:1 and essential icons against surfaces at least 3:1. Check live overlays over both a dark ridge and a bright sky/snow scene; screenshot colors alone do not prove outdoor readability. [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)
- Preserve at least 48 dp touch targets for camera controls; provide an accessible exit action and peak details through the existing Peaks screen. [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- Verify theme preference persistence if an override exists, resource formatting with a non-English device locale, and canvas labels at enlarged font scale.

Outdoor contrast and glove use still require testing on a real phone. This cleanup should not introduce extra tabs, onboarding, or new sensor features.
