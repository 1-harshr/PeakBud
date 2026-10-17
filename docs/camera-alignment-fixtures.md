# Real camera-alignment fixtures

These photographs are test resources under `app/src/test/resources/camera-alignment/`. They are not app assets and are not included in the APK. Source metadata and file hashes are recorded in `fixtures.json`; downloads were checked on 17 October 2026. No code or model was copied with these images.

## Attribution and evidence

| Fixture | Author and source | Licence | Camera-location evidence | Purpose |
| --- | --- | --- | --- | --- |
| `dhanaulti-himalaya.jpg` | Koshy Koshy, [Nanda Devi (11490510604)](https://commons.wikimedia.org/wiki/File:Nanda_Devi_(11490510604).jpg), originally [Flickr](https://www.flickr.com/photos/kkoshy/11490510604/) | [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/) | Source camera location 30.423519 N, 78.246292 E; description explicitly says Dhanaulti, Uttarakhand | Clear distant snowy skyline, blue sky, intervening ridges; 1024 × 507 original, unchanged |
| `deoria-tal.jpg` | Mudassarmunshi, [Deoria Tal](https://commons.wikimedia.org/wiki/File:Deoria_Tal.jpg) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Source camera location 30.522200 N, 79.127700 E; description identifies Deoria Tal | Mountain skyline partly covered by clouds and foreground trees, tents and a small building; 1052 × 780 original, unchanged |
| `hemkund-clouds.jpg` | Naresh Chandra, [Reflection of Nanda Devi Hills on Hemkund Sahib Lake Chamoli, India (Wide)](https://commons.wikimedia.org/wiki/File:Reflection_of_Nanda_Devi_Hills_on_Hemkund_Sahib_Lake_Chamoli,_India_(Wide).jpg) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Source camera location 30.699436 N, 79.611436 E; page reports heading 83.657381615599 degrees | Cloud-interrupted close ridges and reflected mountains; Wikimedia 1280 × 559 thumbnail of an 8708 × 3800 image, otherwise unchanged |
| `ranchi-temple.jpg` | Ms Sarah Welch, [1 Jagganath temple Ranchi Jharkhand](https://commons.wikimedia.org/wiki/File:1_Jagganath_temple_Ranchi_Jharkhand.jpg) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Source camera location 23.316714 N, 85.281144 E; page reports heading 46.318965517241 degrees | Negative view of trees and a building against a cloudy sky in Jharkhand; Wikimedia 1280 × 960 thumbnail of a 3264 × 2448 image, otherwise unchanged |

The file titles and subjects are the uploaders' descriptions, not independently verified summit identifications. The first image's generic title is not evidence that its central summit is Nanda Devi. The reported coordinates are uploader-supplied WGS-84 positions; the number of decimal places does not establish accuracy. No fixture has a surveyed camera pose, verified altitude, distortion calibration or reliable field of view. The two reported headings have unverified accuracy and north-reference conventions, so they are retained as provenance, not test truth. Hemkund is a wide panorama and must not be treated as a calibrated rectilinear camera frame.

## Derived negative fixtures

These are crops of the photographs above, with the same attribution and licences:

| Fixture | Source and crop in original pixels, left/top/right/bottom | Interpretation |
| --- | --- | --- |
| `sky-only.png` | Koshy Koshy's `dhanaulti-himalaya.jpg`, `[0, 0, 1024, 120]`, CC BY 2.0 | Clear sky with no terrain boundary |
| `ground-only.png` | Mudassarmunshi's `deoria-tal.jpg`, `[0, 580, 1052, 780]`, CC BY-SA 4.0 | Grass and foreground ground, no sky |
| `trees-only.png` | Mudassarmunshi's `deoria-tal.jpg`, `[0, 300, 340, 520]`, CC BY-SA 4.0 | Dense foreground foliage with sky gaps and parts of the campsite |

Crops were saved losslessly as PNG; no generated content was added. They exercise scene-content rejection. They are **not independent images captured with a ground-facing or sky-facing camera pose**. Pose/lifecycle tests must cover actual ground-facing inputs separately. Modified CC BY-SA images remain CC BY-SA 4.0. Keep this attribution, the source links and change descriptions when redistributing fixtures or rendered derivatives. The image licences do not change the licence of the application's independently authored code.

## Pixel annotations and honest validation limits

`dhanaulti-skyline.csv` contains sparse, manually inspected pixel positions along the highest visible land boundary. Coordinates refer to the unchanged 1024 × 507 image with origin at the top left. Allow ±10 pixels for hand annotation and JPEG/low-contrast ambiguity. They are useful for checking whether extraction follows the real mountain–sky boundary instead of an internal snowy ridge or the near foreground ridge. They are not angular measurements, named peak correspondences or DEM-derived truth.

Only the clear Dhanaulti frame is a straightforward extraction-positive example. The Deoria Tal, Hemkund and Ranchi images are challenge fixtures: clouds can replace the true skyline, reflections create duplicate boundaries, and trees/buildings create mountain-like silhouettes. A contour alone is insufficient evidence of a valid geographic match. Matching tests should use an independent predicted skyline, ambiguity checks and negative terrain candidates; they must not obtain their “terrain prediction” by copying the contour extracted from the same photograph.

These fixtures establish realistic image-input coverage and allow pixel-domain extraction checks. They cannot establish absolute GPS/heading accuracy or true image-to-DEM registration accuracy. Synthetic fixtures can establish recovery of known mathematical corrections. Measuring outdoor angular accuracy still requires physical-phone captures with original intrinsics/preview transforms, synchronized location/orientation and an independently established pose or surveyed summit bearings. Include clear ridges, haze, cloud cover, snow, trees and buildings across Uttarakhand and Jharkhand in that field set.
