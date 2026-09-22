package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
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

    // Komiho：预载页数（前后各几页）。档位只写值，含义由行标题承担。
    val offscreenLimit by screenModel.preferences.pagerOffscreenLimit.collectAsState()
    SettingsChipRow(MR.strings.pref_pager_offscreen_limit) {
        (PagerConfig.OffscreenPages.MIN..PagerConfig.OffscreenPages.MAX).forEach { pages ->
            FilterChip(
                selected = offscreenLimit == pages,
                onClick = { screenModel.preferences.pagerOffscreenLimit.set(pages) },
                label = { Text(pages.toString()) },
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

    // Komiho: webtoon 预取深度（1=当前行为，2/3=加大预取提前解码，缓解 NPU 增强黑屏）
    val webtoonPrefetchDepth by screenModel.preferences.webtoonPrefetchDepth.collectAsState()
    SettingsChipRow(MR.strings.pref_webtoon_prefetch_depth) {
        ReaderPreferences.WebtoonPrefetchDepth.mapIndexed { index, it ->
            FilterChip(
                selected = webtoonPrefetchDepth == index + 1,
                onClick = { screenModel.preferences.webtoonPrefetchDepth.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    // Komiho: 两代翻页动画互斥，先取状态，供时长滑条显隐与两个开关互关使用。
    val pageTransitionsWebtoon by screenModel.preferences.pageTransitionsWebtoon.collectAsState()
    val pageTransitionsWebtoonV2 by screenModel.preferences.pageTransitionsWebtoonV2.collectAsState()

    val webtoonTapScrollDuration by screenModel.preferences.webtoonTapScrollDuration.collectAsState()
    // v2 的时长按滚动距离自动算，固定时长滑条对它无效 —— 开启 v2 时隐藏，避免改了没效果。
    if (!pageTransitionsWebtoonV2) {
        SliderItem(
            value = webtoonTapScrollDuration,
            valueRange = ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MIN..ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MAX,
            label = stringResource(MR.strings.pref_webtoon_tap_scroll_duration),
            valueString = "${webtoonTapScrollDuration}ms",
            onChange = { screenModel.preferences.webtoonTapScrollDuration.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

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

    // Komiho: v1 / v2 互斥 —— 勾一个自动取消另一个（v1 匀速固定时长，v2 三次方缓出 + 时长按距离算）。
    CheckboxItem(
        label = stringResource(SYMR.strings.pref_page_transitions_linear),
        checked = pageTransitionsWebtoon,
        onClick = {
            val next = !pageTransitionsWebtoon
            screenModel.preferences.pageTransitionsWebtoon.set(next)
            if (next) screenModel.preferences.pageTransitionsWebtoonV2.set(false)
        },
    )

    CheckboxItem(
        label = stringResource(SYMR.strings.pref_page_transitions_v2),
        checked = pageTransitionsWebtoonV2,
        onClick = {
            val next = !pageTransitionsWebtoonV2
            screenModel.preferences.pageTransitionsWebtoonV2.set(next)
            if (next) screenModel.preferences.pageTransitionsWebtoon.set(false)
        },
    )

    // v2 速度档位（每屏基准时长，越小越快）——只在 v2 开启时显示
    if (pageTransitionsWebtoonV2) {
        val pageTransitionsV2Speed by screenModel.preferences.pageTransitionsV2Speed.collectAsState()
        SettingsChipRow(SYMR.strings.pref_page_transitions_v2_speed) {
            ReaderPreferences.PageTransitionsV2Speeds.map { speed ->
                FilterChip(
                    selected = pageTransitionsV2Speed == speed,
                    onClick = { screenModel.preferences.pageTransitionsV2Speed.set(speed) },
                    label = { Text("${speed}ms") },
                )
            }
        }
    }
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
