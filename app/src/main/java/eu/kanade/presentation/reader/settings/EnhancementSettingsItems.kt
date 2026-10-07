package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Komiho (2026-10-02): 「图像增强」重设计后拆出的呈现组件。
 *
 * 该区现在分两级：一级只选方式（关闭 / CPU / GPU / NPU），参数收进各自的详情页。这里放
 * 与具体分组无关的行样式，[ImageEnhancementSection] 负责路由与内容。两个调用点（阅读器内的
 * 标签页、设置 → 阅读器）共用这一份实现。
 */

/**
 * 一级列表的方式行：左侧 radio 表示当前方式，右侧箭头表示可进入详情。
 *
 * 两个点击热区分工不同：
 *  - **圆圈**（[onSelect]）＝ 只切到该方式，停在当前页 —— 与 Material 的 radio 习惯一致，
 *    用户只想换个算法、不想进去调参数时，不必再返回一次；
 *  - **行内其它区域**（[onClick]）＝ 切到该方式 **并** 进入它的详情页，省掉「点进去看一眼、
 *    返回、结果什么都没变」的空转。
 *
 * 圆圈必须自己消费点击（`RadioButton.onClick` 传非空），否则事件会冒泡到整行的 clickable，
 * 把「选中」变成「跳转」。
 *
 * [subtitle] 以「这一刻实际会跑什么」为准：该方式**处于选中态**时显示当前生效的算法 / 模型，
 * 未选中时才显示它自己上次选的取值（「切回去会得到什么」一眼可见），因此不会出现「看着是 A、
 * 实际跑 B」。NPU 行还有一个例外：从未选过、或上次选的模型已卸载 / 本机不兼容时显示占位符 ——
 * 那时确实没有属于用户选择的值可显示（点它仍会切到可用列表里的第一个）。选中态由左侧 radio
 * 表达，不靠副标题有无来区分（2026-10-03 之前是「非当前行一律显示占位符」，四个里三个是空的，
 * 读不出信息）。
 */
@Composable
internal fun EnhancementMethodRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit = onClick,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 详情页下段的「AI 选项」：大图强制增强、面积回缩（以及开启时的滤波强度）。
 *
 * GPU 与 NPU 两个详情页都引用这一份，操作的是同一组偏好，因此状态天然互通 —— 不会出现
 * 「在 GPU 页开了、切到 NPU 页又显示关」的错觉。
 */
@Composable
internal fun AiAdvancedOptions(preferences: ReaderPreferences) {
    // Komiho: 大图强制 AI 增强（放开 r≤1 尺寸门控；MP 输出门仍兜底）。
    val bypass by preferences.aiBypassFitGate.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.ai_bypass_fit),
        checked = bypass,
        onClick = { preferences.aiBypassFitGate.set(!bypass) },
    )

    // Komiho: AI 面积回缩（防摩尔纹）—— AI 2x 输出按显示尺寸用高斯核压回显示带通再交
    // SSIV（SSIV 整图双线性缩小无低通，高频网点会拍频）。
    val areaDownscale by preferences.aiAreaDownscale.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.ai_area_downscale),
        checked = areaDownscale,
        onClick = { preferences.aiAreaDownscale.set(!areaDownscale) },
    )
    if (areaDownscale) {
        // Komiho: 回缩强度 —— σ 随档位走，盖住网点晶格周期才积得成均匀灰；强档灰度均匀但
        // 线稿更软，按网点粗细取舍。
        val strength by preferences.aiAreaDownscaleStrength.collectAsState()
        EnhancementParamLabel(MR.strings.ai_area_downscale_strength)
        SettingsChipRow {
            ReaderPreferences.AiAreaDownscaleStrengthOptions.forEach { (value, labelRes) ->
                FilterChip(
                    selected = strength == value,
                    onClick = { preferences.aiAreaDownscaleStrength.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

/** 详情页里的小标题（「模型」「分块大小」），比一级的分组标题弱一级。 */
@Composable
internal fun EnhancementParamLabel(labelRes: StringResource) {
    EnhancementParamLabel(stringResource(labelRes))
}

/**
 * 小标题的纯文本重载 —— 系列名（`W2xEX` / `Real-CUGAN`）来自模型包 manifest 的 `group`
 * 字段或 label 首词，不是 i18n 资源。
 */
@Composable
internal fun EnhancementParamLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = SettingsItemsPaddings.Horizontal,
            end = SettingsItemsPaddings.Horizontal,
            top = 4.dp,
        ),
    )
}
