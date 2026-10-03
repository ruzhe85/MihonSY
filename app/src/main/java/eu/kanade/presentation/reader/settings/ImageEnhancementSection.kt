package eu.kanade.presentation.reader.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import eu.kanade.tachiyomi.util.waifu2x.PluginUpscaleModel
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
 * 副标题占位符 —— 纯符号，各语言一致，无需 i18n。
 *
 * 只在**确实无值可显示**时用（目前只剩「NPU 一个可用模型都没有」这一种）。其余情况显示
 * 该方式自己上次选的算法 / 模型。
 */
private const val PLACEHOLDER = "—"

/** 二级详情页标识。就地切换，不进导航栈（见 [ImageEnhancementSection] 的说明）。 */
private enum class EnhancementDetail { CPU, GPU, NPU }

/**
 * Komiho: 图像增强设置区，被**两处**共用 —— 阅读器内的标签页（`ImageEnhancementPage`）与
 * 设置 → 阅读器（`PreferenceItem.CustomPreference`）。一份实现是两者结构不走样的前提。
 *
 * 2026-10-02 重设计：原来把所有东西平铺在一屏（降噪 + 总开关 + CPU/GPU/NPU 三组选择器 +
 * 随档位忽隐忽现的三组参数 chips + 夹在中间的开关 + AI 专属开关），层级过密。现在拆成两级：
 *
 *  - **一级**：只选方式 —— 降噪开关、`关闭 / CPU / GPU / NPU` 四行单选、状态角标开关；
 *  - **二级**：CPU（算法 + 倍率）、GPU（模型 + 分块大小 + AI 选项）、NPU（模型 + AI 选项），
 *    顶部有「返回」行，系统返回键同样退回一级。
 *
 * **就地切换，绝不使用导航**：两个调用点都不在 voyager 环境内（设置页的 CustomPreference
 * 位于 Preferences 渲染流程中，阅读器标签页是 ColumnScope 内容），任何
 * `navigator.push` / `LocalNavigator.currentOrThrow` 都会以
 * `IllegalStateException: CompositionLocal is null` 崩溃 —— 备份设置页踩过这个坑。
 * 这里与 `SettingsKomihoBackupScreen` 的 `showSyncConn` 用同一种写法：父屏持有子屏状态。
 *
 * 状态仍只有 [ReaderPreferences.enhancementMode] 一个真值，选模型时顺带把 mode 设为 5，
 * 因此任何时刻恰好一个方式行处于选中态。调用方自己画区标题 —— 这里只渲染内容。
 */
@Composable
fun ImageEnhancementSection(
    preferences: ReaderPreferences,
    modifier: Modifier = Modifier,
) {
    // 普通 remember（非 rememberSaveable），与备份页的子屏状态一致：二级页是瞬时 UI 状态，
    // 不该跨进程恢复（否则重建后停在详情页会让用户失去上下文）。
    var detail by remember { mutableStateOf<EnhancementDetail?>(null) }
    BackHandler(enabled = detail != null) { detail = null }

    Column(modifier) {
        when (detail) {
            null -> EnhancementRootList(preferences = preferences, onOpen = { detail = it })
            EnhancementDetail.CPU -> CpuDetail(preferences = preferences, onBack = { detail = null })
            EnhancementDetail.GPU -> GpuDetail(preferences = preferences, onBack = { detail = null })
            EnhancementDetail.NPU -> NpuDetail(preferences = preferences, onBack = { detail = null })
        }
    }
}

/**
 * 写增强档位。落在 CPU 档位时顺带记住它 —— 一级列表在「CPU 未选中」的行上要显示这个值。
 */
private fun setEnhancementMode(preferences: ReaderPreferences, mode: Int) {
    preferences.enhancementMode.set(mode)
    if (mode in 2..3) preferences.enhancementLastCpuMode.set(mode)
}

/**
 * 写 AI 模型：**按后端**记住这次选择（Vulkan → GPU 记忆，HTP → NPU 记忆），并把档位切到 AI。
 *
 * 一处收口的价值：四个选模型的地方（GPU 详情 chips、NPU 详情 chips、一级的 GPU / NPU 行）
 * 都走这里，不会出现「某条路径忘了记」导致该方式又退回占位符。
 */
private fun setEnhancementModel(preferences: ReaderPreferences, model: UpscaleModelSpec) {
    preferences.aiModelId.set(model.id)
    when (model.backend) {
        UpscaleModelSpec.Backend.NCNN_VULKAN -> preferences.enhancementLastGpuModelId.set(model.id)
        UpscaleModelSpec.Backend.QNN_HTP -> preferences.enhancementLastNpuModelId.set(model.id)
    }
    setEnhancementMode(preferences, 5)
}

/**
 * 一级：只负责选方式，一屏 6 行（降噪 / 四方式 / 角标）。
 *
 * 「关闭」不再是把下面全部藏起来，而是列表里的一个可选项，任何时候都能切回去。
 */
@Composable
private fun EnhancementRootList(
    preferences: ReaderPreferences,
    onOpen: (EnhancementDetail) -> Unit,
) {
    val mode by preferences.enhancementMode.collectAsState()
    val modelId by preferences.aiModelId.collectAsState()
    // findById() 把未知/已卸载的模型 id 归一化到默认值，所以选中态永远有确定答案。
    val activeModel = UpscaleModelRegistry.findById(modelId)

    val context = LocalContext.current
    val npuAvailable = Waifu2x.isCdspAvailable && Waifu2x.isQnnRuntimeAvailable

    // 每个方式各自的「最后一次选择」：非当前方式的行也显示它们，而不是占位符
    // （见 [EnhancementMethodRow] 的说明）。
    val lastCpuMode by preferences.enhancementLastCpuMode.collectAsState()
    val lastGpuModelId by preferences.enhancementLastGpuModelId.collectAsState()
    val lastNpuModelId by preferences.enhancementLastNpuModelId.collectAsState()
    // CPU 记忆存了非法值（老数据 / 手改）时回落 Lanczos3。
    val cpuMemory = lastCpuMode
        .takeIf { flag -> ReaderPreferences.CpuEnhancementModes.any { it.first == flag } } ?: 2
    // GPU 记忆走 findById()：未知 / 已卸载的插件归一化到内置默认模型。
    val gpuMemory = UpscaleModelRegistry.findById(lastGpuModelId)
    // NPU 记忆必须在**本机可用列表**里找：findById() 对已卸载的插件会归一化到内置 Vulkan 模型，
    // 那样 NPU 行会显示一个根本跑不了的模型名。找不到（含从未选过）回落到第一个可用。
    // remember：扫描是幂等的，但没必要每次重组都查一遍 PackageManager。
    val npuCompatible = remember(npuAvailable) {
        if (npuAvailable) compatibleNpuModels(context) else emptyList()
    }
    val npuMemory = npuCompatible.firstOrNull { it.id == lastNpuModelId } ?: npuCompatible.firstOrNull()

    // Komiho: 降噪独立于增强档位（mode 0 也生效），常驻首行。
    val denoise by preferences.denoiseLevel.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.enhancement_denoise),
        checked = denoise != 0,
        onClick = { preferences.denoiseLevel.set(if (denoise != 0) 0 else 1) },
    )

    EnhancementGroupLabel(MR.strings.enhancement_section_methods)

    // 关闭：没有可调的参数，点一下直接生效。
    EnhancementMethodRow(
        label = stringResource(MR.strings.enhancement_off),
        subtitle = null,
        selected = mode == 0,
        onClick = { setEnhancementMode(preferences, 0) },
    )

    // CPU：圆圈只切方式，整行则切方式 + 进详情（算法与倍率沿用上次的选择，详情页里可改）。
    val cpuActive = mode in 2..3
    val activateCpu: () -> Unit = { setEnhancementMode(preferences, cpuMemory) }
    EnhancementMethodRow(
        label = stringResource(MR.strings.enhancement_group_cpu),
        // 显示 CPU **自己**上次选的算法，而不是当前档位 —— 非选中行也有值可读。
        subtitle = ReaderPreferences.CpuEnhancementModes
            .firstOrNull { it.first == cpuMemory }
            ?.let { stringResource(it.second) },
        selected = cpuActive,
        onClick = {
            activateCpu()
            onOpen(EnhancementDetail.CPU)
        },
        onSelect = activateCpu,
    )

    // GPU：切回时恢复**这个后端上次选的模型**（而不是当前档位上的模型）—— 不静默换掉用户在
    // 另一个后端上选过的东西。
    val gpuActive = mode == 5 && activeModel.backend == UpscaleModelSpec.Backend.NCNN_VULKAN
    val activateGpu: () -> Unit = {
        if (!gpuActive) setEnhancementModel(preferences, gpuMemory)
    }
    EnhancementMethodRow(
        label = stringResource(MR.strings.enhancement_group_gpu),
        subtitle = gpuMemory.displayLabel(),
        selected = gpuActive,
        onClick = {
            activateGpu()
            onOpen(EnhancementDetail.GPU)
        },
        onSelect = activateGpu,
    )

    // NPU：门控与详情页共用同一条件（CDSP 优先）。没有可用模型时不改设置，只进详情页看提示。
    if (npuAvailable) {
        val npuActive = mode == 5 && activeModel.backend == UpscaleModelSpec.Backend.QNN_HTP
        val activateNpu: () -> Unit = {
            if (!npuActive) {
                // 本机可用列表为空时再现场扫一次（装了新模型包后不必重启）。
                val target = npuMemory ?: compatibleNpuModels(context).firstOrNull()
                target?.let { setEnhancementModel(preferences, it) }
            }
        }
        EnhancementMethodRow(
            label = stringResource(MR.strings.enhancement_group_npu),
            // 只有「一个可用模型都没有」时才是占位符 —— 那时确实无值可显示。
            subtitle = npuMemory?.displayLabel() ?: PLACEHOLDER,
            selected = npuActive,
            onClick = {
                activateNpu()
                onOpen(EnhancementDetail.NPU)
            },
            onSelect = activateNpu,
        )
    }

    // Komiho: 显示增强状态角标。与降噪同属「独立开关」，一级常驻 —— 关闭增强时不再一起消失。
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_enhancement_status),
        pref = preferences.showEnhancementStatus,
    )
}

/**
 * 本机 HTP 代次可用的 NPU 模型。
 *
 * 一级列表**不做重扫**（重扫只在 NPU 详情页的 ON_RESUME 进行）。但"没扫过"与"没装包"从
 * 列表角度看是一样的，所以这里在空结果时补一次 [UpscaleModelRegistry.ensureScanned] ——
 * 它是进程内幂等的，代价只是一次 PackageManager 查询，不会把可用的模型包误判成未安装。
 */
private fun compatibleNpuModels(context: Context): List<PluginUpscaleModel> {
    val cached = UpscaleModelRegistry.npuModels()
    val models = cached.ifEmpty {
        UpscaleModelRegistry.ensureScanned(context)
        UpscaleModelRegistry.npuModels()
    }
    return models.filter { Waifu2x.detectedQnnArchitecture in it.qnnArches }
}

/** 详情页顶部统一的「返回」行；系统返回键由 [ImageEnhancementSection] 的 BackHandler 接管。 */
@Composable
private fun DetailBackRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onBack)
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(MR.strings.action_back),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * CPU 详情：算法与倍率。
 *
 * 两节恒定可见 —— 进入路径已经把档位设为 CPU，这里不再需要「仅当某档位生效时显示」的条件，
 * 也就不会出现参数行随档位忽隐忽现的跳动。
 */
@Composable
private fun CpuDetail(
    preferences: ReaderPreferences,
    onBack: () -> Unit,
) {
    val mode by preferences.enhancementMode.collectAsState()
    val scale by preferences.lanczosScale.collectAsState()

    DetailBackRow(onBack)

    EnhancementParamLabel(MR.strings.enhancement_group_cpu)
    SettingsChipRow {
        ReaderPreferences.CpuEnhancementModes.forEach { (flag, labelRes) ->
            FilterChip(
                selected = mode == flag,
                onClick = { setEnhancementMode(preferences, flag) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

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

/**
 * GPU 详情：内置 Vulkan 模型、分块大小，以及只在 AI 档生效的「AI 选项」。
 *
 * 模型数量固定为 3，用 chip 表达平级选项正合适 —— 与 CPU 详情、NPU 详情保持同一种
 * 「小标题 + chip 行」结构。
 */
@Composable
private fun GpuDetail(
    preferences: ReaderPreferences,
    onBack: () -> Unit,
) {
    val mode by preferences.enhancementMode.collectAsState()
    val modelId by preferences.aiModelId.collectAsState()
    val activeModel = UpscaleModelRegistry.findById(modelId)
    val tileSize by preferences.aiTileSize.collectAsState()

    DetailBackRow(onBack)

    EnhancementParamLabel(MR.strings.enhancement_detail_model)
    SettingsChipRow {
        AiUpscaleModel.entries
            .filter { Waifu2x.isModelSupported(it) }
            .forEach { model ->
                FilterChip(
                    selected = mode == 5 && activeModel == model,
                    onClick = { setEnhancementModel(preferences, model) },
                    label = { Text(model.displayLabel()) },
                )
            }
    }

    // 分块边长只影响 GPU 路径（NPU 的 tile 由 context 编译期固定）。
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

    EnhancementParamLabel(MR.strings.enhancement_detail_advanced)
    AiAdvancedOptions(preferences)
}

/**
 * NPU 详情：插件模型包提供的模型。
 *
 * 模型按**系列**分组：多成员的系列给一个小标题 + 一行 chips，单成员（或无法解析出系列名）的
 * 直接平铺。组头不再是一个超宽 chip —— 那是上一版看起来「组头和成员分不清」的根因。
 *
 * 扫描只在**本页**触发（首次进入 + 每次 ON_RESUME），安装/卸载模型包后返回即可见。
 */
@Composable
private fun NpuDetail(
    preferences: ReaderPreferences,
    onBack: () -> Unit,
) {
    val modelId by preferences.aiModelId.collectAsState()
    val activeModel = UpscaleModelRegistry.findById(modelId)

    val context = LocalContext.current
    var npuModels by remember { mutableStateOf(UpscaleModelRegistry.npuModels()) }

    // Refresh on first composition AND every time the screen comes back to the foreground, so
    // installing (or uninstalling) a model package and returning updates the list immediately —
    // no app restart, no preference churn.
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

    DetailBackRow(onBack)

    EnhancementParamLabel(MR.strings.enhancement_detail_model)

    val compatible = npuModels.filter { Waifu2x.detectedQnnArchitecture in it.qnnArches }
    if (compatible.isEmpty()) {
        // 没装模型包（或没覆盖本机代次）：一行可点击的下划线提示，跳到模型包发布页。
        Text(
            text = stringResource(MR.strings.ai_model_plugin_hint),
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
        npuSeriesGroups(compatible).forEach { (series, members) ->
            NpuSeriesChips(
                series = series,
                members = members,
                activeModel = activeModel,
                onSelect = { model -> setEnhancementModel(preferences, model) },
            )
        }
    }

    EnhancementParamLabel(MR.strings.enhancement_detail_advanced)
    AiAdvancedOptions(preferences)
}

/**
 * 把模型按系列归组：**多成员的系列各自成组；单成员系列与解析不出系列名的一律并入
 * «Standalone» 组**（key 为 `null`）。
 *
 * 后者是必须的：上一版让它们「不设标题、直接平铺」，结果它们既不像独立项、又紧挨着别组的
 * chip 行，看起来像挂在那个组下面。现在每组都有标题，归属一目了然。
 *
 * 顺序沿用 manifest（[LinkedHashMap] 保持遭遇顺序），所以 Standalone 组出现在它第一个成员的
 * 位置。
 */
private fun npuSeriesGroups(
    models: List<PluginUpscaleModel>,
): List<Pair<String?, List<PluginUpscaleModel>>> {
    val groups = LinkedHashMap<String?, MutableList<PluginUpscaleModel>>()
    models.groupBy { it.seriesName() }.forEach { (series, members) ->
        val key = series?.takeIf { members.size > 1 }
        groups.getOrPut(key) { mutableListOf() }.addAll(members)
    }
    return groups.map { it.key to it.value.toList() }
}

/**
 * 一个组的 chip 行：组标题 + 该组成员的 chip。
 *
 * [series] 为 null 表示 Standalone 组，标题取 `enhancement_series_standalone`；有系列名时成员
 * 显示去掉前缀的短名（`W2xEX` + `Omni Small`），Standalone 组则显示完整名 —— 它的名字本身
 * 就是全名。
 *
 * 保持不折叠：纵向平铺后没有压高度的压力，而折叠会重新引入「多点一次才知道有什么」的问题。
 */
@Composable
private fun NpuSeriesChips(
    series: String?,
    members: List<PluginUpscaleModel>,
    activeModel: UpscaleModelSpec,
    onSelect: (PluginUpscaleModel) -> Unit,
) {
    EnhancementParamLabel(series ?: stringResource(MR.strings.enhancement_series_standalone))
    SettingsChipRow {
        members.forEach { model ->
            FilterChip(
                selected = model == activeModel,
                onClick = { onSelect(model) },
                label = {
                    Text(
                        if (series != null) {
                            model.shortLabel(series)
                        } else {
                            model.displayLabel()
                        },
                    )
                },
            )
        }
    }
}

/** Label text for either kind of model: moko resource for built-ins, JSON text for plugins. */
@Composable
private fun UpscaleModelSpec.displayLabel(): String =
    labelRes?.let { stringResource(it) } ?: labelText.orEmpty()

/**
 * Series name used to group this model: the manifest's `group` when present, otherwise the
 * label's first word (`"W2xEX Omni Small"` -> `"W2xEX"`). A one-word label is a whole name
 * rather than a `series member` pair, so it yields null and the model stays a flat chip.
 */
private fun UpscaleModelSpec.seriesName(): String? {
    group?.takeIf { it.isNotBlank() }?.let { return it }
    val label = labelText?.trim().orEmpty()
    if (label.isEmpty()) return null
    val head = label.substringBefore(' ')
    return head.takeIf { head != label }
}

/** Member name with the series prefix stripped: `"W2xEX Omni Small"` minus `"W2xEX"`. */
private fun UpscaleModelSpec.shortLabel(series: String): String {
    val label = labelText.orEmpty()
    return label.removePrefix(series).trim().ifEmpty { label }
}

/** Muted heading for the method group on the root list. */
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
