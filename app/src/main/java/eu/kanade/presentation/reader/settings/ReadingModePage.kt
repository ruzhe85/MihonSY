package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

@Composable
internal fun ColumnScope.ReadingModePage(screenModel: ReaderSettingsScreenModel) {
    HeadingItem(MR.strings.pref_category_for_this_series)
    val manga by screenModel.mangaFlow.collectAsState()

    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    SettingsChipRow(MR.strings.pref_category_reading_mode) {
        ReadingMode.entries.map {
            FilterChip(
                selected = it == readingMode,
                onClick = { screenModel.onChangeReadingMode(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    val orientation = remember(manga) { ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt()) }
    SettingsChipRow(MR.strings.rotation_type) {
        ReaderOrientation.entries.map {
            FilterChip(
                selected = it == orientation,
                onClick = { screenModel.onChangeOrientation(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    // MihonSY: image enhancement applies to EVERY reading mode, so it sits at the top level
    // of the in-reader settings (not inside the webtoon/pager sections).
    ImageEnhancementSettings(screenModel)

    val viewer by screenModel.viewerFlow.collectAsState()
    if (viewer is WebtoonViewer) {
        WebtoonViewerSettings(screenModel)
        // SY -->
        WebtoonWithGapsViewerSettings(screenModel)
        // SY <--
    } else {
        PagerViewerSettings(screenModel)
    }
}

// MihonSY -->
/**
 * In-reader image enhancement settings. Placed at the top level of the reading-mode page
 * (outside the webtoon/pager sections) on purpose: enhancement is global and applies to
 * every reading mode. A single selector (Off / Anime4K / Lanczos3) avoids the ambiguity of
 * two toggles where one silently overrides the other.
 */
@Composable
private fun ColumnScope.ImageEnhancementSettings(screenModel: ReaderSettingsScreenModel) {
    HeadingItem(MR.strings.pref_image_enhancement_group)
    Text(
        text = stringResource(MR.strings.pref_enhancement_mode_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    val enhancementMode by screenModel.preferences.enhancementMode.collectAsState()
    // MihonSY: no label — the "图像增强" heading + explanation Text above already
    // serve as the section title; the chip row sits directly below.
    SettingsChipRow {
        // Anime4K (index 1) hidden — Lanczos3 measured better in practice.
        ReaderPreferences.EnhancementModes.forEachIndexed { index, label ->
            if (index == 1) return@forEachIndexed
            FilterChip(
                selected = enhancementMode == index,
                onClick = { screenModel.preferences.enhancementMode.set(index) },
                label = { Text(stringResource(label)) },
            )
        }
    }

    if (enhancementMode == 1) {
        // Anime4K (index 1) hidden — an old stored value of 1 shows no chip.
    } else if (enhancementMode in 2..4) {
        // Lanczos3 / Catmull-Rom / Spline36 — scale selection applies to all three.
        val lanczosScale by screenModel.preferences.lanczosScale.collectAsState()
        SettingsChipRow {
            ReaderPreferences.LanczosScaleOptions.forEach { (value, label) ->
                FilterChip(
                    selected = lanczosScale == value,
                    onClick = { screenModel.preferences.lanczosScale.set(value) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }

    // MihonSY: enhancement status overlay toggle, available right here in the
    // reader settings so the user does not have to dig into the app settings.
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_enhancement_status),
        pref = screenModel.preferences.showEnhancementStatus,
    )
}
// MihonSY <--

@Composable
private fun ColumnScope.PagerViewerSettings(screenModel: ReaderSettingsScreenModel) {
    HeadingItem(MR.strings.pager_viewer)

    val navigationModePager by screenModel.preferences.navigationModePager.collectAsState()
    val pagerNavInverted by screenModel.preferences.pagerNavInverted.collectAsState()
    TapZonesItems(
        selected = navigationModePager,
        onSelect = screenModel.preferences.navigationModePager::set,
        invertMode = pagerNavInverted,
        onSelectInvertMode = screenModel.preferences.pagerNavInverted::set,
    )

    val imageScaleType by screenModel.preferences.imageScaleType.collectAsState()
    SettingsChipRow(MR.strings.pref_image_scale_type) {
        ReaderPreferences.ImageScaleType.mapIndexed { index, it ->
            FilterChip(
                selected = imageScaleType == index + 1,
                onClick = { screenModel.preferences.imageScaleType.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    val zoomStart by screenModel.preferences.zoomStart.collectAsState()
    SettingsChipRow(MR.strings.pref_zoom_start) {
        ReaderPreferences.ZoomStart.mapIndexed { index, it ->
            FilterChip(
                selected = zoomStart == index + 1,
                onClick = { screenModel.preferences.zoomStart.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    // SY -->
    val pageLayout by screenModel.preferences.pageLayout.collectAsState()
    SettingsChipRow(SYMR.strings.page_layout) {
        ReaderPreferences.PageLayouts.mapIndexed { index, it ->
            FilterChip(
                selected = pageLayout == index,
                onClick = { screenModel.preferences.pageLayout.set(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }
    // SY <--

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = screenModel.preferences.cropBorders,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_landscape_zoom),
        pref = screenModel.preferences.landscapeZoom,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_navigate_pan),
        pref = screenModel.preferences.navigateToPan,
    )

    val dualPageSplitPaged by screenModel.preferences.dualPageSplitPaged.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_dual_page_split),
        pref = screenModel.preferences.dualPageSplitPaged,
    )

    if (dualPageSplitPaged) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_dual_page_invert),
            pref = screenModel.preferences.dualPageInvertPaged,
        )
    }

    val dualPageRotateToFit by screenModel.preferences.dualPageRotateToFit.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_rotate),
        pref = screenModel.preferences.dualPageRotateToFit,
    )

    if (dualPageRotateToFit) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_page_rotate_invert),
            pref = screenModel.preferences.dualPageRotateToFitInvert,
        )
    }

    // SY -->
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = screenModel.preferences.pageTransitionsPager,
    )

    CheckboxItem(
        label = stringResource(SYMR.strings.invert_double_pages),
        pref = screenModel.preferences.invertDoublePages,
    )

    val centerMarginType by screenModel.preferences.centerMarginType.collectAsState()
    SettingsChipRow(SYMR.strings.pref_center_margin) {
        ReaderPreferences.CenterMarginTypes.mapIndexed { index, it ->
            FilterChip(
                selected = centerMarginType == index,
                onClick = { screenModel.preferences.centerMarginType.set(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }
    // SY <--
}

@Composable
private fun ColumnScope.WebtoonViewerSettings(screenModel: ReaderSettingsScreenModel) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }

    HeadingItem(MR.strings.webtoon_viewer)

    val navigationModeWebtoon by screenModel.preferences.navigationModeWebtoon.collectAsState()
    val webtoonNavInverted by screenModel.preferences.webtoonNavInverted.collectAsState()
    TapZonesItems(
        selected = navigationModeWebtoon,
        onSelect = screenModel.preferences.navigationModeWebtoon::set,
        invertMode = webtoonNavInverted,
        onSelectInvertMode = screenModel.preferences.webtoonNavInverted::set,
    )

    // MihonSY -->
    val webtoonTapScrollDistance by screenModel.preferences.webtoonTapScrollDistance.collectAsState()
    SettingsChipRow(MR.strings.pref_webtoon_tap_scroll_distance) {
        ReaderPreferences.WebtoonTapScrollDistance.mapIndexed { index, it ->
            FilterChip(
                selected = webtoonTapScrollDistance == index,
                onClick = { screenModel.preferences.webtoonTapScrollDistance.set(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    val webtoonTapScrollDuration by screenModel.preferences.webtoonTapScrollDuration.collectAsState()
    SliderItem(
        value = webtoonTapScrollDuration,
        valueRange = ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MIN..ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MAX,
        label = stringResource(MR.strings.pref_webtoon_tap_scroll_duration),
        valueString = "${webtoonTapScrollDuration}ms",
        onChange = { screenModel.preferences.webtoonTapScrollDuration.set(it) },
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_webtoon_original_resolution),
        pref = screenModel.preferences.webtoonOriginalSize,
    )
    // MihonSY <--

    val webtoonSidePadding by screenModel.preferences.webtoonSidePadding.collectAsState()
    SliderItem(
        value = webtoonSidePadding,
        valueRange = ReaderPreferences.let { it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX },
        label = stringResource(MR.strings.pref_webtoon_side_padding),
        valueString = numberFormat.format(webtoonSidePadding / 100f),
        onChange = {
            screenModel.preferences.webtoonSidePadding.set(it)
        },
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = screenModel.preferences.cropBordersWebtoon,
    )

    // SY -->
    CheckboxItem(
        label = stringResource(SYMR.strings.pref_smooth_scroll),
        pref = screenModel.preferences.smoothAutoScroll,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = screenModel.preferences.pageTransitionsWebtoon,
    )
    // SY <--

    val dualPageSplitWebtoon by screenModel.preferences.dualPageSplitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_dual_page_split),
        pref = screenModel.preferences.dualPageSplitWebtoon,
    )

    if (dualPageSplitWebtoon) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_dual_page_invert),
            pref = screenModel.preferences.dualPageInvertWebtoon,
        )
    }

    val dualPageRotateToFitWebtoon by screenModel.preferences.dualPageRotateToFitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_rotate),
        pref = screenModel.preferences.dualPageRotateToFitWebtoon,
    )

    if (dualPageRotateToFitWebtoon) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_page_rotate_invert),
            pref = screenModel.preferences.dualPageRotateToFitInvertWebtoon,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_double_tap_zoom),
        pref = screenModel.preferences.webtoonDoubleTapZoomEnabled,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
        pref = screenModel.preferences.webtoonDisableZoomOut,
    )
}

// SY -->
@Composable
private fun ColumnScope.WebtoonWithGapsViewerSettings(screenModel: ReaderSettingsScreenModel) {
    HeadingItem(MR.strings.vertical_plus_viewer)

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = screenModel.preferences.cropBordersContinuousVertical,
    )
}
// SY <--

@Composable
private fun ColumnScope.TapZonesItems(
    selected: Int,
    onSelect: (Int) -> Unit,
    invertMode: ReaderPreferences.TappingInvertMode,
    onSelectInvertMode: (ReaderPreferences.TappingInvertMode) -> Unit,
) {
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        ReaderPreferences.TapZones.mapIndexed { index, it ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    if (selected != 5) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            ReaderPreferences.TappingInvertMode.entries.map {
                FilterChip(
                    selected = it == invertMode,
                    onClick = { onSelectInvertMode(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
}
