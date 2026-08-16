# MihonSY

<div align="center">

![MihonSY](.github/readme-images/app-icon.png)

**基于 [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) 的漫画阅读器**

包名 `eu.kanade.mihonsy` ｜ 版本 1.0.6 (7) ｜ Android 8.0+

[中文](./README.md) | [English](./README.en.md)

</div>

---

## 简介

MihonSY 是 TachiyomiSY（SY）的分支，在保留 SY 全部特性的基础上，针对**条漫阅读体验**做了增强：

- 点击滚动距离与匀速动画可调
- 更聪明的自动条漫判定
- Komga 追番进度**逐本精确**同步
- 轻量图像增强（Lanczos3，无大模型）
- 原始分辨率 1:1 显示

> ⚠️ 本应用**移除了原版更新检查**，不会联网检查更新；与官方 TachiyomiSY 包名不同，可共存安装，但**请勿混淆两个版本的数据**（备份/恢复时注意区分）。

---

## ✨ 新增功能

### 1. 条漫点击滚动设置

- **点击滚动距离**：半个屏幕 / 3/4 屏幕 / 一个屏幕，三档可选。
- **滚动动画**：点击滚动采用**匀速线性动画**，动画时长可调（0–1000ms，默认 250ms）；设为 0 即瞬时跳页。
- **入口**：阅读器内设置（条漫分组）或 全局设置 → 阅读器 → 条漫。

### 2. 自动条漫判定增强

- 保留原版标签判定（标签含 webtoon / long strip 等）。
- 新增**按图片分辨率判定**：打开阅读器后若首页为长条图（高/宽 > 2.5），自动切换条漫模式。

### 3. Komga 进度逐本同步

- 阅读进度不再使用"把第 1~N 话全部标记已读"的累积接口，改为对**实际读到的单话**逐本 PATCH
  （`PATCH /api/v1/books/{id}/read-progress`），**其他章节不受影响**，进度精确到话。

### 4. 图像增强（轻量方案）

| 算法 | 类型 | 档位 |
|------|------|------|
| **Lanczos3** | 经典插值 | 1.5x / 2x / 2.5x / 3x |

- 针对漫画/条漫线条优化，加载快、内存占用低。
- **不含** waifu2x / Real-CUGAN / Real-ESRGAN 等重型模型（避免卡顿）。
- 入口：全局设置 → 阅读器 → 图像增强；阅读器设置内可直接开关「显示增强状态」。

### 5. 原始分辨率显示

- 条漫模式新增「原始分辨率」开关：图片按原始像素 **1:1** 显示，不缩放。
- 普通翻页模式可在缩放类型中选择「原始大小」。

---

## 🧩 原版 TachiyomiSY 特性（全部保留）

- 多源在线阅读、本地阅读
- 可配置阅读器（多视图、多阅读方向、其他设置）
- 追踪支持：MyAnimeList、AniList、Kitsu、MangaUpdates、Shikimori、Bangumi、Hikka
- 分类管理书架
- 明/暗主题
- 定时更新书架新章节
- 本地/云备份
- Latest 标签（最多 5 个源）
- 自动 webtoon 检测（原版）
- 漫画推荐（MAL / Anilist / Neko Similar Manga）
- Lewd 过滤、追踪过滤、自定义源分类等

---

## 📦 构建

### GitHub Actions（推荐，本仓库已配置）

推送到 `master` 分支自动触发构建，或手动触发 `Build MihonSY APK` workflow：

```bash
git push origin master
# 或手动触发
gh workflow run 332560481 --repo ruzhe85/MihonSY
# 下载产物
gh run download <run-id> --repo ruzhe85/MihonSY
```

- 产物：5 个 ABI 的 release APK（arm64-v8a / armeabi-v7a / x86_64 / x86 / universal）
- 签名：`keystore/mihonmod.jks`（经 GitHub Secrets 注入，不落入代码库）
- 依赖：JDK 17 + Android SDK 36 + NDK 28.2 + CMake

### 本地构建（不推荐）

```bash
# 需要 JDK 17、Android SDK 36、NDK 28.2.13676358、Gradle 9.6.1
./gradlew assembleRelease -Pdisable-code-shrink
```

---

## 🗂️ 项目结构

| 路径 | 说明 |
|------|------|
| `app/src/main/cpp/` | Lanczos3 原生实现（JNI） |
| `.../reader/viewer/webtoon/` | 条漫点击滚动、匀速动画、原始分辨率 |
| `.../reader/setting/ReaderPreferences.kt` | 偏好项定义 |
| `.../util/MihonSyEnhancer.kt` | 图像增强调度 |
| `.../data/track/komga/` | Komga 逐本进度同步 |
| `.github/workflows/build.yml` | GitHub Actions 构建配置 |

---

## 📝 更新记录

详见 [CHANGELOG.md](./CHANGELOG.md)（[English](./CHANGELOG.en.md)）。

---

## ⚠️ 注意事项

- 仅用于个人学习与使用，请勿用于商业用途。
- 请遵守所阅读漫画的版权规定。
- 本 fork 与上游无关联，问题请自行排查或在本仓库 Issue 讨论。

---

## 致谢

- [TachiyomiSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY) — 上游项目
- [Mihon](https://github.com/mihonapp/mihon) — 主项目
- [Anime4K](https://github.com/bloc97/Anime4K) — 图像增强算法
