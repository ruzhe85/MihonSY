package eu.kanade.tachiyomi.util.waifu2x

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Komiho: the **built-in** model catalogue for the AI upscaler — ncnn+Vulkan (GPU) models.
 *
 * 2026-09-19 模型插件化: the QNN/HTP (NPU) entries **left this APK**. They now ship as
 * separate asset-only plugin APKs (one per model family, e.g.
 * `cn.ruzhe.komiho.model.nomosuni`), scanned at runtime by [NpuModelPluginScanner] and
 * surfaced through [UpscaleModelRegistry] as [PluginUpscaleModel]s. Rationale: contexts
 * cost ~27 MiB and are per-HTP-generation, so devices without a Qualcomm NPU (or with a
 * generation a family does not cover) paid for bytes they could never use. The host keeps
 * the QNN runtime libs (`libQnnHtp.so`, `libQnnSystem.so`, the five
 * `libQnnHtpV<arch>{Skel,Stub}.so` pairs) — a plugin only carries `.bin` contexts.
 *
 * This enum therefore only lists the Vulkan entries, and everything downstream speaks in
 * the [UpscaleModelSpec] interface (see [Waifu2x], the preferences and the settings UI).
 *
 * Adding a Vulkan model is still: drop the `.param`/`.bin` pair under `assets/`, add one
 * enum entry. Adding an NPU model family is now: package a plugin APK (CI workflow
 * `build-model-apk.yml`) and install it — **no host release needed** (protocol v1 is
 * forward compatible; see [UpscaleModelSpec]).
 *
 * ⚠️ QNN context pipeline knowledge (NHWC wrap, QAIRT version coupling, fp16-vs-int8,
 * padding auto-derivation, RGB/BGR caveat) lives with the packaging tooling — see the
 * `komiho-add-upscale-model` skill (`references/qnn_pipeline/`). Do not lose it.
 */
enum class AiUpscaleModel(
    override val id: String,
    override val assetDir: String,
    override val stem: String,
    override val scale: Int,
    override val padding: Int,
    override val labelRes: StringResource,
) : UpscaleModelSpec {
    /**
     * 2x residual ESRGAN-style network, 10 conv layers, ~89 KB of weights.
     * Small enough that shipping it in the APK costs nothing measurable.
     */
    AnimeVideoMiniV18(
        id = "animevideo-mini-v18-w2xex",
        assetDir = "w2xex-esrgan/AnimeVideo-MiniV1.8-W2xEX",
        stem = "AnimeVideo-MiniV1.8-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_anime_video,
    ),

    /**
     * Omni-Mini V2 — verified **layer-for-layer identical** to [AnimeVideoMiniV18]: same 24
     * layers, same 10 convolutions, same channel ladder (24 … 24 → 12), same 44,712 weight
     * elements. Only the training run differs, so running it costs exactly the same.
     *
     * Its `.bin` is larger (176 KB vs 89 KB) purely because the weights are stored **fp32**
     * instead of fp16 — that must not be read as more compute.
     */
    OmniMiniV2(
        id = "omni-mini-v2-w2xex",
        assetDir = "w2xex-esrgan/Omni-MiniV2-W2xEX",
        stem = "Omni-MiniV2-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_omni_mini,
    ),

    /**
     * Omni-Turbo V1.5 — same author's retrained sibling of [AnimeVideoMiniV18].
     *
     * Verified by parsing both `.param` files: identical topology (24 layers, 10 convolutions,
     * PReLU x9, `PixelShuffle(0=2)`, bilinear `Interp` bypass), so [scale] and [padding] carry
     * over unchanged — tile seams behave exactly like the existing model.
     *
     * Weights are heavier though: channels are 64 wide instead of 24, i.e. **303,552 weight
     * elements = 6.79x** the compute of [AnimeVideoMiniV18] (measured 0.99 s per 2.97 MP page
     * for the latter, so expect roughly 6 s here). Use it when quality matters more than speed.
     */
    OmniTurboV15(
        id = "omni-turbo-v15-w2xex",
        assetDir = "w2xex-esrgan/Omni-TurboV1.5-W2xEX",
        stem = "Omni-TurboV1.5-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_omni_turbo,
    ),

    // Photo-Small W2xEX 曾在此处（40 层 / 18 卷积 / 598,464 权重元素 = 13.4x 算力 / padding 18），
    // 2026-09-16 按用户反馈「效果很差」移除。若要恢复：把 assets/w2xex-esrgan/Photo-Small-W2xEX/
    // 放回去 + 三语补 ai_model_photo_small，并注意它的 padding 是 18（不是同族的 10）。
    // 其同网络的 NPU 版自 2026-09-19 起走模型插件 APK（不再内置）。
    ;

    override val backend: UpscaleModelSpec.Backend get() = UpscaleModelSpec.Backend.NCNN_VULKAN

    /** Built-in entries are Vulkan-only; NPU models come exclusively from plugin APKs. */
    override val qnnArches: List<Int> get() = emptyList()

    /** Built-in labels come from moko resources, not plain text. */
    override val labelText: String? get() = null

    /** Built-in assets live in the host APK itself. */
    override val sourcePackage: String? get() = null

    companion object {
        /**
         * Model used on a fresh install, whenever a stored id is unknown, and as the Vulkan
         * target when a QNN/HTP model cannot initialise (arch not packed / plugin missing).
         *
         * 2026-09-19: switched from [AnimeVideoMiniV18] to [OmniMiniV2] per user decision.
         * The two are layer-for-layer identical (same compute), only the training run
         * differs — so this change costs nothing at runtime.
         */
        val Default: AiUpscaleModel = OmniMiniV2
    }
}
