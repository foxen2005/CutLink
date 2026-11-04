package cl.digitalfox.cameolink

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Visor/editor: soporte (mat con cuadrícula de 1" u hoja), imágenes y líneas de corte en el orden de
 * su pila, selección con asas. 1 dedo = tocar/mover/escalar/girar (o desplazar en vacío),
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
        set(v) { field = v; orderPos = v?.withIndex()?.associate { (i, id) -> id to i }; invalidate() }
    private var orderPos: Map<Int, Int>? = null
    /** (posición en [cutOrder], fracción de esa forma) que se está cortando. */
    var cutAt: Pair<Int, Double> = 0 to 0.0
        set(v) { field = v; invalidate() }
    val cutProgress get() = cutOrder
    /** Print & Cut activo: se dibujan las marcas en la hoja (misma geometría que el panel). */
    var regmarks: Gpgl.Regmarks? = null
        set(v) { field = v; invalidate() }
    var hud: String? = null                     // texto junto a la selección durante un gesto
        private set
    /** Avisa la escala (px por mm) cuando cambia el zoom. */
    var onScale: ((Float) -> Unit)? = null
    private var shownScale = 0f
    // las dos planchas de las líneas: separadas mientras la máquina busca las marcas (px)
    private var plate = 0f
    private var plateAnim: ValueAnimator? = null

    private var s = 2f
    private var ox = 0f
    private var oy = 0f
    private val dp = ctx.dp(1f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.ink); textSize = 13 * dp; typeface = ctx.resources.getFont(R.font.barlow_semibold)
    }
    private val dash = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    private val hidden = DashPathEffect(floatArrayOf(5 * dp, 4 * dp), 0f)
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    private val path = Path()
    private val rotIcon = ctx.getDrawable(R.drawable.ic_rotate)!!.apply { state = intArrayOf(android.R.attr.state_enabled) }

    private val cInk = ctx.getColor(R.color.ink)
    private val cCyan = ctx.getColor(R.color.cyan)
    private val cMag = ctx.getColor(R.color.mag)
    private val cTable = ctx.getColor(R.color.cyan_20)
    private val cSheet = ctx.getColor(R.color.sheet)
    private val cMat = 0xFFF1F9FB.toInt()
    private val cMatGrid = 0x21001876
    private val cFrame = 0x73001876

    // mesa: trama de cian a 45°
    private val table = Paint().apply {
        val n = (14 * dp).roundToInt().coerceAtLeast(4)
        val tile = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val g = Canvas(tile)
        g.drawColor(cTable)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAEE9F3.toInt() }
        for ((x, y) in listOf(0f to 0f, n.toFloat() to 0f, 0f to n.toFloat(), n.toFloat() to n.toFloat(), n / 2f to n / 2f))
            g.drawCircle(x, y, 1.5f * dp, p)
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
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
        // deja libre la rejilla de herramientas (derecha) y las barras de arriba y abajo
        // en una pantalla angosta la hoja manda: sin este piso la escala sale chica o negativa
        val freeW = max(width - 24 * dp - 192 * dp, width * 0.6f)
        val freeH = max(height - 124 * dp, height * 0.6f)
        s = min(freeW / ew, freeH / eh).coerceAtLeast(0.3f)
        ox = 12 * dp + (freeW - ew * s) / 2
        oy = 62 * dp + (freeH - eh * s) / 2
        invalidate()
    }

    // abrir o cerrar el panel cambia el ancho; el teclado cambia solo el alto y no debe reencuadrar
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) fit()
    }

    /** La máquina empezó a buscar las marcas: las líneas se separan en sus dos planchas. */
    fun separatePlates() { plateAnim?.cancel(); plate = 6 * dp; invalidate() }

    /** Encontró las marcas: las planchas cierran el registro y la línea vuelve a ser índigo. */
    fun closeRegister() {
        plateAnim?.cancel()
        plateAnim = ValueAnimator.ofFloat(plate, 0f).apply {
            duration = 460; interpolator = DecelerateInterpolator(3f)
            addUpdateListener { plate = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Sin animación: el corte falló antes de leer las marcas. */
    fun resetPlates() { plateAnim?.cancel(); plate = 0f; invalidate() }

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
        c.drawPaint(table)
        val sup = support ?: return
        val d = doc ?: return
        if (s != shownScale) { shownScale = s; onScale?.invoke(s) }
        val onPaper = !sup.mat

        // mat (claro con cuadrícula de 1") y/o la hoja
        if (sup.usesMat) {
            drawSupport(c, Support.MAT_MM, sup.matH, cMat)
            stroke.color = cMatGrid; stroke.strokeWidth = 1f; stroke.pathEffect = null
            var g = 25.4
            while (g < Support.MAT_MM) { c.drawLine(sx(g), sy(0.0), sx(g), sy(sup.matH), stroke); g += 25.4 }
            g = 25.4
            while (g < sup.matH) { c.drawLine(sx(0.0), sy(g), sx(Support.MAT_MM), sy(g), stroke); g += 25.4 }
        }
        if (onPaper) drawSupport(c, sup.w, sup.h, cSheet)

        // la pila: imágenes y líneas en su orden
        val selIds = if (cutProgress == null) d.selItems().map { it.id }.toSet() else emptySet()
        val split = plate > 0.05f
        for (it in d.items) {
            if (it.isImage) { drawImage(c, it, it.id in selIds); continue }
            if (!split) drawLine(c, it, sup, it.id in selIds)
        }
        if (split) drawPlates(c, d)
        else drawCovered(c, d, sup, selIds)

        // página del archivo si no coincide con el soporte
        // (con marcas no: el punteado es el área de trabajo y dos rectángulos confunden)
        if (d.kind != "nuevo" && regmarks == null && (abs(d.pageW - sup.w) > 0.5 || abs(d.pageH - sup.h) > 0.5)) {
            stroke.color = cFrame
            stroke.strokeWidth = 1f; stroke.pathEffect = dash
            c.drawRect(sx(0.0), sy(0.0), sx(d.pageW), sy(d.pageH), stroke)
            stroke.pathEffect = null
        }

        // marcas de registro: cuadrado 5 mm arriba-izq, L de 20 mm arriba-der y abajo-izq (cameoAddRegmarks)
        regmarks?.let { r ->
            if (!sup.mat) {
                fill.color = 0xFF111111.toInt()
                for ((x, y, w, h) in Gpgl.regmarkRects(r)) c.drawRect(sx(x), sy(y), sx(x + w), sy(y + h), fill)
                stroke.color = cFrame; stroke.strokeWidth = 1f; stroke.pathEffect = dash
                // área de trabajo: al ras de las marcas (borde superior del cuadrado, exterior de las L)
                c.drawRect(sx(r.inset), sy(r.inset), sx(r.pageW - r.inset), sy(r.pageH - r.bottom), stroke)
                stroke.pathEffect = null
            }
        }

        // origen de la cuchilla
        fill.color = cCyan
        c.drawCircle(sx(0.0), sy(0.0), 5 * dp, fill)
        drawSelection(c, d)
    }

    private fun drawSupport(c: Canvas, w: Double, h: Double, color: Int) {
        fill.color = color
        fill.setShadowLayer(16 * dp, 0f, 4 * dp, 0x29001876)
        c.drawRect(sx(0.0), sy(0.0), sx(w), sy(h), fill)
        fill.clearShadowLayer()
    }

    /** Cuadrado unitario −1…1 -> mm -> pantalla. */
    private fun drawImage(c: Canvas, it: Item, selected: Boolean) {
        val bmp = it.bitmap ?: return
        val w = A.mul(it.t, it.m!!)
        val px = doubleArrayOf(2.0 / bmp.width, 0.0, 0.0, 2.0 / bmp.height, -1.0, -1.0)
        val full = A.mul(doubleArrayOf(s.toDouble(), 0.0, 0.0, s.toDouble(), ox.toDouble(), oy.toDouble()), A.mul(w, px))
        val mx = Matrix()
        mx.setValues(floatArrayOf(full[0].toFloat(), full[2].toFloat(), full[4].toFloat(),
            full[1].toFloat(), full[3].toFloat(), full[5].toFloat(), 0f, 0f, 1f))
        c.drawBitmap(bmp, mx, bmpPaint)
        if (selected) drawPoly(c, it.world(), true, cCyan, 2 * dp)
    }

    /** Índigo = pendiente; cian = lo ya cortado; magenta = fuera del soporte. */
    private fun drawLine(c: Canvas, it: Item, sup: Support, selected: Boolean) {
        val w = it.world()
        val base = if (outside(w, sup)) cMag else cInk
        val pos = orderPos?.get(it.id)
        when {
            selected -> drawPoly(c, w, false, cCyan, 2.6f * dp)
            pos == null || pos > cutAt.first -> drawPoly(c, w, false, base, 1.5f * dp)
            pos < cutAt.first -> drawPoly(c, w, false, cCyan, 1.5f * dp)
            else -> {
                drawPoly(c, w, false, base, 1.5f * dp)
                drawPoly(c, w, false, cCyan, 3 * dp, max(2, (w.size / 2 * cutAt.second).roundToInt()))
            }
        }
    }

    /** Una línea tapada por una imagen se corta igual: lo tapado se ve punteado encima. */
    private fun drawCovered(c: Canvas, d: Doc, sup: Support, selIds: Set<Int>) {
        val above = ArrayList<DoubleArray>()
        for (it in d.items.asReversed()) {
            if (it.isImage) { above += it.bbox(); continue }
            if (above.isEmpty()) continue
            val w = it.world()
            val b = Item.bboxOf(w)
            if (above.none { q -> q[0] < b[2] && q[2] > b[0] && q[1] < b[3] && q[3] > b[1] }) continue
            val col = if (it.id in selIds) cCyan else if (outside(w, sup)) cMag else cInk
            drawPoly(c, w, false, col, 1.5f * dp, effect = hidden)
        }
    }

    /** Las dos planchas multiplicadas: donde se cruzan sale el índigo. */
    private fun drawPlates(c: Canvas, d: Doc) {
        val dx = plate; val dy = plate * 0.7f
        stroke.xfermode = multiply
        for ((col, k) in listOf(cCyan to -1f, cMag to 1f)) {
            c.save(); c.translate(dx * k, dy * k)
            for (it in d.items) if (!it.isImage) drawPoly(c, it.world(), false, col, 1.8f * dp)
            c.restore()
        }
        stroke.xfermode = null
    }
    private fun drawPoly(c: Canvas, w: DoubleArray, close: Boolean, color: Int, width: Float, n: Int = w.size / 2,
                         effect: DashPathEffect? = null) {
        if (w.size < 4) return
        path.reset()
        path.moveTo(sx(w[0]), sy(w[1]))
        for (i in 1 until min(n, w.size / 2)) path.lineTo(sx(w[2 * i]), sy(w[2 * i + 1]))
        if (close) path.close()
        stroke.color = color; stroke.strokeWidth = width; stroke.pathEffect = effect
        c.drawPath(path, stroke)
        stroke.pathEffect = null
    }
    private fun drawSelection(c: Canvas, d: Doc) {
        op?.let { o ->
            if (o.type == OpType.MARQUEE) {
                fill.color = 0x2400B8D9; stroke.color = cInk; stroke.strokeWidth = 1f; stroke.pathEffect = dash
                val l = sx(min(o.ax, o.bx)); val t = sy(min(o.ay, o.by)); val r = sx(max(o.ax, o.bx)); val b = sy(max(o.ay, o.by))
                c.drawRect(l, t, r, b, fill); c.drawRect(l, t, r, b, stroke); stroke.pathEffect = null
            }
        }
        if (d.sel.isEmpty() || cutProgress != null) return
        val hs = handles(d) ?: return
        val (nw, ne, se) = Triple(hs[0], hs[1], hs[2])
        stroke.color = cInk; stroke.strokeWidth = 1f; stroke.pathEffect = dash
        c.drawRect(nw.x, nw.y, se.x, se.y, stroke); stroke.pathEffect = null
        val rot = hs[4]
        c.drawLine((nw.x + ne.x) / 2, if (rot.y > se.y) se.y else nw.y, rot.x, rot.y, stroke)
        val r = 4 * dp
        for (h in hs) {
            fill.color = cCyan; stroke.color = cSheet; stroke.strokeWidth = 2 * dp
            if (h.kind == "rot") { c.drawCircle(h.x, h.y, 14 * dp, fill); c.drawCircle(h.x, h.y, 14 * dp, stroke) }
            else {
                box.set(h.x - 9 * dp, h.y - 9 * dp, h.x + 9 * dp, h.y + 9 * dp)
                c.drawRoundRect(box, r, r, fill); c.drawRoundRect(box, r, r, stroke)
            }
        }
        val i = (10 * dp).toInt()
        rotIcon.setBounds(rot.x.toInt() - i, rot.y.toInt() - i, rot.x.toInt() + i, rot.y.toInt() + i)
        rotIcon.draw(c)
        hud?.let { c.drawText(it, se.x + 14 * dp, se.y + 4 * dp, text) }
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
    private val box = RectF()

    private fun handles(d: Doc): List<Handle>? {
        if (d.sel.isEmpty()) return null
        val b = d.selBBox()
        val l = sx(b[0]); val t = sy(b[1]); val r = sx(b[2]); val bt = sy(b[3])
        // pegado arriba quedaría bajo la barra de soporte: el asa de girar pasa abajo
        val rotY = if (t - 34 * dp < 66 * dp) bt + 34 * dp else t - 34 * dp
        return listOf(Handle("nw", l, t), Handle("ne", r, t), Handle("se", r, bt), Handle("sw", l, bt),
            Handle("rot", (l + r) / 2, rotY))
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
