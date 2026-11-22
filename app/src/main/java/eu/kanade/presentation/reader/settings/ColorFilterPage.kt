package eu.kanade.presentation.reader.settings

import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

@Composable
internal fun ColumnScope.ColorFilterPage(screenModel: ReaderSettingsScreenModel) {
    val customBrightness by screenModel.preferences.customBrightness.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = screenModel.preferences.customBrightness,
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by screenModel.preferences.customBrightnessValue.collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { screenModel.preferences.customBrightnessValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by screenModel.preferences.colorFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = screenModel.preferences.colorFilter,
    )
    if (colorFilter) {
        val colorFilterValue by screenModel.preferences.colorFilterValue.collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                screenModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newRValue, RED_MASK, 16)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.green,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newGValue ->
                screenModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newGValue, GREEN_MASK, 8)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.blue,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newBValue ->
                screenModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newBValue, BLUE_MASK, 0)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.alpha,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newAValue ->
                screenModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by screenModel.preferences.colorFilterMode.collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { screenModel.preferences.colorFilterMode.set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = screenModel.preferences.grayscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = screenModel.preferences.invertedColors,
    )

    // Komiho: 图像调节（亮度 / 对比度 / 伽马）—— 与上面「屏幕亮度」「色彩滤镜」并列的
    // 三条独立滤镜。亮度/对比度是线性变换，走显示层 ColorMatrix，所有版本可用且即时生效；
    // 伽马是幂函数（ColorMatrix 表达不了），12+ 走 RuntimeShader，12 以下走解码期 LUT
    //（改完关掉设置页才重新出图，所以低版本在标题后标注生效时机）。
    val brightnessOn by screenModel.preferences.brightnessFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_image_brightness),
        pref = screenModel.preferences.brightnessFilter,
    )
    if (brightnessOn) {
        val brightnessValue by screenModel.preferences.brightnessFilterValue.collectAsState()
        SliderItem(
            value = brightnessValue,
            valueRange = -100..100,
            steps = 0,
            label = stringResource(MR.strings.pref_image_brightness),
            onChange = { screenModel.preferences.brightnessFilterValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val contrastOn by screenModel.preferences.contrastFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_image_contrast),
        pref = screenModel.preferences.contrastFilter,
    )
    if (contrastOn) {
        val contrastValue by screenModel.preferences.contrastFilterValue.collectAsState()
        SliderItem(
            value = contrastValue,
            valueRange = -100..100,
            steps = 0,
            label = stringResource(MR.strings.pref_image_contrast),
            onChange = { screenModel.preferences.contrastFilterValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val gammaImmediate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val gammaLabel = stringResource(MR.strings.pref_image_gamma)
    val gammaOn by screenModel.preferences.gammaFilter.collectAsState()
    CheckboxItem(
        label = if (gammaImmediate) {
            gammaLabel
        } else {
            "$gammaLabel (${stringResource(MR.strings.filter_applies_after_close)})"
        },
        pref = screenModel.preferences.gammaFilter,
    )
    if (gammaOn) {
        val gammaValue by screenModel.preferences.gammaFilterValue.collectAsState()
        SliderItem(
            value = gammaValue,
            valueRange = 40..250,
            steps = 0,
            label = gammaLabel,
            // 存的是 40…250（两位小数的定点），显示成 0.40…2.50。
            valueString = String.format(Locale.US, "%.2f", gammaValue / 100f),
            onChange = { screenModel.preferences.gammaFilterValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
