package cl.digitalfox.cameolink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla de máquina: conectar (BLE o clásico), estado, firmware, mover el carro, cuadrado de
 * prueba, comando manual y registro. La conexión vive en [Machine]: sigue activa al volver al visor.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), Machine.Observer {

    private val ui = Handler(Looper.getMainLooper())
    private val m = Machine

    private lateinit var connState: TextView
    private lateinit var machineState: TextView
    private lateinit var position: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Machine.init(this)
        setContentView(R.layout.activity_main)
        applySystemInsets(findViewById(R.id.root))

        connState = findViewById(R.id.connState)
        machineState = findViewById(R.id.machineState)
        position = findViewById(R.id.position)
        logView = findViewById(R.id.log)

        findViewById<Spinner>(R.id.mat).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Sin mat", "Mat 12x12", "Mat 12x24"))
            setSelection(1)     // mismo valor por defecto que el panel de Illustrator
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        // Opciones avanzadas: cerradas por defecto; se recuerda si se dejaron abiertas
        val prefs = getSharedPreferences("cameolink", MODE_PRIVATE)
        val adv = findViewById<android.view.View>(R.id.advBox)
        val advBtn = findViewById<Button>(R.id.btnAdvanced)
        fun showAdv(open: Boolean) {
            adv.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            advBtn.text = if (open) "Opciones avanzadas ▴" else "Opciones avanzadas ▾"
        }
        showAdv(prefs.getBoolean("advanced", false))
        advBtn.setOnClickListener {
            val open = adv.visibility != android.view.View.VISIBLE
            prefs.edit().putBoolean("advanced", open).apply()
            showAdv(open)
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { withPermissions { showScanDialog() } }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { m.disconnect() }
        findViewById<Button>(R.id.btnChannels).setOnClickListener { showChannelDialog() }
        findViewById<Button>(R.id.btnStatus).setOnClickListener { m.run("Estado") { m.refreshStatus() } }
        findViewById<Button>(R.id.btnFirmware).setOnClickListener {
            m.run("Firmware") { m.setMachine(m.session.firmware()?.let { "firmware $it" } ?: "firmware: sin respuesta") }
        }
        findViewById<Button>(R.id.btnInit).setOnClickListener {
            m.run("Inicializar") { m.session.initialize(); m.refreshStatus() }
        }
        // El bridge (Python) corre dentro de la app: abre un archivo de cada formato. No necesita la máquina.
        findViewById<Button>(R.id.btnPython).setOnClickListener {
            m.files.execute {
                try {
                    val t0 = System.currentTimeMillis()
                    val py = m.python()
                    val r = py.getModule("cameo_probe").callAttr("run").toString()
                    m.onLog("Python: ${System.currentTimeMillis() - t0} ms · $r")
                } catch (e: Throwable) {
                    m.onLog("Python falló: ${e.message}")
                }
            }
        }

        findViewById<Button>(R.id.btnUp).setOnClickListener { jog(0.0, -step()) }
        findViewById<Button>(R.id.btnDown).setOnClickListener { jog(0.0, step()) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { jog(-step(), 0.0) }
        findViewById<Button>(R.id.btnRight).setOnClickListener { jog(step(), 0.0) }
        findViewById<Button>(R.id.btnHome).setOnClickListener { jog(-m.posX, -m.posY) }

        findViewById<Button>(R.id.btnCut).setOnClickListener { confirmTestCut() }

        val raw = findViewById<EditText>(R.id.rawCmd)
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendRaw(raw.text.toString()) }
        raw.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) sendRaw(raw.text.toString())
            id == EditorInfo.IME_ACTION_SEND
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { m.logLines.clear(); onMachineChanged() }
    }

    override fun onResume() { super.onResume(); m.listen(this) }
    override fun onPause() { m.unlisten(this); super.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopScan()
        super.onDestroy()
    }

    override fun onMachineChanged() {
        connState.text = m.connText
        connState.setTextColor(if (m.connected) 0xFF3CCF91.toInt() else 0xFFE6E6E6.toInt())
        machineState.text = "Máquina: ${m.machineText}"
        position.text = "X ${nf(m.posX)} · Y ${nf(m.posY)} mm (desde el origen)"
        logView.text = m.logLines.take(300).joinToString("\n")     // lo más nuevo; Compartir manda todo
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
        if (m.adapter.isEnabled) then()
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
            if (m.adapter.isEnabled) then?.invoke() else toast("Bluetooth apagado")
        }
    }

    // ------------------------------------------------------------------ búsqueda
    private data class Found(val device: BluetoothDevice, var name: String?, var rssi: Int?, val via: MutableSet<String>) {
        val looksLikeCameo get() = name?.contains(Regex("cameo|silhouette", RegexOption.IGNORE_CASE)) == true
        // lista simple: solo el nombre (sin dirección ni señal); el detalle técnico queda en el registro
        override fun toString(): String =
            (if (looksLikeCameo) "★ " else "") + (name ?: "(sin nombre)") + if ("Emparejado" in via) "   · emparejada" else ""
    }

    private val found = linkedMapOf<String, Found>()
    private var listAdapter: ArrayAdapter<Found>? = null
    private var scanning = false
    private var receiverRegistered = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addFound(result.device, result.scanRecord?.deviceName ?: result.device.name, result.rssi, "BLE")
        }

        override fun onScanFailed(errorCode: Int) = m.onLog("Búsqueda BLE falló (código $errorCode)")
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
        // solo equipos con nombre (los anónimos son ruido: relojes, TV, sensores); la Cameo primero
        a.addAll(found.values.filter { it.name != null }.sortedWith(compareByDescending<Found> { it.looksLikeCameo }
            .thenByDescending { "Emparejado" in it.via }.thenByDescending { it.rssi ?: -999 }))
    }

    private fun showScanDialog() {
        found.clear()
        val a = ArrayAdapter<Found>(this, android.R.layout.simple_list_item_1, mutableListOf())
        listAdapter = a
        for (d in m.adapter.bondedDevices.orEmpty()) addFound(d, d.name, null, "Emparejado")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscando la Cameo… (enciéndela)")
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
        m.adapter.bluetoothLeScanner?.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        receiverRegistered = true
        m.adapter.startDiscovery()
        m.onLog("Buscando dispositivos BLE y clásicos…")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            m.adapter.bluetoothLeScanner?.stopScan(scanCallback)
            m.adapter.cancelDiscovery()
        } catch (_: Exception) {
        }
        if (receiverRegistered) {
            unregisterReceiver(classicReceiver)
            receiverRegistered = false
        }
    }

    /** BLE o clásico: si el dispositivo es dual (o no se sabe) se pregunta. La Cameo 4 va por clásico. */
    private fun chooseTransport(f: Found) {
        stopScan()
        m.onLog("Elegido ${f.name} · ${f.device.address} · ${f.via.sorted().joinToString("+")}${f.rssi?.let { " · $it dBm" } ?: ""}")
        // la Cameo 4 habla por Bluetooth clásico (verificado): sin preguntar
        if (f.looksLikeCameo) { m.connectSpp(f.device); return }
        when (f.device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> m.connectBle(f.device)
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> m.connectSpp(f.device)
            else -> AlertDialog.Builder(this)
                .setTitle("¿Cómo conectar a ${f.name ?: f.device.address}?")
                .setItems(arrayOf("Bluetooth clásico (puerto serie) · Cameo 4", "Bluetooth LE (GATT)")) { _, i ->
                    if (i == 0) m.connectSpp(f.device) else m.connectBle(f.device)
                }
                .show()
        }
    }

    private fun showChannelDialog() {
        val link = m.ble
        if (link == null || m.session.link !== link) {
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
                m.selectTx(link, w[i])
                pickRx(link)
            }
            .show()
    }

    private fun pickRx(link: BleLink) {
        val n = link.notifiable
        val labels = listOf("(ninguno)") + n.map { "${shortUuid(it.service.uuid)} / ${shortUuid(it.uuid)}\n[${propsText(it.properties)}]" }
        AlertDialog.Builder(this)
            .setTitle("Canal RX (respuestas)")
            .setItems(labels.toTypedArray()) { _, i -> m.selectRx(link, if (i == 0) null else n[i - 1]) }
            .show()
    }

    // ------------------------------------------------------------------ carro y prueba
    private fun step(): Double = when (findViewById<RadioGroup>(R.id.stepGroup).checkedRadioButtonId) {
        R.id.step1 -> 1.0
        R.id.step5 -> 5.0
        R.id.step50 -> 50.0
        else -> 10.0
    }

    /** ▶ = X+ (carro), ▼ = Y+ (avance del papel). No se baja de 0: la máquina no conoce coordenadas negativas. */
    private fun jog(dx: Double, dy: Double) {
        val nx = (m.posX + dx).coerceIn(0.0, Gpgl.WIDTH_MM)
        val ny = (m.posY + dy).coerceIn(0.0, Gpgl.LENGTH_MM)
        if (nx == m.posX && ny == m.posY && (dx != 0.0 || dy != 0.0)) {
            toast("Límite: el origen es 0,0")
            return
        }
        if (!m.connected) { toast("Primero conecta la Cameo"); return }
        // Destino absoluto: si se tocan varias flechas seguidas, el último M deja el carro donde dice la pantalla
        val fromX = m.posX
        val fromY = m.posY
        m.posX = nx
        m.posY = ny
        onMachineChanged()
        m.moveTo(nx, ny, onError = {
            // No se envió: volver al último punto que sí se mandó (salvo que otra flecha ya lo haya movido)
            if (m.posX == nx && m.posY == ny) { m.posX = fromX; m.posY = fromY; onMachineChanged() }
        })
    }

    private fun readSettings(): Gpgl.CutSettings? {
        val s = Gpgl.CutSettings(
            speed = findViewById<EditText>(R.id.speed).text.toString().toIntOrNull() ?: -1,
            pressure = findViewById<EditText>(R.id.pressure).text.toString().toIntOrNull() ?: -1,
            mat = findViewById<Spinner>(R.id.mat).selectedItemPosition,
        )
        return try { s.validate(); s } catch (e: IllegalArgumentException) { toast(e.message ?: "valores inválidos"); null }
    }

    private fun confirmTestCut() {
        if (!m.connected) { toast("Primero conecta la Cameo"); return }
        val s = readSettings() ?: return
        val mat = listOf("sin mat", "mat 12x12", "mat 12x24")[s.mat]
        AlertDialog.Builder(this)
            .setTitle("Cortar cuadrado 20 mm")
            .setMessage("Posición  X10 Y10 mm\nVelocidad  ${s.speed}\nPresión  ${s.pressure}\nSoporte  $mat\nCuchilla  trinquete · offset 0,25 mm")
            .setPositiveButton("Cortar") { _, _ -> m.cut(s, listOf(Gpgl.square(10.0, 10.0, 20.0)))?.let { toast(it) } }
            .setNegativeButton("Cancelar", null)
            .show()
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
        m.run("Enviar") { m.session.send(bytes) }     // la respuesta, si llega, aparece en el registro
    }

    private fun shareLog() {
        val text = m.logLines.reversed().joinToString("\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Cameo Link registro")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Compartir registro"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_BT_ON = 2
    }
}
