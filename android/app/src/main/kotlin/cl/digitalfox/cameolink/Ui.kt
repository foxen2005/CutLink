package cl.digitalfox.cameolink

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
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

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

private val ES_CL: Locale = Locale.forLanguageTag("es-CL")

/** Número con coma decimal, como en el panel de Illustrator: 210,0 */
fun nf(v: Double, decimals: Int = 1): String = String.format(ES_CL, "%.${decimals}f", v)
