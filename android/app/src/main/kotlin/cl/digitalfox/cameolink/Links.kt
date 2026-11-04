package cl.digitalfox.cameolink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lo que la sesión necesita de un enlace: escribir bytes (bloqueante, desde el hilo de trabajo). */
interface CameoLink {
    val label: String
    fun write(data: ByteArray)
    fun close()
}

interface LinkListener {
    fun onBytes(data: ByteArray)
    fun onLog(msg: String)
    fun onLinkReady(link: CameoLink)
    fun onLinkLost(reason: String)
}

private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
// Servicios estándar que nunca llevan los datos del plotter (GAP, GATT, info del dispositivo, batería)
private val STANDARD_SERVICES = setOf("1800", "1801", "180a", "180f")

fun shortUuid(u: UUID): String {
    val s = u.toString()
    return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8) else s
}

fun propsText(p: Int): String = buildList {
    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("leer")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("escribir")
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("escribir-sin-resp")
    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notificar")
    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicar")
}.joinToString(",")

private fun BluetoothGattCharacteristic.canWrite() =
    properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

private fun BluetoothGattCharacteristic.canNotify() =
    properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

// ---------------------------------------------------------------------------------------------
// BLE (GATT). El protocolo de la Cameo por BLE no está documentado: se listan todos los servicios
// y se elige el canal de escritura (TX) y de respuesta (RX). La elección automática es una suposición.
// ---------------------------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val listener: LinkListener,
) : CameoLink {
    override val label = "BLE ${device.name ?: device.address}"

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile var tx: BluetoothGattCharacteristic? = null
        private set
    /** RX con notificaciones ya activas; null hasta que selectRx lo confirme. */
    @Volatile var rx: BluetoothGattCharacteristic? = null
        private set
    /** Lo que autoPick cree que es el RX; aún sin notificaciones. */
    @Volatile var suggestedRx: BluetoothGattCharacteristic? = null
        private set
    @Volatile private var mtu = 23
    @Volatile private var opLatch: CountDownLatch? = null
    @Volatile private var opStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var closed = false
    // Tras un timeout, el callback de esa operación puede llegar tarde: se descarta para que no
    // confirme la operación siguiente.
    @Volatile private var staleCallback = false

    val writable: List<BluetoothGattCharacteristic>
        get() = gatt?.services.orEmpty().flatMap { it.characteristics }.filter { it.canWrite() }
    val notifiable: List<BluetoothGattCharacteristic>
        get() = gatt?.services.orEmpty().flatMap { it.characteristics }.filter { it.canNotify() }

    fun connect() {
        listener.onLog("BLE: conectando a ${device.address}…")
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("BLE: conectado, pidiendo MTU 247")
                if (!g.requestMtu(247)) g.discoverServices()
            } else {
                // Desconexión, o conexión fallida (p. ej. status 133): no queda nada útil abierto
                gatt = null
                opLatch?.countDown()
                g.close()
                if (!closed) listener.onLinkLost("BLE desconectado (estado $newState, status $status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            listener.onLog("BLE: MTU $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLinkLost("BLE: falló el descubrimiento de servicios ($status)")
                return
            }
            for (s in g.services) {
                listener.onLog("Servicio ${shortUuid(s.uuid)}")
                for (c in s.characteristics) listener.onLog("   · ${shortUuid(c.uuid)}  [${propsText(c.properties)}]")
            }
            autoPick(g)
            listener.onLinkReady(this@BleLink)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { if (c == rx) listener.onBytes(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c == rx) listener.onBytes(value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            opDone(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            opDone(status)
    }

    private fun opDone(status: Int) {
        if (staleCallback) {
            staleCallback = false
            return
        }
        opStatus = status
        opLatch?.countDown()
    }

    /** Suposición inicial: primer canal escribible fuera de los servicios estándar; RX en el mismo servicio. */
    private fun autoPick(g: BluetoothGatt) {
        val custom = g.services.filter { shortUuid(it.uuid).lowercase() !in STANDARD_SERVICES }
        val t = custom.flatMap { it.characteristics }.firstOrNull { it.canWrite() }
        val r = t?.service?.characteristics?.firstOrNull { it.canNotify() }
            ?: custom.flatMap { it.characteristics }.firstOrNull { it.canNotify() }
        tx = t
        suggestedRx = r
        listener.onLog("Canal TX (suposición): ${t?.let { shortUuid(it.uuid) } ?: "ninguno"}")
        listener.onLog("Canal RX (suposición): ${r?.let { shortUuid(it.uuid) } ?: "ninguno"}")
    }

    fun selectTx(c: BluetoothGattCharacteristic) {
        tx = c
        listener.onLog("Canal TX: ${shortUuid(c.uuid)}")
    }

    /** Activa notificaciones en el canal RX. Bloqueante: llamar desde el hilo de trabajo. */
    fun selectRx(c: BluetoothGattCharacteristic?) {
        val g = gatt ?: throw IOException("sin conexión BLE")
        rx?.let { if (it != c) g.setCharacteristicNotification(it, false) }
        // rx queda null hasta confirmar: si algo falla, la pantalla no muestra un RX que no escucha
        rx = null
        if (c == null) return
        if (!g.setCharacteristicNotification(c, true)) throw IOException("no se pudo activar notificaciones")
        val d = c.getDescriptor(CCCD)
        if (d == null) {
            listener.onLog("RX ${shortUuid(c.uuid)} sin descriptor 2902; se escucha igual")
            rx = c
            return
        }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        runOp("activar RX") {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
        rx = c
        listener.onLog("Canal RX: ${shortUuid(c.uuid)} (notificaciones activas)")
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("sin conexión BLE")
        val c = tx ?: throw IOException("no hay canal TX elegido")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val chunk = (mtu - 3).coerceAtLeast(20)
        var i = 0
        while (i < data.size) {
            val part = data.copyOfRange(i, minOf(data.size, i + chunk))
            runOp("escribir") {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    c.value = part
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
            i += chunk
        }
    }

    /** Android permite una sola operación GATT a la vez: se lanza y se espera su callback. */
    private fun runOp(what: String, start: () -> Boolean) {
        val latch = CountDownLatch(1)
        opLatch = latch
        opStatus = BluetoothGatt.GATT_SUCCESS
        if (!start()) throw IOException("BLE: no se pudo $what (pila ocupada)")
        if (!latch.await(3, TimeUnit.SECONDS)) {
            opLatch = null
            staleCallback = true
            throw IOException("BLE: $what sin confirmación en 3 s")
        }
        if (closed || gatt == null) throw IOException("BLE desconectado")
        if (opStatus != BluetoothGatt.GATT_SUCCESS) throw IOException("BLE: $what falló (status $opStatus)")
    }

    override fun close() {
        closed = true
        opLatch?.countDown()
        val g = gatt
        gatt = null
        g?.disconnect()
        g?.close()
    }
}

// ---------------------------------------------------------------------------------------------
// Bluetooth clásico (SPP / RFCOMM), por si la Cameo expone un puerto serie en vez de BLE.
// ---------------------------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class SppLink(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter,
    private val listener: LinkListener,
) : CameoLink {
    override val label = "Clásico ${device.name?.replace(Regex("[^\\p{L}\\p{N} ._\\-]"), "")?.trim() ?: device.address}"
    val address: String get() = device.address
    // Se asigna antes de connect(): así close() puede cortar un intento que todavía está esperando
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    /** Bloqueante: llamar desde el hilo de trabajo. Prueba seguro, inseguro y canal 1 directo. */
    fun connect() {
        adapter.cancelDiscovery()
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "RFCOMM seguro" to { device.createRfcommSocketToServiceRecord(SPP) },
            "RFCOMM inseguro" to { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            "RFCOMM canal 1" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((name, make) in attempts) {
            if (closed) throw IOException("conexión cancelada")
            var s: BluetoothSocket? = null
            try {
                listener.onLog("Clásico: probando $name…")
                s = make()
                socket = s
                s.connect()
                if (closed) throw IOException("conexión cancelada")
                listener.onLog("Clásico: conectado ($name)")
                startReader(s)
                listener.onLinkReady(this)
                return
            } catch (e: Exception) {
                // Cerrar el socket fallido antes del siguiente intento (si no, algunas pilas rechazan el próximo)
                try {
                    s?.close()
                } catch (_: IOException) {
                }
                socket = null
                if (closed) throw IOException("conexión cancelada")
                last = e
                listener.onLog("Clásico: $name falló: ${e.message}")
            }
        }
        throw IOException("no se pudo abrir el puerto serie: ${last?.message}")
    }

    private fun startReader(s: BluetoothSocket) {
        Thread({
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) listener.onBytes(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            if (!closed) listener.onLinkLost("Clásico desconectado")
        }, "spp-reader").apply { isDaemon = true }.start()
    }

    override fun write(data: ByteArray) {
        val out = socket?.outputStream ?: throw IOException("sin conexión")
        out.write(data)
        out.flush()
    }

    override fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
