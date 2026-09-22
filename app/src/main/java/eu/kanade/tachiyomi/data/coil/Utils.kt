package eu.kanade.tachiyomi.data.coil

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse

internal inline fun Size.widthPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else width.toPx(scale)
}

internal inline fun Size.heightPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else height.toPx(scale)
}

internal fun Dimension.toPx(scale: Scale): Int = pxOrElse {
    when (scale) {
        Scale.FILL -> Int.MIN_VALUE
        Scale.FIT -> Int.MAX_VALUE
    }
}

fun ImageRequest.Builder.cropBorders(enable: Boolean) = apply {
    extras[cropBordersKey] = enable
}

val Options.cropBorders: Boolean
    get() = getExtra(cropBordersKey)

private val cropBordersKey = Extras.Key(default = false)

fun ImageRequest.Builder.customDecoder(enable: Boolean) = apply {
    extras[customDecoderKey] = enable
}

val Options.customDecoder: Boolean
    get() = getExtra(customDecoderKey)

private val customDecoderKey = Extras.Key(default = false)

// MihonSY: signal the decoder to run Lanczos3 enhancement on the decoded bitmap.
fun ImageRequest.Builder.enhanced(enable: Boolean) = apply {
    extras[enhancedKey] = enable
}

val Options.enhanced: Boolean
    get() = getExtra(enhancedKey)

private val enhancedKey = Extras.Key(default = false)

// Komiho: 诊断用 —— 标记该请求来自「预载（prewarm）」还是「当前页 holder」。
// PagerPagePreparer 是唯一会置 true 的地方；其余（ReaderPageImageView）保持默认 false。
fun ImageRequest.Builder.prewarm(enable: Boolean) = apply {
    extras[prewarmKey] = enable
}

val Options.prewarm: Boolean
    get() = getExtra(prewarmKey)

private val prewarmKey = Extras.Key(default = false)

// Komiho: 诊断用 —— 请求所属页的序号（-1 = 未知），用于判断并发请求是否落在同一页。
fun ImageRequest.Builder.pageIndex(index: Int) = apply {
    extras[pageIndexKey] = index
}

val Options.pageIndex: Int
    get() = getExtra(pageIndexKey)

private val pageIndexKey = Extras.Key(default = -1)
