package cl.digitalfox.cameolink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Visor/editor: soporte (mat con cuadrícula de 1" u hoja), imágenes del studio3 debajo, líneas de
 * corte encima, selección con asas. 1 dedo = tocar/mover/escalar/girar (o desplazar en vacío),
 * 2 dedos = zoom. Las coordenadas del documento son mm; [s], [ox], [oy] las llevan a pantalla.
 */
class CanvasView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    interface Listener {
        fun onSelectionChanged()
        fun onEdited()                          // terminó un gesto que cambió el documento
    }

    var listener: Listener? = null
    var doc: Doc? = null
        set(v) { field = v; post { fit() } }
    var support: Support? = null
        set(v) { field = v; invalidate() }
    var multi = false                           // selección múltiple (tocar suma/quita; arrastrar en vacío = recuadro)
    var lockAspect = true
    /** Ids de las formas en el orden en que se mandaron a la máquina (null = no hay corte). */
    var cutOrder: List<Int>? = null
        set(v) { field = v; invalidate() }
    /** (posición en [cutOrder], fracción de esa forma) que se está cortando. */
    var cutAt: Pair<Int, Double> = 0 to 0.0
        set(v) { field = v; invalidate() }
    val cutProgress get() = cutOrder
    /** Print & Cut activo: se dibujan las marcas en la hoja (misma geometría que el panel). */
    var regmarks: Gpgl.Regmarks? = null
        set(v) { field = v; invalidate() }
    var hud: String? = null                     // texto junto a la selección durante un gesto
        private set

    private var s = 2f
    private var ox = 0f
    private var oy = 0f
    private val dp = ctx.dp(1f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9A9A.toInt(); textSize = 12 * dp }
    private val dash = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    private val path = Path()

    private val cAccent = 0xFF00B8D9.toInt()
    private val cAccentPaper = 0xFF00809A.toInt()
    private val cDanger = 0xFFE0218A.toInt()
    private val cSel = 0xFFF0B429.toInt()

    // ------------------------------------------------------------------ vista
    fun fit() {
        val sup = support ?: return
        val d = doc
        val sw = if (sup.usesMat) Support.MAT_MM else sup.w
        val sh = if (sup.usesMat) sup.matH else sup.h
        val b = d?.cutBBox()
        val ew = max(sw, b?.get(2) ?: 0.0).toFloat()
        val eh = max(sh, b?.get(3) ?: 0.0).toFloat()
        if (width == 0 || height == 0) return
        // deja libre la columna de herramientas (derecha, 140 dp) y las barras de arriba/abajo
        val freeW = width - 24 * dp - 140 * dp
        s = min(freeW / ew, (height - 120 * dp) / eh)
        ox = 12 * dp + (freeW - ew * s) / 2
        oy = 64 * dp + (height - 120 * dp - eh * s) / 2
        invalidate()
    }

    fun zoomBy(f: Float, cx: Float = width / 2f, cy: Float = height / 2f) {
        val ns = (s * f).coerceIn(0.3f, 80f)
        val k = ns / s
        ox = cx - (cx - ox) * k
        oy = cy - (cy - oy) * k
        s = ns
        invalidate()
    }

    private fun sx(x: Double) = (ox + x * s).toFloat()
    private fun sy(y: Double) = (oy + y * s).toFloat()
    private fun wx(px: Float) = ((px - ox) / s).toDouble()
    private fun wy(py: Float) = ((py - oy) / s).toDouble()

    // ------------------------------------------------------------------ dibujo
    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF181818.toInt())
        val sup = support ?: return
        val d = doc ?: return
        val onPaper = !sup.mat

        // mat (gris con cuadrícula de 1") y/o la hoja
        if (sup.usesMat) {
            fill.color = 0xFF34393B.toInt()
            c.drawRect(sx(0.0), sy(0.0), sx(Support.MAT_MM), sy(sup.matH), fill)
            stroke.color = 0xFF454C4F.toInt(); stroke.strokeWidth = 1f; stroke.pathEffect = null
            var g = 25.4
            while (g < Support.MAT_MM) { c.drawLine(sx(g), sy(0.0), sx(g), sy(sup.matH), stroke); g += 25.4 }
            g = 25.4
            while (g < sup.matH) { c.drawLine(sx(0.0), sy(g), sx(Support.MAT_MM), sy(g), stroke); g += 25.4 }
        }
        if (onPaper) {
            fill.color = 0xFFF3F2EE.toInt()
            c.drawRect(sx(0.0), sy(0.0), sx(sup.w), sy(sup.h), fill)
        }

        // imágenes (cuadrado unitario −1…1 -> mm -> pantalla)
        val selIds = if (cutProgress == null) d.selItems().map { it.id }.toSet() else emptySet()
        for (it in d.items) {
            if (!it.isImage) continue
            val bmp = it.bitmap ?: continue
            val w = A.mul(it.t, it.m!!)
            val px = doubleArrayOf(2.0 / bmp.width, 0.0, 0.0, 2.0 / bmp.height, -1.0, -1.0)
            val full = A.mul(doubleArrayOf(s.toDouble(), 0.0, 0.0, s.toDouble(), ox.toDouble(), oy.toDouble()), A.mul(w, px))
            val mx = Matrix()
            mx.setValues(floatArrayOf(full[0].toFloat(), full[2].toFloat(), full[4].toFloat(),
                full[1].toFloat(), full[3].toFloat(), full[5].toFloat(), 0f, 0f, 1f))
            c.drawBitmap(bmp, mx, bmpPaint)
            if (it.id in selIds) drawPoly(c, it.world(), true, cSel, 1.5f * dp)
        }

        // página del archivo si no coincide con el soporte
        if (d.kind != "nuevo" && (abs(d.pageW - sup.w) > 0.5 || abs(d.pageH - sup.h) > 0.5)) {
            stroke.color = if (onPaper) 0xFF8A877F.toInt() else 0xFF7D8A8F.toInt()
            stroke.strokeWidth = 1f; stroke.pathEffect = dash
            c.drawRect(sx(0.0), sy(0.0), sx(d.pageW), sy(d.pageH), stroke)
            stroke.pathEffect = null
        }

        // marcas de registro: cuadrado 5 mm arriba-izq, L de 20 mm arriba-der y abajo-izq (cameoAddRegmarks)
        regmarks?.let { r ->
            if (!sup.mat) {
                fill.color = 0xFF111111.toInt()
                for ((x, y, w, h) in Gpgl.regmarkRects(r)) c.drawRect(sx(x), sy(y), sx(x + w), sy(y + h), fill)
                stroke.color = 0x40000000; stroke.strokeWidth = 1f; stroke.pathEffect = dash
                // área de trabajo: lo interno de las marcas, con 5 mm de resguardo
                c.drawRect(sx(r.inset + 5), sy(r.inset + 5), sx(r.pageW - r.inset - 5), sy(r.pageH - r.bottom - 5), stroke)
                stroke.pathEffect = null
            }
        }

        // líneas de corte
        val lineCol = if (onPaper) cAccentPaper else cAccent
        val order = cutOrder
        for (it in d.items) {
            if (it.isImage) continue
            val w = it.world()
            val out = outside(w, sup)
            var col = when { it.id in selIds -> cSel; out -> cDanger; else -> lineCol }
            var width = if (it.id in selIds) 2.4f * dp else 1.6f * dp
            var n = w.size / 2
            if (order != null) {
                val pos = order.indexOf(it.id)
                when {
                    pos < 0 -> {}                                                    // no se corta (fuera del soporte)
                    pos < cutAt.first -> col = if (onPaper) 0xFF9CC9D2.toInt() else 0xFF2C6A77.toInt()
                    pos == cutAt.first -> { col = if (onPaper) 0xFF004F5F.toInt() else 0xFF7FE6F7.toInt(); width = 3f * dp
                        n = max(2, (n * cutAt.second).roundToInt()) }
                    else -> col = if (onPaper) 0xFFC9C6BE.toInt() else 0xFF555566.toInt()
                }
            }
            drawPoly(c, w, false, col, width, n)
        }

        // origen de la cuchilla
        fill.color = cAccent
        c.drawCircle(sx(0.0), sy(0.0), 5 * dp, fill)
        c.drawText("0,0 origen", sx(0.0) + 8 * dp, sy(0.0) - 8 * dp, text)
        drawSelection(c, d)
    }

    private fun drawPoly(c: Canvas, w: DoubleArray, close: Boolean, color: Int, width: Float, n: Int = w.size / 2) {
        if (w.size < 4) return
        path.reset()
        path.moveTo(sx(w[0]), sy(w[1]))
        for (i in 1 until min(n, w.size / 2)) path.lineTo(sx(w[2 * i]), sy(w[2 * i + 1]))
        if (close) path.close()
        stroke.color = color; stroke.strokeWidth = width; stroke.pathEffect = null
        c.drawPath(path, stroke)
    }

    private fun drawSelection(c: Canvas, d: Doc) {
        op?.let { o ->
            if (o.type == OpType.MARQUEE) {
                fill.color = 0x14F0B429; stroke.color = cSel; stroke.strokeWidth = 1f; stroke.pathEffect = dash
                val l = sx(min(o.ax, o.bx)); val t = sy(min(o.ay, o.by)); val r = sx(max(o.ax, o.bx)); val b = sy(max(o.ay, o.by))
                c.drawRect(l, t, r, b, fill); c.drawRect(l, t, r, b, stroke); stroke.pathEffect = null
            }
        }
        if (d.sel.isEmpty() || cutProgress != null) return
        val hs = handles(d) ?: return
        val (nw, ne, se) = Triple(hs[0], hs[1], hs[2])
        stroke.color = cSel; stroke.strokeWidth = 1f; stroke.pathEffect = dash
        c.drawRect(nw.x, nw.y, se.x, se.y, stroke); stroke.pathEffect = null
        c.drawLine((nw.x + ne.x) / 2, nw.y, hs[4].x, hs[4].y, stroke)
        for (h in hs) {
            fill.color = 0xFF1E1E1E.toInt(); stroke.strokeWidth = 2 * dp
            if (h.kind == "rot") { c.drawCircle(h.x, h.y, 11 * dp, fill); c.drawCircle(h.x, h.y, 11 * dp, stroke) }
            else { c.drawRect(h.x - 9 * dp, h.y - 9 * dp, h.x + 9 * dp, h.y + 9 * dp, fill); c.drawRect(h.x - 9 * dp, h.y - 9 * dp, h.x + 9 * dp, h.y + 9 * dp, stroke) }
        }
        text.color = cSel
        c.drawText("↻", hs[4].x - 5 * dp, hs[4].y + 5 * dp, text)
        hud?.let { c.drawText(it, se.x + 14 * dp, se.y + 4 * dp, text) }
        text.color = 0xFF9A9A9A.toInt()
    }

    private fun outside(w: DoubleArray, sup: Support): Boolean {
        var i = 0
        while (i < w.size) {
            if (w[i] < -0.05 || w[i + 1] < -0.05 || w[i] > sup.w + 0.05 || w[i + 1] > sup.h + 0.05) return true
            i += 2
        }
        return false
    }

    fun isOutside(it: Item): Boolean {
        val sup = support ?: return false
        return outside(it.world(), sup)
    }

    // ------------------------------------------------------------------ asas
    private class Handle(val kind: String, val x: Float, val y: Float)

    private fun handles(d: Doc): List<Handle>? {
        if (d.sel.isEmpty()) return null
        val b = d.selBBox()
        val l = sx(b[0]); val t = sy(b[1]); val r = sx(b[2]); val bt = sy(b[3])
        return listOf(Handle("nw", l, t), Handle("ne", r, t), Handle("se", r, bt), Handle("sw", l, bt),
            Handle("rot", (l + r) / 2, t - 34 * dp))
    }

    // ------------------------------------------------------------------ gestos
    private enum class OpType { PAN, MOVE, SCALE, ROT, MARQUEE, PINCH }

    private class Op(val type: OpType) {
        var lastX = 0f; var lastY = 0f
        var ax = 0.0; var ay = 0.0; var bx = 0.0; var by = 0.0
        var handle = ""
        var bb = DoubleArray(4)
        var base: Map<Int, Aff> = emptyMap()
        var undo: Any? = null
        var moved = false
        var pinchDist = 0f; var pinchMidX = 0f; var pinchMidY = 0f
    }

    private var op: Op? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = doc ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(d, e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                // segundo dedo: el gesto en curso se confirma y pasa a zoom
                commit(d)
                op = Op(OpType.PINCH).apply {
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    pinchMidX = (e.getX(0) + e.getX(1)) / 2; pinchMidY = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> move(d, e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { commit(d); op = null; hud = null; invalidate() }
            MotionEvent.ACTION_POINTER_UP -> if (op?.type == OpType.PINCH) op = null
        }
        return true
    }

    private fun down(d: Doc, x: Float, y: Float) {
        hud = null
        if (cutProgress != null) { op = Op(OpType.PAN).apply { lastX = x; lastY = y }; return }
        val hs = handles(d)
        val h = hs?.firstOrNull { hypot(it.x - x, it.y - y) < 22 * dp }
        if (h != null) {
            op = Op(if (h.kind == "rot") OpType.ROT else OpType.SCALE).apply {
                handle = h.kind; ax = wx(x); ay = wy(y); bb = d.selBBox(); base = d.baseT(); undo = d.snapshot()
            }
            return
        }
        val it = d.hit(wx(x), wy(y), 12 * dp / s.toDouble())
        if (it != null) {
            val k = d.keyOf(it)
            if (multi) { if (!d.sel.remove(k)) d.sel += k }
            else if (k !in d.sel) { d.sel.clear(); d.sel += k }
            op = if (k in d.sel) Op(OpType.MOVE).apply { ax = wx(x); ay = wy(y); base = d.baseT(); undo = d.snapshot() } else null
            listener?.onSelectionChanged(); invalidate()
            return
        }
        if (multi) op = Op(OpType.MARQUEE).apply { ax = wx(x); ay = wy(y); bx = ax; by = ay }
        else {
            if (d.sel.isNotEmpty()) { d.sel.clear(); listener?.onSelectionChanged() }
            op = Op(OpType.PAN).apply { lastX = x; lastY = y }
            invalidate()
        }
    }

    private fun move(d: Doc, e: MotionEvent) {
        val o = op ?: return
        val x = e.x; val y = e.y
        when (o.type) {
            OpType.PINCH -> if (e.pointerCount >= 2) {
                val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                if (o.pinchDist > 0) zoomBy(dist / o.pinchDist, mx, my)
                ox += mx - o.pinchMidX; oy += my - o.pinchMidY
                o.pinchDist = dist; o.pinchMidX = mx; o.pinchMidY = my
            }
            OpType.PAN -> { ox += x - o.lastX; oy += y - o.lastY; o.lastX = x; o.lastY = y }
            OpType.MARQUEE -> { o.bx = wx(x); o.by = wy(y) }
            OpType.MOVE -> {
                val dx = wx(x) - o.ax; val dy = wy(y) - o.ay
                if (!o.moved && hypot(dx, dy) * s < 6 * dp) return      // un toque no mueve
                o.moved = true
                d.apply(A.tr(dx, dy), o.base)
                hud = "Δ ${nf(dx)} · ${nf(dy)} mm"
            }
            OpType.SCALE -> {
                val b = o.bb
                val anc = when (o.handle) { "nw" -> b[2] to b[3]; "ne" -> b[0] to b[3]; "se" -> b[0] to b[1]; else -> b[2] to b[1] }
                val c0 = when (o.handle) { "nw" -> b[0] to b[1]; "ne" -> b[2] to b[1]; "se" -> b[2] to b[3]; else -> b[0] to b[3] }
                val vx = c0.first - anc.first; val vy = c0.second - anc.second
                var ssx = (wx(x) - anc.first) / (if (vx != 0.0) vx else 1.0)
                var ssy = (wy(y) - anc.second) / (if (vy != 0.0) vy else 1.0)
                if (lockAspect) { val k = ((wx(x) - anc.first) * vx + (wy(y) - anc.second) * vy) / (vx * vx + vy * vy).coerceAtLeast(1e-9); ssx = k; ssy = k }
                ssx = ssx.coerceAtLeast(0.05); ssy = ssy.coerceAtLeast(0.05)
                d.apply(A.sc(ssx, ssy, anc.first, anc.second), o.base)
                o.moved = true
                hud = "${nf((b[2] - b[0]) * ssx)} × ${nf((b[3] - b[1]) * ssy)} mm"
            }
            OpType.ROT -> {
                val b = o.bb; val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
                var ang = angleDeg(cx, cy, wx(x), wy(y)) - angleDeg(cx, cy, o.ax, o.ay)
                val snap = Math.round(ang / 15.0) * 15.0
                if (abs(ang - snap) < 3) ang = snap                        // imán cada 15°
                d.apply(A.rot(ang, cx, cy), o.base)
                o.moved = true
                hud = "${nf(((ang % 360) + 360) % 360, 0)}°"
            }
        }
        invalidate()
    }

    private fun commit(d: Doc) {
        val o = op ?: return
        when (o.type) {
            OpType.MARQUEE -> {
                val l = min(o.ax, o.bx); val t = min(o.ay, o.by); val r = max(o.ax, o.bx); val b = max(o.ay, o.by)
                for (it in d.items) { val q = it.bbox(); if (q[0] >= l && q[1] >= t && q[2] <= r && q[3] <= b) d.sel += d.keyOf(it) }
                listener?.onSelectionChanged()
            }
            OpType.MOVE, OpType.SCALE, OpType.ROT -> if (o.moved) { d.pushUndo(o.undo!!); listener?.onEdited() }
            else -> {}
        }
        op = null
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }
}
