# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
- Pages are set upright automatically from the direction of their text,
  in the same pass that reads it.
- Offline OCR with PaddleOCR v5 in the phone's language (Latin, Cyrillic,
  Greek, Arabic, Devanagari, Thai and the multilingual model for Chinese
  and Japanese); tilted lines are followed, multi-column pages read
  column by column; a second pass with the multilingual model for unsure
  Latin lines. About 3 s per page on a Pixel 7a.
- Documents of several pages, read in the background while you scan;
  reorder, turn, delete; kept on disk until saved or discarded.
- Searchable PDF: the page photos unchanged, the text as an invisible layer
  in any script, found by Android's and Chrome's viewers as well as by
  macOS Preview; saved to one folder picked once, or shared.
- Portrait and landscape (controls beside the preview, the photo and the
  pages); light and dark theme following the system; a themed launcher
  icon.
- English and German.
