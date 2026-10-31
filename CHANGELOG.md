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
