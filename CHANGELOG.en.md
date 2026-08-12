# MihonSY Changelog

> MihonSY is a personal fork of [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY).
> Versioning is managed independently (restarted from 1.0.0). The update checker is
> removed — do not confuse it with the official TachiyomiSY.

[中文](./CHANGELOG.md) | [English](./CHANGELOG.en.md)

## v1.0.0 (MihonSY)

> Based on TachiyomiSY 1.13.2 upstream source.

### New Features

1. **Webtoon tap-to-scroll settings**
   - Tap scroll distance: half screen / 3/4 screen / full screen (3 options).
   - Scroll animation: tap scrolling now uses a **constant-speed animation** (linear
     interpolation), with adjustable duration (0–1000ms, default 250ms); set to 0ms for
     an instant jump.
   - Where: reader settings (webtoon group) and Global settings → Reader → Webtoon.

2. **Enhanced auto-webtoon detection**
   - Keeps the original tag-based detection (tags containing webtoon / long strip, etc.).
   - New **aspect-ratio detection**: if the first page of a chapter is a long strip
     (height/width > 2.5), the reader automatically switches to webtoon mode.

3. **Komga progress sync changed to per-book marking**
   - Progress no longer uses the cumulative endpoint (which marked chapters 1–N as read);
     it now PATCHes the **actually read chapter** per book
     (`PATCH /api/v1/books/{id}/read-progress`), so other chapters are unaffected.

4. **Image enhancement (lightweight, no heavy models)**
   - Built-in **Anime4K** (GPU shader, Fast / High / Ultra) and **Lanczos3** (classic
     resampling) algorithms, tuned for webtoon/manga line art; does not include heavy
     models such as waifu2x / Real-CUGAN / Real-ESRGAN — fast to load, low memory usage.

5. **Original-resolution display**
   - Webtoon mode gains an "Original resolution" toggle: images render at 1:1 original
     pixels without scaling; in paging mode you can pick "Original size" in zoom type.

### Changes

- App renamed to **MihonSY**, package `eu.kanade.mihonsy` (no conflict with official
  TachiyomiSY; both can be installed side by side).
- Removed the upstream update checker (no longer checks for updates online).
- Removed Firebase / Crashlytics telemetry (no google-services configuration).
- Built with an independent signing keystore (`keystore/mihonmod.jks`), consistent with
  the mihon_img_upscale series.
