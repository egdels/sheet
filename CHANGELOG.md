# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Pages whose text could not be read are marked in the overview, with
  "Read again".
- Deleting a page asks first.
- Camera permission refused for good: a button opens the app's settings,
  and the camera comes back as soon as it is granted there.

### Changed

- OCR about 40 % faster: on a Pixel 7a a dense A4 page (222 words) takes
  5.9 s instead of 10.8 s, the same page photographed sideways 8.5 s
  instead of 14.7 s. Only the start of the longest lines is read to tell
  the script and which way up the page is.
- Turning a page keeps its text instead of reading it again; the PDF shows
  the page as turned and its text stays searchable.
- The screen keeps its orientation while you aim, so turning the phone to
  frame the page does not flip the layout.
- The folder picker opens in the folder you last saved to.
- Scans are left out of Android's backup and device transfer; the network
  permission a library brought in is removed.
- ONNX Runtime 1.31.1, built from source without telemetry.

### Fixed

- After Android closed the app in the background it comes back on the
  screen you left (pages or crop, with your corners); opening it from the
  launcher no longer starts a second copy.
- Errors while saving or processing a page show a message instead of
  closing the app; Back is ignored while a page is being processed.
- A damaged document file no longer loses the pages: they are rebuilt from
  their photos.
- PDF text layer: characters outside the Basic Multilingual Plane (rare
  CJK characters, emoji) are found and copied correctly.
- Saving after the folder was deleted or access to it was revoked asks for
  a folder again instead of failing.
- Landscape: the buttons on the page tiles stay on screen.
- Deleting the last page returns to the camera; files of deleted pages and
  retaken photos are removed.

## [0.1.0]

### Added

- Camera with the page outlined live (DocQuadNet-256, smoothed, shown once
  it is steady and only when the model is sure of it, so not on a covered
  lens); the volume keys take the photo.
- Corner detection on the photo with extra passes on the photo turned when
  the page lies at an angle, then snapping onto the paper's edges; printed
  rules near the edge are not taken for it.
- Crop screen: drag a corner under a magnifier; the page is straightened to
  the paper's real aspect ratio. Tapping a page in the overview cuts it out
  again from its photo. On a Pixel 7a the crop screen shows about 0.2 s
  after the photo.
- Sharp scans: the shutter focuses and exposes on the page and waits for
  still hands ("Hold still"); "Move closer" while the page is small in the
  picture. The PDF shows the page cleaned up like a scan (white paper
  without shadows or colour cast, black crisp text) at about 0.8 MB per
  A4 page; OCR reads the page as photographed.
- Pages are set upright automatically from the direction of their text,
  in the same pass that reads it.
- Offline OCR with PaddleOCR (Latin, Cyrillic, Greek, Arabic,
  Devanagari, Thai, Korean, Chinese and Japanese) with no language to
  choose: the script on the page picks the model, the phone's languages
  first. Latin, Chinese and Japanese pages are read by PP-OCRv6, lines
  with accents or CJK also by PP-OCRv5 and the surer reading kept (much
  better on phone photos); tilted lines are followed, multi-column pages
  read column by column. On a Pixel 7a, before PP-OCRv6, about 3 s for a
  card, 4.5-5 s for a full A4 letter (several lines are read at once).
- Documents of several pages, read in the background while you scan;
  reorder, turn, delete; kept on disk until saved or discarded.
- Searchable PDF: the page photos unchanged, the text as an invisible layer
  in any script, found by Android's and Chrome's viewers as well as by
  macOS Preview; saved to one folder picked once, or shared.
- Portrait and landscape (controls beside the preview, the photo and the
  pages); light and dark theme following the system; a themed launcher
  icon.
- English and German.
