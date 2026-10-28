# Developing Sheet

How Sheet is built, tested and measured, the decisions behind it and how
the code is laid out.

An offline document scanner for Android: camera, corners, searchable PDF.
GPL-3.0-or-later. Kotlin, Compose, CameraX, ONNX Runtime. Android only,
decided by the developer on 2026-10-25.

Sheet is a rewrite of [MakeACopy](https://github.com/egdels/makeacopy)
(Java, Apache-2.0, ~90k lines grown over 840 commits; the commands below
expect it checked out next to this repository as `../makeacopy-github`).
It is a new app with its own package
id (`de.schliweb.sheet`), not an update of MakeACopy. Two things are taken
over on purpose, everything else is left behind:

- **DocQuadNet-256**, the corner model (MobileNetV3 + FPN, 256x256 input,
  4 corner heatmaps + page mask at 64x64). The trained `.ort` file and the
  training pipeline (`training/` over there) come along; the app-side
  code (letterbox, runner, postprocessor, score, edge snap, One-Euro
  smoothing, under `ml/` there) is ported to Kotlin, not copied.
- **PaddleOCR v5** (det + rec per script, CTC dictionaries) with its
  pipeline: DB postprocessing, quad crop, CTC decoding, language routing,
  line/word splitting, result building (`app/src/paddle/` there).

The product rule, shared with the developer's other apps: **open it and
scan**. No account, no cloud, no settings screen. Defaults instead of
options and dialogs. Internal complexity is fine if the user never sees it.

## Build, test, run

Needs JDK 17+, Android SDK with platform 37 (`ANDROID_HOME` is set on this
machine), Gradle 9.7 via the wrapper.

The app links ONNX Runtime built from the `external/onnxruntime` submodule
(v1.24.1); its outputs are not committed, so build them once (and after
changing `ort/*.config`) before building the app:

```sh
git submodule update --init external/onnxruntime && git -C external/onnxruntime submodule update --init cmake/external/onnx
PATH=<venv with flatbuffers>/bin:$PATH ABIS="arm64-v8a x86_64" scripts/build_onnxruntime.sh
                                 # -> app/src/main/jniLibs/<abi>/*.so + app/libs/onnxruntime.jar, ~3 min per ABI
./gradlew :core:test             # JVM tests, no emulator
./gradlew :eval:run --args="<data>/own_tilt_eval_holdout <data>/own_tilt_eval_tune"
                                 # corner pipeline vs. labels on the Mac, ~40 s for 370 photos;
                                 # --samples <out.tsv> per photo, --model <file.ort> for a candidate model
E=../makeacopy-github/app/src/androidTestPaddle/assets
./gradlew :eval:run --args="ocr $E/eval $E/synth $E/eval/jpn"   # OCR vs ground truth (CER, WER,
                                 # lines found), ~20 s; --text <dir> writes each result, --max-side <px>
./gradlew :eval:run --args="ocr --orientation $E/eval $E/synth $E/eval/jpn"   # every sample turned four
                                 # ways: does OcrEngine.orientation set it upright? (179/188)
./gradlew :app:connectedDebugAndroidTest   # OCR on the device/emulator: clean_scan, CER must stay < 0.03
scripts/icon.py                  # icon PNGs (fastlane 512, Packaging/AppIcon-256.png) from the vector's geometry
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug         # must stay at zero errors
./gradlew :app:assembleRelease   # R8; unsigned without RELEASE_KEYSTORE_PATH etc.
                                 # sign with ~/.android/debug.keystore (apksigner) to try it on the
                                 # phone: R8 problems (JNI lookups by name) only show there, and
                                 # timings must come from it (debuggable: EdgeSnap 4.2 s vs 0.76 s)
adb push <signed.apk> /data/local/tmp/s.apk && adb shell pm install -r /data/local/tmp/s.apk
                                 # for timings: compiled ahead of time as a real install is
                                 # (dumpsys package dexopt: oat/arm64/base.odex, reason=install).
                                 # Android 17 on the Pixel 7a has the JIT off; after `adb install`
                                 # the app stays interpreted (run-from-apk), and `pm compile` writes
                                 # to dalvik-cache, which apps may not execute: Enhance 1.8 s vs 0.09 s
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a &   # arm64-v8a, 16 KB pages
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n de.schliweb.sheet/.MainActivity
adb exec-out screencap -p > shot.png
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a -camera-back virtualscene -virtualscene-poster wall=<page.jpg> &
                                 # the emulator's camera is a 3D room; the poster hangs on a wall
                                 # (moving around needs the emulator window); the TV in the default
                                 # view is detected as a page, good enough to check overlay mapping
scripts/docquad_outputs.py <model.ort> <dataset> <dir>   # DocQuadNet raw outputs per labelled photo
                                 # (needs onnxruntime, numpy, pillow)
scripts/docquad_expected.sh <dir> > <dir>/expected.txt   # MakeACopy's postprocessor on them (javac/java)
scripts/strip_comments.py <copy> # removes the comments from a copy of the sources for publishing
                                 # (see AI_POLICY.md); --dry-run lists the files it would change
```

The published code carries no comments (see AI_POLICY.md); why things
are the way they are is recorded in this file.

MakeACopy's OCR sets are read in place, not copied: the provenance of
several images (news page, language samples, Japanese) is undocumented.
The reference PaddleOCR (3.7, same v5 det + latin rec) runs from the
MakeACopy monorepo: `cd ../makeacopy/paddle-worker && WORKER_DET_MODEL=
PP-OCRv5_mobile_det WORKER_REC_MODEL=latin_PP-OCRv5_mobile_rec ../venv/bin/
python <script>` with `from app import engine; engine.run_ocr(path)`.
OCR baseline (`ocr`, 1920 px): main set mean CER 0.013 (reference with the
same line grouping but no column layout: 0.139 over the Latin samples incl.
synth, Sheet 0.140 before ColumnLayout), synth 0.095 (all lines but 1 of
390 found; synth_dropcap_footer's ground truth is in drawing order, so its
CER says nothing), jpn: horizontal 0.024, vertical fails (not ported).

Labelled corner photos (images/ + labels/*.json with `corners_px`) live on
the developer's disk, not in the repository: `<data>/own_tilt_eval_holdout`
(150) and `own_tilt_eval_tune` (218), `my_data_converted` (61). Never
train on the hold-out set.

Baseline of the shipped model in `:eval:run` (mean / median corner error,
relative to the diagonal; "plain" = one pass, "still" = with rotation,
"snap" = still + EdgeSnap, what the crop screen starts with; "good" =
worst corner within 1 %):
holdout 1.38 / 0.56 % plain, 1.05 / 0.52 % still, 0.92 / 0.38 % snap
(good 91 / 98 / 109 of 150); tune 3.08 / 0.61 %, 1.83 / 0.56 %,
1.67 / 0.33 % (120 / 137 / 149 of 218); my_data 2.73 / 0.46 %,
2.01 / 0.40 %, 1.97 / 0.42 %; smartdoc_val_sub_trainable (75) 0.40 /
0.27 %, 0.45 / 0.27 %, 0.43 / 0.24 %. Page shape (PageSize within 0.03 of
A4/Letter): from labels 134/150, 180/218, 39/61, 67/75; from snap 96, 134,
36, 59: the projective ratio is sensitive to corner error. MakeACopy measured
the same model on the phone (training/runs/corner_eval_2026-09-20_repo_v6
there): holdout identical; on 91-95 % of the tune and my_data photos within
0.2 %, the rest are low-confidence photos (< 0.3) where AWT vs. Android
scaling, the JPEG decoder or ORT 1.31 vs. 1.24 tip the result. Compare
changes with the eval, absolute numbers for hard photos come from a device.

## Decisions

- **F-Droid stays possible**: everything builds from source. Hence no
  OpenCV at all (MakeACopy built it from a submodule): the corner and OCR
  code need only grayscale, blur, resize, Sobel and contours, which live
  in `core` as plain Kotlin on arrays; the perspective warp is
  `android.graphics.Matrix.setPolyToPoly`. ONNX Runtime is a minimal build
  from source, reduced to the operators of DocQuadNet and PaddleOCR v5
  (the union of their `required_operators.config`).
- **No Tesseract**, no OCR flavors: one app, one OCR engine.
- **Two modules**, as in Crate: `core/` is plain Kotlin/JVM (geometry,
  model pre- and postprocessing, OCR pipeline, page/document model, PDF
  text layer layout) and is where the tests are; `app/` is camera, ONNX
  Runtime sessions, Compose UI, SAF and PDF writing.
- **Corner detection is DocQuadNet alone.** MakeACopy's OpenCV contour
  fallback failed on 115 of 150 hold-out photos and is gone. Kept: the
  rotation test-time augmentation for stills (mean error 1.4 % -> 1.05 %
  of the diagonal on the hold-out set, 3.1 % -> 1.8 % on the tune set) and
  the mask-vs-heatmap choice.
  Dropped: the other peak modes and the unused "suspicious" diagnostics.
- **EdgeSnap** (MakeACopy's EdgeSnapCornerRefiner) runs once on the
  detected outline of a still, never after the user dragged a corner. New
  rule: a gradient peak with an opposite one within 4 px is a printed line,
  not the paper's edge; without it data sheets and forms snapped onto
  their border rule and SmartDoc got worse with snapping (65 -> 56 good).
- **PageSize**: projective aspect ratio (Zhang & He) as in MakeACopy, but
  the fallback focal length is 0.75 x the long edge instead of 1.0 (44 vs
  36 of 70 labelled photos without a real solution within 0.03 of A4/
  Letter). Edge lengths only when the page was shot straight on.
- **ONNX Runtime**: `scripts/build_onnxruntime.sh`, a lean port of
  MakeACopy's F-Droid build: `--minimal_build extended` with the union of
  `ort/*.config`, CPU only, no exceptions/RTTI/ML ops, 16 KB pages, about
  4 MB per ABI. The eval uses the desktop package from Maven Central
  (1.31.0; 1.24.1 there lacks the macOS natives).
- **Camera**: CameraX's LifecycleCameraController, no hand-written crop
  or rotation maths: analysis frames are made upright and detected at
  <= 720 px, and the outline goes to the preview through
  ImageProxyTransformFactory (rotation on, crop rect off) and
  CoordinateTransform. One smoothing stage (LiveTracker); MakeACopy had
  One-Euro plus an EMA plus separate hysteresis.
  With the rotation locked and the phone held sideways, frames and photo
  follow the display, so the page arrives on its side. Measured on the
  Pixel 7a (2026-10-27, A4 letter, both ways round): live outline shown
  (confidence 0.86-0.95), still found in the first passes, the reading
  pass set the page upright. A sticky note on its side was not seen live
  (0.015 vs 0.88 upright); turning the frames by the sensor's orientation
  would help there, not done.
  The camera and crop screens are black in light and dark mode (as camera
  apps and Lens: no glare around the 4:3 preview, no flash between the
  two), so their system bar icons are always light (SheetScreen); the
  overview follows the system theme.
- **Look**: the wallpaper's colours on Android 12+ (dynamic colour, as
  Google's own apps), before that a scheme around the icon's petrol
  (ui/Theme.kt); one green for the page outline everywhere (PageOutline).
  The shutter is a white ring with a white disc as in camera apps; the
  ring turns green while a page is seen. The page count on the camera is
  in the primary colour, not the error red of a default Badge. In the
  overview each page is a sheet in its own shape, standing on its buttons
  in a 3:4 cell. On the crop screen the page keeps its colours and the
  rest of the photo is dimmed; the handles are rings open in the middle,
  so the paper's corner stays visible. "Discard" is an icon at the top end (landscape: atop the
  side panel), away from "Save", and its question survives turning.
- **Landscape** (window wider than tall, `ui.isLandscape`): the controls
  move from a row at the bottom into a column at the end: the shutter in
  the bar beside the 4:3 preview, the crop buttons stacked (main one on
  top), the overview's folder and buttons in a 260 dp panel, page tiles
  sized so that a whole one fits the height.
- **OCR** (PaddleOCR v5, `core.ocr`): the device language picks the
  recogniser (`RecModel.forLocale`, unknown languages read with the
  multilingual one); no language setting. Detection always at 1920 px
  (MakeACopy's "best quality" option; 1536 lost small_text 0.025 -> 0.076)
  and the multilingual second pass for unsure Latin lines (also measured).
  Text boxes follow a tilted line (MakeACopy's were axis-aligned: CER 0.74
  vs 0.01 at 2 degrees of skew). Multi-column reading order always on;
  tall, narrow boxes (vertical margin notes, at most a quarter of the boxes)
  are left out of the column analysis and read last, else a note split in
  two can make a column of its own (seen in MakeACopy on an appointment card).
  Not ported: vertical CJK, low-contrast crop enhancement, MakeACopy's
  dictionary post-correction; add them when the eval shows a need.
- **Scanning is a loop** (as in Lens): camera -> crop -> "Use page" ->
  camera again; OCR reads the pages one at a time in the background. The
  page overview (count button bottom left in the camera) reorders, turns
  (a quarter clockwise: only the count in the document, the images stay
  as they are; the text is read again)
  and deletes; "Discard" asks first. Re-cropping a page later is not
  possible (the photo is not kept).
- **Pages are set upright automatically** in the pass that reads them
  (OcrEngine.recognizeUpright: tall line boxes = on its side, recogniser
  confidence both ways round = upside down; for CJK only upside down; the
  boxes and the lines read upright are used again for the text). Only a
  new page (revision 0); a page the user turned is left alone.
- **OCR time** (Pixel 7a, release build, one card, ~30 words): 3.0-3.2 s
  per page (detector 1.3 s, ~17 recogniser runs 1.2 s, Kotlin ~0.4 s),
  first page after start +0.6 s for loading. Was 4.9 s with a separate
  orientation pass (detector at 960 px, 12 lines) and 4 ORT threads.
  A full A4 letter (263 words, 77 boxes, 2240x3138, compiled as a real
  install, measured with app_process on the phone): 5.6-6.6 s one line
  after another, of which recogniser 83 runs x 55 ms (77 %), detector
  0.7-1.1 s, rendering crops 0.16 s, Kotlin 0.08 s. Now four lines at
  once (OcrEngine readers = 4, recogniser sessions one thread each,
  longest lines handed out first): 4.5-5.0 s, the text identical.
  Tried and dropped: ORT batches of 4 or 8 lines (slower per line),
  2-4 threads per run (no faster than 2), thread affinity to the big
  cores (not implemented in ORT for Android), XNNPACK (takes none of the
  pre-fused ops of our .ort models; the recogniser crashed, the detector
  got slower). Static int8 (QDQ, per-channel weights, MinMax over 548
  line crops of the synth and language samples, model raised to opset
  13; ORT +0.2 MB for the QLinear kernels), latin recogniser, four runs
  at once on the Pixel 7a: fp32 35.5 ms a line; everything quantized
  26.4 ms but CER 0.013 -> 0.023 (accents: deu 0.000 -> 0.018); convs
  only 29.6 ms, backbone convs only 30.9 ms, mean CER unchanged but
  doubled letters lost in small print (cillum -> cilum, nulla -> nula):
  CTC needs the blank between them. Not worth it at ~0.4 s a page;
  quantization-aware training would be the way.
- **A page can be cut out again**: its photo (<id>.photo.jpg), outline and
  quarter turns are kept until the document is saved; tapping the page in
  the overview opens the crop screen with them.
- **Share** writes the PDF into cache/share and hands it out through a
  FileProvider (`${applicationId}.files`); the document stays.
- **Volume keys** take the photo while the camera shows (MainActivity ->
  vm.onShutterKey -> shutter flow -> CameraScreen).
- **The document in progress lives on disk** (`filesDir/document`:
  document.json, <id>.plain.jpg at quality 100, <id>.jpg at quality 90,
  <id>.photo.jpg, <id>.txt.json), because Android kills camera apps in the
  background; on start, pages not yet enhanced or read are queued.
- **Sharp pages** (a letter on the floor, Pixel 7a, 2026-10-27: the photo
  itself was blurred and the page 127 dpi):
  - The shutter focuses, meters and white-balances on the middle of the
    outline (startFocusAndMetering, at most 1.5 s) and, at the same time,
    waits until the gyroscope says the phone is still for 0.2 s (below
    0.05 rad/s, at most 1.5 s; Stillness, camera/Steady): pressing it
    shakes the phone. "Hold still" shows meanwhile.
  - "Move closer" while the page's long side is under half the picture's
    (off again above 0.58): below that an A4 page comes out under
    ~170 dpi. The camera gives apps 4032x3024 at most (16 MP is RAW only),
    so being close is the only way to more pixels. With it: 268 dpi.
  - The PDF shows the page enhanced (core.image.Enhance): paper colour
    estimated in ~40 blocks along the long side and divided out (shadows,
    light falling off, colour cast), lifted 5 % so paper is clean 255,
    a block darker than 0.7 of the typical paper counting as paper only
    when flat (bright quarter's mean within 6 of the median, down to 0.3;
    gain up to 3): a hand's shadow over a letter then turns white (two
    pages 849 -> 722 KB and 814 -> 687 KB), a photo pasted onto the page
    and a black bar stay as they are,
    ink to black plus a mid-tone curve, unsharp mask on luminance. OCR
    reads the plain page: on the enhanced one the eval lost Chinese
    strokes (chi_sim 0.025 -> 0.150) and small print (0.025 -> 0.071),
    though phone photos gained (phone_photo 0.043 -> 0.023);
    `--enhance` measures it.
  - One lossy step after the photo: the plain page is written at quality
    100, the enhanced one from it at 90; turning a page never re-encodes,
    the PDF draws the JPEG turned (image matrix). Paper at 255 and
    quality 90 halved the PDF (two pages 3.4 MB -> 1.6 MB) with no visible
    change in the strokes at 3x; 85 showed ringing.
  - Enhancing runs in the OCR queue, not behind "Use page", row by row in
    bands on all cores (small methods, so a JIT compiles them early):
    7 MP page on the Pixel 7a 0.08-0.09 s, 0.2 s with decoding and
    encoding (one piece, interpreted: 3.5 s; compiled: 0.2 s).
- **Output**: PDFs go to one folder picked through the Storage Access
  Framework on the first save (persisted grant, the previous one released
  on change; "Change" under the folder name is the only knob). No storage
  permission. Name `Scan yyyy-MM-dd HH.mm.pdf`; the provider resolves
  clashes. Saving waits for the text of all pages, then clears the
  document; a snackbar offers "Open".
- **PDF written by core itself** (`core.pdf.SearchablePdf`), no PDF library
  in the app: page JPEGs go in as they are (DCTDecode, no recompression);
  the text is an invisible layer (render mode 3) word by word over its box,
  stretched with Tz, in reading order, in GlyphlessFont (a 2-glyph
  TrueType built in code) with Identity-H and a ToUnicode map, as
  Tesseract/OCRmyPDF do: every script, no font licence, a few hundred
  bytes. FontDescriptor /Descent -1 as Tesseract's: with 0 macOS finds no
  text at all ("Failed to determine ascent and decent"). Page box: A4 or Letter when the aspect is within 3 %, else the
  long side is A4's. Checked by PDFBox 3 in core's tests (test dependency
  only) and, for a PDF saved on the phone, by pypdf, PDFium (pypdfium2:
  text and search) and PDFKit (`PDFDocument.findString` in a Swift script):
  each viewer family reads the text layer differently.

## Layout

```
core/  de.schliweb.sheet.core: Quad/Point (corner order TL, TR, BR, BL;
       Quad.of orders four unordered points, isPlausibleIn = within 25 %
       of the image and clockwise convex)
       core.corners: Letterbox (fit into 256, black padding), DocQuad
       (DocQuadOutput -> Detection: peaks, mask box, choice, confidence =
       weakest peak x corner/mask IoU), RotationTta (frames + decision for
       stills; the app draws each RotatedFrame and runs the model)
       CornerDetector (DocQuadModel + PageImage interfaces; detectFrame =
       one pass, detectStill = scaled to 720 px + rotation passes),
       LiveTracker (preview outline: One-Euro per coordinate, shown after
       2 plausible frames with confidence >= 0.1, hidden after 3 misses,
       restart on a jump; a covered lens gave convex outlines at 0.0),
       EdgeSnap (outline onto the paper's edges at <= 1600 px),
       Stillness (gyroscope readings -> held still)
       core.image: Enhance (the PDF's scanned look), GrayImage (luma, 3x3 blur and Sobel as OpenCV's),
       Gradients (the same per pixel on demand, bit-identical; EdgeSnap
       reads ~1 % of the image: 0.8 s -> 0.11 s on the Pixel 7a)
       core: PageSize (pixel size of the straightened page)
       core.document: Document (pages in order: id, revision, quad, turns),
       DocumentStore (JSON + images on disk, atomic writes, stale text by
       revision)
       core.pdf: SearchablePdf (writer, PdfPage drawn with its turns), GlyphlessFont, Jpeg (size
       from the frame header)
       core.ocr: OcrEngine (detect -> DbPostprocess -> straightened crop ->
       recogniser -> CtcDecoder -> WordSplitter/CTC tokens -> ColumnLayout
       -> Lines; OcrModels/OcrModel/Tensor are the model interface), Box/
       TextBox/OcrWord/OcrLine/OcrPage, Bidi (visual -> logical for RTL),
       RecModel (models and language mapping), DetLetterbox, Vocab
       tests: resources/docquad = 10 recorded model outputs + MakeACopy's
       results (DocQuadParityTest, matched to 1e-9 px)
app/   de.schliweb.sheet: MainActivity, SheetViewModel (the only UI state:
       Screen Camera/Crop/Pages, live outline, analyzer on its own thread
       at <= 5 Hz; capture -> detectStill -> clamp -> EdgeSnap -> Crop;
       document, OCR queue (Channel on a single-thread dispatcher),
       thumbnails), ui/SheetScreen, ui/Theme, ui/PagesScreen (grid, per page: earlier,
       later, turn, delete; "Reading text..." while queued),
       crop/CropScreen (drag the nearest corner within 48 dp, magnifier,
       dragged handle shrinks to a dot; "Cancel" when re-cutting a page),
       crop/Warp (Matrix.setPolyToPoly),
       camera/CameraScreen (permission, LifecycleCameraController, 4:3
       everywhere, capture ~12 MP, PreviewView FIT_CENTER, overlay via CoordinateTransform,
       shutter: focus + still hands, hints), camera/Steady (gyroscope),
       camera/Frames (ImageProxy -> upright, scaled bitmap),
       corners/OrtDocQuadModel (assets/docquad/docquadnet256.ort),
       corners/BitmapPageImage, export/SaveFolder (SAF tree in
       SharedPreferences, createDocument + write), ocr/AssetOcrModels (assets/paddleocr,
       sessions loaded on first use); OCR runs after "Use page" on a
       single-thread dispatcher, from the queue
       androidTest: OcrOnDeviceTest (needs a running emulator or device)
eval/  JVM tool: AwtPageImage, OrtDocQuadModel (desktop ORT), QuadError
       (MakeACopy's CornerEvalMetrics: best cyclic order, polygon IoU),
       OcrEval + OrtOcrModels (`sheet-eval ocr`)
ort/   operator lists of the shipped models, input of the ORT build
res    values (English) and values-de; adaptive icon in drawable/ +
       mipmap-anydpi-v26 (monochrome layer for themed icons)
fastlane/metadata/android/{en-US,de-DE}: title (<= 30), short (<= 80),
       full description, changelogs/<versionCode>.txt, images/icon.png
.github/workflows/android.yml: tests, lint, debug APK; ORT built from the
       submodule for arm64 + x86_64 and cached
.github/workflows/release.yml: on a v* tag (must match appVersion): release
       notes from CHANGELOG.md, ORT for all four ABIs, signed APK + AAB
       (secrets KEYSTORE_BASE64, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD,
       SIGNING_STORE_PASSWORD, as in Crate)
```
