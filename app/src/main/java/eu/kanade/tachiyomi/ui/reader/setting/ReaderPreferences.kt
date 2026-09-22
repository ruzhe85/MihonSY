package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import eu.kanade.tachiyomi.util.waifu2x.AiUpscaleModel
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelRegistry
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR

class ReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    // region General

    // SY -->
    val pageTransitionsPager: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_pager_key", true)

    val pageTransitionsWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_webtoon_key", true)

    // Komiho: 条页点击滚屏的第二套动画（ComicScreen 手感：五次方减速 + 时长按距离算）。
    // 与 pageTransitionsWebtoon 互斥（UI 层保证两个开关互关）；默认关闭，保持 v1 行为不变。
    val pageTransitionsWebtoonV2: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_transitions_webtoon_v2_key",
        false,
    )

    // Komiho: v2 的速度档位 = 每屏基准时长（ms，越小越快）。
    // ComicScreen 反编译得出的基准 300 实测明显偏拖，故做成四档可调。
    val pageTransitionsV2Speed: Preference<Int> = preferenceStore.getInt(
        "pref_page_transitions_v2_speed",
        PAGE_TRANSITIONS_V2_SPEED_DEFAULT,
    )
    // SY <--

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val verticalNavigator: Preference<Set<ReadingMode>> = preferenceStore.getEnumSet(
        "pref_vertical_navigator",
        emptySet(),
    )

    val verticalNavigatorOnLeft: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_vertical_navigator_on_left",
        false,
    )

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt(
        "pref_vertical_navigator_height",
        65,
    )

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // endregion

    // region Split two-page spread

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    // MihonSY -->
    val webtoonTapScrollDistance: Preference<Int> = preferenceStore.getInt(
        "webtoon_tap_scroll_distance",
        WEBTOON_TAP_SCROLL_DISTANCE_DEFAULT,
    )

    val webtoonTapScrollDuration: Preference<Int> = preferenceStore.getInt(
        "webtoon_tap_scroll_duration",
        WEBTOON_TAP_SCROLL_DURATION_DEFAULT,
    )

    val webtoonOriginalSize: Preference<Boolean> = preferenceStore.getBoolean(
        "webtoon_original_size",
        false,
    )

    // Komiho: webtoon 预取深度（extra layout space 倍数）。1 = 当前行为（约 1 屏），
    // 2/3 = 加大预取、提前解码后续页面，缓解 NPU 增强时的黑屏间隙。默认 1（原版）。
    val webtoonPrefetchDepth: Preference<Int> = preferenceStore.getInt(
        "webtoon_prefetch_depth",
        WEBTOON_PREFETCH_DEPTH_DEFAULT,
    )

    // MihonSY image enhancement -->
    /** 0 = Off, 1 = Anime4K (disabled), 2 = Lanczos3, 3 = Catmull-Rom, 4 = Spline36 (disabled),
     *  5 = AI upscale (Komiho: ncnn + Vulkan, fixed 2x model).
     *  Single selector so the algorithms never conflict. Anime4K/Spline36 are retained
     *  in the index map for backward-compatible stored values but excluded from the build. */
    val enhancementMode: Preference<Int> = preferenceStore.getInt("pref_enhancement_mode", 0)

    // MihonSY: Anime4K disabled — preference retained for stored-value compatibility only.
    // val anime4kMode: Preference<Int> = preferenceStore.getInt("pref_anime4k_mode", 0) // 0 Fast, 1 High, 2 Ultra

    val lanczosScale: Preference<Int> = preferenceStore.getInt("pref_lanczos_scale", 200) // 150/200/300 = 1.5x/2x/3x

    /**
     * Komiho: tile edge (px) for the AI upscaler — forwarded to the native `tilesize`
     * (`waifu2x.cpp:150`, default 128) via `nativeUpdatePerformanceConfig`.
     *
     * Only affects AI upscale (mode 5). Larger tiles cut the number of tile
     * dispatches (and the per-tile fixed overhead) at the cost of a higher peak
     * GPU working set — each tile allocates `(tilesize + 2*prepadding)` input and
     * `tilesize * scale` output, so the working set scales with the square of this value.
     * The engine ships with `prepadding = 18`, annotated as safe up to tile size 256.
     */
    val aiTileSize: Preference<Int> = preferenceStore.getInt("pref_ai_tile_size", 128)

    /**
     * Komiho: which AI model the upscaler runs (a built-in [AiUpscaleModel] or a model
     * delivered by an installed plugin APK, see [UpscaleModelRegistry]).
     *
     * Stored as the model's stable string id, not an index — adding or reordering catalogue
     * entries must never remap an existing install's choice. Unknown ids (dropped model,
     * uninstalled plugin) fall back to [AiUpscaleModel.Default].
     */
    val aiModelId: Preference<String> = preferenceStore.getString(
        "pref_ai_model_id",
        AiUpscaleModel.Default.id,
    )

    /** Independent toggle: show the bottom-left enhancement status overlay (elapsed seconds / OK). */
    val showEnhancementStatus: Preference<Boolean> = preferenceStore.getBoolean("pref_show_enhancement_status", false)

    /**
     * Komiho (2026-09-19): fingerprint of every preference that changes the **rendered result**
     * of enhancement.
     *
     * Cached artefacts — the pager's prepared pages and its "this pair is already rendered"
     * guard — used to be validated against [enhancementMode] alone. So switching the AI model,
     * the resampler scale, the AI tile size or border cropping kept the old bitmap on screen
     * until the LRU evicted it (users had to leave the chapter or keep scrolling to see the new
     * setting). Comparing this key instead makes a change take effect on the next page render.
     *
     * Only settings that change the **pixels** belong here: [showEnhancementStatus] and the
     * prefetch-depth knobs must NOT be added, otherwise toggling them would throw away work.
     */
    fun enhancementCacheKey(): String = buildString {
        append(enhancementMode.get())
        append('|').append(lanczosScale.get())
        append('|').append(aiModelId.get())
        append('|').append(aiTileSize.get())
        append('|').append(cropBorders.get())
        append('|').append(cropBordersWebtoon.get())
    }
    // MihonSY image enhancement <--
    // MihonSY <--

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    // SY -->

    val readerThreads: Preference<Int> = preferenceStore.getInt("eh_reader_threads", 2)

    val readerInstantRetry: Preference<Boolean> = preferenceStore.getBoolean("eh_reader_instant_retry", true)

    val aggressivePageLoading: Preference<Boolean> = preferenceStore.getBoolean("eh_aggressive_page_loading", false)

    val cacheSize: Preference<String> = preferenceStore.getString("eh_cache_size", "75")

    val autoscrollInterval: Preference<Float> = preferenceStore.getFloat("eh_util_autoscroll_interval", 3f)

    val smoothAutoScroll: Preference<Boolean> = preferenceStore.getBoolean("smooth_auto_scroll", true)

    val preserveReadingPosition: Preference<Boolean> = preferenceStore.getBoolean("eh_preserve_reading_position", false)

    val preloadSize: Preference<Int> = preferenceStore.getInt("eh_preload_size", 10)

    val useAutoWebtoon: Preference<Boolean> = preferenceStore.getBoolean("eh_use_auto_webtoon", true)

    val continuousVerticalTappingByPage: Preference<Boolean> = preferenceStore.getBoolean("continuous_vertical_tapping_by_page", false)

    val cropBordersContinuousVertical: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_continues_vertical", false)

    val readerBottomButtons: Preference<Set<String>> = preferenceStore.getStringSet("reader_bottom_buttons", ReaderBottomButton.BUTTONS_DEFAULTS)

    val pageLayout: Preference<Int> = preferenceStore.getInt("page_layout", PagerConfig.PageLayout.AUTOMATIC)

    /**
     * Komiho：分页阅读的「预载页数」= ViewPager 离屏缓冲（当前页**前后各保留几页**）。
     *
     * 默认 [PagerConfig.OffscreenPages.DEFAULT]（1，保守、最省内存）。调大能让连翻 / 跳页更"即出"，
     * 代价是每档约多 2 页**已解码的增强位图**（双页一跨页增强后 4016×2880 ≈ 46MB），以及每页
     * 一次**推测性**的 GPU 推理（引擎只有一个、串行）。稳态阅读（每页停留 > 渲染耗时）看不出差别。
     */
    val pagerOffscreenLimit: Preference<Int> =
        preferenceStore.getInt("pref_pager_offscreen_limit", PagerConfig.OffscreenPages.DEFAULT)

    val invertDoublePages: Preference<Boolean> = preferenceStore.getBoolean("invert_double_pages", false)

    val centerMarginType: Preference<Int> = preferenceStore.getInt("center_margin_type", PagerConfig.CenterMarginType.NONE)

    val archiveReaderMode: Preference<Int> = preferenceStore.getInt("archive_reader_mode", ArchiveReaderMode.LOAD_FROM_FILE)
    // SY <--

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(MR.strings.tapping_inverted_horizontal, shouldInvertHorizontal = true),
        VERTICAL(MR.strings.tapping_inverted_vertical, shouldInvertVertical = true),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    object ArchiveReaderMode {
        const val LOAD_FROM_FILE = 0
        const val LOAD_INTO_MEMORY = 1
        const val CACHE_TO_DISK = 2
    }

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        const val MILLI_CONVERSION = 100

        // MihonSY -->
        const val WEBTOON_TAP_SCROLL_DISTANCE_DEFAULT = 1 // 0 = half, 1 = 3/4, 2 = full
        const val WEBTOON_TAP_SCROLL_DURATION_DEFAULT = 250
        const val WEBTOON_TAP_SCROLL_DURATION_MIN = 0
        const val WEBTOON_TAP_SCROLL_DURATION_MAX = 1000

        val WebtoonTapScrollDistance = listOf(
            MR.strings.webtoon_tap_scroll_half,
            MR.strings.webtoon_tap_scroll_three_quarter,
            MR.strings.webtoon_tap_scroll_full,
        )

        val WebtoonTapScrollFractions = floatArrayOf(0.5f, 0.75f, 1.0f)

        // Komiho: webtoon 预取深度档位（倍数）。1 = 当前原版行为，上限 3。
        const val WEBTOON_PREFETCH_DEPTH_MIN = 1
        const val WEBTOON_PREFETCH_DEPTH_MAX = 3
        const val WEBTOON_PREFETCH_DEPTH_DEFAULT = 1
        val WebtoonPrefetchDepth = listOf(
            MR.strings.webtoon_prefetch_1,
            MR.strings.webtoon_prefetch_2,
            MR.strings.webtoon_prefetch_3,
        )

        // Komiho 翻页动画 v2 的速度档位：每屏基准时长（ms），越小越快。
        // 实际时长 = (|距离| / 可视高度 + 1) × 该值，因此同一档位下距离越长越慢。
        val PageTransitionsV2Speeds = listOf(50, 100, 150, 200)
        const val PAGE_TRANSITIONS_V2_SPEED_DEFAULT = 100

        // MihonSY image enhancement -->
        // index: 0 Off / 1 Anime4K (disabled) / 2 Lanczos3 / 3 Catmull-Rom / 4 Spline36 (disabled)
        //        / 5 AI upscale (Komiho: ncnn + Vulkan, fixed 2x model)
        val EnhancementModes = listOf(
            MR.strings.enhancement_off,
            MR.strings.enhancement_anime4k, // retained for backward-compatible stored values; hidden in UI
            MR.strings.enhancement_lanczos3,
            MR.strings.enhancement_catmull_rom,
            MR.strings.enhancement_spline36, // retained for backward-compatible stored values; hidden in UI
            // Komiho: GPU AI upscale. Scale is baked into the model (2x), so no scale picker.
            MR.strings.enhancement_ai_upscale,
        )

        // MihonSY: Anime4K disabled — quality list no longer referenced anywhere.
        // val Anime4kModes = listOf(
        //     MR.strings.anime4k_mode_fast,
        //     MR.strings.anime4k_mode_high,
        //     MR.strings.anime4k_mode_ultra,
        // )

        val LanczosScaleOptions = listOf(
            150 to MR.strings.lanczos_scale_1_5x,
            200 to MR.strings.lanczos_scale_2x,
            250 to MR.strings.lanczos_scale_2_5x,
            300 to MR.strings.lanczos_scale_3x,
        )

        /**
         * Komiho: AI tile edge options. 128 is the native default (`waifu2x.cpp:150`);
         * 256 is the largest value the bundled `prepadding = 18` is documented safe for.
         */
        val AiTileSizeOptions = listOf(
            96 to MR.strings.ai_tile_size_96,
            128 to MR.strings.ai_tile_size_128,
            192 to MR.strings.ai_tile_size_192,
            256 to MR.strings.ai_tile_size_256,
        )

        /**
         * Komiho: CPU-side modes as an explicit (flag → label) table for the grouped picker.
         * Flags match the values stored in [enhancementMode]; [EnhancementModes] stays as the
         * full index map so old stored values keep resolving.
         */
        val CpuEnhancementModes = listOf(
            2 to MR.strings.enhancement_lanczos3,
            3 to MR.strings.enhancement_catmull_rom,
        )
        // MihonSY image enhancement <--
        // MihonSY <--

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }

        // SY -->
        val PageLayouts = listOf(
            SYMR.strings.single_page,
            SYMR.strings.double_pages,
            SYMR.strings.automatic_orientation,
        )

        val CenterMarginTypes = listOf(
            SYMR.strings.center_margin_none,
            SYMR.strings.center_margin_double_page,
            SYMR.strings.center_margin_wide_page,
            SYMR.strings.center_margin_double_and_wide_page,
        )

        val archiveModeTypes = listOf(
            SYMR.strings.archive_mode_load_from_file,
            SYMR.strings.archive_mode_load_into_memory,
            SYMR.strings.archive_mode_cache_to_disk,
        )
        // SY <--
    }
}
