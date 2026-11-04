package cl.digitalfox.cameolink

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mismo criterio que bridge/ordering.py. */
class OrderingTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) =
        Ordering.Shape(doubleArrayOf(x, y, x + w, y, x + w, y + h, x, y + h, x, y), true)

    @Test
    fun holesBeforeTheirContour() {
        // el contorno va primero en la lista (como un duplicado agregado después de editar)
        val shapes = listOf(rect(0.0, 0.0, 90.0, 50.0), rect(10.0, 20.0, 6.0, 6.0), rect(70.0, 20.0, 6.0, 6.0))
        val order = Ordering.order(shapes)
        assertEquals(0, order.last())
        assertEquals(setOf(1, 2), order.take(2).toSet())
    }

    @Test
    fun nestedThreeLevels() {
        val shapes = listOf(rect(0.0, 0.0, 100.0, 100.0), rect(10.0, 10.0, 80.0, 80.0), rect(40.0, 40.0, 10.0, 10.0))
        assertEquals(listOf(2, 1, 0), Ordering.order(shapes))
    }

    @Test
    fun sameLevelNearestNeighbourFromOrigin() {
        val shapes = listOf(rect(200.0, 0.0, 10.0, 10.0), rect(0.0, 0.0, 10.0, 10.0), rect(100.0, 0.0, 10.0, 10.0))
        assertEquals(listOf(1, 2, 0), Ordering.order(shapes))
    }

    @Test
    fun duplicateOnTopIsNotInside() {
        val a = rect(0.0, 0.0, 10.0, 10.0)
        assertEquals(listOf(0, 0), Ordering.depths(listOf(a, rect(0.0, 0.0, 10.0, 10.0))).toList())
    }
}
