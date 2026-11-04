package cl.digitalfox.cameolink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator

/** Qué tan juntas están las dos planchas: lista = en registro, ocupada = buscando, fuera = sin máquina. */
enum class Register { READY, BUSY, OFF }

/**
 * Dos planchas, cian y magenta, multiplicadas: donde coinciden sale el índigo del resto de la app. El
 * estado de la máquina se lee en qué tan separadas están.
 */
abstract class PlateView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val cyan = ctx.getColor(R.color.cyan)
    private val magenta = ctx.getColor(R.color.mag)
    private var apart = 1f                      // 0 = en registro, 1 = separación completa
    private var anim: ValueAnimator? = null

    var state: Register? = null
        set(v) {
            if (v == field) return
            field = v
            anim?.cancel()
            anim = when (v) {
                Register.BUSY -> ValueAnimator.ofFloat(1f, 0.2f).apply {
                    duration = 1100; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                }
                else -> ValueAnimator.ofFloat(apart, if (v == Register.READY) 0f else 1f).apply {
                    duration = 460; interpolator = DecelerateInterpolator(3f)
                }
            }.apply {
                addUpdateListener { apart = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    /** Separación completa de cada plancha, en px. */
    protected abstract val reach: Float
    protected abstract fun drawPlate(c: Canvas)

    override fun onDraw(c: Canvas) {
        val dx = reach * apart; val dy = dx * 0.8f
        c.save(); c.translate(-dx, -dy); paint.color = cyan; drawPlate(c); c.restore()
        c.save(); c.translate(dx, dy); paint.color = magenta; drawPlate(c); c.restore()
    }

    // la animación de «ocupada» es infinita: no debe correr con la pantalla tapada ni perderse al volver
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) anim?.resume() else anim?.pause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state == Register.BUSY) anim?.start()
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}

/** Cruz de registro. */
class RegisterMark @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : PlateView(ctx, attrs) {
    override val reach get() = width * 0.115f

    override fun drawPlate(c: Canvas) {
        val w = width.toFloat(); val m = w / 2
        // deja lugar para la separación: la cruz ocupa el 80 % del cuadro
        val half = w * 0.40f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (w * 0.075f).coerceIn(2f, 4f * resources.displayMetrics.density)
        c.drawCircle(m, m, w * 0.235f, paint)
        c.drawLine(m - half, m, m + half, m, paint)
        c.drawLine(m, m - half, m, m + half, paint)
    }
}

/** La marca «Cameo Link». */
class RegisterText @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : PlateView(ctx, attrs) {
    private val label = ctx.getString(R.string.app_name)
    override val reach get() = 1.5f * resources.displayMetrics.density

    init {
        paint.typeface = ctx.resources.getFont(R.font.barlow_bold)
        paint.textSize = ctx.dp(1f) * 20
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((paint.measureText(label) + 2 * reach + 2).toInt(), (paint.fontSpacing + 2 * reach).toInt())
    }

    override fun drawPlate(c: Canvas) {
        paint.style = Paint.Style.FILL
        val fm = paint.fontMetrics
        c.drawText(label, reach, height / 2f - (fm.ascent + fm.descent) / 2, paint)
    }
}

/** Regla de escala: un tramo redondo en mm al zoom actual. */
class ScaleBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = ctx.dp(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.ink_2); textSize = 12 * d; typeface = ctx.resources.getFont(R.font.barlow_medium)
    }
    private var bar = 0f
    private var label = ""

    /** [pxPerMm] de la vista: elige el tramo más largo que entra en 60 dp. */
    fun update(pxPerMm: Float) {
        val mm = listOf(100, 50, 20, 10, 5, 2, 1).firstOrNull { it * pxPerMm <= 60 * d } ?: 1
        bar = (mm * pxPerMm).coerceAtMost(60 * d)
        label = "$mm mm"
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((64 * d).toInt(), (32 * d).toInt())

    override fun onDraw(c: Canvas) {
        val y = 9 * d
        c.drawRect(0f, y - d, bar, y + d, paint)
        c.drawRect(0f, y - 4 * d, 1.5f * d, y + 4 * d, paint)
        c.drawRect(bar - 1.5f * d, y - 4 * d, bar, y + 4 * d, paint)
        c.drawText(label, 0f, 28 * d, paint)
    }
}
