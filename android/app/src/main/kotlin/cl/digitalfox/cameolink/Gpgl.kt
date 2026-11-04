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
        val passes: Int = 1,
        val depth: Int? = null,           // solo AutoBlade (TF); con trinquete/pluma va null
    ) {
        fun validate() {
            require(speed in SPEED_RANGE) { "velocidad fuera de rango $SPEED_RANGE: $speed" }
            require(pressure in PRESSURE_RANGE) { "presión fuera de rango $PRESSURE_RANGE: $pressure" }
            require(mat in 0..2) { "mat inválido: $mat" }
            require(tool in 1..2) { "carro debe ser 1 o 2: $tool" }
            require(passes >= 1) { "pasadas debe ser al menos 1" }
            depth?.let { require(it in 1..10) { "profundidad fuera de rango 1..10: $it" } }
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
        ) + listOfNotNull(s.depth?.let { "TF$it,$t" })
    }

    /** plot_commands(): clip + M/D. Puntos (x,y) en mm; bbox = minx,miny,maxx,maxy. [offset] se
     *  RESTA a cada punto (Print & Cut: el origen pasa a ser la marca superior izquierda). */
    fun plotCommands(paths: List<List<Pair<Double, Double>>>, bbox: DoubleArray, passes: Int = 1,
                     offset: Pair<Double, Double> = 0.0 to 0.0): List<String> {
        val (minx, miny, maxx, maxy) = bbox
        val (ox, oy) = offset
        val cmds = mutableListOf(
            "\\${mm(miny - oy)},${mm(minx - ox)}",
            "Z${mm(maxy - oy)},${mm(maxx - ox)}",
            "L0", "FE0,0", "FF0,0,0",
        )
        repeat(passes) {
            for (pts in paths) {
                if (pts.size < 2) continue
                val (x0, y0) = pts[0]
                cmds += "M${mm(y0 - oy)},${mm(x0 - ox)}"
                for ((x, y) in pts.drop(1)) cmds += "D${mm(y - oy)},${mm(x - ox)}"
            }
        }
        return cmds
    }

    // ------------------------------------------------------------------ Print & Cut (RegmarkSettings)
    /** Distancias en mm; page = hoja impresa. Valores por defecto = los calibrados en la Cameo de
     *  Jean (panel de Illustrator): inset 10, inferior 25 sin mat, grosor 0,5, búsqueda −11/−8. */
    data class Regmarks(
        val pageW: Double,
        val pageH: Double,
        val inset: Double = 10.0,
        val insetBottom: Double? = null,     // sin mat la marca inferior va a 25 mm; null = igual que inset
        val thick: Double = 0.5,             // grosor de las líneas (TB53)
        val length: Double = 20.0,           // brazo de las L (TB51)
        val manual: Boolean = false,         // true: la cuchilla ya está sobre el cuadrado (TB23)
        val searchX: Double = 0.0,           // corrige dónde empieza a buscar el sensor (TB123 top/left)
        val searchY: Double = 0.0,
        val calibX: Double = 0.0,            // desfase constante del corte respecto de lo impreso
        val calibY: Double = 0.0,
    ) {
        val bottom get() = insetBottom ?: inset
        val height get() = pageH - inset - bottom
        val width get() = pageW - 2 * inset
        /** Origen del trazado una vez encontradas las marcas: la marca superior izquierda + calibración. */
        val origin get() = (inset + calibX) to (inset + calibY)
    }

    /** Rectángulos negros de las marcas (x, y, ancho, alto en mm de la hoja), igual que
     *  cameoAddRegmarks() del panel: cuadrado 5 mm arriba-izq, L arriba-der y L abajo-izq.
     *  Una sola fuente para el visor y el PDF impreso. */
    fun regmarkRects(r: Regmarks): List<DoubleArray> = listOf(
        doubleArrayOf(r.inset, r.inset, 5.0, 5.0),
        doubleArrayOf(r.pageW - r.inset - r.length, r.inset, r.length, r.thick),
        doubleArrayOf(r.pageW - r.inset - r.thick, r.inset, r.thick, r.length),
        doubleArrayOf(r.inset, r.pageH - r.bottom - r.thick, r.length, r.thick),
        doubleArrayOf(r.inset, r.pageH - r.bottom - r.length, r.thick, r.length),
    )

    /** regmark_commands(): secuencia previa a la búsqueda. */
    fun regmarkCommands(r: Regmarks): List<String> =
        listOf("TB50,0", "TB99", "TB52,2", "TB51,${mm(r.length)}", "TB53,${mm(r.thick)}", "TB55,1")

    /** regmark_search_command(): automático TB123,alto,ancho,top,left; manual TB23,alto,ancho. */
    fun regmarkSearchCommand(r: Regmarks, matOffset: Pair<Double, Double> = 0.0 to 0.0): String {
        if (r.manual) return "TB23,${mm(r.height)},${mm(r.width)}"
        val top = maxOf(0.0, r.inset + matOffset.second + r.searchY)
        val left = maxOf(0.0, r.inset + matOffset.first + r.searchX)
        return "TB123,${mm(r.height)},${mm(r.width)},${mm(top)},${mm(left)}"
    }

    fun bboxOf(paths: List<List<Pair<Double, Double>>>): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (p in paths) for ((x, y) in p) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
        return doubleArrayOf(x0, y0, x1, y1)
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
