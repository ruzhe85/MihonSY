# MihonSY

<div align="center">

![MihonSY](.github/readme-images/app-icon.png)

**基于 TachiyomiSY 的漫画阅读器，主打图像增强与条漫阅读增强**

包名 `eu.kanade.mihonsy` ｜ 版本 1.1.0 (9) ｜ Android 8.0+

[中文](./README.md) | [English](./README.en.md)

</div>

---

## 简介

MihonSY 是 [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) 的一个分支，在保留上游全部特性的基础上，强化了**图像增强**与**条漫阅读体验**。

- 端侧图像增强
- 条漫阅读体验优化
- Komga 进度**逐本精确**同步

---

## ✨ 核心功能

### 1. 图像增强

图像增强包含经典插值与AI超分两套方案：

**经典插值**：Lanczos3 / Catmull-Rom，
**AI 超分（GPU / NPU）**


- NPU 模型以**独立模型包 APK** 形式分发（复用 Komiho 成品，经包名前缀 + 证书 SHA-256 白名单校验），不打包进主程序。

### 2. 条漫阅读增强

- **点击滚动距离**：半屏 / 3/4 屏 / 全屏，三档可选。
- **滚动动画**：匀速线性动画，时长 0–1000ms 可调（0 = 瞬时跳页）；v1.1.0 起新增**缓出动画**。
- **瞬时触发**：v1.1.0 起点击改为瞬时触发，更跟手流畅。
- **原始分辨率**：条漫按原始像素 1:1 显示，不缩放。
- **预载设置**：v1.1.0 新增，页漫与条漫均可设置预载页面 / 屏幕数量。
- 入口：阅读器设置（条漫分组）或 全局设置 → 阅读器 → 条漫。

### 3. 自动条漫判定增强

- 保留原版标签判定（webtoon / long strip 等）。
- 新增**按图片比例判定**：首页为长条图（高/宽 > 2.5）自动切条漫。

### 4. Komga 进度逐本同步

- 改为对实际读到的单话逐本 `PATCH /api/v1/books/{id}/read-progress`，**其他章节不受影响**，进度精确到话。

---

## 🧩 保留的上游特性

- 多源在线 + 本地阅读
- 可配置阅读器（多视图、多方向）
- 追踪：MyAnimeList、AniList、Kitsu、MangaUpdates、Shikimori、Bangumi、Hikka
- 分类书架、明暗主题、定时更新、本地/云备份
- Latest 标签、自动 webtoon 检测、漫画推荐
- Lewd/追踪/自定义源分类过滤等

---

## ⚠️ 注意事项

- 内置更新检查会访问 `ruzhe85/MihonSY` 的 Releases（最多每 3 天一次，自动忽略 pre-release）。
- 与官方 TachiyomiSY 包名不同，可共存，但**备份/恢复时注意区分数据**。
- 仅个人学习使用，请遵守所读漫画的版权。

---

## 致谢

- [TachiyomiSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY)
- [Mihon](https://github.com/mihonapp/mihon)
- [mihon_img_upscale (HaoweiLi97)](https://github.com/HaoweiLi97/mihon_img_upscale) 
