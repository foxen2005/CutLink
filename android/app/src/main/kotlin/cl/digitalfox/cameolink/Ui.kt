package cl.digitalfox.cameolink

import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

/** targetSdk 36 dibuja bajo las barras del sistema: se rellenan a mano. */
fun applySystemInsets(root: View) {
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
}

/** Aviso breve con la tinta de la app, arriba al centro. */
fun Activity.pill(msg: String) {
    val host = findViewById<FrameLayout>(android.R.id.content)
    host.findViewWithTag<View>("pill")?.let { host.removeView(it) }
    val v = TextView(this).apply {
        tag = "pill"; text = msg; textSize = 14f; gravity = Gravity.CENTER; maxLines = 2
        setTextColor(0xFFFFFFFF.toInt())
        typeface = resources.getFont(R.font.barlow_semibold)
        setBackgroundResource(R.drawable.bg_pill_ink)
        setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
        alpha = 0f
    }
    host.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
        topMargin = (findViewById<View>(R.id.root)?.paddingTop ?: 0) + dp(66f).toInt()
        marginStart = dp(24f).toInt(); marginEnd = dp(24f).toInt()
    })
    v.animate().alpha(1f).setDuration(160).start()
    v.postDelayed({ host.removeView(v) }, 2600)
}

/** Velo índigo detrás de un diálogo: se llama desde onWindowFocusChanged. */
fun Activity.veil(on: Boolean) {
    val host = findViewById<FrameLayout>(android.R.id.content)
    val cur = host.findViewWithTag<View>("veil")
    if (!on) { cur?.let { host.removeView(it) }; return }
    if (cur != null) return
    val v = View(this).apply { tag = "veil"; setBackgroundColor(0x57001876); alpha = 0f }
    host.addView(v, FrameLayout.LayoutParams(-1, -1))
    v.animate().alpha(1f).setDuration(160).start()
}

/** Decimales con coma, como se muestran; el punto también vale. El teclado sigue siendo numérico. */
fun EditText.commaDecimal() {
    keyListener = DigitsKeyListener.getInstance("0123456789,.-")
    setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

private val ES_CL: Locale = Locale.forLanguageTag("es-CL")

/** Número con coma decimal, como en el panel de Illustrator: 210,0 */
fun nf(v: Double, decimals: Int = 1): String = String.format(ES_CL, "%.${decimals}f", v)
