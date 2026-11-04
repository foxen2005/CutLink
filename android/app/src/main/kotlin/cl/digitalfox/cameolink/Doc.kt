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
    /** Alto del mat: 12x24 si el soporte lo es, o si la hoja pegada no cabe en el 12x12. */
    val matH get() = if (id == "mat24" || (onMat && h > MAT_MM)) MAT24_MM else MAT_MM
    /** TG de la máquina: 0 sin mat, 1 = 12x12, 2 = 12x24. */
    val tg get() = if (!usesMat) 0 else if (matH > MAT_MM) 2 else 1

    companion object {
        const val MAT_MM = 304.8
        const val MAT24_MM = 609.6
        // Print & Cut admite hojas de hasta 30 × 61 cm con las marcas dentro (vinilos grandes)
        const val PNC_MAX_W = 300.0
        const val PNC_MAX_H = 610.0
        val PRESETS = listOf(
            Support("mat12", "Mat 12x12", MAT_MM, MAT_MM, mat = true, onMat = false),
            Support("mat24", "Mat 12x24", MAT_MM, MAT24_MM, mat = true, onMat = false),
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
    /** Caja de los cortes; ceros si todavía no hay ninguno (hoja en blanco). */
    fun cutBBox(): DoubleArray = items.filter { !it.isImage }.let { if (it.isEmpty()) DoubleArray(4) else bboxAll(it) }

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
    /** Centra la selección (su caja) en el área de trabajo [x0,y0,x1,y1], a lo ancho y a lo alto. */
    fun centerIn(area: DoubleArray) {
        val b = selBBox()
        apply(A.tr((area[0] + area[2]) / 2 - (b[0] + b[2]) / 2, (area[1] + area[3]) / 2 - (b[1] + b[3]) / 2))
    }

    // ---- agregar (las formas son LÍNEAS DE CORTE: no recortan la imagen, que se imprime completa
    // y deja sangrado natural si el corte se corre)
    private fun add(it: Item) { items += it; sel.clear(); sel += keyOf(it) }

    fun addShape(kind: String, cx: Double, cy: Double, w: Double, h: Double, radius: Double = 6.0) =
        add(Item(nextId++, false, Shapes.points(kind, cx, cy, w, h, radius), true, null, null))

    /** Imagen de [wMm] de ancho centrada en (cx, cy), debajo de las líneas de corte. */
    fun addImage(bmp: Bitmap, path: String?, cx: Double, cy: Double, wMm: Double) {
        val hMm = wMm * bmp.height / bmp.width
        add(Item(nextId++, true, DoubleArray(0), true, doubleArrayOf(wMm / 2, 0.0, 0.0, hMm / 2, cx, cy), bmp, imagePath = path))
    }

    // ---- capas: solo importan entre imágenes; las líneas de corte siempre se ven encima y el orden
    // de corte lo decide Ordering
    fun moveLayer(dir: Int) {
        val slots = items.indices.filter { items[it].isImage }
        val imgs = slots.map { items[it] }.toMutableList()
        val picked = selItems().filter { it.isImage }.toSet()
        val order = if (dir > 0) imgs.indices.reversed() else imgs.indices
        for (i in order) {
            val j = i + dir
            if (j in imgs.indices && imgs[i] in picked && imgs[j] !in picked) { val t = imgs[i]; imgs[i] = imgs[j]; imgs[j] = t }
        }
        slots.forEachIndexed { k, s -> items[s] = imgs[k] }
    }
    val selHasImage get() = selItems().any { it.isImage }

    // ---- rellenar: la selección es una unidad y se repite en grilla dentro del área de trabajo
    class Fill(val cols: Int, val rows: Int, val w: Double, val h: Double) { val count get() = cols * rows }

    fun fillPlan(area: DoubleArray, gap: Double): Fill {
        val b = selBBox(); val w = b[2] - b[0]; val h = b[3] - b[1]
        val g = max(0.0, gap)
        fun fit(room: Double, size: Double) = if (size <= 0) 0 else Math.floor((room + g + 1e-9) / (size + g)).toInt()
        return Fill(fit(area[2] - area[0], w), fit(area[3] - area[1], h), w, h)
    }

    fun fill(area: DoubleArray, gap: Double) {
        val p = fillPlan(area, gap)
        if (p.count !in 1..MAX_FILL) return
        val g = max(0.0, gap)
        val b = selBBox()
        val base = selItems()
        val orig = base.map { it.t.copyOf() }
        val gx = area[0] + (area[2] - area[0] - (p.cols * p.w + (p.cols - 1) * g)) / 2
        val gy = area[1] + (area[3] - area[1] - (p.rows * p.h + (p.rows - 1) * g)) / 2
        val keys = mutableListOf<String>()
        for (r in 0 until p.rows) for (c in 0 until p.cols) {
            val mv = A.tr(gx + c * (p.w + g) - b[0], gy + r * (p.h + g) - b[1])
            val gid = nextGroup++                                  // cada copia se mueve junta
            base.forEachIndexed { k, it ->
                val t = A.mul(mv, orig[k])
                if (r == 0 && c == 0) { it.t = t; it.g = gid } else items += it.copyWith(nextId++, t, gid)
            }
            keys += "g$gid"
        }
        sel.clear(); sel += keys
    }
    fun rotate90() { val b = selBBox(); apply(A.rot(90.0, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) }
    fun flipH() { val b = selBBox(); apply(A.sc(-1.0, 1.0, (b[0] + b[2]) / 2, 0.0)) }
    fun flipV() { val b = selBBox(); apply(A.sc(1.0, -1.0, 0.0, (b[1] + b[3]) / 2)) }

    /** Lo que está bajo el dedo: la línea de corte más chica (así se elige un agujero dentro de su
     *  contorno), y si no hay líneas, la imagen de más arriba: lo que se ve es lo que se toca. */
    fun hit(x: Double, y: Double, tolMm: Double): Item? {
        var best: Item? = null
        var bestA = Double.MAX_VALUE
        var topImage: Item? = null
        for (it in items) {
            val p = it.world()
            var h = (it.closed || it.isImage) && pointInPoly(x, y, p)
            if (!h) {
                var i = 2
                while (i < p.size && !h) { h = segDist(x, y, p[i - 2], p[i - 1], p[i], p[i + 1]) < tolMm; i += 2 }
                if (!h && it.isImage) h = segDist(x, y, p[6], p[7], p[0], p[1]) < tolMm
            }
            if (!h) continue
            if (it.isImage) { topImage = it; continue }        // las de más arriba van después en la lista
            val b = it.bbox()
            val area = (b[2] - b[0]) * (b[3] - b[1])
            if (area <= bestA) { bestA = area; best = it }
        }
        return best ?: topImage
    }

    /** Formas a cortar en orden de corte (interiores primero, recalculado con lo editado). */
    fun cutOrder(include: (Item) -> Boolean): List<Item> {
        val shapes = items.filter { !it.isImage && include(it) }
        val idx = Ordering.order(shapes.map { Ordering.Shape(it.world(), it.closed) })
        return idx.map { shapes[it] }
    }

    /** Proyecto: todo lo editado. [imageRef] convierte la ruta de cada imagen en lo que se guarda. */
    fun toJson(imageRef: (String?) -> String?): JSONObject {
        val arr = org.json.JSONArray()
        for (it in items) {
            val o = JSONObject().put("id", it.id).put("image", it.isImage).put("closed", it.closed)
                .put("t", org.json.JSONArray(it.t.toList()))
            it.g?.let { g -> o.put("g", g) }
            if (it.isImage) o.put("m", org.json.JSONArray(it.m!!.toList())).put("file", imageRef(it.imagePath))
            else o.put("pts", org.json.JSONArray(it.pts.map { v -> Math.round(v * 1000) / 1000.0 }))
            arr.put(o)
        }
        return JSONObject().put("version", 1).put("name", name).put("kind", kind)
            .put("pageW", pageW).put("pageH", pageH).put("items", arr)
    }

    companion object {
        const val MAX_FILL = 500      // más copias que esto es casi siempre un error y traba el visor

        /** Abre un proyecto guardado con [toJson]; las imágenes que falten se saltan y se avisan. */
        fun fromProject(js: JSONObject, loadImage: (String) -> Pair<Bitmap, String>?): Doc {
            val missing = mutableListOf<String>()
            val items = js.getJSONArray("items")
            val parsed = mutableListOf<Item>()
            fun arr(a: org.json.JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val g = if (o.has("g")) o.getInt("g") else null
                val t = arr(o.getJSONArray("t"))
                if (o.getBoolean("image")) {
                    val file = o.optString("file")
                    val img = loadImage(file)
                    if (img == null) { missing += file; continue }
                    parsed += Item(o.getInt("id"), true, DoubleArray(0), true, arr(o.getJSONArray("m")), img.first, t, g, img.second)
                } else {
                    parsed += Item(o.getInt("id"), false, arr(o.getJSONArray("pts")), o.getBoolean("closed"), null, null, t, g)
                }
            }
            val warn = if (missing.isEmpty()) emptyList() else listOf("Faltan ${missing.size} imágenes del proyecto")
            val d = Doc(js.getString("name"), js.getString("kind"), js.getDouble("pageW"), js.getDouble("pageH"), warn)
            d.items = parsed
            d.nextId = (parsed.maxOfOrNull { it.id } ?: 0) + 1
            d.nextGroup = (parsed.mapNotNull { it.g }.maxOrNull() ?: 0) + 1
            return d
        }

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

/** Formas simples para encimar a las imágenes, como polilíneas cerradas en mm. */
object Shapes {
    const val CIRCLE = "circle"
    const val RECT = "rect"
    const val ROUND = "round"

    fun points(kind: String, cx: Double, cy: Double, w: Double, h: Double, radius: Double): DoubleArray {
        val p = ArrayList<Double>()
        val x0 = cx - w / 2; val y0 = cy - h / 2
        when (kind) {
            CIRCLE -> for (i in 0..96) {
                val a = i * Math.PI / 48
                p += cx + w / 2 * cos(a); p += cy + h / 2 * sin(a)
            }
            RECT -> for ((x, y) in listOf(x0 to y0, x0 + w to y0, x0 + w to y0 + h, x0 to y0 + h, x0 to y0)) { p += x; p += y }
            else -> {
                val r = min(radius, min(w, h) / 2)
                fun arc(ax: Double, ay: Double, a0: Double) {
                    for (i in 0..12) { val a = a0 + i * Math.PI / 24; p += ax + r * cos(a); p += ay + r * sin(a) }
                }
                arc(x0 + w - r, y0 + r, -Math.PI / 2)
                arc(x0 + w - r, y0 + h - r, 0.0)
                arc(x0 + r, y0 + h - r, Math.PI / 2)
                arc(x0 + r, y0 + r, Math.PI)
                p += p[0]; p += p[1]
            }
        }
        return p.toDoubleArray()
    }
}

/** Ángulo (grados) de p respecto de c. */
fun angleDeg(cx: Double, cy: Double, x: Double, y: Double) = Math.toDegrees(atan2(y - cy, x - cx))

fun DoubleArray.isAlmost(o: DoubleArray, eps: Double = 1e-9) = size == o.size && indices.all { abs(this[it] - o[it]) < eps }
