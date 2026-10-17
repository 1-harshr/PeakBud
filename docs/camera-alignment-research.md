# Camera-to-terrain alignment research

Research checked 17 October 2026. This document separates published evidence from proposed PeakBud engineering choices. It is not a claim of outdoor accuracy.

## What PeakLens establishes

PeakLens explicitly compares the camera scene with terrain geometry, rather than relying on compass placement alone. Its public description identifies SRTM as the elevation source and OpenStreetMap as the peak source; offline operation uses downloaded maps. [PeakLens official site](https://peaklens.com/)

The authors' testing paper describes a pipeline that extracts a skyline from camera frames, generates a virtual panorama from position/orientation/FOV, excludes terrain-hidden peaks, aligns the two skylines, projects names, and updates positions using motion sensors. It also describes offline terrain/peak storage and validation against manually annotated frame sequences. Relevant metrics include false visible labels, missed labels, and angular positioning error. These are stronger acceptance criteria than a plausible screenshot. [Frajberg et al., A Testing Framework for Multi-Sensor Mobile Applications, section 4](https://www.riverpublishers.com/journal/journal_articles/RP_Journal_1550-4646_1511.pdf)

Baboud et al. use rendered silhouette edges, known approximate viewpoint/FOV, and robust edge matching to estimate pose. The method targets seasonal/weather changes and edges not represented in terrain. This supports geometry-driven matching rather than a classifier that recognizes individual named mountains. [Authors' CVPR 2011 publication page](https://publications.graphics.tudelft.nl/papers/484)

Fedorov's thesis describes filtering noisy image edges and matching edge orientation/strength against a rendered panorama, searching horizontal and vertical displacement. Snow lines, cloud edges, and foreground objects are specific sources of confusion. PeakBud can use the same general registration principle without reproducing that implementation. [Mountain Peak Detection in Online Social Media, sections 3.3–4.2](https://arxiv.org/html/1508.02959)

## Public repository and asset decisions

| Project | Relevant work | Verified licence / decision |
| --- | --- | --- |
| [PeakNavApp](https://github.com/Upabjojr/PeakNavApp) | JVM skyline extraction, terrain/photo matching, confidence rejection, benchmark scripts, learned pixel/boundary forests | [GPL-3.0-only code/assets, with listed exceptions](https://raw.githubusercontent.com/Upabjojr/PeakNavApp/main/LICENSE). Research reference only; do not copy code or ship models into PeakBud without deliberately adopting applicable terms. |
| [TouqeerAhmad/skyline_detection](https://github.com/TouqeerAhmad/skyline_detection) | IJCNN 2021 shallow learned filters and dynamic-programming skyline path; Python/OpenCV plus Matlab | [Custom licence limits redistribution to non-commercial research collaboration/demonstration](https://raw.githubusercontent.com/TouqeerAhmad/skyline_detection/main/LICENSE). Do not bundle implementation/filter assets for a general app. Dataset terms are separate. |
| [GeoPose3K](https://cphoto.fit.vutbr.cz/geoPose3K/) | Known-viewpoint camera-pose evaluation dataset | Potential external benchmark, not automatically a distributable fixture source. Verify individual image/data redistribution rights before importing; the paper's licence alone does not establish rights in photographs. |

PeakNav's README reports confidence-gated photo evaluation and explains that its learned extraction handles snow/cloud ambiguity better than its classical extractor. These are that author's measurements, not transferable PeakBud performance. Its repository is useful evidence that on-device JVM inference is feasible, not permission to import its trained forests. [PeakNav skyline documentation](https://github.com/Upabjojr/PeakNavApp#photo-skyline-matching)

Recommended first implementation: independently authored bounded classical image processing and robust geometric matching, with no external trained weights. Record actual limitations; a future permissively licensed sky/terrain segmentation model requires its own model-card, training-data, licence, accuracy, and runtime review.

## Recommended first algorithm

The following are engineering proposals, not specifications copied from PeakLens.

1. Downsample analysis frames to a bounded size. Find top-connected sky-like regions and a continuous boundary using colour, local texture, edge strength, and smoothness. Preserve per-column evidence and invalid/occluded columns. Never replace missing evidence with a fabricated skyline.
2. Reject frames without a substantial sky/ground split, broad supported boundary, useful skyline relief, or reliable camera geometry. Reject excessive pitch/roll, clipped ridges, uniformly blue/white frames, and highly jagged/rectilinear foreground edges. Blue colour alone is insufficient: roofs, snow, clouds, haze, and vegetation remain difficult.
3. Convert observed boundary pixels to calibrated camera rays. Transform candidate terrain directions into the same frame, including roll. Search yaw/pitch corrections around the sensor estimate at coarse then fine resolution. Match broad shape and local slope with robust residuals so partial occlusion cannot dominate.
4. Accept only when coverage, relief, residual, multiple separated features, and the gap to a genuinely separate competing yaw basin all pass. Adjacent grid samples around one minimum are not independent competitors. Reject solutions at search limits. Require temporal agreement across multiple frames; an arbitrary best score always exists even in a bad scene.
5. Store an alignment correction independently of sensor readings. Continue sensor-driven movement while periodically verifying observed-vs-predicted skyline. Revoke on stale verification, GPS relocation, region/calibration/rotation changes, large motion, or repeated mismatch. A frozen correction without visual revalidation is not maintained camera alignment.
6. Suppress camera-aligned peak anchors in unsupported or foreground-occluded columns when the observed boundary disagrees with the terrain. Skyline registration confirms a pose, not the visibility of every individual summit. Preserve existing terrain occlusion checks and clearly distinguish sensor-estimated labels from aligned labels.

This conservative clear-sky version will reject some useful cloudy/snowy views. It cannot establish semantic mountain identity from colour/edges alone, and must not claim universal rejection of every roof/tree arrangement. A held-out negative image suite and outdoor data should determine whether gating is adequate.

## CameraX geometry and lifecycle

Use `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`, a dedicated bounded worker, and `ImageProxy.close()` in `finally`; do not close the wrapped `Media.Image`. Clear the analyzer and unbind owned use cases on disposal. Analysis may drop frames while preview remains responsive. [Android image-analysis guide](https://developer.android.com/media/camera/camerax/analyze)

Bind preview and analysis in one `UseCaseGroup` with the preview's `ViewPort`. CameraX then maps their crop rectangles to the same sensor area. Actual resolutions can still differ. [Android ViewPort API](https://developer.android.com/reference/androidx/camera/core/ViewPort)

Apply `cropRect`, rotation metadata, and the analysis-to-preview transform explicitly. Fit/center-crop dimensions and portrait rotation must not silently alter the FOV. Validate sensor→analysis→view round trips and portrait/landscape fixtures; do not derive camera intrinsics from a downsampled thumbnail's aspect ratio. [Android output-transform guide](https://developer.android.com/media/camera/camerax/transform-output)

## Performance and validation priorities

Cache the observer's terrain horizon independently of phone rotation; camera frames must not rerun the long terrain calculation. Measure terrain build separately from frame extraction/matching. Reuse the offline region reader, keep work cancellable, and measure cold/warm latency and peak memory on Android.

Synthetic terrain renders establish recovery of known yaw/pitch, crop/rotation math, repeated-shape rejection, and tracking-loss behaviour. They do not establish real-camera extraction. Add independently licensed real mountain images with recorded viewpoint, source/author/licence, intrinsics or explicit uncertainty, and manually checked skyline annotations. Include negative ground/buildings/trees/sky and difficult clouds/snow/haze. Do not report heading accuracy from images without ground-truth heading. Keep tuning and held-out evaluation images separate; record false acceptances as well as successful matches. Physical-phone outdoor tests remain required for lens distortion, sensor/image timestamp mismatch, motion blur, and magnetic interference.
