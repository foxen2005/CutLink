package cl.digitalfox.cameolink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Estado compartido de la app: la conexión con la Cameo, la cola de comandos, el registro y el
 * trabajo de corte. Vive mientras viva el proceso, así el visor y la pantalla de máquina usan la
 * misma conexión. Las pantallas se suscriben con [listen] y reciben los cambios en el hilo de UI.
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object Machine : LinkListener {
    interface Observer {
        fun onMachineChanged()
    }

    private val ui = Handler(Looper.getMainLooper())
    // Un solo hilo para todo lo que habla con la máquina: los comandos nunca se intercalan
    val worker = Executors.newSingleThreadExecutor()
    // Python (abrir archivos) en otro hilo: leer un .studio3 no frena los comandos ni la UI
    val files = Executors.newSingleThreadExecutor()
    val session = CameoSession(::onLog)

    private lateinit var app: Context
    lateinit var adapter: BluetoothAdapter
        private set
    var ble: BleLink? = null
        private set
    var spp: SppLink? = null
        private set

    @Volatile var connText = "Sin conexión"
        private set
    @Volatile var machineText = "—"
        private set
    @Volatile var cutting = false
        private set

    // Posición del carro que lleva la app (mm desde el origen). La máquina no la informa.
    var posX = 0.0
    var posY = 0.0

    val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val observers = mutableListOf<Observer>()
    private var notifyPending = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        adapter = app.getSystemService(BluetoothManager::class.java).adapter
    }

    val connected get() = session.link != null

    fun listen(o: Observer) { observers += o; o.onMachineChanged() }
    fun unlisten(o: Observer) { observers -= o }

    /** Varios cambios seguidos (ráfagas de bytes) se avisan una sola vez cada 150 ms. */
    private fun changed() = ui.post {
        if (notifyPending) return@post
        notifyPending = true
        ui.postDelayed({
            notifyPending = false
            observers.toList().forEach { it.onMachineChanged() }
        }, 150)
    }

    override fun onLog(msg: String) {
        android.util.Log.i("CameoLink", msg)     // también a logcat: se lee por adb sin tocar la pantalla
        val line = "${timeFmt.format(Date())}  $msg"
        ui.post {
            logLines.addFirst(line)
            while (logLines.size > 800) logLines.removeLast()
        }
        changed()
    }

    fun toast(msg: String) = ui.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    fun setMachine(msg: String) { machineText = msg; changed() }
    private fun setConn(msg: String) { connText = msg; changed() }

    // ------------------------------------------------------------------ Python
    fun python(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        return Python.getInstance()
    }

    // ------------------------------------------------------------------ conexión
    fun connectBle(d: BluetoothDevice) {
        closeLinks()
        setConn("Conectando por BLE a ${d.name ?: d.address}…")
        ble = BleLink(app, d, this).also { it.connect() }
    }

    fun connectSpp(d: BluetoothDevice) {
        closeLinks()
        setConn("Conectando por Bluetooth clásico a ${d.name ?: d.address}…")
        val link = SppLink(d, adapter, this)
        spp = link
        worker.execute {
            try {
                link.connect()
            } catch (e: Exception) {
                if (spp === link) {
                    spp = null
                    onLinkLost(e.message ?: "falló la conexión")
                }
            }
        }
    }

    private fun closeLinks() {
        session.link = null
        ble?.close()
        spp?.close()
        ble = null
        spp = null
    }

    fun disconnect(msg: String = "Desconectado") {
        closeLinks()
        setConn(msg)
        setMachine("—")
    }

    private fun isCurrent(link: CameoLink) = link === ble || link === spp

    fun connLabel(): String {
        val link = session.link ?: return connText
        return if (link is BleLink) "${link.label} · TX ${link.tx?.let { shortUuid(it.uuid) } ?: "—"} · " +
            "RX ${link.rx?.let { shortUuid(it.uuid) } ?: "—"}"
        else link.label
    }

    override fun onLinkReady(link: CameoLink) {
        if (!isCurrent(link)) {     // un enlace que el usuario ya descartó
            link.close()
            return
        }
        session.link = link
        ui.post { posX = 0.0; posY = 0.0 }
        setConn("Conectado: ${connLabel()}")
        if (link is BleLink) {
            val rx = link.suggestedRx
            if (rx != null) worker.execute {
                try {
                    link.selectRx(rx)
                } catch (e: Exception) {
                    onLog("No se pudo activar RX: ${e.message}")
                }
                setConn("Conectado: ${connLabel()}")
            }
        }
        onLog("Listo. Prueba «Estado»: si responde, el canal es el correcto.")
        run("Estado") { refreshStatus() }
    }

    override fun onLinkLost(reason: String) {
        ui.post {
            closeLinks()
            setConn("Sin conexión: $reason")
            setMachine("—")
        }
        onLog(reason)
    }

    override fun onBytes(data: ByteArray) = session.onBytes(data)

    fun selectTx(link: BleLink, c: android.bluetooth.BluetoothGattCharacteristic) {
        link.selectTx(c)
        setConn("Conectado: ${connLabel()}")
    }

    fun selectRx(link: BleLink, c: android.bluetooth.BluetoothGattCharacteristic?) = worker.execute {
        try {
            link.selectRx(c)
        } catch (e: Exception) {
            onLog("No se pudo activar RX: ${e.message}")
        }
        setConn("Conectado: ${connLabel()}")     // muestra el RX que de verdad quedó activo
    }

    // ------------------------------------------------------------------ comandos
    /** Ejecuta en el hilo de trabajo; los errores van al registro, a un toast y a onError (en la UI). */
    fun run(what: String, onError: () -> Unit = {}, block: () -> Unit): Boolean {
        if (!connected) {
            toast("Primero conecta la Cameo")
            return false
        }
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                onLog("$what: ${e.message}")
                ui.post { onError() }
                toast("$what: ${e.message}")
            }
        }
        return true
    }

    fun refreshStatus(): String? {
        val st = session.status()
        setMachine(st ?: "sin respuesta (¿canal RX?)")
        return st
    }

    fun ensureInitialized() {
        if (!session.initialized) {
            onLog("Inicializando (ESC EOT + FG)…")
            session.initialize()
        }
    }

    /** Mueve el carro (herramienta arriba) a una posición absoluta en mm. */
    fun moveTo(x: Double, y: Double, onError: () -> Unit = {}) = run("Mover", onError) {
        ensureInitialized()
        session.sendCommands(Gpgl.moveTo(x, y))
    }

    // ------------------------------------------------------------------ corte
    class Progress(val fraction: Double, val text: String)

    // El estado del corte vive aquí y no en la pantalla: si el visor se cierra o se recrea
    // durante el corte, la nueva pantalla sigue viendo el avance y puede Detener.
    @Volatile var progress: Progress? = null
        private set
    @Volatile var lastResult: String? = null       // null = terminó bien (o nunca hubo corte)
        private set
    private var abortFlag = AtomicBoolean(false)

    fun abortCut() { if (cutting) { abortFlag.set(true); progress = Progress(progress?.fraction ?: 0.0, "Deteniendo…"); changed() } }
    val stopRequested get() = cutting && abortFlag.get()

    /** Como wait_for_ready del bridge: espera hasta [timeoutMs] a que diga "lista". */
    private fun waitReady(timeoutMs: Long): String? {
        val t0 = System.currentTimeMillis()
        var st = session.status()
        while (st != "lista" && st != "sin material" && System.currentTimeMillis() - t0 < timeoutMs) {
            if (abortFlag.get()) return "detenido"
            Thread.sleep(500)
            st = session.status()
        }
        return st
    }

    /**
     * find_regmarks del bridge: configura y lanza la búsqueda (la máquina escanea, hasta 40 s).
     * Una sola implementación para «Probar búsqueda» y para «Escanear y cortar». Se puede detener
     * durante la espera. Devuelve (encontradas, respuesta cruda). Si no las encuentra, sube la
     * cuchilla y vuelve al origen.
     */
    fun findRegmarks(r: Gpgl.Regmarks): Pair<Boolean, String?> {
        session.sendCommands(Gpgl.regmarkCommands(r))
        session.send(Gpgl.encode(listOf(Gpgl.regmarkSearchCommand(r))))
        val t0 = System.currentTimeMillis()
        var resp: String? = null
        while (resp == null && System.currentTimeMillis() - t0 < 40_000 && !stopRequested) resp = session.readReply(1000)
        val ok = resp?.trim() == "0"
        if (!ok) session.sendCommands(listOf("SO0", "FN0"))
        onLog("Búsqueda de marcas: respuesta ${resp?.trim() ?: if (stopRequested) "(detenida)" else "ninguna"}")
        return ok to resp?.trim()
    }

    /**
     * Mismo flujo que run_job del bridge: inicializa, espera "lista", configura, envía el trazado en
     * bloques de 64 comandos (entre bloques se puede detener: sube la cuchilla y vuelve al origen),
     * termina con M0,0 y espera a que la máquina quede lista. Avance y resultado en [progress] /
     * [lastResult] (las pantallas los leen en onMachineChanged); [preCut] corre antes del trazado
     * (Print & Cut: buscar las marcas) y devuelve el offset de origen o lanza si falla.
     */
    fun cut(s: Gpgl.CutSettings, paths: List<List<Pair<Double, Double>>>,
            preCut: (() -> Pair<Double, Double>)? = null): String? {
        if (!connected) return "Primero conecta la Cameo"
        if (cutting) return "Ya hay un corte en curso"
        cutting = true
        abortFlag = AtomicBoolean(false)
        lastResult = null
        progress = Progress(0.0, "Inicializando")
        changed()
        fun report(f: Double, t: String) { progress = Progress(f, t); changed() }
        fun finish(err: String?) {
            cutting = false
            lastResult = err
            progress = null
            changed()
            toast(err ?: "Corte terminado")
        }
        worker.execute {
            try {
                s.validate()
                session.initialize()
                val st = waitReady(20_000)
                if (st != "lista") {
                    setMachine(st ?: "sin respuesta")
                    finish(when (st) {
                        "detenido" -> "Detenido antes de empezar"
                        null -> "La máquina no responde: no se corta a ciegas"
                        "sin material" -> "No hay material cargado en la máquina"
                        else -> "La máquina no está lista ($st)"
                    })
                    return@execute
                }
                setMachine("cortando…")
                session.sendCommands(Gpgl.setupCommands(s))
                var offset = 0.0 to 0.0
                if (preCut != null) {
                    report(0.02, "Buscando marcas de registro…")
                    offset = preCut()
                }
                val cmds = Gpgl.plotCommands(paths, Gpgl.bboxOf(paths), s.passes, offset)
                val total = cmds.size
                for (i in cmds.indices step 64) {
                    if (abortFlag.get()) {
                        session.sendCommands(listOf("SO0", "FN0"))     // cuchilla arriba y al origen
                        setMachine("detenido")
                        ui.post { posX = 0.0; posY = 0.0 }
                        finish("Detenido: cuchilla arriba y de vuelta al origen")
                        return@execute
                    }
                    val end = minOf(total, i + 64)
                    session.sendCommands(cmds.subList(i, end))
                    report(0.05 + 0.85 * end / total, "Enviando $end de $total comandos")
                }
                session.sendCommands(Gpgl.endCommands())
                ui.post { posX = 0.0; posY = 0.0 }
                report(0.92, "Cortando… esperando a la máquina")
                val t0 = System.currentTimeMillis()
                var misses = 0
                var warned = false
                while (true) {
                    Thread.sleep(700)
                    // Todo el trabajo ya está en la máquina: desde aquí no se frena el carro. Se sigue
                    // esperando (y la app sigue "cortando") para que nadie le mande otro trabajo encima.
                    if (abortFlag.get() && !warned) {
                        warned = true
                        report(0.95, "La Cameo termina lo que ya recibió: usa su botón de pausa si hace falta")
                    }
                    val now = session.status()
                    when {
                        now == "lista" -> break
                        now == "moviéndose" -> misses = 0
                        // respuesta perdida o mal formada (sobre todo por Bluetooth): no es un error, se reintenta
                        now == null || now.startsWith("desconocido") ->
                            if (++misses >= 6) { finish("Sin respuesta clara de la máquina durante el corte"); return@execute }
                        else -> { finish("Estado inesperado durante el corte: $now"); return@execute }
                    }
                    if (System.currentTimeMillis() - t0 > 30 * 60_000) { finish("El corte no terminó en 30 min"); return@execute }
                }
                if (warned) { setMachine("lista"); finish("La Cameo terminó lo que ya había recibido"); return@execute }
                setMachine("lista")
                onLog("Corte terminado: ${paths.size} trazados")
                finish(null)
            } catch (e: Exception) {
                onLog("Corte: ${e.message}")
                finish(e.message ?: "error")
            }
        }
        return null
    }
}
