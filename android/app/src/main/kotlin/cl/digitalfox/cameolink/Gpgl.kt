package cl.digitalfox.cameolink

/**
 * Port mínimo de bridge/gpgl.py: mismos comandos y unidades (1 mm = 20 SU, coordenadas y,x).
 * Puro, sin Android: se prueba con JUnit contra los mismos casos que test_gpgl.py.
 */
object Gpgl {
    const val ESC: Byte = 0x1b
    const val ENQ: Byte = 0x05
    const val EOT: Byte = 0x04
    const val ETX: Byte = 0x03

    const val SU_PER_MM = 20.0
    const val WIDTH_MM = 304.8          // ancho útil Cameo 4 (eje del carro, x)
    const val LENGTH_MM = 3000.0        // largo máximo (avance del papel, y)
    val SPEED_RANGE = 1..30
    val PRESSURE_RANGE = 1..33
    const val RATCHET_OFFSET_MM = 0.25  // cuchilla de trinquete de Jean (no enviar TF)

    val STATUS_REQUEST = byteArrayOf(ESC, ENQ)
    val INIT = byteArrayOf(ESC, EOT)

    /** mm -> SU. Math.rint redondea al par igual que round() de Python. */
    fun mm(v: Double): Int = Math.rint(v * SU_PER_MM).toInt()

    fun statusName(reply: String): String = when (reply.trim()) {
        "0" -> "lista"
        "1" -> "moviéndose"
        "2" -> "sin material"
        else -> "desconocido ($reply)"
    }

    data class CutSettings(
        val speed: Int = 10,
        val pressure: Int = 10,
        val mat: Int = 1,                 // 0 = sin mat, 1 = 12x12, 2 = 12x24
        val tool: Int = 1,
        val bladeOffsetMm: Double = RATCHET_OFFSET_MM,
        val lift: Boolean = false,
        val sharpenCorners: Boolean = false,
    ) {
        fun validate() {
            require(speed in SPEED_RANGE) { "velocidad fuera de rango $SPEED_RANGE: $speed" }
            require(pressure in PRESSURE_RANGE) { "presión fuera de rango $PRESSURE_RANGE: $pressure" }
            require(mat in 0..2) { "mat inválido: $mat" }
            require(tool in 1..2) { "carro debe ser 1 o 2: $tool" }
        }
    }

    /** setup_commands() con cuchilla de trinquete: sin TF. */
    fun setupCommands(s: CutSettings): List<String> {
        val t = s.tool
        val sh = if (s.sharpenCorners) 1 else 0
        return listOf(
            "TG${s.mat}",
            "FN0", "TB50,0",
            "J$t",
            "FX${s.pressure},$t",
            "TJ0",
            "!${s.speed},$t",
            "FC0,1,$t",
            "FE${if (s.lift) 1 else 0},$t",
            "FF$sh,$sh,$t",
            "FX${s.pressure},$t",
            "TJ3",
            "FC${mm(s.bladeOffsetMm)},1,$t",
        )
    }

    /** plot_commands() sin offset: clip + M/D. Puntos (x,y) en mm; bbox = minx,miny,maxx,maxy. */
    fun plotCommands(paths: List<List<Pair<Double, Double>>>, bbox: DoubleArray): List<String> {
        val (minx, miny, maxx, maxy) = bbox
        val cmds = mutableListOf(
            "\\${mm(miny)},${mm(minx)}",
            "Z${mm(maxy)},${mm(maxx)}",
            "L0", "FE0,0", "FF0,0,0",
        )
        for (pts in paths) {
            if (pts.size < 2) continue
            val (x0, y0) = pts[0]
            cmds += "M${mm(y0)},${mm(x0)}"
            for ((x, y) in pts.drop(1)) cmds += "D${mm(y)},${mm(x)}"
        }
        return cmds
    }

    /** end_commands() con return_home: vuelve al origen y suelta la herramienta. */
    fun endCommands(): List<String> = listOf("M0,0", "SO0", "FN0")

    /**
     * Movimiento con la herramienta arriba a una posición absoluta (mm desde el origen).
     * Usa M (probado en cada corte real) en vez del O relativo del bridge, que nunca se usó en la máquina.
     */
    fun moveTo(xMm: Double, yMm: Double): List<String> = listOf("FN0", "M${mm(yMm)},${mm(xMm)}")

    fun square(xMm: Double, yMm: Double, sizeMm: Double): List<Pair<Double, Double>> = listOf(
        xMm to yMm, xMm + sizeMm to yMm, xMm + sizeMm to yMm + sizeMm, xMm to yMm + sizeMm, xMm to yMm,
    )

    /** Trabajo completo del cuadrado de prueba (sin el initialize, que necesita leer respuestas). */
    fun testSquareJob(s: CutSettings, xMm: Double, yMm: Double, sizeMm: Double): List<String> {
        s.validate()
        val bbox = doubleArrayOf(xMm, yMm, xMm + sizeMm, yMm + sizeMm)
        return setupCommands(s) + plotCommands(listOf(square(xMm, yMm, sizeMm)), bbox) + endCommands()
    }

    /** Cada comando ASCII terminado en ETX, todos en un solo buffer. */
    fun encode(cmds: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in cmds) {
            out.write(c.toByteArray(Charsets.US_ASCII))
            out.write(ETX.toInt())
        }
        return out.toByteArray()
    }
}
