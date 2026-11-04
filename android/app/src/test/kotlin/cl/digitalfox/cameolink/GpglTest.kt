package cl.digitalfox.cameolink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mismos casos que bridge/tests/test_gpgl.py: el port debe generar exactamente los mismos comandos. */
class GpglTest {
    @Test
    fun mmToSu() {
        assertEquals(20, Gpgl.mm(1.0))
        assertEquals(1000, Gpgl.mm(50.0))
        assertEquals(1, Gpgl.mm(0.05))
        assertEquals(0, Gpgl.mm(0.024))
        assertEquals(2, Gpgl.mm(0.125))   // 2.5 SU -> 2, como round() de Python
    }

    @Test
    fun setupUsesToolholderAndRatchetOffset() {
        val cmds = Gpgl.setupCommands(Gpgl.CutSettings(speed = 10, pressure = 12, mat = 1))
        assertEquals("TG1", cmds[0])
        assertTrue("J1" in cmds)
        assertTrue("!10,1" in cmds)
        assertEquals(2, cmds.count { it == "FX12,1" })
        assertTrue("FC5,1,1" in cmds)                  // 0.25 mm = 5 SU
        assertFalse(cmds.any { it.startsWith("TF") })  // trinquete: sin TF
    }

    @Test
    fun setupMatchesPythonSequence() {
        // setup_commands(CutSettings(blade="ratchet")) en el bridge
        assertEquals(
            listOf("TG1", "FN0", "TB50,0", "J1", "FX10,1", "TJ0", "!10,1", "FC0,1,1", "FE0,1",
                "FF0,0,1", "FX10,1", "TJ3", "FC5,1,1"),
            Gpgl.setupCommands(Gpgl.CutSettings()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSpeedOutOfRange() = Gpgl.CutSettings(speed = 31).validate()

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPressureOutOfRange() = Gpgl.CutSettings(pressure = 34).validate()

    @Test
    fun plotSquareSwapsXyAndClips() {
        val sq = listOf(10.0 to 20.0, 60.0 to 20.0, 60.0 to 70.0, 10.0 to 70.0, 10.0 to 20.0)
        val cmds = Gpgl.plotCommands(listOf(sq), doubleArrayOf(10.0, 20.0, 60.0, 70.0))
        assertEquals("\\400,200", cmds[0])
        assertEquals("Z1400,1200", cmds[1])
        assertEquals("M400,200", cmds[5])
        assertEquals("D400,1200", cmds[6])
        assertEquals("D400,200", cmds.last())
    }

    @Test
    fun endReturnsHome() = assertEquals(listOf("M0,0", "SO0", "FN0"), Gpgl.endCommands())

    @Test
    fun moveToIsAbsoluteYx() = assertEquals(listOf("FN0", "M300,100"), Gpgl.moveTo(5.0, 15.0))

    @Test
    fun testSquareJobMatchesPython() {
        // setup_commands + plot_commands + end_commands del bridge para el mismo cuadrado
        assertEquals(
            listOf("TG1", "FN0", "TB50,0", "J1", "FX10,1", "TJ0", "!10,1", "FC0,1,1", "FE0,1", "FF0,0,1",
                "FX10,1", "TJ3", "FC5,1,1", "\\200,200", "Z600,600", "L0", "FE0,0", "FF0,0,0",
                "M200,200", "D200,600", "D600,600", "D600,200", "D200,200", "M0,0", "SO0", "FN0"),
            Gpgl.testSquareJob(Gpgl.CutSettings(), 10.0, 10.0, 20.0),
        )
    }

    @Test
    fun encodeTerminatesEachCommandWithEtx() {
        assertArrayEquals(byteArrayOf('F'.code.toByte(), 'G'.code.toByte(), 3, 'L'.code.toByte(), '0'.code.toByte(), 3),
            Gpgl.encode(listOf("FG", "L0")))
    }

    @Test
    fun statusNames() {
        assertEquals("lista", Gpgl.statusName("0"))
        assertEquals("sin material", Gpgl.statusName("2\r"))
    }

    // ---- Print & Cut: mismos casos que test_gpgl.py ----
    @Test
    fun plotOffsetShiftsToRegmarkOrigin() {
        val cmds = Gpgl.plotCommands(listOf(listOf(15.0 to 15.0, 25.0 to 15.0)), doubleArrayOf(15.0, 15.0, 25.0, 15.0), offset = 10.0 to 10.0)
        assertTrue("M100,100" in cmds && "D100,300" in cmds)
    }

    @Test
    fun regmarkSearchGeometry() =
        assertEquals("TB123,5540,3800,200,200", Gpgl.regmarkSearchCommand(Gpgl.Regmarks(210.0, 297.0)))

    @Test
    fun regmarkBottomInsetReducesHeight() {
        val r = Gpgl.Regmarks(210.0, 297.0, insetBottom = 25.0)
        assertEquals(262.0, r.height, 1e-9)
        assertEquals("TB123,5240,3800,200,200", Gpgl.regmarkSearchCommand(r))
    }

    @Test
    fun regmarkSearchAddsMatOffset() =
        assertEquals("TB123,5540,3800,500,300", Gpgl.regmarkSearchCommand(Gpgl.Regmarks(210.0, 297.0), 5.0 to 15.0))

    @Test
    fun regmarkSetupUsesThicknessAndLength() {
        val cmds = Gpgl.regmarkCommands(Gpgl.Regmarks(210.0, 297.0, thick = 0.3))
        assertTrue("TB51,400" in cmds && "TB53,6" in cmds)
    }

    @Test
    fun manualRegmarkUsesTB23() =
        assertEquals("TB23,5240,3800", Gpgl.regmarkSearchCommand(Gpgl.Regmarks(210.0, 297.0, insetBottom = 25.0, manual = true), 5.0 to 5.0))

    @Test
    fun searchOffsetShiftsStartAndClamps() =
        // top = 10 − 15 -> se recorta a 0; left = 10 + 5
        assertEquals("TB123,5540,3800,0,300", Gpgl.regmarkSearchCommand(Gpgl.Regmarks(210.0, 297.0, searchX = 5.0, searchY = -15.0)))

    @Test
    fun calibratedDefaultsOfJeansCameo() =
        // A4 sin mat con los valores del panel: inset 10, inferior 25, búsqueda −11/−8
        assertEquals("TB123,5240,3800,40,0", Gpgl.regmarkSearchCommand(Gpgl.Regmarks(210.0, 297.0, insetBottom = 25.0, searchX = -11.0, searchY = -8.0)))

    @Test
    fun describeShowsControlsAndHex() {
        assertEquals("<ESC><ENQ>  [1B 05]", CameoSession.describe(byteArrayOf(0x1b, 0x05)))
    }
}
