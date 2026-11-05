package cl.digitalfox.cameolink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Editor sin imágenes (Bitmap necesita Android): formas, centrar, rellenar y soportes. */
class DocTest {
    private fun doc() = Doc("t", "nuevo", 210.0, 297.0, emptyList())

    /** Imagen de [w] × [h] mm sin Bitmap, al fondo de la pila, como la deja abrir un archivo. */
    private fun Doc.image(id: Int, cx: Double, cy: Double, w: Double, h: Double): Item {
        val it = Item(id, true, DoubleArray(0), true, doubleArrayOf(w / 2, 0.0, 0.0, h / 2, cx, cy), null)
        items.add(0, it)
        return it
    }

    @Test
    fun imageUnderAShapeCanBeTouched() {
        val d = doc()
        d.addShape(Shapes.CIRCLE, 50.0, 50.0, 40.0, 40.0)
        val circle = d.items.last()
        val img = d.image(1000, 50.0, 50.0, 20.0, 20.0)
        assertEquals(img, d.hit(50.0, 50.0, 1.0))           // dentro del círculo y sobre la imagen
        assertEquals(circle, d.hit(70.0, 50.0, 1.0))        // en el trazo
        assertEquals(circle, d.hit(35.0, 50.0, 1.0))        // dentro del círculo, fuera de la imagen
    }

    @Test
    fun lineBelowAnImageIsCoveredByIt() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 40.0, 40.0)
        val line = d.items.last()
        val img = d.image(1000, 50.0, 50.0, 60.0, 60.0)
        assertEquals(line, d.hit(70.0, 50.0, 1.0))
        d.sel.clear(); d.sel += d.keyOf(line)
        assertTrue(d.moveLayer(-1))
        assertEquals(listOf(line, img), d.items)
        assertEquals(img, d.hit(70.0, 50.0, 1.0))
        assertEquals(false, d.moveLayer(-1))                // ya está abajo de todo
        assertTrue(d.moveLayer(1))
        assertEquals(listOf(img, line), d.items)
    }

    @Test
    fun layerStepsOverWhatItDoesNotCross() {
        val d = doc()
        val img = d.image(1000, 50.0, 50.0, 20.0, 20.0)
        d.addShape(Shapes.RECT, 150.0, 150.0, 10.0, 10.0)   // lejos: no cuenta como un paso
        val far = d.items.last()
        d.addShape(Shapes.RECT, 50.0, 50.0, 10.0, 10.0)
        val near = d.items.last()
        assertTrue(d.moveLayer(-1))
        assertEquals(listOf(near, img, far), d.items)
    }

    @Test
    fun duplicatedImageStaysUnderTheLines() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 40.0, 40.0)
        val line = d.items.last()
        val img = d.image(1000, 150.0, 150.0, 20.0, 20.0)
        d.sel.clear(); d.sel += d.keyOf(img)
        d.duplicate()
        assertEquals(listOf(true, true, false), d.items.map { it.isImage })
        assertEquals(line, d.items.last())
    }

    @Test
    fun severalGroupsCanBeUngrouped() {
        val d = doc()
        d.addShape(Shapes.RECT, 20.0, 20.0, 10.0, 10.0)
        d.fill(doubleArrayOf(0.0, 0.0, 30.0, 12.0), 2.0)     // cada copia queda como su grupo
        assertTrue(d.sel.size > 1 && d.allGrouped)
        d.ungroup()
        assertEquals(false, d.allGrouped)
    }

    @Test
    fun cutOrderIgnoresTheStack() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 40.0, 40.0)
        val a = d.items.last()
        d.image(1000, 50.0, 50.0, 60.0, 60.0)
        d.sel.clear(); d.sel += d.keyOf(a)
        d.moveLayer(-1)
        assertEquals(listOf(a), d.cutOrder { true })        // tapada, se corta igual
    }

    @Test
    fun circleHasRadiusAndIsClosed() {
        val p = Shapes.points(Shapes.CIRCLE, 50.0, 40.0, 30.0, 30.0, 0.0)
        for (k in 0 until p.size / 2) assertEquals(15.0, hypot(p[2 * k] - 50.0, p[2 * k + 1] - 40.0), 1e-9)
        assertEquals(p[0], p[p.size - 2], 1e-9)
        assertEquals(p[1], p[p.size - 1], 1e-9)
    }

    @Test
    fun roundedRectStaysInsideItsBox() {
        val b = Item.bboxOf(Shapes.points(Shapes.ROUND, 100.0, 100.0, 60.0, 40.0, 6.0))
        assertEquals(70.0, b[0], 1e-9); assertEquals(130.0, b[2], 1e-9)
        assertEquals(80.0, b[1], 1e-9); assertEquals(120.0, b[3], 1e-9)
    }

    @Test
    fun roundedCornersStayRoundWhenStretched() {
        val d = doc()
        d.addShape(Shapes.ROUND, 100.0, 100.0, 60.0, 40.0, 6.0)
        val it = d.items.last()
        d.apply(A.sc(2.0, 1.0, 100.0, 100.0))
        val b = it.bbox()
        assertEquals(120.0, b[2] - b[0], 1e-9); assertEquals(40.0, b[3] - b[1], 1e-9)
        val w = it.world()
        val cx = b[2] - 6.0; val cy = b[1] + 6.0                 // esquina superior derecha, radio 6 intacto
        for (k in 0..12) assertEquals(6.0, hypot(w[2 * k] - cx, w[2 * k + 1] - cy), 1e-9)
    }

    @Test
    fun roundedCornersGrowWithAnEvenScaleAndKeepTheirTurn() {
        val d = doc()
        d.addShape(Shapes.ROUND, 100.0, 100.0, 60.0, 40.0, 6.0)
        val it = d.items.last()
        d.apply(A.sc(2.0, 2.0, 100.0, 100.0))
        d.apply(A.rot(90.0, 100.0, 100.0))
        val b = it.bbox()
        assertEquals(80.0, b[2] - b[0], 1e-9); assertEquals(120.0, b[3] - b[1], 1e-9)
        val again = Doc.fromProject(d.toJson { null }) { null }.items.last().bbox()
        assertEquals(b[2] - b[0], again[2] - again[0], 1e-6)     // el radio vuelve con el proyecto
    }

    @Test
    fun centerInWorkArea() {
        val d = doc()
        d.addShape(Shapes.RECT, 30.0, 30.0, 20.0, 10.0)
        d.centerIn(doubleArrayOf(15.0, 15.0, 195.0, 267.0))
        val b = d.selBBox()
        assertEquals(105.0, (b[0] + b[2]) / 2, 1e-9)
        assertEquals(141.0, (b[1] + b[3]) / 2, 1e-9)
    }

    @Test
    fun fillCountsLikeTheMockupAndStaysInsideTheArea() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 70.0, 100.0)
        val area = doubleArrayOf(15.0, 15.0, 195.0, 267.0)       // A4 con marcas: 180 × 252 mm
        val plan = d.fillPlan(area, 3.0)
        assertEquals(4, plan.count); assertEquals(Packing.GRID, plan.layout)
        d.fill(area, 3.0)
        assertEquals(4, d.items.size)
        assertEquals(4, d.sel.size)                              // cada copia es su propio grupo
        val b = d.bboxAll(d.items)
        assertTrue(b[0] >= 15.0 && b[1] >= 15.0 && b[2] <= 195.0 && b[3] <= 267.0)
        assertEquals(143.0, b[2] - b[0], 1e-3)                   // 70 + 3 + 70
    }

    @Test
    fun circlesPackInAlternatingColumnsWithTheFullGap() {
        val d = doc()
        d.addShape(Shapes.CIRCLE, 50.0, 50.0, 40.0, 40.0)
        val area = doubleArrayOf(10.0, 10.0, 200.0, 272.0)       // A4 con marcas: 190 × 262 mm
        val plan = d.fillPlan(area, 3.0)
        assertEquals(28, plan.count)                             // en grilla entrarían 24
        assertEquals(Packing.COLUMNS, plan.layout)
        d.fill(area, 3.0)
        val centers = d.items.map { it.bbox().let { b -> (b[0] + b[2]) / 2 to (b[1] + b[3]) / 2 } }
        for (i in centers.indices) for (j in 0 until i) {
            val dist = hypot(centers[i].first - centers[j].first, centers[i].second - centers[j].second)
            assertTrue("copias a $dist mm", dist >= 43.0 - 0.05)
        }
        val b = d.bboxAll(d.items)
        assertTrue(b[0] >= 10.0 - 1e-6 && b[1] >= 10.0 - 1e-6 && b[2] <= 200.0 + 1e-6 && b[3] <= 272.0 + 1e-6)
    }

    @Test
    fun fillTurnsTheDesignWhenMoreCopiesFit() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 100.0, 40.0)
        val area = doubleArrayOf(0.0, 0.0, 130.0, 210.0)
        val plan = d.fillPlan(area, 3.0)
        assertTrue(plan.rotated)
        assertEquals(6, plan.count)                              // sin girar serían 4
        d.fill(area, 3.0)
        assertEquals(6, d.items.size)
        val q = d.items[0].bbox()
        assertEquals(40.0, q[2] - q[0], 1e-6); assertEquals(100.0, q[3] - q[1], 1e-6)
    }

    @Test
    fun gapIsMeasuredBetweenCutLinesAndImagesStayInside() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 40.0, 40.0)
        d.image(1000, 50.0, 50.0, 46.0, 46.0)                    // sangrado de 3 mm por lado
        d.selectAll()
        assertEquals(2, d.fillPlan(doubleArrayOf(0.0, 0.0, 89.5, 46.0), 3.0).count)      // 46 + 43
        assertEquals(1, d.fillPlan(doubleArrayOf(0.0, 0.0, 86.0, 46.0), 3.0).count)
    }

    @Test
    fun squareImageNeverPrintsInsideTheNextCircle() {
        val d = doc()
        d.addShape(Shapes.CIRCLE, 50.0, 50.0, 40.0, 40.0)
        d.image(1000, 50.0, 50.0, 40.0, 40.0)                    // las esquinas sobran del círculo
        d.selectAll()
        val plan = d.fillPlan(doubleArrayOf(10.0, 10.0, 200.0, 272.0), 3.0)
        assertEquals(24, plan.count)                             // alternar aquí metería esquinas en los vecinos
    }

    @Test
    fun slantedShapesDoNotOverlapDiagonally() {
        val d = doc()
        d.addShape(Shapes.RECT, 100.0, 100.0, 100.0, 10.0)
        d.sel.clear(); d.sel += d.keyOf(d.items.last())
        d.apply(A.rot(45.0, 100.0, 100.0))
        val area = doubleArrayOf(0.0, 0.0, 190.0, 262.0)
        d.fill(area, 3.0)
        val its = d.items
        assertTrue(its.size > 1)
        for (i in its.indices) for (j in 0 until i) {
            val a = its[i].world(); val b = its[j].world()
            for (k in 0 until a.size / 2) assertEquals("esquina dentro de otra copia", false, Doc.pointInPoly(a[2 * k], a[2 * k + 1], b))
        }
    }

    @Test
    fun straightLineWithoutGapDoesNotExplode() {
        val d = doc()
        d.items += Item(1, false, doubleArrayOf(0.0, 0.0, 100.0, 100.0), false, null, null)
        d.sel += d.keyOf(d.items.last())
        val plan = d.fillPlan(doubleArrayOf(0.0, 0.0, 190.0, 262.0), 0.0)
        assertTrue(plan.count <= Doc.MAX_FILL + 1)
    }

    @Test
    fun fillKeepsAGroupTogether() {
        val d = doc()
        d.addShape(Shapes.CIRCLE, 50.0, 50.0, 30.0, 30.0)
        d.addShape(Shapes.RECT, 50.0, 50.0, 40.0, 40.0)
        d.selectAll()
        d.fill(doubleArrayOf(0.0, 0.0, 100.0, 50.0), 2.0)        // caben 2 de 40
        assertEquals(4, d.items.size)
        assertEquals(2, d.sel.size)
    }

    @Test
    fun fillThatDoesNotFitDoesNothing() {
        val d = doc()
        d.addShape(Shapes.RECT, 50.0, 50.0, 300.0, 300.0)
        d.fill(doubleArrayOf(0.0, 0.0, 100.0, 100.0), 3.0)
        assertEquals(1, d.items.size)
    }

    @Test
    fun blankSheetBBoxIsZero() = assertEquals(0.0, doc().cutBBox()[2], 0.0)

    @Test
    fun projectRoundTripKeepsPositionsGroupsAndIds() {
        val d = doc()
        d.addShape(Shapes.CIRCLE, 50.0, 50.0, 30.0, 30.0)
        d.addShape(Shapes.RECT, 50.0, 50.0, 40.0, 40.0)
        d.selectAll(); d.group()
        d.apply(A.tr(12.5, -3.0))
        val back = Doc.fromProject(org.json.JSONObject(d.toJson { it }.toString())) { null }
        assertEquals(2, back.items.size)
        assertTrue(back.items.all { it.g != null } && back.items[0].g == back.items[1].g)
        val a = d.bboxAll(d.items); val b = back.bboxAll(back.items)
        for (k in 0..3) assertEquals(a[k], b[k], 0.01)
        // lo nuevo no choca con lo guardado
        back.addShape(Shapes.RECT, 10.0, 10.0, 5.0, 5.0)
        assertEquals(3, back.items.map { it.id }.toSet().size)
    }

    @Test
    fun matCodesForTheMachine() {
        assertEquals(1, Support.PRESETS.first { it.id == "mat12" }.tg)
        assertEquals(2, Support.PRESETS.first { it.id == "mat24" }.tg)
        val a4 = Support.PRESETS.first { it.id == "a4" }
        assertEquals(0, a4.tg)
        assertEquals(1, a4.copy(onMat = true).tg)
        assertEquals(2, Support("custom", "x", 300.0, 500.0, mat = false, onMat = true).tg)   // no cabe en 12x12
    }
}
