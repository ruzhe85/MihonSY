package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * In-reader image enhancement settings, promoted to its own main tab (2nd tab, immediately to
 * the right of Reading Mode). The grouped layout (Off / CPU / GPU / NPU) lives in
 * [ImageEnhancementSection] so this sheet and Settings → Reader stay structurally identical.
 *
 * The section renders its own contents; the tab title already communicates "Image enhancement",
 * so no extra [MR.strings.pref_image_enhancement_group] heading is drawn here.
 */
@Composable
internal fun ColumnScope.ImageEnhancementPage(screenModel: ReaderSettingsScreenModel) {
    ImageEnhancementSection(preferences = screenModel.preferences)
}
