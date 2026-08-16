# MihonSY Changelog

> MihonSY is a fork of [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY).
> Versioning is managed independently (restarted from 1.0.0). The update checker is
> removed — do not confuse it with the official TachiyomiSY.

[中文](./CHANGELOG.md) | [English](./CHANGELOG.en.md)

## v1.0.6 (MihonSY)

### New

- **Two new enhancement algorithms**: Catmull-Rom and Spline36 (selectable
  alongside Lanczos3, all supporting 1.5x / 2x / 2.5x / 3x). Catmull-Rom is
  sharper, Spline36 reconstructs more detail — pick by preference.

## v1.0.5 (MihonSY)

### Improvements & Fixes

- **Downloaded chapters can be enhanced too**: enabling image enhancement no
  longer crashes the reader on downloaded CBZ chapters — they now enhance
  normally.

## v1.0.4 (MihonSY)

### Improvements & Fixes

- **Image enhancement rework**: enhancement now works in every reading mode
  (paged, webtoon, long-strip); when enhancement is off, the original
  full-resolution direct decode is restored, so quality and performance are
  unaffected.
- **Lanczos3 much faster**: decode at view size + separable convolution
  (~12x faster); a new **2.5x** scale preset (1.5x / 2x / 2.5x / 3x).
- **New enhancement status display**: a new independent toggle "Show enhancement
  status" (off by default) under Image enhancement; when enabled, a bottom-left
  overlay shows the elapsed enhancement time (OK when done) for performance
  observation.
- **Webtoon tap-scroll distance fixed on tablets**: the scroll distance now uses
  the actual visible reader height instead of the physical screen height — on
  tablets a "one screen" tap scrolled only ~1/3 before; now it scrolls a full
  screen (all presets: half / 3/4 / full).

## v1.0.3 (MihonSY)

### Improvements & Fixes

- **Webtoon tap-scroll tuning**: the "full screen" preset now scrolls one screen
  height minus a 23dp peek margin — each tap scrolls a complete screen with a
  sliver of the next page visible at the bottom; the "half" and "3/4 screen"
  presets are unchanged.
- **Removed** the 1.0.2 "snap-to-page-boundary" behavior (it made the scroll
  distance drift from the setting; rolled back).

## v1.0.2 (MihonSY)

> Based on TachiyomiSY 1.13.2 upstream source (self-developed update, not an upstream sync).

### Improvements & Fixes

- **Webtoon tap-scroll snaps to page boundary**: after the tap-scroll animation ends
  (or an instant jump), the view auto-aligns to the nearest page top (advancing to the
  next page if past the halfway point) — scrolling always settles on a whole page, with
  a feel closer to ComicScreen. Tap-scroll distance (half / 3/4 / full screen) and
  animation duration settings are unchanged.
- **Onboarding cleanup**: the "Send crash reports" and "Share analytics" toggles are
  hidden from the welcome screen (code kept, UI commented out).

### Changes

- Release pipeline: APK assets renamed to `mihonsy-x.y.z-abi.apk`; release notes now
  automatically include both Chinese and English changelog sections.

## v1.0.1 (MihonSY)

> Based on TachiyomiSY 1.13.2 upstream source.

### New Features

1. **Webtoon feature pack**
   - **Tap-to-scroll settings**: tap scroll distance — half screen / 3/4 screen / full
     screen (3 options); scroll animation — constant-speed animation (linear
     interpolation), adjustable duration (0–1000ms, default 250ms), 0ms = instant jump.
   - **Auto-webtoon detection**: keeps the original tag-based detection (tags containing
     webtoon / long strip, etc.); new aspect-ratio detection inspects the first 5 pages —
     if any is a long strip (height/width > 2.5) the reader switches to webtoon mode
     automatically, including chapters that open with a horizontal cover followed by
     tall strips.
   - **Original-resolution display**: webtoon mode gains an "Original resolution" toggle
     that renders images at 1:1 original pixels without scaling; in paging mode you can
     pick "Original size" in the zoom type.

2. **Komga progress sync changed to per-book marking**
   - Progress no longer uses the cumulative endpoint (which marked chapters 1–N as read);
     it now PATCHes the **actually read chapter** per book
     (`PATCH /api/v1/books/{id}/read-progress`), so other chapters are unaffected.

3. **Image enhancement (lightweight, no heavy models)**
   - Built-in **Anime4K** (GPU shader, Fast / High / Ultra) and **Lanczos3** (classic
     resampling) algorithms, tuned for webtoon/manga line art; does not include heavy
     models such as waifu2x / Real-CUGAN / Real-ESRGAN — fast to load, low memory usage.

4. **Download compatibility (卓易通 / HarmonyOS)**
   - For file systems that do not support SAF `renameDocument` (e.g. the HarmonyOS 卓易通
     compatibility layer), downloads now fall back to "copy to target + delete temp file",
     so they no longer stick at `.tmp` and fail.
   - Code ported from [zsyou/mihon-harmony](https://github.com/zsyou/mihon-harmony).

### Improvements & Fixes

- **Tap-scroll animation optimization**: replaced the previous `smoothScrollBy` path with
  a constant-speed linear animation — scrolling is smoother with no jank; a new tap cancels
  the running animation, so rapid taps never fight.
