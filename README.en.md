# MihonSY

<div align="center">

![MihonSY](.github/readme-images/app-icon.png)

**A manga reader based on [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)**

Package `eu.kanade.mihonsy` ｜ Version 1.0.6 (7) ｜ Android 8.0+

[English](./README.en.md) | [中文](./README.md)

</div>

---

## About

MihonSY is a fork of TachiyomiSY (SY). It keeps all of SY's features and enhances the **webtoon reading experience**:

- Adjustable tap-to-scroll distance with constant-speed animation
- Smarter automatic webtoon detection
- Komga tracking progress synced **per book**, precisely
- Lightweight image enhancement (Lanczos3, no heavy models)
- Original-resolution (1:1) display

> ⚠️ This app has the **update checker removed** and never checks for updates online. It uses a different package name from the official TachiyomiSY, so both can be installed side by side — but **do not mix data between the two versions** (be careful when backing up / restoring).

---

## ✨ New Features

### 1. Webtoon Tap-to-Scroll Settings

- **Tap scroll distance**: half screen / 3/4 screen / full screen (3 options).
- **Scroll animation**: tap scrolling uses a **constant-speed linear animation**; the duration is adjustable (0–1000ms, default 250ms). Set to 0 for an instant jump.
- **Where**: reader settings (webtoon group) or Global settings → Reader → Webtoon.

### 2. Enhanced Auto-Webtoon Detection

- Keeps the original tag-based detection (tags containing webtoon / long strip, etc.).
- **New aspect-ratio detection**: when the first page of a chapter is a long strip (height/width > 2.5), the reader automatically switches to webtoon mode.

### 3. Per-Book Komga Progress Sync

- Instead of the cumulative "mark chapters 1–N as read" endpoint, progress is now synced per book via a single-chapter PATCH
  (`PATCH /api/v1/books/{id}/read-progress`) — **other chapters are unaffected**, progress is precise to the chapter.

### 4. Image Enhancement (Lightweight)

| Algorithm | Type | Presets |
|-----------|------|---------|
| **Lanczos3** | Classic resampling | 1.5x / 2x / 2.5x / 3x |

- Optimized for manga/webtoon line art; fast to load and low memory usage.
- **No** heavy models such as waifu2x / Real-CUGAN / Real-ESRGAN (avoids lag).
- **Where**: Global settings → Reader → Image enhancement; the reader settings
  dialog can toggle "Show enhancement status" directly.

### 5. Original-Resolution Display

- Webtoon mode has a new "Original resolution" toggle: images display at **1:1** original pixels, no scaling.
- In regular paging mode you can pick "Original size" in the zoom type.

---

## 🧩 Upstream TachiyomiSY Features (All Kept)

- Online reading from a variety of sources; local reading
- Configurable reader (multiple viewers, reading directions, other settings)
- Tracker support: MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi, Hikka
- Categories to organize your library
- Light and dark themes
- Scheduled library updates for new chapters
- Local/cloud backups
- Latest tab (up to 5 sources)
- Automatic webtoon detection (upstream)
- Manga recommendations (MAL / AniList / Neko Similar Manga)
- Lewd filter, tracking filter, custom source categories, and more

---

## 📦 Build

### GitHub Actions (recommended, already configured in this repo)

Pushing to the `master` branch triggers a build automatically, or manually trigger the `Build MihonSY APK` workflow:

```bash
git push origin master
# or manually trigger
gh workflow run 332560481 --repo ruzhe85/MihonSY
# download artifacts
gh run download <run-id> --repo ruzhe85/MihonSY
```

- Artifacts: release APKs for 5 ABIs (arm64-v8a / armeabi-v7a / x86_64 / x86 / universal)
- Signing: `keystore/mihonmod.jks` (injected via GitHub Secrets, never committed)
- Dependencies: JDK 17 + Android SDK 36 + NDK 28.2 + CMake

### Local Build (not recommended)

```bash
# Requires JDK 17, Android SDK 36, NDK 28.2.13676358, Gradle 9.6.1
./gradlew assembleRelease -Pdisable-code-shrink
```

---

## 🗂️ Project Structure

| Path | Description |
|------|-------------|
| `app/src/main/cpp/` | Lanczos3 native implementation (JNI) |
| `.../reader/viewer/webtoon/` | Tap-to-scroll, constant-speed animation, original resolution |
| `.../reader/setting/ReaderPreferences.kt` | Preference definitions |
| `.../util/MihonSyEnhancer.kt` | Image enhancement orchestration |
| `.../data/track/komga/` | Per-book Komga progress sync |
| `.github/workflows/build.yml` | GitHub Actions build configuration |

---

## 📝 Changelog

See [CHANGELOG.en.md](./CHANGELOG.en.md).

---

## ⚠️ Notes

- For personal learning and use only; do not use commercially.
- Please respect the copyright of the manga you read.
- This fork is not affiliated with the upstream project; for issues please open an Issue in this repository.

---

## Credits

- [TachiyomiSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY) — upstream project
- [Mihon](https://github.com/mihonapp/mihon) — main project
- [Anime4K](https://github.com/bloc97/Anime4K) — image enhancement algorithm
