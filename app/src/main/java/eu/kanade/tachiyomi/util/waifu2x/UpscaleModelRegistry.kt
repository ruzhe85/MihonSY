package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context

/**
 * Komiho (2026-09-19 模型插件化): the single lookup point for AI upscale models.
 *
 * Combines the built-in Vulkan catalogue ([AiUpscaleModel]) with the NPU models discovered
 * in installed model-package APKs ([NpuModelPluginScanner]). Downstream code (preferences,
 * [Waifu2x], the settings UI) asks here instead of the enum, so a persisted id resolves no
 * matter which side it came from — and an id whose plugin has been uninstalled falls back
 * to [AiUpscaleModel.Default] exactly like any other unknown id.
 *
 * Scanning is cheap (a handful of package-manager queries + one small JSON per package)
 * but not free, so it is cached: [ensureScanned] runs it once per process (the reader
 * path needs it before any inference), and the settings UI re-runs [refresh] on resume so
 * installing/uninstalling a model package is reflected immediately on return.
 */
object UpscaleModelRegistry {

    @Volatile
    private var pluginModels: List<PluginUpscaleModel> = emptyList()

    @Volatile
    private var scanned = false

    /**
     * Scans once per process; subsequent calls are no-ops until [refresh]. Safe to call on
     * the hot path — the second invocation costs a single volatile read.
     */
    fun ensureScanned(context: Context) {
        if (scanned) return
        synchronized(this) {
            if (scanned) return
            pluginModels = NpuModelPluginScanner.scan(context.applicationContext)
            scanned = true
        }
    }

    /** Forces a rescan and returns the fresh plugin list. Call on settings resume. */
    fun refresh(context: Context): List<PluginUpscaleModel> {
        synchronized(this) {
            pluginModels = NpuModelPluginScanner.scan(context.applicationContext)
            scanned = true
            return pluginModels
        }
    }

    /** Built-in (Vulkan) models — the GPU group in the settings UI. */
    fun gpuModels(): List<AiUpscaleModel> = AiUpscaleModel.entries.toList()

    /** NPU models from installed plugin APKs — empty until [ensureScanned]/[refresh]. */
    fun npuModels(): List<PluginUpscaleModel> = pluginModels

    /**
     * Resolves a persisted id against built-in **and** plugin models, falling back to
     * [AiUpscaleModel.Default]. The fallback covers: unknown id, a model dropped from the
     * catalogue, and a model whose plugin APK has been uninstalled — the reader must never
     * be left with an AI mode that cannot start.
     */
    fun findById(id: String?): UpscaleModelSpec =
        AiUpscaleModel.entries.firstOrNull { it.id == id }
            ?: pluginModels.firstOrNull { it.id == id }
            ?: AiUpscaleModel.Default

    // Where the settings UI's "download NPU models" hint points.
    // ⚠️ Kept as a plain const inside this standalone object — a `companion object` here
    // would not compile ("Modifier 'companion' is not applicable inside 'standalone
    // object'", the same trap Waifu2x hit).
    const val MODEL_PACKAGE_RELEASE_URL = "https://github.com/ruzhe85/Komiho/releases/tag/qnn-model"
}
