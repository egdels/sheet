# Sheet

<img src="Packaging/AppIcon-256.png" width="128" height="128" alt="Sheet app icon: a sheet of paper in green scan brackets" align="right">

An offline document scanner for Android. Point the camera at a page, it
finds the corners, and you get a searchable PDF in a folder you chose.
No account, no cloud, no network permission, no settings screen.

Written in Kotlin, GPLv3, built from source down to the inference engine.
Sheet is a rewrite of [MakeACopy](https://github.com/egdels/makeacopy) that
keeps its corner model and its PaddleOCR pipeline and leaves the rest
behind.

## An experiment, in the open

Sheet is also a proof of concept. In the F-Droid community, code written
by AI is viewed critically: as "AI slop", and in the discussion about
marking it as an anti-feature. Sheet tests two claims in public:

1. An app built mostly by an AI coding agent, directed and checked by an
   experienced developer, can be **better than the
   original**, MakeACopy, by the same developer.
2. **You could not tell.** Nothing in the app or its source gives the AI
   away: remove `AI_POLICY.md`, this section and the script that strips
   the code's comments (`scripts/strip_comments.py`), and what is left
   reads like any other project.

The second claim is exactly why Sheet does not hide anything. If AI
involvement cannot be seen in the result, the only honest basis for
judging an app is what it does, measured, and how it was made, disclosed.
So both are here, though not in the commit history: that deliberately
carries no marks of the agent, to test whether anyone can tell from it.
[AI_POLICY.md](AI_POLICY.md) describes who does what, and says so.

What "better" means so far, against MakeACopy, measured with the tools
in this repository (commands in [DEVELOPMENT.md](DEVELOPMENT.md)):

| | MakeACopy | Sheet |
|---|---|---|
| Source of the app (main, without tests) | ~90,000 lines of Java | ~5,300 lines of Kotlin |
| Native dependencies | OpenCV, Tesseract, ONNX Runtime | ONNX Runtime only, built from source |
| OCR on a line tilted by 2° (character error rate) | 0.74 (axis-aligned boxes) | 0.01 (boxes follow the line) |
| Multi-column reading order | an option | always on; two-column sample 0.66 → 0.00 |
| Edge snap, SmartDoc photos with all corners within 1 % (65 of 75 before snapping) | 56 of 75: snaps onto printed border rules | 64 of 75 |
| Contour fallback when the model is unsure | OpenCV, wrong on 115 of 150 hold-out photos | dropped |

Most MakeACopy figures are its method run in Sheet's evaluation (same
models, same photos, MakeACopy's rule instead of Sheet's), so the table
compares methods, not two builds side by side.

What this does **not** show:

- The hard part was not rewritten. The corner model and its training
  data, and the choice of PaddleOCR, come from MakeACopy and months of
  work on it; Sheet took about two days because it could stand on them.
- Sheet does less. MakeACopy has a document library, import of images
  and PDFs, image filters to choose from and more OCR options; Sheet has
  none of these yet (it cleans every page up the same way).
- It has been tried on one real phone (a Pixel 7a) so far, plus the
  emulator.

Judge for yourself: run the evaluations, read the code, compare the
apps.

Sheet is not submitted to F-Droid or any other app store; it is a
demonstration. F-Droid is discussing how to mark apps written with AI
([#641](https://gitlab.com/fdroid/admin/-/work_items/641),
[#710](https://gitlab.com/fdroid/admin/-/work_items/710)), largely by
scanning for tell-tale signs: agent instruction files, `.gitignore`
entries, `Co-Authored-By` trailers, commits by AI tools. This repository
has none of them: the history was started fresh, the commits carry no
AI attribution (the developer commits them, with messages suggested by
the IDE's AI assistant), the agent's instruction file is never committed
and the comments are stripped (see [AI_POLICY.md](AI_POLICY.md)). Such scans can
only find what developers leave in; what is removed on purpose they
cannot see. Disclosing it, as here, is the only reliable signal.

## What it does

- **Takes a sharp photo.** The shutter focuses and exposes on the page and
  waits for still hands; "Move closer" shows while the page is too small
  in the picture for a sharp scan.
- **Finds the page while you aim.** A small model trained for this
  (DocQuadNet-256) outlines the page live in the camera preview, and again
  on the photo, where it also tries the photo turned when the page lies at
  an angle. The outline then snaps onto the paper's edges in the full
  photo, without mistaking a printed border rule for the edge.
- **Straightens the page** to its real proportions: the paper's aspect
  ratio is recovered from the perspective, so a page shot at a slant is
  not squashed. Drag a corner under a magnifier if the outline is off;
  tap a page later to cut it out again.
- **Sets pages upright** by itself, from the direction of their text.
- **Reads the text offline** with PaddleOCR v5 in the phone's language:
  Latin scripts, Cyrillic, Greek, Arabic, Devanagari, Thai, Chinese and
  Japanese. Text lines are followed even when slightly tilted,
  and multi-column pages read column by column.
- **Page after page**: scan, the text is read in the background, scan the
  next. Reorder, turn and delete pages; the document survives the app
  being closed.
- **Searchable PDF**: each page cleaned up like a scan (white paper
  without shadows or colour cast, black, crisp text; about 0.8 MB an A4
  page), with the text as an invisible layer you can search, select and
  copy, in any script. The text is read from the page as photographed.
  Saved to one folder you pick once, or shared to another app.

Volume keys take the photo. Light and dark theme follow the system.
English and German.

## Measured

`./gradlew :eval:run` runs the same pipeline on the Mac against labelled
photos (see [DEVELOPMENT.md](DEVELOPMENT.md)). With the shipped models: mean corner error
0.9 % of the photo's diagonal on 150 tilted hold-out photos; OCR character
error rate 1.3 % on MakeACopy's eval pages, on par with the reference
PaddleOCR with the same models where MakeACopy's own port fell behind on
skewed and multi-column pages.

## Building

```sh
git submodule update --init external/onnxruntime
git -C external/onnxruntime submodule update --init cmake/external/onnx
scripts/build_onnxruntime.sh     # needs cmake, ninja, python3 with flatbuffers, the NDK
./gradlew :app:assembleDebug
```

`scripts/build_onnxruntime.sh` builds ONNX Runtime for the four Android
ABIs, reduced to the operators of the shipped models; the app links that,
nothing prebuilt. Details and the evaluation tools are in [DEVELOPMENT.md](DEVELOPMENT.md).

## Privacy

Nothing leaves the phone; Sheet has no network permission. See
[PRIVACY.md](PRIVACY.md).

## License

GPL-3.0-or-later, see `LICENSE`. Third-party models and libraries are
listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). How AI tools
were used to build Sheet: [AI_POLICY.md](AI_POLICY.md).
