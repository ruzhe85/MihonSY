package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTheme

/**
 * 纸化背景的页面纹理叠加。
 *
 * 纸档（羊皮纸 / 牛皮纸）下，页面除了颜色坡道（`ReaderActivity` 在 viewer 硬件层上叠的
 * `ColorMatrix`），还要**乘一层纸纹**才有纸的颗粒感 —— 只过坡道等于一层变色滤镜，白场漫画看
 * 上去是平的。纸纹均值 = 档位纸色（[ReaderTheme.PaperStyle.whiteColor]），所以页面白场乘完
 * 后与容器 letterbox 的纸纹衬底同色衔接。
 *
 * 由 `ReaderActivity` 在背景档位变化时调用 [update] 刷新；页面 View 在 `dispatchDraw` 里直接
 * 取用，随页面重绘自然生效（不进解码通道，不改任何位图输出）。
 */
object PaperPageTexture {

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var bitmap: Bitmap? = null

    /** saveLayer 的合成画笔：层回贴时以 MULTIPLY 与页面内容混合。 */
    private val layerPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    /** 纸纹本身的画笔：层内以 SRC_OVER 平铺绘制。 */
    private val texturePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val decoded = mutableMapOf<Int, Bitmap>()

    /**
     * 按阅读器背景档位切换页面纸纹。非纸档位直接关闭。
     * 纸纹位图按资源 id 缓存，来回切换不重复解码。
     */
    fun update(context: Context, theme: Int) {
        val res = when (theme) {
            ReaderTheme.PARCHMENT -> R.drawable.reader_bg_parchment_tile
            ReaderTheme.KRAFT -> R.drawable.reader_bg_kraft_tile
            else -> null
        }
        if (res == null) {
            enabled = false
            bitmap = null
            texturePaint.shader = null
            return
        }
        val bmp = decoded.getOrPut(res) {
            BitmapFactory.decodeResource(context.resources, res)
        }
        bitmap = bmp
        texturePaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        enabled = true
    }

    /**
     * 在页面内容之上乘一层纸纹。未启用或边界非法时不做任何事。
     *
     * 用 saveLayer 承载 MULTIPLY：直接把带 PorterDuff 的画笔画到硬件加速画布上，在部分设备上
     * 不被支持；套一层离屏层再回贴是全版本可靠路径。
     */
    fun drawOver(canvas: Canvas, width: Int, height: Int) {
        if (!enabled || bitmap == null || width <= 0 || height <= 0) return
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), layerPaint)
        canvas.drawPaint(texturePaint)
        canvas.restoreToCount(count)
    }
}
