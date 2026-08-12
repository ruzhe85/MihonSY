# MihonSY 更新公告 / Changelog

> MihonSY 是基于 [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) 的分支，
> 版本号独立管理（1.0.0 起全新开始），已移除原版更新检查，请勿与官方 TachiyomiSY 混用。

[中文](./CHANGELOG.md) | [English](./CHANGELOG.en.md)

## v1.0.1 (MihonSY)

> 基于 TachiyomiSY 1.13.2 主线源码。

### 新增功能

1. **条漫功能增强**
   - **点击滚动设置**：「点击滚动距离」半个屏幕 / 3/4 屏幕 / 一个屏幕三档可选；「滚动动画速度」改为匀速动画（线性插值），时长可调（0–1000ms，默认 250ms），设为 0ms 即瞬时跳页。
   - **自动条漫判定**：保留原标签判定（标签含 webtoon / long strip 等），新增按图片分辨率判定——打开阅读器后检查前 5 张图片，若存在长条图（高/宽 > 2.5）自动切换条漫模式，封面为横向图、后续为长条图的章节也能自动识别。
   - **原始分辨率显示**：条漫模式新增「原始分辨率」开关，图片按原始像素 1:1 显示不做缩放；普通翻页模式可在缩放类型中选择「原始大小」。

2. **Komga 进度同步改为逐本标记**
   - 阅读进度不再通过累积接口（会把第 1~N 话全部标为已读），改为对**实际读到的单话**逐本 PATCH（`PATCH /api/v1/books/{id}/read-progress`），其他章节不受影响。

3. **图像增强（轻量，无大模型）**
   - 内置 **Anime4K**（GPU shader，Fast / High / Ultra）与 **Lanczos3**（经典插值）两种算法，针对条漫/漫画线条优化；不包含 waifu2x / Real-CUGAN / Real-ESRGAN 等重型模型，加载快、内存占用低。

4. **下载兼容增强（卓易通 / HarmonyOS）**
   - 对不支持 SAF `renameDocument` 的文件系统（如鸿蒙卓易通兼容层），下载完成时自动回退为"复制到目标 + 删除临时文件"，不再卡在 `.tmp` 导致章节下载失败。
   - 代码移植自 [zsyou/mihon-harmony](https://github.com/zsyou/mihon-harmony)。

### 优化与修复

- **点击滚动动画优化**：以匀速线性动画替代原先的 `smoothScrollBy` 滚动方式，滚动过程更平滑、无卡顿；连续点击自动取消上一次动画，快速翻页不打架。
