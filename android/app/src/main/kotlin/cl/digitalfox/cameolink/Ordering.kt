package cl.digitalfox.cameolink

/**
 * Port de bridge/ordering.py: interiores antes que exteriores (si el contorno se cortara antes,
 * la pieza quedaría suelta y el corte de adentro saldría corrido) y, dentro de cada nivel,
 * vecino más cercano. Se recalcula en cada corte: después de editar, el orden del archivo ya
 * no sirve (una forma movida o duplicada dentro de otra queda al final de la lista).
 */
object Ordering {
    class Shape(val pts: DoubleArray, val closed: Boolean) {
        val bbox: DoubleArray = Item.bboxOf(pts)
        val n get() = pts.size / 2
    }

    private fun samples(s: Shape): List<Pair<Double, Double>> {
        val n = s.n
        var cx = 0.0; var cy = 0.0
        for (k in 0 until n) { cx += s.pts[2 * k]; cy += s.pts[2 * k + 1] }
        return listOf(s.pts[0] to s.pts[1], s.pts[2 * (n / 2)] to s.pts[2 * (n / 2) + 1], cx / n to cy / n)
    }

    /** outer contiene a inner: caja dentro de caja y al menos 2 de 3 puntos de prueba dentro. */
    fun contains(outer: Shape, inner: Shape): Boolean {
        if (!outer.closed || outer.n < 3) return false
        val o = outer.bbox; val i = inner.bbox
        if (!(o[0] <= i[0] && o[1] <= i[1] && i[2] <= o[2] && i[3] <= o[3])) return false
        if (outer.pts.contentEquals(inner.pts)) return false
        return samples(inner).count { (x, y) -> Doc.pointInPoly(x, y, outer.pts) } >= 2
    }

    fun depths(shapes: List<Shape>): IntArray = IntArray(shapes.size) { i ->
        shapes.indices.count { j -> i != j && contains(shapes[j], shapes[i]) }
    }

    /** Índices en orden de corte. */
    fun order(shapes: List<Shape>): List<Int> {
        if (shapes.size <= 1) return shapes.indices.toList()
        val d = depths(shapes)
        val remaining = shapes.indices.sortedByDescending { d[it] }.toMutableList()
        val out = mutableListOf<Int>()
        var cx = 0.0; var cy = 0.0
        while (remaining.isNotEmpty()) {
            val level = d[remaining[0]]
            val group = remaining.filter { d[it] == level }.toMutableList()
            while (group.isNotEmpty()) {
                val best = group.minBy { val p = shapes[it].pts; (p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy) }
                group.remove(best); remaining.remove(best); out += best
                val p = shapes[best].pts
                cx = p[p.size - 2]; cy = p[p.size - 1]
            }
        }
        return out
    }
}
