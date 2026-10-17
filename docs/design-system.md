# PeakBud design system

The app uses a shared Compose design system for appearance, spacing, shapes, and scalable text. The [UX source review](ux-design-review.md) records the reasoning and Android references.

## Appearance

Settings → Display → Appearance offers **Use device setting**, **Light**, and **Dark**. System is the default; the choice is stored in DataStore and applied by `PeakBudRoot` to every screen and sheet. Startup waits for the stored preference before showing app content.

`ui/theme/Theme.kt` owns the complete light/dark Material color schemes and shapes. Use paired roles such as `surface`/`onSurface`, `primaryContainer`/`onPrimaryContainer`, and `errorContainer`/`onErrorContainer`. System bar icon colors follow the selected background.

`SceneColors.kt` defines semantic day/night colors for the panorama sky, terrain, axes, peak labels, and leaders. The camera uses a fixed white-on-dark overlay treatment because the image itself can be bright snow or dark rock in either app theme. Full-screen camera metadata and its exit control use the same treatment.

## Spacing and geometry

`ui/theme/Tokens.kt` is the source of truth:

| Group | Purpose |
| --- | --- |
| `TrailSpace` | Layout rhythm: 2, 4, 8, 12, 16, 20, 24, 32, 48 dp; screen/card insets |
| `TrailSize` | Minimum 48 dp camera actions, icon sizes, navigation clearance, panel widths, slider geometry |
| `TrailRadius` | Small, medium, card, large, sheet corners |
| `TrailStroke` | Borders, progress, and canvas leader widths |
| `TrailElevation` | Slider thumb and floating elevation |
| `TrailCanvas` | Canvas label padding, markers, safe bands, and leader gaps |

Use existing tokens in feature layouts; add a named component dimension when a real new role is needed. Convert dp to pixels only inside drawing code. Geographic distances, sensor angles, terrain calculations, and gesture math retain their domain units.

## Typography

`ui/theme/Type.kt` defines the full Material type scale using the local system sans font in sp, with explicit line heights. Use `titleLarge` for screen/sheet headings, `bodyLarge` for primary settings text, `bodySmall` for supporting detail, and `labelMedium` for short metadata.

`CanvasTokens.kt` converts the same text roles through `LocalDensity`, including font scaling, and measures native font metrics for label baselines/heights. Both camera and panorama labels ellipsize long text. Panorama axis labels reject collisions and the footer has its own contrasting backing.

## Strings and measurements

All application copy, accessibility descriptions, permission/sensor messages, progress phrases, and unit templates belong in `res/values/strings.xml`. `ui/text/TrailStrings.kt` resolves Android resources in callbacks and canvas drawing as well as Compose. Use complete positional format strings and plurals, rather than building English fragments around values.

Shared presentation helpers (`height`, `distance`, `angleText`, `cardinal`, `coordinate`, `peakReason`) currently live in `feature/panorama/PeakBudApp.kt` and are reused by the canvases. Heights/distances respect Feet and miles; decimal formatting follows the resource locale. Editable coordinate values deliberately retain a decimal point for unambiguous parsing. GPS accuracy is explicitly shown in metres. Unknown and stale GPS states are labeled; camera metadata never substitutes a demo/manual viewpoint for a device fix.

Peak/place names and dataset attribution remain regional data. URLs, filenames, identifiers, telemetry keys, diagnostic exceptions, and projection constants remain code/data rather than translation resources. Terrain visibility reasons and installation stages are typed values; presentation maps them to resources.

## Verification

- `DesignContrastTest` checks actual day/night Material content pairs, panorama labels/axes, and translucent camera text backings over white and black images against 4.5:1.
- `AppearanceTest` exercises the Settings controls, persistence across Activity recreation, all main screen themes, and metric/imperial formatting with French resource locale.
- Navigation and full-screen camera instrumentation tests cover returning from full screen, retained position metadata, manual viewpoint, and curated region entry points.
- Build with `:app:assembleDebug`, run JVM tests with `:app:testDebugUnitTest`, and check Android lint with `:app:lintDebug`. Run instrumentation on an emulator. Visual checks should include system theme switching, enlarged fonts, landscape, and camera overlays.

Outdoor visibility, glare, and glove interaction still need validation on a real phone; emulator screenshots do not prove those properties.

### Checked on 17 October 2026

Debug APK and instrumentation APK built successfully; Android lint passed (existing dependency/platform warnings remain). All 44 JVM tests passed, including the contrast checks. All seven instrumentation tests passed at both normal and 200% system font scale. The formatting check used French resources with metric and imperial values.

Emulator screenshots were inspected for light/dark Home, Peaks, Regions, Settings, and camera treatment. Device night-mode switching with System selected was checked live, along with landscape at enlarged text size. Enlarged-text review led to wrapping viewpoint controls, measured tab widths, single-line field-of-view values, and footer-safe panorama labels. Font scale, night mode, and portrait rotation were restored after the checks.
