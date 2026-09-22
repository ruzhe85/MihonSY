package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Komiho (2026-09-19 模型插件化): discovers installed NPU model-package APKs and turns them
 * into [PluginUpscaleModel]s.
 *
 * A model package is an **asset-only APK** — no executable code, no components. It carries:
 *  - `assets/<assetDir>/<stem>.v<arch>.bin` context binaries (one per HTP generation);
 *  - `assets/models.json` — the manifest describing every model in the package.
 *
 * Discovery: on Android 11+ an app cannot enumerate other packages without declaring it.
 * The `<queries>` intent-based route does not work here because a no-code APK has no
 * component to host an `intent-filter` (PackageManager resolves filters off the parsed
 * manifest, but a manifest without any activity/service/receiver yields no queryable
 * handle). We therefore declare `QUERY_ALL_PACKAGES` and filter by the reserved package
 * prefix — acceptable because distribution is sideload-only, and every candidate is
 * additionally verified below.
 *
 * Security: a package is only accepted when **its signing certificate matches the host
 * APK's**. Combined with the reserved prefix, a third party cannot inject a fake model
 * package into the picker (worst case: an unsigned/mismatched package is ignored).
 *
 * Forward compatibility: `models.json` starts with `protocolVersion`. A package whose
 * protocol is *newer* than [SUPPORTED_PROTOCOL_VERSION] is skipped as a whole (the host
 * would not understand its semantics); unknown JSON fields are ignored, so new optional
 * metadata never breaks an older host — see [UpscaleModelSpec].
 */
object NpuModelPluginScanner {

    /**
     * Reserved applicationId prefixes for model packages.
     *
     * MihonSY: 本 fork 直接复用 Komiho 发布的模型包（`ruzhe85/Komiho` 的 `qnn-model`
     * release，见 [UpscaleModelRegistry.MODEL_PACKAGE_RELEASE_URL]），因此必须同时接受
     * Komiho 的前缀；第二项留给本仓库将来自建的模型包（`-PmodelId` 起这个前缀即可）。
     */
    val MODEL_PACKAGE_PREFIXES = listOf(
        "cn.ruzhe.komiho.model.",
        "eu.kanade.mihonsy.model.",
    )

    /**
     * MihonSY: 允许的模型包签名证书 SHA-256 白名单。
     *
     * 复用 Komiho 的模型包意味着「签名必须与宿主一致」这条判据不再成立——宿主是
     * `mihonmod.jks`，模型包是 Komiho 的 `komiho-release.jks`。判据因此改为「宿主自己的
     * 签名，或已知颁发者的证书指纹」，语义不变：仍然只认自己人打的包。
     *
     * ⚠️ Komiho 的 release keystore 与其口令是随其仓库公开的，所以这条校验防的是
     * 「装错包 / 来路不明的包」，不是密码学意义上的防伪。
     */
    private val ALLOWED_PLUGIN_CERT_SHA256 = setOf(
        "A2:B7:E5:24:EE:59:9F:84:15:60:8A:D7:BE:1A:90:A1:C8:37:9C:51:92:3A:15:A7:93:79:29:36:29:81:64:D2",
    )

    /**
     * Manifest protocol this host understands. Bump only on a semantic redesign of
     * `models.json`; additive optional fields do NOT require a bump (they are ignored).
     */
    const val SUPPORTED_PROTOCOL_VERSION = 1

    private const val MANIFEST_ASSET = "models.json"
    private const val DEFAULT_ASSET_DIR = "qnn-contexts"

    private const val KEY_PROTOCOL_VERSION = "protocolVersion"
    private const val KEY_MODELS = "models"
    private const val KEY_ID = "id"
    private const val KEY_STEM = "stem"
    private const val KEY_SCALE = "scale"
    private const val KEY_PADDING = "padding"
    private const val KEY_ARCHES = "arches"
    private const val KEY_LABEL = "label"
    private const val KEY_ASSET_DIR = "assetDir"

    /**
     * Scans every installed package whose applicationId starts with
     * [MODEL_PACKAGE_PREFIX], verifies its signature against the host's and parses its
     * manifest. Malformed entries are skipped individually (with a WARN), never fatal —
     * one broken package must not hide the others.
     */
    fun scan(context: Context): List<PluginUpscaleModel> {
        val pm = context.packageManager
        // MihonSY: 宿主签名读不到时不再直接放弃扫描 —— 白名单里的证书指纹仍足以判定
        // 模型包是否可信（见 [isTrustedSignature]）。
        val hostSignature = firstSignature(pm, context.packageName)
        if (hostSignature == null) {
            logcat(LogPriority.WARN) {
                "ModelPlugins: host signature unavailable — falling back to the cert whitelist"
            }
        }

        val candidates = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ModelPlugins: package enumeration failed" }
            return emptyList()
        }

        val models = mutableListOf<PluginUpscaleModel>()
        val seenIds = mutableSetOf<String>()
        for (info in candidates) {
            val pkg = info.packageName ?: continue
            if (MODEL_PACKAGE_PREFIXES.none { pkg.startsWith(it) }) continue

            val pluginSignature = firstSignature(pm, pkg)
            if (pluginSignature == null || !isTrustedSignature(pluginSignature, hostSignature)) {
                logcat(LogPriority.WARN) {
                    "ModelPlugins: $pkg signature is not trusted — skipped"
                }
                continue
            }

            models += parsePackage(context, pkg, seenIds)
        }
        return models
    }

    /** 签名可信 = 与宿主一致，或证书 SHA-256 在白名单内。 */
    private fun isTrustedSignature(plugin: ByteArray, host: ByteArray?): Boolean {
        if (host != null && plugin.contentEquals(host)) return true
        return try {
            val hex = MessageDigest.getInstance("SHA-256")
                .digest(plugin)
                .joinToString(":") { "%02X".format(it) }
            hex in ALLOWED_PLUGIN_CERT_SHA256
        } catch (e: Exception) {
            false
        }
    }

    /** Opens [pkg]'s `models.json` and converts each valid entry into a [PluginUpscaleModel]. */
    private fun parsePackage(context: Context, pkg: String, seenIds: MutableSet<String>): List<PluginUpscaleModel> {
        return try {
            val pluginContext = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
            val manifest = pluginContext.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(manifest)

            val protocol = root.optInt(KEY_PROTOCOL_VERSION, 1)
            if (protocol > SUPPORTED_PROTOCOL_VERSION) {
                logcat(LogPriority.WARN) {
                    "ModelPlugins: $pkg manifest protocol v$protocol > supported " +
                        "v$SUPPORTED_PROTOCOL_VERSION — update the host app to use it; skipped"
                }
                return emptyList()
            }

            val entries = root.optJSONArray(KEY_MODELS) ?: return emptyList()
            val result = mutableListOf<PluginUpscaleModel>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val model = entryOrNull(pkg, entry) ?: continue
                if (!seenIds.add(model.id)) {
                    logcat(LogPriority.WARN) { "ModelPlugins: duplicate model id ${model.id} — skipped" }
                    continue
                }
                logcat(LogPriority.WARN) {
                    "ModelPlugins: discovered ${model.id} (${model.qnnArches}) from $pkg"
                }
                result += model
            }
            result
        } catch (e: Exception) {
            // No manifest, unparseable JSON, absent assets — treat as "no models here".
            logcat(LogPriority.WARN, e) { "ModelPlugins: failed to read $pkg — skipped" }
            emptyList()
        }
    }

    /** Validates one manifest entry; `null` when a required field is missing or nonsense. */
    private fun entryOrNull(pkg: String, entry: JSONObject): PluginUpscaleModel? {
        val id = entry.optString(KEY_ID).trim()
        val stem = entry.optString(KEY_STEM).trim()
        val label = entry.optString(KEY_LABEL).trim()
        val padding = entry.optInt(KEY_PADDING, -1)
        val scale = entry.optInt(KEY_SCALE, 2)
        val assetDir = entry.optString(KEY_ASSET_DIR).trim().ifEmpty { DEFAULT_ASSET_DIR }
        val arches = entry.optJSONArray(KEY_ARCHES)?.let { array ->
            (0 until array.length()).mapNotNull { array.optInt(it, -1).takeIf { v -> v > 0 } }
        }.orEmpty()

        if (id.isEmpty() || stem.isEmpty() || label.isEmpty() || padding <= 0 || arches.isEmpty()) {
            logcat(LogPriority.WARN) { "ModelPlugins: incomplete entry in $pkg — skipped" }
            return null
        }
        return PluginUpscaleModel(
            id = id,
            stem = stem,
            padding = padding,
            qnnArches = arches,
            labelText = label,
            sourcePackage = pkg,
            scale = if (scale > 0) scale else 2,
            assetDir = assetDir,
        )
    }

    /** First signing certificate of [pkg] as raw bytes, or null when unreadable. */
    private fun firstSignature(pm: PackageManager, pkg: String): ByteArray? = try {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
        val sigs = info.signingInfo?.apkContentsSigners ?: info.signatures
        sigs?.firstOrNull()?.toByteArray()
    } catch (e: Exception) {
        null
    }
}
