package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.waifu2x.AiUpscaleModel
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelRegistry
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelSpec
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Komiho: the grouped image-enhancement picker, shared by **both** settings surfaces —
 * the in-reader sheet (via `ReadingModePage`) and Settings → Reader (via
 * `PreferenceItem.CustomPreference`). One implementation is what keeps the two structurally
 * identical; they had silently drifted apart before.
 *
 * Layout, top to bottom:
 *  - Off, on its own row;
 *  - **CPU** group — Lanczos3 / Catmull-Rom, plus the scale row while a CPU mode is active;
 *  - **GPU** group — one chip per built-in Vulkan model, plus the tile-size row while a GPU
 *    mode is active;
 *  - **NPU** group — models delivered by installed plugin APKs. Gated on
 *    `Waifu2x.isCdspAvailable` **first**: firmware-disabled compute DSPs (no fastrpc control
 *    node) skip the group entirely, since nothing there could ever run. 2026-09-19 模型插件化:
 *    the host APK ships **no** NPU contexts anymore, so with a CDSP present this group has
 *    three states:
 *      1. no QNN runtime (non-Qualcomm) — the whole group is not rendered;
 *      2. runtime present, no compatible plugin installed — a non-selectable hint that
 *         links to the model-package release on GitHub (installing a package and returning
 *         to this screen refreshes the list immediately);
 *      3. plugin installed — one chip per model whose generation set covers this device's
 *         on-chip HTP (a family that skips this generation stays invisible instead of
 *         offering a chip that could only ever fall back);
 *  - the enhancement-status overlay toggle.
 *
 * The groups are **purely visual**: the underlying state is still the single
 * [ReaderPreferences.enhancementMode] flag, so exactly one chip is selected at any time
 * (picking a model also sets mode 5). Callers draw the section heading themselves — this
 * composable renders contents only.
 */
@Composable
fun ImageEnhancementSection(
    preferences: ReaderPreferences,
    modifier: Modifier = Modifier,
) {
    val mode by preferences.enhancementMode.collectAsState()

    Column(modifier) {
        // Off — not part of either platform group.
        SettingsChipRow {
            FilterChip(
                selected = mode == 0,
                onClick = { preferences.enhancementMode.set(0) },
                label = { Text(stringResource(MR.strings.enhancement_off)) },
            )
        }

        EnhancementGroupLabel(MR.strings.enhancement_group_cpu)
        SettingsChipRow {
            ReaderPreferences.CpuEnhancementModes.forEach { (flag, labelRes) ->
                FilterChip(
                    selected = mode == flag,
                    onClick = { preferences.enhancementMode.set(flag) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        if (mode in 2..3) {
            // Resampling scale applies to both CPU modes.
            val scale by preferences.lanczosScale.collectAsState()
            EnhancementParamLabel(MR.strings.enhancement_scale)
            SettingsChipRow {
                ReaderPreferences.LanczosScaleOptions.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = scale == value,
                        onClick = { preferences.lanczosScale.set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }

        // findById() normalises unknown/removed stored ids (dropped model, uninstalled
        // plugin) to the default, so exactly one model chip stays selected no matter what
        // the preference holds.
        val modelId by preferences.aiModelId.collectAsState()
        val activeModel = UpscaleModelRegistry.findById(modelId)

        // GPU group — built-in Vulkan models only. NPU models live in their own group below.
        EnhancementGroupLabel(MR.strings.enhancement_group_gpu)
        SettingsChipRow {
            AiUpscaleModel.entries
                .filter { Waifu2x.isModelSupported(it) }
                .forEach { model ->
                    FilterChip(
                        selected = mode == 5 && activeModel == model,
                        onClick = {
                            preferences.aiModelId.set(model.id)
                            preferences.enhancementMode.set(5)
                        },
                        label = { Text(model.displayLabel()) },
                    )
                }
        }
        if (mode == 5 && activeModel.backend == UpscaleModelSpec.Backend.NCNN_VULKAN) {
            // Tile edge only affects the GPU path (NPU uses the fixed QNN context).
            val tileSize by preferences.aiTileSize.collectAsState()
            EnhancementParamLabel(MR.strings.pref_ai_tile_size)
            SettingsChipRow {
                ReaderPreferences.AiTileSizeOptions.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = tileSize == value,
                        onClick = { preferences.aiTileSize.set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }

        // NPU group — models from installed plugin APKs, only where a compute DSP *and* an
        // NPU runtime exist. The CDSP probe comes first: with the DSP switched off in firmware
        // there is nothing to load `libQnnHtp.so` for, and every model would only fall back to
        // Vulkan, which looks identical to a working NPU from the outside.
        if (Waifu2x.isCdspAvailable && Waifu2x.isQnnRuntimeAvailable) {
            EnhancementGroupLabel(MR.strings.enhancement_group_npu)

            val context = LocalContext.current
            var npuModels by remember { mutableStateOf(UpscaleModelRegistry.npuModels()) }

            // Refresh on first composition AND every time the screen comes back to the
            // foreground, so installing (or uninstalling) a model package and returning to
            // this screen updates the list immediately — no app restart, no preference
            // churn. The scan is a handful of PackageManager queries + one small JSON.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        npuModels = UpscaleModelRegistry.refresh(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(Unit) {
                npuModels = UpscaleModelRegistry.refresh(context)
            }

            val compatible = npuModels.filter { Waifu2x.detectedQnnArchitecture in it.qnnArches }
            if (compatible.isEmpty()) {
                // No plugin installed (or none covering this generation): a non-selectable
                // hint instead of an empty chip row.
                val hint = stringResource(MR.strings.ai_model_plugin_hint)
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { context.openInBrowser(UpscaleModelRegistry.MODEL_PACKAGE_RELEASE_URL) }
                        .padding(
                            start = SettingsItemsPaddings.Horizontal,
                            end = SettingsItemsPaddings.Horizontal,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                )
            } else {
                SettingsChipRow {
                    compatible.forEach { model ->
                        FilterChip(
                            selected = mode == 5 && activeModel == model,
                            onClick = {
                                preferences.aiModelId.set(model.id)
                                preferences.enhancementMode.set(5)
                            },
                            label = { Text(model.displayLabel()) },
                        )
                    }
                }
            }
        }

        CheckboxItem(
            label = stringResource(MR.strings.pref_show_enhancement_status),
            pref = preferences.showEnhancementStatus,
        )
    }
}

/** Label text for either kind of model: moko resource for built-ins, JSON text for plugins. */
@Composable
private fun UpscaleModelSpec.displayLabel(): String =
    labelRes?.let { stringResource(it) } ?: labelText.orEmpty()

/** Muted heading for a platform group (CPU / GPU / NPU). */
@Composable
private fun EnhancementGroupLabel(labelRes: StringResource) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = SettingsItemsPaddings.Horizontal,
            end = SettingsItemsPaddings.Horizontal,
            top = SettingsItemsPaddings.Vertical,
            bottom = 2.dp,
        ),
    )
}

/** Lighter label for a parameter row nested inside a group (scale / tile size). */
@Composable
private fun EnhancementParamLabel(labelRes: StringResource) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = SettingsItemsPaddings.Horizontal,
            end = SettingsItemsPaddings.Horizontal,
            top = 4.dp,
        ),
    )
}
