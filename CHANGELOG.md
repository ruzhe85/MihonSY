# MihonSY 更新公告 / Changelog

> MihonSY 是基于 [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) 的个人定制 fork，
> 版本号继承原版（1.13.2 / 81），已移除原版更新检查，请勿与官方 TachiyomiSY 混用。

## v1.13.2 (MihonSY)

### 新增功能

1. **条漫点击滚动设置**
   - 「点击滚动距离」：半个屏幕 / 3/4 屏幕 / 一个屏幕 三档可选。
   - 「滚动动画速度」：点击滚动改为**匀速动画**（线性插值），时长可调（0–1000ms，默认 250ms）；设为 0ms 即瞬时跳页。
   - 入口：阅读器内设置（条漫分组）与 全局设置 → 阅读器 → 条漫。

2. **自动条漫判定增强**
   - 保留原标签判定（标签含 webtoon / long strip 等）。
   - 新增**按图片分辨率判定**：打开阅读器后若首页为长条图（高/宽 > 2.5），自动切换为条漫阅读模式。

3. **Komga 进度同步改为逐本标记**
   - 阅读进度不再通过累积接口（会把第 1~N 话全部标为已读），改为对**实际读到的单话**逐本 PATCH（`PATCH /api/v1/books/{id}/read-progress`），其他章节不受影响。

4. **图像增强（轻量，无大模型）**
   - 内置 **Anime4K**（GPU shader，Fast / High / Ultra）与 **Lanczos3**（经典插值）两种算法，针对条漫/漫画线条优化；不包含 waifu2x / Real-CUGAN / Real-ESRGAN 等重型模型，加载快、内存占用低。

5. **「原始分辨率」显示**
   - 条漫模式新增「原始分辨率」开关：图片按原始像素 1:1 显示，不做放大缩小处理；普通翻页模式可在缩放类型中选择「原始大小」。

### 变更

- 应用更名为 **MihonSY**，包名 `eu.kanade.mihonsy`（与官方 TachiyomiSY 互不冲突，可共存安装）。
- 移除原版更新检查（不再联网检查更新）。
- 移除 Firebase / Crashlytics 遥测（无 google-services 配置）。
- 使用独立签名密钥构建（`keystore/mihonmod.jks`），与 mihon_img_upscale 系列一致。
