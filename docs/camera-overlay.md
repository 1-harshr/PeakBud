# Live camera peak identification

The main screen is now a rear-camera preview with geographic peak labels. The
terrain panorama remains available through the Panorama switch. Image recognition
is not required for the initial camera experience.

## Pipeline

1. Choose a hiking area from Regions and download it. Existing HGT terrain, peak records, and
   line-of-sight checks determine visible and uncertain candidate summits.
2. Enable the camera with its runtime permission. CameraX binds a rear-camera
   Preview to the screen lifecycle; leaving the view unbinds only that use case.
3. Allow the first-use location prompt or choose Enable GPS. Android foreground GPS supplies a fresh observer
   position; terrain provides the ground altitude. Permission denial leaves the
   panorama and manual viewpoint available. Region selection automatically starts
   GPS if precise permission has already been granted.
4. Rotation-vector readings (or accelerometer/magnetometer fallback) provide the
   rear-camera direction, pitch, and roll, remapped for display rotation. Apply
   magnetic declination and manual heading trim to obtain true-north orientation.
5. Read the actual capture focal length and sensor dimensions through Camera2.
   Transform focal vectors and the sensor center using CameraX's sensor-to-view
   matrix so the pinhole projection follows preview rotation and center cropping.
6. Project each summit's bearing and elevation into camera pixels, rejecting
   points behind the lens or outside the viewport. Draw readable callouts with
   leader lines and tap targets. Skip overlapping callouts; the Peaks list retains
   access to details. Uncertain peaks carry a question mark when enabled.

Camera mode starts compass tracking. Location is requested at first use, even
before a terrain download; coordinates, GPS altitude, and sensor status are visible. Its overlay uses current unsmoothed pose to
avoid the panorama's smoothing lag. The −1° / +1° / Reset controls adjust heading
without detaching tracking. Camera mode has no panorama FOV or zoom slider.

Labels are withheld while using fictional demo terrain, without active/fresh GPS,
without a usable/reliable compass pose, during terrain calculation, or when camera
calibration/streaming is unavailable. Camera permission is requested by the Enable
camera button; denial offers an Android settings shortcut. No photos are captured,
stored, or uploaded.

## Full-screen camera

The Live camera screen offers **Full screen**. It hides the header, tabs, alignment
controls, and system bars, retaining peak callouts and a compact translucent overlay
with heading, latitude/longitude, GPS altitude/source, fix status, and view angles.
**Exit full screen** and Android Back both return to the normal camera layout.
System bars can be revealed temporarily with an edge swipe and are restored on
exit. Controls respect safe screen insets; CameraX recomputes the projection for
the larger preview. Full-screen selection survives configuration changes.

## Calibration limits

This is a sensor-aligned prototype, not verified visual registration. Camera focal
length and physical-size metadata estimate a centered pinhole lens; lens distortion,
principal-point offsets, stabilization, and logical multi-camera behavior can leave
errors. There is no zoom interaction, visual skyline matching, or per-device lens
calibration yet. Heading trim corrects a horizontal offset only. Compass reliability
and fresh GPS are prerequisites, not guarantees of correct peak identity.

Choose a built-in hiking region and tap Download; see
[region-catalog.md](region-catalog.md). Test in that region outdoors. The fictional
demo deliberately cannot put fabricated mountain names over the camera image.

## Verification

- Unit tests cover pinhole perspective, optical center at nonzero pitch, true-north
  wrap, roll, changed focal scale/center, and behind-camera/invalid-pose rejection.
- Emulator navigation tests cover camera as the default, switching to Panorama,
  region entry points, manual viewpoint, and settings.
- See [implementation-status.md](implementation-status.md) for current build and
  preview checks. Emulator success does not verify real summit alignment.

Before relying on the overlay: test portrait/landscape and camera lifecycle,
permission denial/revocation, disabled/stale GPS, magnetic interference, and known
summits on real hardware. Compare placements across the frame with photographs
from surveyed viewpoints and record angular error. Check installed packs after a
cold start in airplane mode. Camera video itself requires no internet.

## References

- [CameraX preview](https://developer.android.com/media/camera/camerax/preview)
- [PreviewView sensor-to-view transform](https://developer.android.com/reference/androidx/camera/view/PreviewView#getSensorToViewTransform())
- [Camera2 lens and sensor metadata](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [Sensor coordinate system](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-coords)
