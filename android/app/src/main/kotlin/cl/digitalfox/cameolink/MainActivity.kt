package cl.digitalfox.cameolink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

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
            adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, listOf("Sin mat", "Mat 12x12", "Mat 12x24"))
                .apply { setDropDownViewResource(R.layout.spinner_drop) }
            setSelection(1)     // mismo valor por defecto que el panel de Illustrator
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        // Opciones avanzadas: cerradas por defecto; se recuerda si se dejaron abiertas
        val prefs = getSharedPreferences("cameolink", MODE_PRIVATE)
        val adv = findViewById<android.view.View>(R.id.advBox)
        val advBtn = findViewById<Button>(R.id.btnAdvanced)
        fun showAdv(open: Boolean) {
            adv.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            advBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 0)
        }
        showAdv(prefs.getBoolean("advanced", false))
        advBtn.setOnClickListener {
            val open = adv.visibility != android.view.View.VISIBLE
            prefs.edit().putBoolean("advanced", open).apply()
            showAdv(open)
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { withPermissions { startSearch() } }
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

        findViewById<View>(R.id.btnUp).setOnClickListener { jog(0.0, -step()) }
        findViewById<View>(R.id.btnDown).setOnClickListener { jog(0.0, step()) }
        findViewById<View>(R.id.btnLeft).setOnClickListener { jog(-step(), 0.0) }
        findViewById<View>(R.id.btnRight).setOnClickListener { jog(step(), 0.0) }
        findViewById<View>(R.id.btnHome).setOnClickListener { jog(-m.posX, -m.posY) }

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

    override fun onResume() { super.onResume(); m.toaster = { pill(it) }; m.listen(this) }
    override fun onPause() { m.toaster = null; m.unlisten(this); super.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopScan()
        super.onDestroy()
    }

    override fun onMachineChanged() {
        val bad = m.machineText == "sin material" || m.machineText.startsWith("sin respuesta")
        findViewById<PlateView>(R.id.mark).state = when {
            m.connecting -> Register.BUSY
            m.connected && !bad -> Register.READY
            else -> Register.OFF
        }
        connState.text = when {
            m.connected -> m.session.link?.label?.substringAfter(' ') ?: "Cameo"
            m.connecting -> "Conectando…"
            else -> "Sin conexión"
        }
        machineState.text = when {
            m.connected -> if (m.machineText == "—") "Conectada" else m.machineText.replaceFirstChar { it.uppercase() }
            m.connecting -> "Espera un momento"
            else -> m.connText.substringAfter("Sin conexión: ", "Enciende la Cameo y búscala")
        }
        renderDevices()
        // la acción principal es buscar solo cuando falta la máquina
        val needsSearch = !m.connected && !m.connecting
        findViewById<Button>(R.id.btnScan).apply {
            text = if (scanning) "Buscando…" else "Buscar Cameo"
            isEnabled = !scanning
            if (needsSearch != scanIsPrimary) {
                scanIsPrimary = needsSearch
                setBackgroundResource(if (needsSearch) R.drawable.bg_primary else R.drawable.bg_round)
            }
        }
        position.text = "X ${nf(m.posX)} · Y ${nf(m.posY)} mm desde el origen"
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
        /** La Cameo manda su nombre con bytes basura al final ("CAMEO 4-812231B��"): se limpian para
         *  mostrarlo y para juntar sus dos direcciones. */
        val cleanName get() = name?.replace(Regex("[^\\p{L}\\p{N} ._\\-]"), "")?.trim()
        /** Orden para conectar por puerto serie: la que Android marca como clásica (BR/EDR) primero; la
         *  BLE (C0:1B… en la Cameo 4) al final, porque esa no abre RFCOMM y cada intento tarda ~10 s. */
        val sppRank get() = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> 0
            BluetoothDevice.DEVICE_TYPE_DUAL -> 1
            BluetoothDevice.DEVICE_TYPE_UNKNOWN -> 2
            else -> 3
        }
    }

    private val found = linkedMapOf<String, Found>()
    private var devices: List<Found> = emptyList()
    private var shownDevices: List<Any?>? = null
    private var scanIsPrimary: Boolean? = null
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
        // solo equipos con nombre (los anónimos son ruido: relojes, TV, sensores); la Cameo primero.
        // La Cameo 4 tiene DOS direcciones con el mismo nombre (clásica 00:1B… y BLE C0:1B…): se muestra
        // una sola, la clásica, que es por donde corta (la BLE no conecta por puerto serie).
        // solo se juntan las Cameo (sus dos direcciones); otros equipos con el mismo nombre (dos
        // parlantes iguales) siguen apareciendo por separado
        devices = found.values.filter { !it.cleanName.isNullOrEmpty() }
            .groupBy { if (it.looksLikeCameo) "cameo:${it.cleanName}" else "addr:${it.device.address}" }.values
            .map { same -> same.minBy { it.sppRank } }
            .sortedWith(compareByDescending<Found> { it.looksLikeCameo }
                .thenByDescending { "Emparejado" in it.via }.thenByDescending { it.rssi ?: -999 })
        renderDevices()
    }

    private val endScan = Runnable { stopScan(); onMachineChanged() }

    /** Busca 15 s; la lista se llena en la misma pantalla. */
    private fun startSearch() {
        ui.removeCallbacks(endScan)
        stopScan()
        found.clear(); devices = emptyList()
        for (d in m.adapter.bondedDevices.orEmpty()) addFound(d, d.name, null, "Emparejado")
        startScan()
        onMachineChanged()
        ui.postDelayed(endScan, 15_000)
    }

    /** Lista simple: el nombre y, si corresponde, «emparejada»; el detalle técnico queda en el registro. */
    private fun renderDevices() {
        val here = m.spp?.takeIf { m.connected }?.address
        // solo si cambió: rehacer las filas entre el apoyar y el levantar del dedo pierde el toque
        val key = listOf(scanning, here, devices.map { it.device.address to it.cleanName })
        if (key == shownDevices) return
        shownDevices = key
        findViewById<TextView>(R.id.foundTitle).text = if (scanning) "Buscando… (enciende la Cameo)" else "Encontradas"
        val box = findViewById<LinearLayout>(R.id.devBox)
        box.removeAllViews()
        for ((i, f) in devices.withIndex()) {
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(getColor(R.color.rule)) }, LinearLayout.LayoutParams(-1, 1))
            box.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f).toInt()
            setBackgroundResource(R.drawable.bg_row)
            isClickable = true
            setOnClickListener { chooseTransport(f) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = f.cleanName; textSize = 15f; setTextColor(getColor(R.color.ink))
                    typeface = resources.getFont(R.font.barlow_semibold)
                })
                addView(TextView(context).apply {
                    text = if (!f.looksLikeCameo) "Otro dispositivo" else if ("Emparejado" in f.via) "Emparejada" else "Cameo"
                    textSize = 13f; setTextColor(getColor(R.color.ink_2))
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (f.device.address == here) addView(TextView(context).apply {
                text = "Conectada"; textSize = 13f; setTextColor(getColor(R.color.ink))
                typeface = resources.getFont(R.font.barlow_bold)
                setBackgroundResource(R.drawable.bg_tag)
                setPadding(dp(10f).toInt(), dp(3f).toInt(), dp(10f).toInt(), dp(3f).toInt())
            })
            })
        }
        if (devices.isEmpty()) box.addView(TextView(this).apply {
            text = if (scanning) "Sin resultados todavía" else "Toca Buscar Cameo"
            textSize = 14f; setTextColor(getColor(R.color.ink_2))
        })
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
        ui.removeCallbacks(endScan)
        stopScan()
        m.onLog("Elegido ${f.name} · ${f.device.address} · ${f.via.sorted().joinToString("+")}${f.rssi?.let { " · $it dBm" } ?: ""}")
        // la Cameo 4 habla por Bluetooth clásico (verificado): sin preguntar. Se prueban todas sus
        // direcciones (clásica primero); si una falla, la app intenta sola la otra.
        if (f.looksLikeCameo) {
            val same = found.values.filter { it.cleanName == f.cleanName }
            val order = (listOf(f) + same).distinctBy { it.device.address }.sortedBy { it.sppRank }
            m.onLog("Orden de conexión: " + order.joinToString(" → ") { "${it.device.address} (tipo ${it.device.type})" })
            m.connectSppAny(order.map { it.device })
            return
        }
        when (f.device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> m.connectBle(f.device)
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> m.connectSpp(f.device)
            else -> Sheet(this)
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
        Sheet(this)
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
        Sheet(this)
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
        Sheet(this)
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

    private fun toast(msg: String) = pill(msg)

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_BT_ON = 2
    }
}
