# MihonSY

<div align="center">

![MihonSY](.github/readme-images/app-icon.png)

**A manga reader based on [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), focused on image enhancement and an enhanced webtoon experience**

Package `eu.kanade.mihonsy` ｜ Version 1.1.0 (9) ｜ Android 8.0+

[English](./README.en.md) | [中文](./README.md)

</div>

---

## About

MihonSY is a fork of [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY). It keeps all upstream features and improves **image enhancement** and the **webtoon reading experience**.

- On-device image enhancement
- Improved webtoon reading experience
- Komga progress synced **per book**, precisely

---

## ✨ Key Features

### 1. Image Enhancement

Image enhancement covers both classic interpolation and AI upscaling:

**Classic interpolation**: Lanczos3 / Catmull-Rom.
**AI upscaling (GPU / NPU)**

- NPU models ship as **separate model-pack APKs** (reusing Komiho's builds, validated by package-prefix + SHA-256 cert allowlist), not bundled into the app.

### 2. Webtoon Reading Enhancements

- **Tap scroll distance**: half / 3-4 / full screen.
- **Scroll animation**: constant-speed linear, 0–1000ms (0 = instant); v1.1.0 adds an **ease-out animation**.
- **Instant trigger**: from v1.1.0, taps trigger instantly for a more responsive feel.
- **Original resolution**: webtoon pages render at 1:1 native pixels.
- **Preload settings**: new in v1.1.0 — paged and webtoon modes can both set the number of preloaded pages / screens.
- Where: reader settings (webtoon group) or Global settings → Reader → Webtoon.

### 3. Enhanced Auto-Webtoon Detection

- Keeps tag-based detection (webtoon / long strip) and adds **aspect-ratio detection** (first page height/width > 2.5 → auto webtoon).

### 4. Per-Book Komga Progress Sync

- Progress is PATCHed per actually-read book (`PATCH /api/v1/books/{id}/read-progress`); other chapters are unaffected.

---

## 🧩 Upstream Features (Kept)

- Online sources + local reading
- Configurable reader (multiple viewers, directions)
- Trackers: MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi, Hikka
- Categories, light/dark themes, scheduled updates, local/cloud backups
- Latest tab, auto webtoon detection, recommendations
- Lewd/tracker/custom-source filters, and more

---

## ⚠️ Notes

- The built-in updater checks the `ruzhe85/MihonSY` Releases (at most once per 3 days, auto-skips pre-releases).
- Different package name from official TachiyomiSY, so they can coexist — but **keep their data separate** when backing up/restoring.
- For personal learning and use only; respect manga copyright.

---

## Credits

- [TachiyomiSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY)
- [Mihon](https://github.com/mihonapp/mihon)
- [mihon_img_upscale (HaoweiLi97)](https://github.com/HaoweiLi97/mihon_img_upscale)
