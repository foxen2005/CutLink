package cl.digitalfox.cameolink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), LinkListener {

    private val ui = Handler(Looper.getMainLooper())
    // Un solo hilo para todo lo que habla con la máquina: los comandos nunca se intercalan
    private val worker = Executors.newSingleThreadExecutor()
    private val session = CameoSession(::onLog)

    private lateinit var adapter: BluetoothAdapter
    private var ble: BleLink? = null
    private var spp: SppLink? = null

    // Posición del carro que lleva la app (mm desde el origen). La máquina no la informa.
    // Solo se toca en el hilo de la UI; al hilo de trabajo se le pasa el destino ya calculado.
    private var posX = 0.0
    private var posY = 0.0

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private lateinit var connState: TextView
    private lateinit var machineState: TextView
    private lateinit var position: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets()

        adapter = getSystemService(BluetoothManager::class.java).adapter
        connState = findViewById(R.id.connState)
        machineState = findViewById(R.id.machineState)
        position = findViewById(R.id.position)
        logView = findViewById(R.id.log)

        findViewById<Spinner>(R.id.mat).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Sin mat", "Mat 12x12", "Mat 12x24"))
            setSelection(1)     // mismo valor por defecto que el panel de Illustrator
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { withPermissions { showScanDialog() } }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnect("Desconectado") }
        findViewById<Button>(R.id.btnChannels).setOnClickListener { showChannelDialog() }
        findViewById<Button>(R.id.btnStatus).setOnClickListener { runMachine("Estado") { refreshStatus() } }
        findViewById<Button>(R.id.btnFirmware).setOnClickListener {
            runMachine("Firmware") {
                val fw = session.firmware()
                setMachine(if (fw == null) "Firmware: sin respuesta" else "Firmware: $fw")
            }
        }
        findViewById<Button>(R.id.btnInit).setOnClickListener {
            runMachine("Inicializar") {
                session.initialize()
                refreshStatus()
            }
        }

        // Paso 0: el bridge (Python) corre dentro de la app. No necesita la máquina conectada.
        findViewById<Button>(R.id.btnPython).setOnClickListener {
            worker.execute {
                try {
                    val t0 = System.currentTimeMillis()
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                    val tStart = System.currentTimeMillis() - t0
                    val r = Python.getInstance().getModule("cameo_probe").callAttr("run").toString()
                    onLog("Python: arranque $tStart ms · total ${System.currentTimeMillis() - t0} ms")
                    onLog("Python: $r")
                } catch (e: Throwable) {
                    onLog("Python falló: ${e.message}")
                }
            }
        }

        findViewById<Button>(R.id.btnUp).setOnClickListener { jog(0.0, -step()) }
        findViewById<Button>(R.id.btnDown).setOnClickListener { jog(0.0, step()) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { jog(-step(), 0.0) }
        findViewById<Button>(R.id.btnRight).setOnClickListener { jog(step(), 0.0) }
        findViewById<Button>(R.id.btnHome).setOnClickListener { jog(-posX, -posY) }

        findViewById<Button>(R.id.btnCut).setOnClickListener { confirmTestCut() }

        val raw = findViewById<EditText>(R.id.rawCmd)
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendRaw(raw.text.toString()) }
        raw.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) sendRaw(raw.text.toString())
            id == EditorInfo.IME_ACTION_SEND
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            logLines.clear()
            logView.text = ""
        }

        updatePosition()
        onLog("Cameo Link ${packageManager.getPackageInfo(packageName, 0).versionName} · Android ${Build.VERSION.RELEASE}")
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopScan()
        ble?.close()
        spp?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** targetSdk 36 dibuja bajo las barras del sistema: se rellenan a mano. */
    private fun applyInsets() {
        val root = findViewById<android.view.View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // ------------------------------------------------------------------ permisos
    private var pendingAfterPermission: (() -> Unit)? = null

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withPermissions(then: () -> Unit) {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            ensureBluetoothOn(then)
        } else {
            pendingAfterPermission = then
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val then = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            then?.let { ensureBluetoothOn(it) }
        } else {
            toast("Sin permiso de Bluetooth no se puede buscar la Cameo")
        }
    }

    private fun ensureBluetoothOn(then: () -> Unit) {
        if (adapter.isEnabled) then()
        else {
            pendingAfterPermission = then
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ON)
        }
    }

    @Deprecated("startActivityForResult de Activity plana")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BT_ON) {
            val then = pendingAfterPermission
            pendingAfterPermission = null
            if (adapter.isEnabled) then?.invoke() else toast("Bluetooth apagado")
        }
    }

    // ------------------------------------------------------------------ búsqueda
    private data class Found(val device: BluetoothDevice, var name: String?, var rssi: Int?, val via: MutableSet<String>) {
        val looksLikeCameo get() = name?.contains(Regex("cameo|silhouette", RegexOption.IGNORE_CASE)) == true
        override fun toString(): String {
            val n = name ?: "(sin nombre)"
            val r = rssi?.let { " · $it dBm" } ?: ""
            return "${if (looksLikeCameo) "★ " else ""}$n\n${device.address} · ${via.sorted().joinToString("+")}$r"
        }
    }

    private val found = linkedMapOf<String, Found>()
    private var listAdapter: ArrayAdapter<Found>? = null
    private var scanning = false
    private var receiverRegistered = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addFound(result.device, result.scanRecord?.deviceName ?: result.device.name, result.rssi, "BLE")
        }

        override fun onScanFailed(errorCode: Int) = onLog("Búsqueda BLE falló (código $errorCode)")
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
            addFound(d, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: d.name, rssi, "Clásico")
        }
    }

    private fun addFound(d: BluetoothDevice, name: String?, rssi: Int?, via: String) = ui.post {
        val f = found.getOrPut(d.address) { Found(d, name, rssi, mutableSetOf()) }
        if (name != null) f.name = name
        if (rssi != null) f.rssi = rssi
        f.via += via
        val a = listAdapter ?: return@post
        a.clear()
        a.addAll(found.values.sortedWith(compareByDescending<Found> { it.looksLikeCameo }
            .thenByDescending { it.name != null }.thenByDescending { it.rssi ?: -999 }))
    }

    private fun showScanDialog() {
        found.clear()
        val a = ArrayAdapter<Found>(this, android.R.layout.simple_list_item_1, mutableListOf())
        listAdapter = a
        for (d in adapter.bondedDevices.orEmpty()) addFound(d, d.name, null, "Emparejado")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscando… (enciende la Cameo)")
            .setAdapter(a) { _, which -> a.getItem(which)?.let { chooseTransport(it) } }
            .setNegativeButton("Cancelar", null)
            .create()
        val timeout = Runnable {
            stopScan()
            dialog.setTitle("Elige la Cameo")
        }
        dialog.setOnDismissListener {
            ui.removeCallbacks(timeout)     // que no corte una búsqueda posterior
            stopScan()
            listAdapter = null
        }
        dialog.show()
        startScan()
        ui.postDelayed(timeout, 15_000)
    }

    private fun startScan() {
        scanning = true
        adapter.bluetoothLeScanner?.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        receiverRegistered = true
        adapter.startDiscovery()
        onLog("Buscando dispositivos BLE y clásicos…")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
            adapter.cancelDiscovery()
        } catch (_: Exception) {
        }
        if (receiverRegistered) {
            unregisterReceiver(classicReceiver)
            receiverRegistered = false
        }
    }

    /** BLE o clásico: si el dispositivo es dual (o no se sabe) se pregunta, porque es justo lo que hay que averiguar. */
    private fun chooseTransport(f: Found) {
        stopScan()
        when (f.device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> connectBle(f.device)
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> connectSpp(f.device)
            else -> AlertDialog.Builder(this)
                .setTitle("¿Cómo conectar a ${f.name ?: f.device.address}?")
                .setItems(arrayOf("Bluetooth LE (GATT)", "Bluetooth clásico (puerto serie)")) { _, i ->
                    if (i == 0) connectBle(f.device) else connectSpp(f.device)
                }
                .show()
        }
    }

    // ------------------------------------------------------------------ conexión
    private fun connectBle(d: BluetoothDevice) {
        closeLinks()
        setConn("Conectando por BLE a ${d.name ?: d.address}…")
        ble = BleLink(this, d, this).also { it.connect() }
    }

    private fun connectSpp(d: BluetoothDevice) {
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

    private fun disconnect(msg: String) {
        closeLinks()
        setConn(msg)
        setMachine("Máquina: —")
    }

    private fun isCurrent(link: CameoLink) = link === ble || link === spp

    private fun connLabel(link: CameoLink): String =
        if (link is BleLink) "Conectado: ${link.label} · TX ${link.tx?.let { shortUuid(it.uuid) } ?: "—"} · " +
            "RX ${link.rx?.let { shortUuid(it.uuid) } ?: "—"}"
        else "Conectado: ${link.label}"

    override fun onLinkReady(link: CameoLink) {
        if (!isCurrent(link)) {     // un enlace que el usuario ya descartó
            link.close()
            return
        }
        session.link = link
        resetPosition()
        setConn(connLabel(link))
        if (link is BleLink) {
            // Activar el RX sugerido; si no hay o falla, igual se puede escribir
            val rx = link.suggestedRx
            if (rx != null) worker.execute {
                try {
                    link.selectRx(rx)
                } catch (e: Exception) {
                    onLog("No se pudo activar RX: ${e.message}")
                }
                setConn(connLabel(link))
            }
        }
        onLog("Listo. Prueba «Estado»: si responde, el canal es el correcto.")
    }

    override fun onLinkLost(reason: String) {
        ui.post {
            closeLinks()
            setConn("Sin conexión: $reason")
            setMachine("Máquina: —")
        }
        onLog(reason)
    }

    override fun onBytes(data: ByteArray) = session.onBytes(data)

    private fun showChannelDialog() {
        val link = ble
        if (link == null || session.link !== link) {
            toast("Solo aplica a una conexión BLE")
            return
        }
        val w = link.writable
        if (w.isEmpty()) {
            toast("La Cameo no expone canales escribibles por BLE")
            return
        }
        val labels = w.map { "${shortUuid(it.service.uuid)} / ${shortUuid(it.uuid)}\n[${propsText(it.properties)}]" }
        AlertDialog.Builder(this)
            .setTitle("Canal TX (escribir)")
            .setItems(labels.toTypedArray()) { _, i ->
                link.selectTx(w[i])
                pickRx(link)
            }
            .show()
    }

    private fun pickRx(link: BleLink) {
        val n = link.notifiable
        val labels = listOf("(ninguno)") + n.map { "${shortUuid(it.service.uuid)} / ${shortUuid(it.uuid)}\n[${propsText(it.properties)}]" }
        AlertDialog.Builder(this)
            .setTitle("Canal RX (respuestas)")
            .setItems(labels.toTypedArray()) { _, i ->
                val c = if (i == 0) null else n[i - 1]
                worker.execute {
                    try {
                        link.selectRx(c)
                    } catch (e: Exception) {
                        onLog("No se pudo activar RX: ${e.message}")
                    }
                    setConn(connLabel(link))     // muestra el RX que de verdad quedó activo
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ máquina
    private fun requireConnected(): Boolean {
        if (session.link == null) toast("Primero conecta la Cameo")
        return session.link != null
    }

    /** Ejecuta en el hilo de trabajo; los errores van al registro, a un toast y a onError (en la UI). */
    private fun runMachine(what: String, onError: () -> Unit = {}, block: () -> Unit) {
        if (!requireConnected()) return
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                onLog("$what: ${e.message}")
                ui.post {
                    onError()
                    toast("$what: ${e.message}")
                }
            }
        }
    }

    private fun refreshStatus() {
        val st = session.status()
        setMachine(if (st == null) "Máquina: sin respuesta (¿canal RX?)" else "Máquina: $st")
    }

    private fun ensureInitialized() {
        if (!session.initialized) {
            onLog("Inicializando (ESC EOT + FG)…")
            session.initialize()
        }
    }

    private fun step(): Double = when (findViewById<RadioGroup>(R.id.stepGroup).checkedRadioButtonId) {
        R.id.step1 -> 1.0
        R.id.step5 -> 5.0
        R.id.step50 -> 50.0
        else -> 10.0
    }

    /** ▶ = X+ (carro), ▼ = Y+ (avance del papel). No se baja de 0: la máquina no conoce coordenadas negativas. */
    private fun jog(dx: Double, dy: Double) {
        val nx = (posX + dx).coerceIn(0.0, Gpgl.WIDTH_MM)
        val ny = (posY + dy).coerceIn(0.0, Gpgl.LENGTH_MM)
        if (nx == posX && ny == posY && (dx != 0.0 || dy != 0.0)) {
            toast("Límite: el origen es 0,0")
            return
        }
        if (!requireConnected()) return
        // Destino absoluto: si se tocan varias flechas seguidas, el último M deja el carro donde dice la pantalla
        val fromX = posX
        val fromY = posY
        posX = nx
        posY = ny
        updatePosition()
        runMachine("Mover", onError = {
            // No se envió: volver al último punto que sí se mandó (salvo que otra flecha ya lo haya movido)
            if (posX == nx && posY == ny) {
                posX = fromX
                posY = fromY
                updatePosition()
            }
        }) {
            ensureInitialized()
            session.sendCommands(Gpgl.moveTo(nx, ny))
        }
    }

    private fun resetPosition() = ui.post {
        posX = 0.0
        posY = 0.0
        updatePosition()
    }

    private fun updatePosition() {
        position.text = "X %.1f · Y %.1f mm (desde el origen)".format(Locale.forLanguageTag("es-CL"), posX, posY)
    }

    private fun readSettings(): Gpgl.CutSettings? {
        val speed = findViewById<EditText>(R.id.speed).text.toString().toIntOrNull()
        val pressure = findViewById<EditText>(R.id.pressure).text.toString().toIntOrNull()
        val s = Gpgl.CutSettings(
            speed = speed ?: -1,
            pressure = pressure ?: -1,
            mat = findViewById<Spinner>(R.id.mat).selectedItemPosition,
        )
        return try {
            s.validate()
            s
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "valores inválidos")
            null
        }
    }

    private fun confirmTestCut() {
        if (!requireConnected()) return
        val s = readSettings() ?: return
        val mat = listOf("sin mat", "mat 12x12", "mat 12x24")[s.mat]
        AlertDialog.Builder(this)
            .setTitle("Cortar cuadrado 20 mm")
            .setMessage("Posición  X10 Y10 mm\nVelocidad  ${s.speed}\nPresión  ${s.pressure}\nSoporte  $mat\nCuchilla  trinquete · offset 0,25 mm")
            .setPositiveButton("Cortar") { _, _ -> runTestCut(s) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runTestCut(s: Gpgl.CutSettings) = runMachine("Corte") {
        session.initialize()
        val st = session.status()
        if (st != "lista") {
            setMachine("Máquina: ${st ?: "sin respuesta"}")
            throw IllegalStateException(
                if (st == null) "la máquina no responde: no se corta a ciegas" else "la máquina no está lista ($st)")
        }
        setMachine("Máquina: cortando…")
        session.sendCommands(Gpgl.testSquareJob(s, 10.0, 10.0, 20.0))
        resetPosition()     // el trabajo termina con M0,0
        // Esperar a que termine (como wait_for_ready del bridge): solo se sigue esperando mientras se mueve
        val t0 = System.currentTimeMillis()
        while (true) {
            Thread.sleep(500)
            val now = session.status()
            when {
                now == "lista" -> {
                    setMachine("Máquina: lista · corte terminado")
                    onLog("Corte terminado")
                    return@runMachine
                }
                now == null -> throw IllegalStateException("sin respuesta durante el corte")
                now != "moviéndose" -> throw IllegalStateException("estado inesperado durante el corte: $now")
                System.currentTimeMillis() - t0 > 120_000 ->
                    throw IllegalStateException("el corte no terminó en 2 min (sigue moviéndose)")
            }
        }
    }

    /** Texto ASCII + ETX, o bytes crudos con "hex:" (ej. hex:1b05 = consulta de estado). */
    private fun sendRaw(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val bytes = if (t.startsWith("hex:", ignoreCase = true)) {
            val h = t.substring(4).replace(Regex("[^0-9a-fA-F]"), "")
            if (h.isEmpty() || h.length % 2 != 0) {
                toast("hex: necesita pares de dígitos (ej. hex:1b05)")
                return
            }
            ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            if (t.any { it.code !in 0x20..0x7e }) {
                toast("Solo ASCII; para otros bytes usa hex:")
                return
            }
            Gpgl.encode(listOf(t))
        }
        runMachine("Enviar") {
            session.send(bytes)
            // La respuesta, si llega, aparece en el registro sola
        }
    }

    // ------------------------------------------------------------------ registro / UI
    override fun onLog(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        ui.post {
            logLines.addFirst(line)
            while (logLines.size > 800) logLines.removeLast()
            // Ráfagas de paquetes: se repinta como mucho cada 200 ms, no por cada línea
            if (!logRenderPending) {
                logRenderPending = true
                ui.postDelayed(renderLog, 200)
            }
        }
    }

    private var logRenderPending = false
    private val renderLog = Runnable {
        logRenderPending = false
        logView.text = logLines.joinToString("\n")
    }

    private fun shareLog() {
        val text = logLines.reversed().joinToString("\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Cameo Link registro")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Compartir registro"))
    }

    private fun setConn(msg: String) = ui.post { connState.text = msg }
    private fun setMachine(msg: String) = ui.post { machineState.text = msg }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_BT_ON = 2
    }
}
