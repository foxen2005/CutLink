package cl.digitalfox.cameolink

import android.graphics.Bitmap
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Afín 2D (a, b, c, d, e, f): x' = a·x + c·y + e ; y' = b·x + d·y + f. Misma convención que el bridge. */
typealias Aff = DoubleArray

object A {
    val I: Aff get() = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    fun mul(p: Aff, q: Aff): Aff = doubleArrayOf(
        p[0] * q[0] + p[2] * q[1], p[1] * q[0] + p[3] * q[1],
        p[0] * q[2] + p[2] * q[3], p[1] * q[2] + p[3] * q[3],
        p[0] * q[4] + p[2] * q[5] + p[4], p[1] * q[4] + p[3] * q[5] + p[5])
    fun tr(dx: Double, dy: Double): Aff = doubleArrayOf(1.0, 0.0, 0.0, 1.0, dx, dy)
    fun sc(sx: Double, sy: Double, cx: Double, cy: Double): Aff = doubleArrayOf(sx, 0.0, 0.0, sy, cx - sx * cx, cy - sy * cy)
    fun rot(deg: Double, cx: Double, cy: Double): Aff {
        val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
        return doubleArrayOf(c, s, -s, c, cx - c * cx + s * cy, cy - s * cx - c * cy)
    }
}

/** Una forma de corte o una imagen (studio3), con su transformación de edición [t]. */
class Item(
    val id: Int,
    val isImage: Boolean,
    val pts: DoubleArray,           // forma: x0,y0,x1,y1… en mm del archivo
    val closed: Boolean,
    val m: Aff?,                    // imagen: cuadrado unitario −1…1 -> mm
    val bitmap: Bitmap?,
    var t: Aff = A.I,
    var g: Int? = null,             // grupo: se selecciona/mueve junto
    val imagePath: String? = null,  // imagen original (para imprimir con más resolución que el visor)
) {
    fun copyWith(id: Int, t: Aff, g: Int?) = Item(id, isImage, pts, closed, m, bitmap, t, g, imagePath)

    /** Puntos en mm de página (con la edición aplicada). Imagen: sus 4 esquinas. */
    fun world(): DoubleArray {
        val src = if (isImage) unitCorners(m!!) else pts
        val out = DoubleArray(src.size)
        var i = 0
        while (i < src.size) {
            val x = src[i]; val y = src[i + 1]
            out[i] = t[0] * x + t[2] * y + t[4]
            out[i + 1] = t[1] * x + t[3] * y + t[5]
            i += 2
        }
        return out
    }

    fun bbox(): DoubleArray = bboxOf(world())

    companion object {
        fun unitCorners(m: Aff) = doubleArrayOf(
            -m[0] - m[2] + m[4], -m[1] - m[3] + m[5], m[0] - m[2] + m[4], m[1] - m[3] + m[5],
            m[0] + m[2] + m[4], m[1] + m[3] + m[5], -m[0] + m[2] + m[4], -m[1] + m[3] + m[5])

        fun bboxOf(p: DoubleArray): DoubleArray {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            var i = 0
            while (i < p.size) { x0 = min(x0, p[i]); x1 = max(x1, p[i]); y0 = min(y0, p[i + 1]); y1 = max(y1, p[i + 1]); i += 2 }
            return doubleArrayOf(x0, y0, x1, y1)
        }
    }
}

/** Sobre qué se corta. mat = es el mat; onMat = hoja pegada sobre el mat (TG1). */
data class Support(val id: String, val name: String, val w: Double, val h: Double, val mat: Boolean, val onMat: Boolean) {
    val usesMat get() = mat || onMat
    val label get() = "$name${if (onMat) " sobre mat" else ""} · ${nf(w)} × ${nf(h)} mm"

    companion object {
        const val MAT_MM = 304.8
        val PRESETS = listOf(
            Support("mat12", "Mat 12x12", MAT_MM, MAT_MM, mat = true, onMat = false),
            Support("a4", "A4", 210.0, 297.0, mat = false, onMat = false),
            Support("carta", "Carta", 215.9, 279.4, mat = false, onMat = false),
        )
    }
}

/** Documento abierto + editor (seleccionar, mover, escalar, girar, agrupar, deshacer). */
class Doc(val name: String, val kind: String, val pageW: Double, val pageH: Double, val warnings: List<String>) {
    var items = mutableListOf<Item>()
    val sel = LinkedHashSet<String>()           // claves: "g<n>" grupo o "i<id>" forma suelta
    private var nextId = 1
    private var nextGroup = 1
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private class Snapshot(val items: List<Item>, val sel: List<String>)

    fun keyOf(it: Item) = it.g?.let { "g$it" } ?: "i${it.id}"
    fun selItems() = items.filter { keyOf(it) in sel }
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun snapshot(): Any = Snapshot(items.map { it.copyWith(it.id, it.t.copyOf(), it.g) }, sel.toList())
    fun pushUndo(s: Any = snapshot()) {
        undoStack.addLast(s as Snapshot); if (undoStack.size > 60) undoStack.removeFirst(); redoStack.clear()
    }
    private fun restore(s: Snapshot) { items = s.items.map { it.copyWith(it.id, it.t.copyOf(), it.g) }.toMutableList(); sel.clear(); sel += s.sel }
    fun undo() { if (canUndo) { redoStack.addLast(snapshot() as Snapshot); restore(undoStack.removeLast()) } }
    fun redo() { if (canRedo) { undoStack.addLast(snapshot() as Snapshot); restore(redoStack.removeLast()) } }

    fun selBBox(): DoubleArray = bboxAll(selItems())
    fun bboxAll(list: List<Item>): DoubleArray {
        var b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (it in list) { val q = it.bbox(); b = doubleArrayOf(min(b[0], q[0]), min(b[1], q[1]), max(b[2], q[2]), max(b[3], q[3])) }
        return b
    }
    fun cutBBox(): DoubleArray = bboxAll(items.filter { !it.isImage })

    /** Aplica M a la selección (sobre [base] si se pasa: así un gesto no acumula errores). */
    fun apply(mtx: Aff, base: Map<Int, Aff>? = null) {
        for (it in selItems()) it.t = A.mul(mtx, base?.get(it.id) ?: it.t)
    }
    fun baseT(): Map<Int, Aff> = items.associate { it.id to it.t.copyOf() }

    fun group() { val g = nextGroup++; for (it in selItems()) it.g = g; sel.clear(); sel += "g$g" }
    fun ungroup() { val its = selItems(); its.forEach { it.g = null }; sel.clear(); its.forEach { sel += keyOf(it) } }
    fun duplicate() {
        val gm = HashMap<Int, Int>()
        val copies = selItems().map { it.copyWith(nextId++, A.mul(A.tr(5.0, 5.0), it.t), it.g?.let { g -> gm.getOrPut(g) { nextGroup++ } }) }
        items.addAll(copies); sel.clear(); copies.forEach { sel += keyOf(it) }
    }
    fun delete() { items.removeAll { keyOf(it) in sel }; sel.clear() }
    fun selectAll() { sel.clear(); items.forEach { sel += keyOf(it) } }
    /** Centra la selección (su caja) en una hoja de w × h mm, a lo ancho y a lo alto. */
    fun centerOn(w: Double, h: Double) {
        val b = selBBox()
        apply(A.tr(w / 2 - (b[0] + b[2]) / 2, h / 2 - (b[1] + b[3]) / 2))
    }
    fun rotate90() { val b = selBBox(); apply(A.rot(90.0, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) }
    fun flipH() { val b = selBBox(); apply(A.sc(-1.0, 1.0, (b[0] + b[2]) / 2, 0.0)) }
    fun flipV() { val b = selBBox(); apply(A.sc(1.0, -1.0, 0.0, (b[1] + b[3]) / 2)) }

    /** La forma más chica bajo el dedo (así se elige un agujero dentro de su contorno). */
    fun hit(x: Double, y: Double, tolMm: Double): Item? {
        var best: Item? = null
        var bestA = Double.MAX_VALUE
        for (it in items) {
            val p = it.world()
            var h = (it.closed || it.isImage) && pointInPoly(x, y, p)
            if (!h) {
                var i = 2
                while (i < p.size && !h) { h = segDist(x, y, p[i - 2], p[i - 1], p[i], p[i + 1]) < tolMm; i += 2 }
                if (!h && it.isImage) h = segDist(x, y, p[6], p[7], p[0], p[1]) < tolMm
            }
            if (!h) continue
            val b = it.bbox()
            val area = (b[2] - b[0]) * (b[3] - b[1]) + if (it.isImage) 1e-3 else 0.0
            if (area < bestA) { bestA = area; best = it }
        }
        return best
    }

    /** Formas a cortar en orden de corte (interiores primero, recalculado con lo editado). */
    fun cutOrder(include: (Item) -> Boolean): List<Item> {
        val shapes = items.filter { !it.isImage && include(it) }
        val idx = Ordering.order(shapes.map { Ordering.Shape(it.world(), it.closed) })
        return idx.map { shapes[it] }
    }

    companion object {
        fun pointInPoly(x: Double, y: Double, p: DoubleArray): Boolean {
            var inside = false
            val n = p.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = p[2 * i]; val yi = p[2 * i + 1]; val xj = p[2 * j]; val yj = p[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        fun segDist(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
            val t = if (l > 0) ((x - ax) * dx + (y - ay) * dy) / l else 0.0
            val c = t.coerceIn(0.0, 1.0)
            return hypot(x - ax - c * dx, y - ay - c * dy)
        }

        /** Lee el JSON de bridge.mobile.load. Las imágenes del studio3 se agrupan con su línea de corte. */
        fun fromJson(js: JSONObject, loadBitmap: (String) -> Bitmap?): Doc {
            val page = js.getJSONArray("page_mm")
            val warn = js.getJSONArray("warnings").let { a -> List(a.length()) { a.getString(it) } }
            val d = Doc(js.getString("name"), js.getString("kind"), page.getDouble(0), page.getDouble(1), warn)
            val groups = HashMap<String, Int>()
            val paths = js.getJSONArray("paths")
            for (i in 0 until paths.length()) {
                val p = paths.getJSONObject(i)
                val pts = p.getJSONArray("pts")
                val arr = DoubleArray(pts.length() * 2)
                for (k in 0 until pts.length()) { val q = pts.getJSONArray(k); arr[2 * k] = q.getDouble(0); arr[2 * k + 1] = q.getDouble(1) }
                val g = if (p.isNull("group")) null else groups.getOrPut(p.getString("group")) { d.nextGroup++ }
                d.items += Item(d.nextId++, false, arr, p.getBoolean("closed"), null, null, A.I, g)
            }
            val imgs = js.getJSONArray("images")
            val imageItems = mutableListOf<Item>()
            for (i in 0 until imgs.length()) {
                val im = imgs.getJSONObject(i)
                val mj = im.getJSONArray("matrix")
                val m = DoubleArray(6) { mj.getDouble(it) }
                val file = im.getString("file")
                val bmp = loadBitmap(file) ?: continue
                val it = Item(d.nextId++, true, DoubleArray(0), true, m, bmp, imagePath = file)
                // la forma cerrada más chica que contiene el centro de la imagen = su línea de corte
                val c = Item.unitCorners(m).let { q -> doubleArrayOf((q[0] + q[4]) / 2, (q[1] + q[5]) / 2) }
                val owner = d.items.filter { s -> s.closed && pointInPoly(c[0], c[1], s.pts) }
                    .minByOrNull { s -> s.bbox().let { b -> (b[2] - b[0]) * (b[3] - b[1]) } }
                if (owner != null) { if (owner.g == null) owner.g = d.nextGroup++; it.g = owner.g }
                imageItems += it
            }
            // imágenes debajo de las líneas: se dibujan primero
            d.items = (imageItems + d.items).toMutableList()
            return d
        }
    }
}

/** Ángulo (grados) de p respecto de c. */
fun angleDeg(cx: Double, cy: Double, x: Double, y: Double) = Math.toDegrees(atan2(y - cy, x - cx))

fun DoubleArray.isAlmost(o: DoubleArray, eps: Double = 1e-9) = size == o.size && indices.all { abs(this[it] - o[it]) < eps }
