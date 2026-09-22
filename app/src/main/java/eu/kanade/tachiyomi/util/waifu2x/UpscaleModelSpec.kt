package eu.kanade.tachiyomi.util.waifu2x

import dev.icerock.moko.resources.StringResource

/**
 * Komiho (2026-09-19 模型插件化): unified description of **one** AI upscale model, whether it
 * is built into the host APK ([AiUpscaleModel], ncnn/Vulkan) or delivered by an installed
 * asset-only model-package APK ([PluginUpscaleModel], QNN/HTP).
 *
 * Everything downstream of the catalogue ([Waifu2x], preferences, the settings UI) speaks in
 * this interface, so adding a new NPU model family is a matter of installing one more plugin
 * APK — the host app never changes.
 *
 * Forward compatibility contract (protocol v1):
 *  - the plugin manifest (`models.json`) carries `protocolVersion`; a host that only
 *    understands older protocols skips the whole package instead of crashing;
 *  - unknown JSON fields are ignored, so new optional metadata can be added freely without
 *    updating the host;
 *  - only a **protocol redesign** (field semantic changes) requires a host update.
 */
interface UpscaleModelSpec {

    /** Stable identifier persisted in preferences. Never rename a shipped id. */
    val id: String

    /** Model file stem (ncnn `.param`/`.bin` or QNN context `.v<arch>.bin`). */
    val stem: String

    /** Assets directory inside the owning APK that holds the model files. */
    val assetDir: String

    /** Super-resolution factor baked into the network (no free-form scale). */
    val scale: Int

    /**
     * Tile halo (px) = `Σ(kernel-1)×dilation / 2` of the network — wrong values produce
     * visible tile seams. For plugin models this is computed by the packaging pipeline,
     * never hand-edited.
     */
    val padding: Int

    /** Which native engine executes this model. */
    val backend: Backend

    /**
     * HTP generations the model ships a context for (QNN models only; empty for ncnn).
     * [Waifu2x.contextAssetFor] refuses to build a file name for an arch outside this set,
     * which is what makes a v69/v73/v79-only family fall back cleanly on a v75 device.
     */
    val qnnArches: List<Int>

    /** Built-in models: moko string resource for the label. */
    val labelRes: StringResource?

    /** Plugin models: plain-text label from `models.json` (localised by the packager). */
    val labelText: String?

    /**
     * Package name of the plugin APK this model was loaded from; `null` for built-in
     * models. [Waifu2x] uses it to open the owning APK's [android.content.res.AssetManager]
     * instead of the host's.
     */
    val sourcePackage: String?

    enum class Backend { NCNN_VULKAN, QNN_HTP }
}

/**
 * Komiho (2026-09-19): one NPU model delivered by an installed model-package APK.
 *
 * Instances are produced by [NpuModelPluginScanner] from the plugin's `models.json`; the
 * values are therefore **data, not code** — the plugin APK contains no executable classes,
 * only the context files `assets/<assetDir>/<stem>.v<arch>.bin` and the manifest.
 */
data class PluginUpscaleModel(
    override val id: String,
    override val stem: String,
    override val padding: Int,
    override val qnnArches: List<Int>,
    override val labelText: String,
    override val sourcePackage: String,
    override val scale: Int = 2,
    override val assetDir: String = "qnn-contexts",
) : UpscaleModelSpec {
    override val backend: UpscaleModelSpec.Backend get() = UpscaleModelSpec.Backend.QNN_HTP
    override val labelRes: StringResource? get() = null
}
