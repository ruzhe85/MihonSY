# MihonSY

<div align="center">

![MihonSY](.github/readme-images/app-icon.png)

**A manga reader based on [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), focused on image enhancement, an enhanced webtoon experience and multi-device sync**

Package `eu.kanade.mihonsy` ｜ Version 1.2.0 (10) ｜ Android 8.0+

[English](./README.en.md) | [中文](./README.md)

</div>

---

## About

MihonSY is a fork of [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY). It keeps all upstream features and improves **image enhancement**, the **webtoon reading experience** and **multi-device sync**.

- On-device image enhancement: classic interpolation + AI upscaling (GPU / NPU)
- Improved webtoon reading experience
- WebDAV multi-device sync: library, progress, history, bookmarks and settings

---

## ✨ Key Features

### 1. Image Enhancement

- **AI upscaling (GPU)**: ncnn + Vulkan, with three built-in models — AnimeVideoMiniV18 / OmniMiniV2 (default) / OmniTurboV15 — at a fixed 2x.
- **AI upscaling (NPU)**: Qualcomm QNN / HTP hardware acceleration.
- **Lanczos3 / Catmull-Rom**: classic interpolation with **1.5x / 2x / 2.5x / 3x** ratios (native separable convolution).
- **Denoise**: an independent switch (off by default), decoupled from enhancement — it smooths luminance only and keeps hue.
- **Enhancement badge**: an independent switch (off by default) showing the engine actually used, its timing and the output resolution in the bottom-left corner; skipped pages show "skipped".
- NPU models ship as **separate model-pack APKs**.

### 2. Webtoon Reading Enhancements

- **Tap scroll distance**: half / 3-4 / full screen (computed from the reader's actual visible height, so tablets scroll a full screen too).
- **Scroll animation**: 0–1000ms (0 = instant page jump).
- **Instant trigger**: from v1.1.0, taps trigger instantly for a more responsive feel.
- **Original resolution**: webtoon pages render at 1:1 native pixels.
- **Preload settings**: new in v1.1.0 — paged and webtoon modes can both set the number of preloaded pages / screens; near the end of a chapter the next chapter's first screen is preloaded as well, for smoother chapter transitions.

### 3. Enhanced Auto-Webtoon Detection

- Keeps tag-based detection (webtoon / long strip) and adds **aspect-ratio detection** (first page height/width > 2.5 → auto webtoon).
- **Sticks per title once matched**: following chapters of the same title open as webtoon directly; if the title turns out to be mixed image/text (the recheck fails), it falls back to tag and default detection.

### 4. Per-Book Komga Progress Sync

- Progress is PATCHed per actually-read book (`PATCH /api/v1/books/{id}/read-progress`); other chapters are unaffected.

### 5. Multi-Device Sync (WebDAV)

- **Three real-time light channels**: reading progress, reading history and page bookmarks each travel in their own small remote file — **synced in real time**, not bound to the full-sync period.
- **Deletions travel too**: every deletion leaves a tombstone before it propagates, and a ledger records what this device owned at the last successful sync — so a title or bookmark deleted on one device is never pulled back by another.
- **Pick what syncs**: 13 sections (library, categories, chapters, read state, history, bookmarks, custom info, saved searches, app / source / extension-store settings, …) can be toggled individually. Library-type sections are unioned across devices; settings-type sections keep each device's own choice.
- **Choose your own triggers**: one switch each for opening a chapter, leaving a chapter and opening the library; "on app start" and "on returning to the app" can pull **history + bookmarks** or run a **full sync**. A ticked full sync always runs — it is not throttled by the sync frequency.
- **Clean notifications**: sync and automatic backups produce no notification by default (they don't enter a foreground service either); you are only notified on errors.

### 6. Bookmarks and Reading History

- **Bottom "Bookmarks" tab**: gathers page bookmarks from every title and jumps straight to that title / chapter / page; hide it in Settings → Appearance if you don't need it.
- **Bookmarks can be backed up and synced**: backup / restore gains a "page bookmarks" section, and bookmarks sync across devices — deletions included.
- **In-reader bookmark list**: long-press the reader's bookmark button to see the current chapter's bookmarks.
- **History shows page progress**: history entries show "pages read / total · percentage".
- **Continue reading follows history**: it takes the next chapter from reading history instead of chapter-list order.

### 7. Deep Crop Edge

- An improved crop algorithm — the crop can cut **through watermarks and page numbers** instead of being blocked by them.

---

## ⚠️ Notes

- The built-in updater checks the `ruzhe85/MihonSY` Releases (at most once per 3 days, auto-skips pre-releases).
- Multi-device sync: **only WebDAV** has the three real-time light channels for progress / history / bookmarks; Google Drive and SyncYomi only do full syncs.
- Different package name from official TachiyomiSY, so they can coexist — but **keep their data separate** when backing up/restoring.
- For personal learning and use only; respect manga copyright.

---

## Credits

- [TachiyomiSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY)
- [Mihon](https://github.com/mihonapp/mihon)
- [mihon_img_upscale (HaoweiLi97)](https://github.com/HaoweiLi97/mihon_img_upscale)
