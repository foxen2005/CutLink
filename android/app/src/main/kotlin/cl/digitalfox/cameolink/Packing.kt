package cl.digitalfox.cameolink

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Acomoda copias de un diseño en un área rectangular. Prueba grilla, columnas alternadas y filas
 * alternadas: las alternadas meten más copias con formas redondas (círculo de 40 mm, 3 mm, en
 * 190 × 262 mm: 24 en grilla, 28 así).
 *
 * Reglas: entre líneas de corte queda la separación pedida; la imagen de una copia no entra en la línea de otra
 * (se imprimiría dentro de la pieza vecina); y todo, imagen incluida, queda dentro del área, para no
 * imprimir sobre las marcas. Se trabaja con envolventes convexas: exactas en círculos y rectángulos,
 * en formas cóncavas dejan de más. Toda distribución se valida contra sus vecinos reales.
 */
object Packing {
    const val GRID = "grilla"
    const val COLUMNS = "columnas alternadas"
    const val ROWS = "filas alternadas"

    /** [cells]: esquina superior izquierda de cada copia, ya centradas en el área. */
    class Result(val cells: List<DoubleArray>, val w: Double, val h: Double, val layout: String)

    private const val MAX_VERTICES = 64
    private const val MIN_STEP = 0.05                 // una línea recta sin separación no da pasos infinitos
    private const val LIMIT = Doc.MAX_FILL + 1        // más allá da lo mismo: no se va a rellenar

    private fun hullOf(pts: DoubleArray): List<Pair<Double, Double>> {
        val p = (0 until pts.size / 2).map { pts[2 * it] to pts[2 * it + 1] }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (p.size < 3) return p
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        fun half(src: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            for (q in src) {
                while (out.size >= 2 && cross(out[out.size - 2], out.last(), q) <= 0) out.removeAt(out.size - 1)
                out += q
            }
            return out.dropLast(1)
        }
        return half(p) + half(p.reversed())
    }

    private fun flat(p: List<Pair<Double, Double>>) = p.flatMap { listOf(it.first, it.second) }.toDoubleArray()

    /** Envolvente con un máximo de vértices y lo que se perdió al recortarla (se suma a la separación). */
    private fun simplified(pts: DoubleArray): Pair<DoubleArray, Double> {
        val h = hullOf(pts)
        if (h.size <= MAX_VERTICES) return flat(h) to 0.0
        // parte de una muestra pareja y agrega los vértices que más se alejan: una punta no se pierde
        val keep = (0 until MAX_VERTICES * 3 / 4).map { it * h.size / (MAX_VERTICES * 3 / 4) }.toSortedSet()
        fun worst(): Pair<Int, Double> {
            val poly = flat(keep.map { h[it] })
            var at = -1; var far = 0.0
            for (i in h.indices) if (i !in keep) {
                val d = (0 until poly.size / 2).minOf { k ->
                    val j = (k + 1) % (poly.size / 2)
                    Doc.segDist(h[i].first, h[i].second, poly[2 * k], poly[2 * k + 1], poly[2 * j], poly[2 * j + 1])
                }
                if (d > far) { far = d; at = i }
            }
            return at to far
        }
        var w = worst()
        while (keep.size < MAX_VERTICES && w.first >= 0) { keep += w.first; w = worst() }
        return flat(keep.map { h[it] }) to w.second
    }

    /** El diseño a repetir: su corte, su imagen (si sobresale de otra forma) y su caja total. */
    private class Unit(val cut: DoubleArray, val ink: DoubleArray?, val g: Double, val w: Double, val h: Double) {
        val swapped get() = Unit(swap(cut), ink?.let { swap(it) }, g, h, w)

        private fun swap(p: DoubleArray) = DoubleArray(p.size) { p[if (it % 2 == 0) it + 1 else it - 1] }

        private fun shifted(p: DoubleArray, dx: Double, dy: Double) = DoubleArray(p.size) { p[it] + if (it % 2 == 0) dx else dy }

        /** ¿Una copia corrida (dx, dy) respeta las reglas? */
        fun clear(dx: Double, dy: Double): Boolean {
            val q = shifted(cut, dx, dy)
            if (!apart(cut, q, g) || !apart(q, cut, g)) return false
            val i = ink ?: return true
            return !overlaps(i, q) && !overlaps(shifted(i, dx, dy), cut)
        }
    }

    /** La que más copias deja; a igual cantidad gana la grilla, la más simple de despegar. */
    fun best(cut: DoubleArray, ink: DoubleArray?, roomW: Double, roomH: Double, gap: Double): Result {
        val b = Item.bboxOf(if (ink == null) cut else cut + ink)
        val w = b[2] - b[0]; val h = b[3] - b[1]
        fun local(p: DoubleArray) = DoubleArray(p.size) { p[it] - b[it % 2] }
        val (poly, lost) = simplified(local(cut))
        val u = Unit(poly, ink?.let { flat(hullOf(local(it))) }, max(0.0, gap) + lost, w, h)
        val grid = grid(u, roomW, roomH)
        val cols = staggered(u, roomW, roomH)
        val rows = staggered(u.swapped, roomH, roomW).map { doubleArrayOf(it[1], it[0]) }
        val (cells, layout) = listOf(grid to GRID, cols to COLUMNS, rows to ROWS).maxBy { it.first.size }
        return Result(centered(cells, w, h, roomW, roomH), w, h, layout)
    }

    private fun grid(u: Unit, roomW: Double, roomH: Double): List<DoubleArray> {
        fun cells(sx: Double, sy: Double): List<DoubleArray> {
            val out = ArrayList<DoubleArray>()
            for (c in 0 until fit(roomW, u.w, sx)) for (r in 0 until fit(roomH, u.h, sy)) {
                if (out.size >= LIMIT) return out
                out += doubleArrayOf(c * sx, r * sy)
            }
            return out
        }
        val tight = cells(stepX(u, 0.0), stepY(u))
        // una forma inclinada puede pisar a su vecina en diagonal: la caja + separación nunca falla
        return if (valid(u, tight)) tight else cells(u.w + u.g + MIN_STEP, u.h + u.g + MIN_STEP)
    }

    /** Columnas corridas media altura: cada una se mete en el hueco de la anterior. */
    private fun staggered(u: Unit, roomW: Double, roomH: Double): List<DoubleArray> {
        val sy = stepY(u)
        val shift = sy / 2
        // la columna vecina queda media altura arriba y abajo; la siguiente, justo al lado
        val sx = maxOf(stepX(u, shift), stepX(u, -shift), stepX(u, 0.0) / 2)
        val out = ArrayList<DoubleArray>()
        for (c in 0 until fit(roomW, u.w, sx)) {
            val off = if (c % 2 == 0) 0.0 else shift
            for (r in 0 until fit(roomH - off, u.h, sy)) {
                if (out.size >= LIMIT) return if (valid(u, out)) out else emptyList()
                out += doubleArrayOf(c * sx, off + r * sy)
            }
        }
        return if (valid(u, out)) out else emptyList()
    }

    /** Cada distancia distinta entre copias que se pueden tocar, una sola vez: en una trama son pocas. */
    private fun valid(u: Unit, cells: List<DoubleArray>): Boolean {
        val seen = HashSet<Pair<Long, Long>>()
        for (i in cells.indices) for (j in 0 until i) {
            val dx = cells[i][0] - cells[j][0]; val dy = cells[i][1] - cells[j][1]
            if (abs(dx) > u.w + u.g || abs(dy) > u.h + u.g) continue
            if (seen.add(Math.round(dx * 100) to Math.round(dy * 100)) && !u.clear(dx, dy)) return false
        }
        return true
    }

    private fun centered(cells: List<DoubleArray>, w: Double, h: Double, roomW: Double, roomH: Double): List<DoubleArray> {
        if (cells.isEmpty()) return cells
        val dx = (roomW - (cells.maxOf { it[0] } + w)) / 2
        val dy = (roomH - (cells.maxOf { it[1] } + h)) / 2
        return cells.map { doubleArrayOf(it[0] + dx, it[1] + dy) }
    }

    private fun fit(room: Double, size: Double, step: Double) =
        if (size > room + 1e-4) 0 else min(floor((room - size + 1e-4) / step), LIMIT.toDouble()).toInt() + 1

    /** Menor avance en X, con la copia corrida [dy]. */
    private fun stepX(u: Unit, dy: Double) = search(u.w + u.g + 1) { u.clear(it, dy) }

    private fun stepY(u: Unit) = search(u.h + u.g + 1) { u.clear(0.0, it) }

    private fun search(top: Double, free: (Double) -> Boolean): Double {
        var lo = 0.0; var hi = top
        repeat(26) { val m = (lo + hi) / 2; if (free(m)) hi = m else lo = m }
        return max(hi, MIN_STEP)
    }

    /** Ningún vértice de [a] dentro de [b] ni a menos de [g] de sus lados. */
    private fun apart(a: DoubleArray, b: DoubleArray, g: Double): Boolean {
        val n = b.size / 2
        var i = 0
        while (i < a.size) {
            val x = a[i]; val y = a[i + 1]
            if (n >= 3 && Doc.pointInPoly(x, y, b)) return false
            if (n == 1 && hypot(x - b[0], y - b[1]) < g) return false
            for (k in 0 until n) {
                val j = (k + 1) % n
                if (n > 1 && Doc.segDist(x, y, b[2 * k], b[2 * k + 1], b[2 * j], b[2 * j + 1]) < g - 1e-9) return false
            }
            i += 2
        }
        return true
    }

    /** Dos convexos se tocan si ningún lado de ninguno los separa. */
    private fun overlaps(a: DoubleArray, b: DoubleArray): Boolean {
        fun separated(p: DoubleArray): Boolean {
            val n = p.size / 2
            for (k in 0 until n) {
                val j = (k + 1) % n
                val nx = p[2 * j + 1] - p[2 * k + 1]; val ny = p[2 * k] - p[2 * j]
                fun span(q: DoubleArray): Pair<Double, Double> {
                    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                    var i = 0
                    while (i < q.size) { val v = q[i] * nx + q[i + 1] * ny; lo = min(lo, v); hi = max(hi, v); i += 2 }
                    return lo to hi
                }
                val (al, ah) = span(a); val (bl, bh) = span(b)
                if (ah <= bl + 1e-9 || bh <= al + 1e-9) return true
            }
            return false
        }
        return !separated(a) && !separated(b)
    }
}
