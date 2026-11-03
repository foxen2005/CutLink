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

    @Test
    fun describeShowsControlsAndHex() {
        assertEquals("<ESC><ENQ>  [1B 05]", CameoSession.describe(byteArrayOf(0x1b, 0x05)))
    }
}
