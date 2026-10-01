package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import org.json.JSONObject
import java.io.File
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
     * MihonSY 直接复用 Komiho 发布的模型包，
     * 因此必须同时接受 Komiho 的前缀；第二项留给本仓库将来自建的模型包。
     */
    val MODEL_PACKAGE_PREFIXES = listOf(
        "cn.ruzhe.komiho.model.",
        "eu.kanade.mihonsy.model.",
    )

    /**
     * HTP generation numbers asked for by name.
     *
     * A model package's applicationId is `cn.ruzhe.komiho.model.v<arch>` (`v69`, `v75`, …), so
     * the candidate names are enumerable even where the installed-package list is not: some
     * ROMs hand `getInstalledPackages` back with only the calling package inside, while the
     * per-name lookup path still answers. Sweeping a range instead of listing today's
     * generations keeps a future SoC working without a host release.
     */
    private val PROBE_ARCH_CODES = 64..96

    /** Package declared in this app's `<queries>`; used to sanity-check the per-name lookup. */
    private const val CONTROL_PROBE_PACKAGE = "com.android.settings"

    /**
     * Manifest protocol this host understands. Bump only on a semantic redesign of
     * `models.json`; additive optional fields do NOT require a bump (they are ignored).
     */
    const val SUPPORTED_PROTOCOL_VERSION = 1

    private const val MANIFEST_ASSET = "models.json"
    private const val DEFAULT_ASSET_DIR = "qnn-contexts"

    /** Name of the trace file written only when a scan finds nothing (see [scan]). */
    private const val DIAG_FILE = "npu-diag.txt"

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
     * [MODEL_PACKAGE_PREFIXES], verifies its signature against the host's and parses its
     * manifest. Malformed entries are skipped individually (with a WARN), never fatal —
     * one broken package must not hide the others.
     */
    fun scan(context: Context): List<PluginUpscaleModel> {
        val pm = context.packageManager
        // Komiho (2026-09-23): a scan that finds nothing has to say *why* somewhere. Some OEM
        // ROMs also swallow logcat (a Nubia/RedMagic build reports `0 B readable` for main and
        // system even for a self-written probe), so the only reliable channel is a trace in the
        // app's own external files dir. It is written **only when discovery comes up empty**
        // (and a stale one is dropped on success), so a healthy device never touches disk.
        val diag = StringBuilder()

        // Komiho (2026-09-23): the host's own certificate is not always reachable through
        // SigningInfo — some OEM ROMs hand back a null `apkContentsSigners` for a v2-only
        // signed APK, and then this whole scan used to bail out before looking at a single
        // package (the model list went empty while the very same packages loaded fine in a
        // sibling build on the same device). Losing the host certificate must NOT disable
        // discovery: [ALLOWED_PLUGIN_CERT_SHA256] still separates our packages from foreign
        // ones. The host certificate stays the primary check whenever it is available.
        val hostSignature = firstSignature(pm, context.packageName)
        diag.append("host signature: ")
            .append(
                if (hostSignature == null) {
                    "UNREADABLE (whitelist only)"
                } else {
                    "${hostSignature.size} bytes"
                },
            )
            .append('\n')
        if (hostSignature == null) {
            logcat(LogPriority.WARN) {
                "ModelPlugins: host signature unavailable — falling back to the cert whitelist"
            }
        }

        // Two independent discovery channels. Enumeration is the natural one, but a ROM may
        // answer `getInstalledPackages` with only the calling package (seen on Android 16 even
        // with QUERY_ALL_PACKAGES granted and the model package installed), while the per-name
        // lookup path still works — so ask for the names we expect as well.
        val names = LinkedHashSet<String>()
        val enumerated = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            diag.append("package enumeration FAILED: ").append(e).append('\n')
            logcat(LogPriority.WARN, e) { "ModelPlugins: package enumeration failed" }
            null
        }
        enumerated?.forEach { info -> info.packageName?.let(names::add) }
        diag.append("installed packages: ").append(enumerated?.size ?: -1)
            .append(" -> [").append(names.joinToString(",")).append("]\n")

        var probed = 0
        for (arch in PROBE_ARCH_CODES) {
            for (prefix in MODEL_PACKAGE_PREFIXES) {
                val pkg = prefix + "v" + arch
                if (names.contains(pkg)) continue
                if (runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) {
                    names += pkg
                    probed++
                }
            }
        }
        // Control probe: a package this app declares in <queries>. Separates "per-name lookup
        // works, the list API is what is blocked" from "every package query is blocked".
        diag.append("control probe ($CONTROL_PROBE_PACKAGE): ")
            .append(runCatching { pm.getPackageInfo(CONTROL_PROBE_PACKAGE, 0) }.isSuccess)
            .append('\n')
        diag.append("name probes: ").append(PROBE_ARCH_CODES.count())
            .append(" tried, ").append(probed).append(" hit\n")
        diag.append("prefixes: ").append(MODEL_PACKAGE_PREFIXES.joinToString(",")).append('\n')

        val models = mutableListOf<PluginUpscaleModel>()
        val seenIds = mutableSetOf<String>()
        for (pkg in names) {
            if (MODEL_PACKAGE_PREFIXES.none { pkg.startsWith(it) }) continue
            diag.append("candidate: ").append(pkg).append('\n')

            val pluginSignature = firstSignature(pm, pkg)
            if (pluginSignature == null) {
                diag.append("  signature: UNREADABLE -> skipped\n")
                logcat(LogPriority.WARN) { "ModelPlugins: $pkg signature unreadable — skipped" }
                continue
            }
            if (!isTrustedSignature(pluginSignature, hostSignature)) {
                diag.append("  signature: UNTRUSTED -> skipped\n")
                logcat(LogPriority.WARN) {
                    "ModelPlugins: $pkg signature is not trusted — skipped"
                }
                continue
            }
            diag.append("  signature: ok\n")

            val parsed = parsePackage(context, pkg, seenIds, diag)
            diag.append("  parsed models: ").append(parsed.size).append('\n')
            models += parsed
        }

        diag.append("RESULT: ").append(models.size).append(" models\n")
        if (models.isEmpty()) {
            writeDiagnostics(context, diag)
            logcat(LogPriority.WARN) {
                "ModelPlugins: no model discovered — trace written to $DIAG_FILE"
            }
        } else {
            // Discovery worked, so nothing to explain: drop the trace of an earlier failure —
            // whatever sits on disk must describe the latest attempt, not an old one.
            clearDiagnostics(context)
        }
        return models
    }

    /** Writes the scan trace to `Android/data/<pkg>/files/<DIAG_FILE>` (see [scan]). */
    private fun writeDiagnostics(context: Context, diag: StringBuilder) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, DIAG_FILE).writeText(diag.toString())
        }
    }

    /** Drops the trace left by an earlier empty scan, so it can never be read as current. */
    private fun clearDiagnostics(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, DIAG_FILE).delete()
        }
    }

    /**
     * Trusted = the package carries the host's own certificate, or its SHA-256 is listed in
     * [ALLOWED_PLUGIN_CERT_SHA256].
     *
     * The whitelist covers the "host certificate unreadable" case above. It is not
     * cryptography-grade anti-tampering — the release keystore ships with the repository —
     * it only tells "a package we produced" apart from "some other package".
     */
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

    /** SHA-256 of the Komiho release certificate — what every model package is signed with. */
    private val ALLOWED_PLUGIN_CERT_SHA256 = setOf(
        "A2:B7:E5:24:EE:59:9F:84:15:60:8A:D7:BE:1A:90:A1:C8:37:9C:51:92:3A:15:A7:93:79:29:36:29:81:64:D2",
    )

    /** Opens [pkg]'s `models.json` and converts each valid entry into a [PluginUpscaleModel]. */
    private fun parsePackage(
        context: Context,
        pkg: String,
        seenIds: MutableSet<String>,
        diag: StringBuilder,
    ): List<PluginUpscaleModel> {
        return try {
            val pluginContext = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
            val manifest = pluginContext.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(manifest)

            val protocol = root.optInt(KEY_PROTOCOL_VERSION, 1)
            diag.append("  models.json read ok, protocol=").append(protocol).append('\n')
            if (protocol > SUPPORTED_PROTOCOL_VERSION) {
                diag.append("  protocol newer than supported -> skipped\n")
                logcat(LogPriority.WARN) {
                    "ModelPlugins: $pkg manifest protocol v$protocol > supported " +
                        "v$SUPPORTED_PROTOCOL_VERSION — update the host app to use it; skipped"
                }
                return emptyList()
            }

            val entries = root.optJSONArray(KEY_MODELS)
            diag.append("  entries: ").append(entries?.length() ?: -1).append('\n')
            if (entries == null) return emptyList()

            val result = mutableListOf<PluginUpscaleModel>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val model = entryOrNull(pkg, entry, diag) ?: continue
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
            diag.append("  models.json FAILED -> ").append(e).append('\n')
            logcat(LogPriority.WARN, e) { "ModelPlugins: failed to read $pkg — skipped" }
            emptyList()
        }
    }

    /** Validates one manifest entry; `null` when a required field is missing or nonsense. */
    private fun entryOrNull(pkg: String, entry: JSONObject, diag: StringBuilder): PluginUpscaleModel? {
        val id = entry.optString(KEY_ID).trim()
        val stem = entry.optString(KEY_STEM).trim()
        val label = entry.optString(KEY_LABEL).trim()
        val padding = entry.optInt(KEY_PADDING, -1)
        val scale = entry.optInt(KEY_SCALE, 2)
        val assetDir = entry.optString(KEY_ASSET_DIR).trim().ifEmpty { DEFAULT_ASSET_DIR }
        val arches = parseArches(entry)

        if (id.isEmpty() || stem.isEmpty() || label.isEmpty() || padding <= 0 || arches.isEmpty()) {
            diag.append("  entry '").append(id).append("' rejected: padding=").append(padding)
                .append(" arches=").append(arches)
                .append(" rawArches=").append(entry.optJSONArray(KEY_ARCHES))
                .append('\n')
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

    /**
     * HTP generations from a manifest entry.
     *
     * Komiho (2026-09-23): the packager writes these as **strings** (`os.environ` values are
     * always strings, so `models.json` really contains `"arches": ["75"]`), while this reader
     * used to call `optInt` — a number-only accessor. Accept either shape so a package built
     * by the workflow is readable as-is.
     */
    private fun parseArches(entry: JSONObject): List<Int> {
        val array = entry.optJSONArray(KEY_ARCHES) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            when (val raw = array.opt(i)) {
                is Int -> raw
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }?.takeIf { it > 0 }
        }
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
