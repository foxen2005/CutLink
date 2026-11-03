package cl.digitalfox.cameolink

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Sesión con la máquina sobre cualquier enlace: mismo flujo que la clase Cameo de gpgl.py
 * (drain antes de consultar, respuestas terminadas en ETX, initialize = ESC EOT + FG + TB71/FA/TC).
 * Todas las llamadas son bloqueantes: usar solo desde el hilo de trabajo.
 */
class CameoSession(private val log: (String) -> Unit) {
    @Volatile var link: CameoLink? = null
        set(value) {
            field = value
            initialized = false
            synchronized(lock) { rx.reset() }
        }
    @Volatile var initialized = false
        private set

    private val rx = ByteArrayOutputStream()
    private val lock = Object()

    fun onBytes(data: ByteArray) {
        log("← ${describe(data)}")
        synchronized(lock) {
            rx.write(data)
            lock.notifyAll()
        }
    }

    fun send(data: ByteArray) {
        val l = link ?: throw IOException("sin conexión")
        log("→ ${describe(data)}")
        l.write(data)
    }

    fun sendCommands(cmds: List<String>) = send(Gpgl.encode(cmds))

    /** Lee hasta ETX; null si no llega a tiempo. Lo que venga después del ETX queda en el buffer. */
    fun readReply(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                val buf = rx.toByteArray()
                val i = buf.indexOf(Gpgl.ETX)
                if (i >= 0) {
                    rx.reset()
                    rx.write(buf, i + 1, buf.size - i - 1)
                    return String(buf, 0, i, Charsets.US_ASCII)
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
        }
    }

    /** La máquina encola respuestas a consultas anteriores: se descartan antes de preguntar. */
    fun drain(waitMs: Long = 150) {
        Thread.sleep(waitMs)
        synchronized(lock) { rx.reset() }
    }

    fun query(data: ByteArray, timeoutMs: Long): String? {
        send(data)
        return readReply(timeoutMs)
    }

    /** null = sin respuesta (canal RX equivocado o máquina apagada). */
    fun status(): String? {
        drain()
        val r = query(Gpgl.STATUS_REQUEST, 2000) ?: return null
        return Gpgl.statusName(r)
    }

    fun firmware(): String? = query(Gpgl.encode(listOf("FG")), 5000)?.trim()

    fun initialize() {
        drain()
        send(Gpgl.INIT)
        firmware()          // la máquina espera esta consulta tras el init
        for (q in listOf("TB71", "FA", "TC")) query(Gpgl.encode(listOf(q)), 2000)
        initialized = true
    }

    companion object {
        private val NAMES = mapOf(0x1b to "ESC", 0x05 to "ENQ", 0x04 to "EOT", 0x03 to "ETX")

        /** Texto legible (controles como <ESC>) + hex si es corto: lo necesario para descifrar el protocolo. */
        fun describe(data: ByteArray): String {
            val text = buildString {
                for (b in data) {
                    val v = b.toInt() and 0xff
                    when {
                        v in 0x20..0x7e -> append(v.toChar())
                        NAMES.containsKey(v) -> append("<${NAMES[v]}>")
                        else -> append("<%02X>".format(v))
                    }
                }
            }
            if (data.size > 48) return "$text  (${data.size} B)"
            val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            return "$text  [$hex]"
        }
    }
}
