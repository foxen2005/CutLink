package cl.digitalfox.cameolink

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pantalla principal (según informe/maqueta-visor-android.html): abrir SVG/DXF/studio3 (también
 * con "Abrir con…" / compartir), elegir soporte SIEMPRE al abrir, ver y editar, material y cuchilla
 * del material, y cortar con progreso y Detener. La conexión vive en [Machine].
 */
@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class ViewerActivity : Activity(), Machine.Observer, CanvasView.Listener {

    private val m = Machine
    private lateinit var canvas: CanvasView
    private var doc: Doc? = null
    private var support: Support? = null
    private var showTools = true
    private var materials: List<JSONObject> = emptyList()
    private var blades: List<JSONObject> = emptyList()
    // corte en curso lanzado desde esta pantalla: para pintar el avance forma por forma
    private var cutSizes: List<Int> = emptyList()
    private var cutPasses = 1
    // campos X/Y/ancho/alto: lo que se mostró (redondeado) y la selección a la que pertenecen
    private val shown = HashMap<Int, String>()
    private var editingSel: Set<String> = emptySet()
    // Print & Cut: posiciones al imprimir; si cambian, la hoja impresa ya no calza
    private var printedSignature: String? = null
    private var openedFrom: File? = null      // carpeta del proyecto abierto, si vino de uno

    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Machine.init(this)
        setContentView(R.layout.activity_viewer)
        applySystemInsets(v(R.id.root))
        canvas = v(R.id.canvas)
        canvas.listener = this

        v<Button>(R.id.btnOpen).setOnClickListener { pickFile() }
        v<Button>(R.id.btnOpen2).setOnClickListener { pickFile() }
        v<Button>(R.id.btnBlank).setOnClickListener { newBlank() }
        v<Button>(R.id.btnAdd).setOnClickListener { showAddMenu() }
        v<Button>(R.id.btnSave).setOnClickListener { askSave() }
        v<Button>(R.id.bPanel).setOnClickListener { togglePanel() }
        v<Button>(R.id.bFill).setOnClickListener { askFill() }
        v<Button>(R.id.bLayerUp).setOnClickListener { edit { it.moveLayer(1) } }
        v<Button>(R.id.bLayerDown).setOnClickListener { edit { it.moveLayer(-1) } }
        v<Button>(R.id.connPill).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        v<Button>(R.id.supportChip).setOnClickListener { doc?.let { askSupport(it, keepCurrent = true) } }
        v<Button>(R.id.btnTools).setOnClickListener { showTools = !showTools; refreshPanel() }

        v<Button>(R.id.bZoomIn).setOnClickListener { canvas.zoomBy(1.3f) }
        v<Button>(R.id.bZoomOut).setOnClickListener { canvas.zoomBy(1 / 1.3f) }
        v<Button>(R.id.bFit).setOnClickListener { canvas.fit() }
        v<Button>(R.id.bMulti).setOnClickListener {
            canvas.multi = !canvas.multi
            toast(if (canvas.multi) "Selección múltiple: toca formas o arrastra un recuadro" else "Selección simple")
            refreshPanel()
        }
        v<Button>(R.id.bAll).setOnClickListener { doc?.let { it.selectAll(); onSelectionChanged() } }
        v<Button>(R.id.bUndo).setOnClickListener { doc?.let { it.undo(); onEdited() } }
        v<Button>(R.id.bRedo).setOnClickListener { doc?.let { it.redo(); onEdited() } }

        v<Button>(R.id.bGroup).setOnClickListener { edit { it.group() } }
        v<Button>(R.id.bUngroup).setOnClickListener { edit { it.ungroup() } }
        v<Button>(R.id.bDup).setOnClickListener { edit { it.duplicate() } }
        v<Button>(R.id.bRot).setOnClickListener { edit { it.rotate90() } }
        v<Button>(R.id.bFlipH).setOnClickListener { edit { it.flipH() } }
        v<Button>(R.id.bFlipV).setOnClickListener { edit { it.flipV() } }
        v<Button>(R.id.bDel).setOnClickListener { edit { it.delete() } }
        v<Button>(R.id.bCenter).setOnClickListener { edit { it.centerIn(workArea()) } }

        v<Switch>(R.id.swLock).setOnCheckedChangeListener { _, on -> canvas.lockAspect = on }
        for (id in listOf(R.id.fx, R.id.fy, R.id.fw, R.id.fh)) {
            val e = v<EditText>(id)
            e.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { numEdit(id); true } else false }
            e.setOnFocusChangeListener { _, has ->
                if (has) editingSel = doc?.sel?.toSet() ?: emptySet()     // lo que se escriba es para ESTA selección
                else numEdit(id)
            }
        }

        // Print & Cut: los valores calibrados se recuerdan (nunca se vuelve a teclear lo mismo)
        val prefs = m.prefs
        val pncFields = mapOf(R.id.pInset to "p_inset", R.id.pThick to "p_thick", R.id.pSearchX to "p_sx",
            R.id.pSearchY to "p_sy", R.id.pCalX to "p_cx", R.id.pCalY to "p_cy")
        for ((id, key) in pncFields) prefs.getString(key, null)?.let { v<EditText>(id).setText(it) }
        v<Switch>(R.id.swPnc).isChecked = prefs.getBoolean("p_on", false)
        v<Switch>(R.id.swPnc).setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("p_on", on).apply(); refreshPanel() }
        v<RadioGroup>(R.id.pncMode).setOnCheckedChangeListener { _, _ -> refreshPanel() }
        val pncWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().apply { for ((id, key) in pncFields) putString(key, v<EditText>(id).text.toString()) }.apply()
                refreshPanel()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        for (id in listOf(R.id.pInset, R.id.pInsetB, R.id.pThick, R.id.pSearchX, R.id.pSearchY, R.id.pCalX, R.id.pCalY))
            v<EditText>(id).addTextChangedListener(pncWatcher)
        v<Button>(R.id.btnPrint).setOnClickListener { printSheet() }
        v<Button>(R.id.bSearch).setOnClickListener { testSearch() }

        v<Spinner>(R.id.material).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) = applyMaterial(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        v<EditText>(R.id.offset).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshOffsetNote()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        v<Button>(R.id.bCut).setOnClickListener { confirmCut() }
        v<Button>(R.id.bStop).setOnClickListener { m.abortCut() }

        loadPresets()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); m.listen(this); m.autoConnect() }
    override fun onPause() {
        m.unlisten(this)
        // guardado automático: si la app se cierra, en Abrir queda «Seguir con lo último»
        if (doc?.items?.isNotEmpty() == true) saveProject(AUTOSAVE, quiet = true)
        super.onPause()
    }

    override fun onMachineChanged() {
        val pill = v<Button>(R.id.connPill)
        pill.text = if (m.connected) "● ${m.session.link?.label?.substringAfter(' ') ?: "Cameo"} · ${m.machineText}"
        else "● Sin conexión"
        pill.setTextColor(if (m.connected) 0xFF3CCF91.toInt() else 0xFFFF9FD0.toInt())

        // corte: el estado vive en Machine (sobrevive si esta pantalla se cerró y se volvió a abrir)
        val p = m.progress
        val box = v<View>(R.id.progressBox)
        if (m.cutting && p != null) {
            box.visibility = View.VISIBLE
            v<ProgressBar>(R.id.progressBar).progress = (p.fraction * 1000).toInt()
            v<TextView>(R.id.progressText).text = p.text
            if (canvas.cutOrder != null && cutSizes.isNotEmpty()) {
                // fracción del envío -> forma que se está cortando (por cantidad de puntos, con pasadas)
                val perPass = cutSizes.sum().coerceAtLeast(1)
                val sent = ((p.fraction - 0.05) / 0.85).coerceIn(0.0, 1.0) * perPass * cutPasses
                val inPass = if (sent >= perPass * cutPasses) perPass.toDouble() else sent % perPass
                var acc = 0; var idx = 0
                while (idx < cutSizes.size - 1 && acc + cutSizes[idx] < inPass) { acc += cutSizes[idx]; idx++ }
                canvas.cutAt = idx to ((inPass - acc) / cutSizes[idx]).coerceIn(0.0, 1.0)
            }
        } else if (box.visibility == View.VISIBLE || canvas.cutOrder != null) {
            box.visibility = View.GONE
            canvas.cutOrder = null
            cutSizes = emptyList()
            refreshPanel()
        }
    }

    // ------------------------------------------------------------------ abrir
    /** Con archivos recientes muestra la lista; si no, va directo al selector de Android. */
    private fun pickFile() {
        val entries = mutableListOf<Pair<String, () -> Unit>>(
            "Buscar archivo…" to { browseFile() },
            "Hoja en blanco" to { newBlank() },
        )
        val last = File(projectsDir(), AUTOSAVE)
        if (doc == null && File(last, "proyecto.json").exists()) entries += "↩ Seguir con lo último" to { openProject(last) }
        if (savedProjects().isNotEmpty()) entries += "📁 Proyectos guardados…" to { showProjects() }
        recentFiles().forEach { (u, n) -> entries += n to { openUri(Uri.parse(u)) } }
        AlertDialog.Builder(this)
            .setTitle("Abrir")
            .setItems(entries.map { it.first }.toTypedArray()) { _, i -> entries[i].second() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ proyectos
    private fun projectsDir() = File(filesDir, "proyectos").apply { mkdirs() }

    private fun savedProjects(): List<File> = projectsDir().listFiles().orEmpty()
        .filter { it.name != AUTOSAVE && File(it, "proyecto.json").exists() }
        .sortedByDescending { File(it, "proyecto.json").lastModified() }

    private fun askSave() {
        val d = doc ?: return
        val pad = dp(16f).toInt()
        val name = EditText(this).apply { hint = "Nombre del proyecto"; setText(d.name.substringBeforeLast('.')); setSelectAllOnFocus(true); singleLine() }
        AlertDialog.Builder(this)
            .setTitle("Guardar proyecto")
            .setView(LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(name, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Guardar") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { "Proyecto" }
                if (File(projectsDir(), projectDirName(n)).exists()) {
                    AlertDialog.Builder(this)
                        .setTitle("Ya existe «$n»")
                        .setMessage("¿Reemplazar ese proyecto?")
                        .setPositiveButton("Reemplazar") { _, _ -> saveProject(n, quiet = false) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else saveProject(n, quiet = false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Nombre de carpeta seguro; nunca el del guardado automático. */
    private fun projectDirName(name: String): String =
        name.replace(Regex("[^\\p{L}\\p{N} ._\\-]"), "_").trim('.', ' ', '_').ifEmpty { "Proyecto" }

    private fun EditText.singleLine() { isSingleLine = true }

    /** Guarda posiciones, formas, grupos y soporte, con copia de las imágenes (el original puede moverse). */
    private fun saveProject(name: String, quiet: Boolean) {
        val d = doc ?: return
        val s = support ?: return
        val dir = File(projectsDir(), if (quiet) AUTOSAVE else projectDirName(name))
        val copies = LinkedHashMap<File, File>()
        val keep = hashSetOf("proyecto.json")
        val js = d.toJson { p ->
            p?.let {
                val src = File(it)
                // dos imágenes con el mismo nombre en carpetas distintas no deben pisarse
                val destName = if (src.parentFile == dir) src.name else "${Integer.toHexString(src.path.hashCode())}_${src.name}"
                val dest = File(dir, destName)
                if (src != dest) copies[src] = dest
                keep += destName
                destName
            }
        }
        js.put("support", JSONObject().put("id", s.id).put("name", s.name).put("w", s.w).put("h", s.h).put("mat", s.mat).put("onMat", s.onMat))
        // si el documento abierto vive en esta carpeta, sus imágenes pueden volver con Deshacer: no se limpia
        val clean = dir != openedFrom
        m.files.execute {
            try {
                dir.mkdirs()
                for ((a, b) in copies) if (a.exists()) a.copyTo(b, overwrite = true)
                File(dir, "proyecto.json").writeText(js.toString())
                if (clean) dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }     // imágenes que ya no se usan
                if (!quiet) { m.onLog("Proyecto guardado: $name"); m.toast("Guardado: $name") }
            } catch (e: Exception) {
                m.onLog("No se pudo guardar: ${e.message}")
                if (!quiet) m.toast("No se pudo guardar: ${e.message}")
            }
        }
    }

    private fun openProject(dir: File) {
        v<TextView>(R.id.fileName).text = "Abriendo…"
        m.files.execute {
            try {
                val js = JSONObject(File(dir, "proyecto.json").readText())
                val d = Doc.fromProject(js) { n -> File(dir, n).takeIf { it.exists() }?.let { f -> loadBitmap(f.path)?.let { it to f.path } } }
                val o = js.getJSONObject("support")
                val s = Support(o.getString("id"), o.getString("name"), o.getDouble("w"), o.getDouble("h"), o.getBoolean("mat"), o.getBoolean("onMat"))
                runOnUiThread { showDoc(d, s); openedFrom = dir }
            } catch (e: Exception) {
                m.onLog("No se pudo abrir el proyecto: ${e.message}")
                m.toast("No se pudo abrir el proyecto: ${e.message}")
                runOnUiThread { v<TextView>(R.id.fileName).text = doc?.name ?: "Ningún archivo abierto" }
            }
        }
    }

    private fun showProjects() {
        val list = savedProjects()
        AlertDialog.Builder(this)
            .setTitle("Proyectos guardados")
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> openProject(list[i]) }
            .setNeutralButton("Borrar…") { _, _ -> deleteProject(list) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteProject(list: List<File>) {
        AlertDialog.Builder(this)
            .setTitle("¿Cuál borrar?")
            .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle("Borrar «${list[i].name}»")
                    .setMessage("El proyecto y sus imágenes se eliminan de la app.")
                    .setPositiveButton("Borrar") { _, _ -> list[i].deleteRecursively(); toast("Borrado") }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun newBlank() = askSupport(Doc("Hoja en blanco", "nuevo", 0.0, 0.0, emptyList()), keepCurrent = false)

    private fun browseFile() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"     // .studio3 y .dxf no tienen tipo MIME fiable: se detecta por contenido
        }, REQ_OPEN)
    }

    @Deprecated("startActivityForResult de Activity plana")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { addImage(it) }
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.let { d ->
            d.data?.let { uri ->
                // permiso duradero: sin esto, un archivo reciente no se podría reabrir después
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
                openUri(uri)
            }
        }
    }

    /** Últimos 8 archivos abiertos: (uri, nombre), lo más nuevo primero. */
    private fun recentFiles(): List<Pair<String, String>> {
        val a = JSONArray(m.prefs.getString("recents", "[]"))
        val granted = contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        return List(a.length()) { a.getJSONObject(it) }.map { it.getString("uri") to it.getString("name") }
            .filter { it.first in granted }       // sin permiso duradero no se puede reabrir
    }

    private fun addRecent(uri: Uri, name: String) {
        val list = listOf(uri.toString() to name) + recentFiles().filter { it.first != uri.toString() }
        val a = JSONArray()
        list.take(8).forEach { (u, n) -> a.put(JSONObject().put("uri", u).put("name", n)) }
        m.prefs.edit().putString("recents", a.toString()).apply()
    }

    private fun handleIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { openUri(it) }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    /** Copia el archivo a la caché (Python lee rutas, no content://) y lo abre con bridge.mobile. */
    private fun openUri(uri: Uri) {
        v<TextView>(R.id.fileName).text = "Abriendo…"
        m.files.execute {
            try {
                val name = displayName(uri).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dir = File(cacheDir, "open").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }          // solo el último abierto
                val f = File(dir, name)
                contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                val t0 = System.currentTimeMillis()
                val js = JSONObject(m.python().getModule("bridge.mobile")
                    .callAttr("load_json", f.absolutePath, File(cacheDir, "img").absolutePath).toString())
                if (!js.optBoolean("ok")) throw IllegalStateException(js.optString("error"))
                val d = Doc.fromJson(js) { p -> loadBitmap(p) }
                m.onLog("Abierto $name: ${js.getJSONObject("stats")} en ${System.currentTimeMillis() - t0} ms")
                runOnUiThread { addRecent(uri, name); askSupport(d, keepCurrent = false) }
            } catch (e: Throwable) {
                m.onLog("No se pudo abrir: ${e.message}")
                runOnUiThread {
                    v<TextView>(R.id.fileName).text = doc?.name ?: "Ningún archivo abierto"
                    AlertDialog.Builder(this).setTitle("No se pudo abrir").setMessage(e.message).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    /** Imágenes del studio3 reducidas a ≤ [maxPx] (1600 para ver; más para imprimir). */
    private fun loadBitmap(path: String, maxPx: Int = 1600): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // las fotos del celular guardan los píxeles acostados y la rotación en EXIF: BitmapFactory la ignora
        val deg = try {
            when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: java.io.IOException) { 0 }
        if (deg == 0) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    // ------------------------------------------------------------------ soporte (siempre al abrir)
    private fun askSupport(d: Doc, keepCurrent: Boolean) {
        val pad = dp(16f).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val customIdx = Support.PRESETS.size
        val options = Support.PRESETS.map { "${it.name}  ·  ${nf(it.w)} × ${nf(it.h)} mm" } + "Personalizado  ·  X × Y mm"
        val radios = options.mapIndexed { i, t -> RadioButton(this).apply { id = 100 + i; text = t } }
        radios.forEach { group.addView(it) }
        val custom = LinearLayout(this)
        val cw = EditText(this).apply { hint = "Ancho X (mm) · máx. 304,8"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("200") }
        val ch = EditText(this).apply { hint = "Largo Y (mm)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("150") }
        custom.addView(cw, LinearLayout.LayoutParams(0, -2, 1f)); custom.addView(ch, LinearLayout.LayoutParams(0, -2, 1f))
        val onMat = Switch(this).apply { text = "Pegado sobre el mat" }
        val fit = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        box.addView(group); box.addView(custom); box.addView(onMat); box.addView(fit)

        val cur = support
        val start = if (keepCurrent && cur != null) Support.PRESETS.indexOfFirst { it.id == cur.id }.let { if (it < 0) customIdx else it } else 0
        if (keepCurrent && cur != null && cur.id == "custom") { cw.setText(cur.w.toString()); ch.setText(cur.h.toString()) }
        onMat.isChecked = keepCurrent && cur?.onMat == true
        group.check(100 + start)

        fun current(): Support? {
            val i = group.checkedRadioButtonId - 100
            return if (i in Support.PRESETS.indices) Support.PRESETS[i].copy(onMat = !Support.PRESETS[i].mat && onMat.isChecked)
            else {
                val w = cw.text.toString().replace(',', '.').toDoubleOrNull() ?: return null
                val h = ch.text.toString().replace(',', '.').toDoubleOrNull() ?: return null
                Support("custom", "Personalizado", w, h, mat = false, onMat = onMat.isChecked)
            }
        }
        var dialog: AlertDialog? = null
        fun update() {
            val i = group.checkedRadioButtonId - 100
            custom.visibility = if (i == customIdx) View.VISIBLE else View.GONE
            onMat.visibility = if (Support.PRESETS.getOrNull(i)?.mat == true) View.GONE else View.VISIBLE
            val s = current()
            val b = d.cutBBox()
            val err = when {
                s == null || s.w <= 0 || s.h <= 0 -> "Escribe ancho y largo en mm"
                s.w > Support.MAT_MM -> "El ancho máximo es 304,8 mm"
                s.onMat && s.h > Support.MAT24_MM -> "Sobre el mat el largo máximo es 609,6 mm (mat 12x24)"
                else -> null
            }
            val out = if (s != null) d.items.count { !it.isImage && it.world().let { w -> (0 until w.size / 2).any { k -> w[2 * k] < -0.05 || w[2 * k + 1] < -0.05 || w[2 * k] > s.w + 0.05 || w[2 * k + 1] > s.h + 0.05 } } } else 0
            fit.text = when {
                err != null -> "✕ $err"
                out > 0 -> "✕ $out de ${d.items.count { !it.isImage }} trazados se salen (${nf(b[2] - b[0])} × ${nf(b[3] - b[1])} mm)"
                d.items.none { !it.isImage } -> "✓ Hoja de ${nf(s!!.w)} × ${nf(s.h)} mm"
                else -> "✓ El diseño (${nf(b[2] - b[0])} × ${nf(b[3] - b[1])} mm) cabe en ${nf(s!!.w)} × ${nf(s.h)} mm"
            }
            fit.setTextColor(if (err != null || out > 0) 0xFFFF9FD0.toInt() else 0xFF3CCF91.toInt())
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = err == null
                text = if (out > 0) "Continuar igual" else "Continuar"
            }
        }
        group.setOnCheckedChangeListener { _, _ -> update() }
        onMat.setOnCheckedChangeListener { _, _ -> update() }
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        cw.addTextChangedListener(watcher); ch.addTextChangedListener(watcher)

        val dlg = AlertDialog.Builder(this)
            .setTitle("¿Sobre qué vas a cortar?")
            .setMessage(d.name)
            .setView(android.widget.ScrollView(this).apply { addView(box) })     // con teclado abierto no cabe
            .setPositiveButton("Continuar") { _, _ -> current()?.let { showDoc(d, it) } }
            .setNegativeButton("Cancelar") { _, _ -> v<TextView>(R.id.fileName).text = doc?.name ?: "Ningún archivo abierto" }
            .create()
        dialog = dlg
        dlg.setOnShowListener { update() }
        dlg.show()
    }

    private fun showDoc(d: Doc, s: Support) {
        if (d !== doc) openedFrom = null
        if (d !== doc || s != support) printedSignature = null     // otra hoja: lo impreso antes ya no vale
        // imágenes agregadas al documento anterior: nadie más las usa
        if (d !== doc) File(filesDir, "imagenes").listFiles()?.forEach { it.delete() }
        // marca inferior: 25 mm sin mat (rodillos), 10 con mat — mismo criterio que el panel
        if (s != support) v<EditText>(R.id.pInsetB).setText(if (s.usesMat) "10" else "25")
        doc = d
        support = s
        canvas.support = s
        canvas.doc = d
        v<View>(R.id.emptyBox).visibility = View.GONE
        v<TextView>(R.id.fileName).text = d.name
        refreshPanel()
    }

    // ------------------------------------------------------------------ panel
    private fun refreshPanel() {
        val d = doc
        val s = support
        val has = d != null && s != null
        v<View>(R.id.supportChip).visibility = if (has) View.VISIBLE else View.GONE
        v<View>(R.id.viewBar).visibility = if (has) View.VISIBLE else View.GONE
        v<View>(R.id.btnTools).visibility = if (has) View.VISIBLE else View.GONE
        v<View>(R.id.btnAdd).visibility = if (has) View.VISIBLE else View.GONE
        v<Button>(R.id.btnTools).setTextColor(if (showTools) 0xFFF0B429.toInt() else 0xFFE6E6E6.toInt())
        v<Button>(R.id.bMulti).setTextColor(if (canvas.multi) 0xFFF0B429.toInt() else 0xFFE6E6E6.toInt())
        if (!has) return
        d!!; s!!
        v<Button>(R.id.supportChip).text = "Soporte  ${s.label}"

        val cutting = m.cutting
        val selCount = d.sel.size
        v<View>(R.id.editBar).visibility = if (selCount > 0 && showTools && !cutting) View.VISIBLE else View.GONE
        v<Button>(R.id.bGroup).isEnabled = selCount >= 2
        v<Button>(R.id.bUngroup).isEnabled = d.sel.any { it.startsWith("g") }
        v<Button>(R.id.bLayerUp).isEnabled = d.selHasImage
        v<Button>(R.id.bLayerDown).isEnabled = d.selHasImage
        v<View>(R.id.cutBox).visibility = if (cutting) View.GONE else View.VISIBLE
        v<View>(R.id.btnAdd).visibility = if (cutting) View.GONE else View.VISIBLE     // durante el corte no se edita
        v<View>(R.id.btnSave).visibility = if (cutting) View.GONE else View.VISIBLE
        v<Button>(R.id.bUndo).isEnabled = d.canUndo && !cutting
        v<Button>(R.id.bRedo).isEnabled = d.canRedo && !cutting

        // selección
        v<View>(R.id.selSec).visibility = if (selCount > 0) View.VISIBLE else View.GONE
        if (selCount > 0) {
            val its = d.selItems()
            val nPaths = its.count { !it.isImage }; val nImg = its.size - nPaths
            val groups = d.sel.count { it.startsWith("g") }
            val who = if (selCount == 1) (if (groups == 1) "1 grupo" else "1 forma") else "$selCount objetos"
            v<TextView>(R.id.selTitle).text = "$who · $nPaths trazado${if (nPaths == 1) "" else "s"}" +
                if (nImg > 0) " + $nImg imagen${if (nImg == 1) "" else "es"}" else ""
            val b = d.selBBox()
            for ((id, value) in listOf(R.id.fx to b[0], R.id.fy to b[1], R.id.fw to b[2] - b[0], R.id.fh to b[3] - b[1])) {
                val e = v<EditText>(id)
                if (!e.hasFocus()) { val t = nf(value).replace(',', '.'); e.setText(t); shown[id] = t }
            }
        }

        // Print & Cut
        val pnc = v<Switch>(R.id.swPnc).isChecked
        val manual = v<RadioButton>(R.id.pncManual).isChecked
        v<View>(R.id.pncBox).visibility = if (pnc) View.VISIBLE else View.GONE
        v<View>(R.id.pncManualNote).visibility = if (manual) View.VISIBLE else View.GONE
        v<View>(R.id.pSearchRow).alpha = if (manual) 0.45f else 1f
        val r = if (pnc) regmarks() else null
        canvas.regmarks = r
        val pncProblems = mutableListOf<String>()
        var pncBlocks = false
        if (pnc) {
            if (s.mat) { pncProblems += "Elige el tamaño de la hoja impresa (A4, Carta o Personalizado, «sobre el mat» si corresponde): el mat 12x12 no se imprime."; pncBlocks = true }
            else if (s.w > Support.PNC_MAX_W || s.h > Support.PNC_MAX_H) { pncProblems += "Print & Cut admite hasta 30 × 61 cm con las marcas dentro (tu hoja: ${nf(s.w / 10)} × ${nf(s.h / 10)} cm)."; pncBlocks = true }
            else if (r == null) { pncProblems += "Revisa los valores de las marcas (números en mm)"; pncBlocks = true }
            else {
                // fuera del área, o encima de una marca (con 1 mm de holgura): la máquina podría no leerla
                val marks = Gpgl.regmarkRects(r)
                val near = d.items.count { !it.isImage && it.world().let { w -> (0 until w.size / 2).any { k ->
                    val x = w[2 * k]; val y = w[2 * k + 1]
                    x < r.inset || y < r.inset || x > r.pageW - r.inset || y > r.pageH - r.bottom ||
                        marks.any { (mx, my, mw, mh) -> x > mx - 1 && x < mx + mw + 1 && y > my - 1 && y < my + mh + 1 } } } }
                if (near > 0) pncProblems += "$near trazados se salen del área de las marcas o tocan una marca: la máquina podría no leerlas."
                if (printedSig != null && printedSig != signature(d)) pncProblems += "Moviste algo después de imprimir: la hoja impresa ya no calza con el corte."
            }
        }
        v<TextView>(R.id.pncWarn).apply { text = pncProblems.joinToString("\n"); visibility = if (pncProblems.isEmpty()) View.GONE else View.VISIBLE }
        // imprimir sirve con o sin marcas (diseño de fondo + corte encima); con Print & Cut agrega las marcas
        v<View>(R.id.btnPrint).visibility = View.VISIBLE
        v<Button>(R.id.btnPrint).isEnabled = !s.mat && !cutting && !(pnc && pncBlocks) && d.items.any { it.isImage }
        v<Button>(R.id.bSearch).isEnabled = pnc && !pncBlocks && !cutting

        // documento
        val shapes = d.items.filter { !it.isImage }
        val out = shapes.count { canvas.isOutside(it) }
        val b = d.cutBBox()
        val len = shapes.sumOf { it.world().let { w -> (1 until w.size / 2).sumOf { k -> Math.hypot(w[2 * k] - w[2 * k - 2], w[2 * k + 1] - w[2 * k - 1]) } } }
        val kind = when (d.kind) { "studio3" -> "Silhouette .studio3"; "nuevo" -> "Hoja en blanco"; else -> d.kind.uppercase() }
        val imgs = d.items.count { it.isImage }
        v<TextView>(R.id.docInfo).text = buildString {
            append("Tipo  $kind\n")
            if (d.kind != "nuevo") append("Página del archivo  ${nf(d.pageW)} × ${nf(d.pageH)} mm\n")
            if (shapes.isNotEmpty()) append("Diseño  ${nf(b[2] - b[0])} × ${nf(b[3] - b[1])} mm\n")
            append("Trazados  ${shapes.size}\n")
            append("Largo de corte  ${nf(len / 10)} cm")
            if (imgs > 0) append("\nImágenes  $imgs (solo se ven, no se cortan)")
        }
        val warns = d.warnings + if (out > 0) listOf("$out trazados se salen del soporte y no se cortarán.") else emptyList()
        v<TextView>(R.id.docWarn).apply { text = warns.joinToString("\n"); visibility = if (warns.isEmpty()) View.GONE else View.VISIBLE }

        val toCut = shapes.size - out
        v<Button>(R.id.bCut).apply {
            isEnabled = toCut > 0 && !cutting && !(pnc && pncBlocks)
            text = (if (pnc) "Escanear y cortar " else "Cortar ") +
                if (out > 0) "$toCut de ${shapes.size} trazados" else "${shapes.size} trazados"
        }
    }

    override fun onSelectionChanged() {
        // un número a medio escribir era para la selección anterior: se descarta (numEdit lo ignora)
        currentFocus?.let { if (it is EditText && it.id in listOf(R.id.fx, R.id.fy, R.id.fw, R.id.fh)) it.clearFocus() }
        refreshPanel(); canvas.invalidate()
    }
    override fun onEdited() { refreshPanel(); canvas.invalidate() }

    private fun edit(block: (Doc) -> Unit) {
        val d = doc ?: return
        if (d.sel.isEmpty()) return
        d.pushUndo()
        block(d)
        onEdited()
    }

    /** Dónde se puede trabajar: toda la hoja, o lo interno de las marcas, al ras de ellas. */
    private fun workArea(): DoubleArray {
        val s = support ?: return DoubleArray(4)
        val r = activeMarks()
        return if (r != null) doubleArrayOf(r.inset, r.inset, s.w - r.inset, s.h - r.bottom)
        else doubleArrayOf(0.0, 0.0, s.w, s.h)
    }

    /** Marcas que de verdad aplican: Print & Cut activo, sobre una hoja (el mat no se imprime) y valores válidos. */
    private fun activeMarks(): Gpgl.Regmarks? =
        if (v<Switch>(R.id.swPnc).isChecked && support?.mat == false) regmarks() else null

    private fun togglePanel() {
        val p = v<View>(R.id.sidePanel)
        val open = p.visibility != View.VISIBLE
        p.visibility = if (open) View.VISIBLE else View.GONE
        v<Button>(R.id.bPanel).text = if (open) "▶" else "◀"
        canvas.post { canvas.fit() }
    }

    private fun showAddMenu() {
        if (doc == null) return
        val labels = arrayOf("🖼  Imagen… (PNG / JPG)", "◯  Círculo", "▭  Rectángulo", "▢  Rectángulo redondeado")
        AlertDialog.Builder(this)
            .setTitle("Agregar")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    }, REQ_IMAGE)
                    1 -> addShape(Shapes.CIRCLE)
                    2 -> addShape(Shapes.RECT)
                    3 -> addShape(Shapes.ROUND)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Con algo seleccionado la forma cae centrada encima (lo normal: una imagen); si no, al centro del área. */
    private fun addShape(kind: String) {
        val d = doc ?: return
        val hasSel = d.sel.isNotEmpty()
        val a = if (hasSel) d.selBBox() else workArea()
        val cx = (a[0] + a[2]) / 2; val cy = (a[1] + a[3]) / 2
        val side = if (hasSel) minOf(a[2] - a[0], a[3] - a[1]) * 0.9 else 50.0
        d.pushUndo()
        if (kind == Shapes.CIRCLE) d.addShape(kind, cx, cy, side, side)
        else if (hasSel) d.addShape(kind, cx, cy, (a[2] - a[0]) * 0.9, (a[3] - a[1]) * 0.9)
        else d.addShape(kind, cx, cy, 60.0, 50.0)
        onEdited()
    }

    /** Copia la imagen a la carpeta de la app (el original puede moverse) y la pone en el área de trabajo. */
    private fun addImage(uri: Uri) = m.files.execute {
        try {
            val dir = File(filesDir, "imagenes").apply { mkdirs() }
            val f = File(dir, "${System.currentTimeMillis()}_" + displayName(uri).replace(Regex("[^\\w.\\-]"), "_"))
            contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            val bmp = loadBitmap(f.absolutePath) ?: throw IllegalStateException("no es una imagen que Android pueda abrir")
            runOnUiThread {
                val d = doc ?: return@runOnUiThread
                val a = workArea()
                d.pushUndo()
                d.addImage(bmp, f.absolutePath, (a[0] + a[2]) / 2, (a[1] + a[3]) / 2, minOf(80.0, (a[2] - a[0]) * 0.8))
                onEdited()
            }
        } catch (e: Throwable) {
            m.onLog("No se pudo agregar la imagen: ${e.message}")
            m.toast("No se pudo agregar la imagen: ${e.message}")
        }
    }

    /** Repite la selección en grilla hasta llenar el área de trabajo. */
    private fun askFill() {
        val d = doc ?: return
        if (d.sel.isEmpty()) return
        val pad = dp(16f).toInt()
        val area = workArea()
        val gap = EditText(this).apply {
            hint = "Separación (mm)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(m.prefs.getString("fill_gap", "3"))
        }
        val info = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(gap); addView(info) }
        fun gapMm() = gap.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        var dialog: AlertDialog? = null
        fun update() {
            val p = d.fillPlan(area, gapMm())
            val aw = area[2] - area[0]; val ah = area[3] - area[1]
            val ok = p.count in 1..Doc.MAX_FILL
            info.text = when {
                p.count == 0 -> "No cabe: el diseño (${nf(p.w)} × ${nf(p.h)} mm) es más grande que el área (${nf(aw)} × ${nf(ah)} mm)"
                !ok -> "Serían ${p.count} copias: el máximo es ${Doc.MAX_FILL}. Sube la separación o agranda el diseño."
                else -> "Caben ${p.count} copias (${p.cols} × ${p.rows}) de ${nf(p.w)} × ${nf(p.h)} mm en ${nf(aw)} × ${nf(ah)} mm"
            }
            info.setTextColor(if (ok) 0xFF3CCF91.toInt() else 0xFFFF9FD0.toInt())
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = ok
        }
        gap.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val dlg = AlertDialog.Builder(this)
            .setTitle("Rellenar el área de trabajo")
            .setMessage(if (activeMarks() != null) "Área interna de las marcas de Print & Cut" else "Toda la hoja")
            .setView(box)
            .setPositiveButton("Rellenar") { _, _ ->
                m.prefs.edit().putString("fill_gap", gap.text.toString()).apply()
                d.pushUndo()
                d.fill(area, gapMm())
                onEdited()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog = dlg
        dlg.setOnShowListener { update() }
        dlg.show()
    }

    /** X, Y, ancho y alto exactos en mm (con o sin proporción). */
    private fun numEdit(changed: Int) {
        val d = doc ?: return
        if (d.sel.isEmpty() || d.sel.toSet() != editingSel) return   // se escribió para otra selección
        val text = v<EditText>(changed).text.toString()
        if (text == shown[changed]) return                           // no se cambió nada (solo se tocó el campo)
        val b = d.selBBox()
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val value = text.replace(',', '.').toDoubleOrNull() ?: return
        // solo cambia el campo editado; los demás usan el valor exacto (no el redondeado a 0,1 que se ve)
        val nx = if (changed == R.id.fx) value else b[0]
        val ny = if (changed == R.id.fy) value else b[1]
        var nw = if (changed == R.id.fw) value else w
        var nh = if (changed == R.id.fh) value else h
        if (nw <= 0 || nh <= 0 || w <= 0 || h <= 0) return
        if (canvas.lockAspect) { if (changed == R.id.fh) nw = w * nh / h else if (changed == R.id.fw) nh = h * nw / w }
        val mtx = A.mul(A.tr(nx - b[0], ny - b[1]), A.sc(nw / w, nh / h, b[0], b[1]))
        if (mtx.isAlmost(A.I, 1e-6)) return
        d.pushUndo()
        d.apply(mtx)
        onEdited()
    }

    // ------------------------------------------------------------------ material y cuchilla
    private fun loadPresets() = m.files.execute {
        try {
            val js = JSONObject(m.python().getModule("bridge.mobile").callAttr("presets_json").toString())
            val mats = js.getJSONArray("materials").toList()
            val bl = js.getJSONArray("blades").toList()
            runOnUiThread {
                materials = mats; blades = bl
                v<Spinner>(R.id.material).adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                    mats.map { it.getString("name") + if (it.optBoolean("own")) " · propio" else "" })
                // el último material usado
                val last = m.prefs.getString("material", null)
                mats.indexOfFirst { it.optString("id") == last }.takeIf { it >= 0 }?.let { v<Spinner>(R.id.material).setSelection(it) }
            }
        } catch (e: Throwable) {
            m.onLog("No se pudieron leer los materiales: ${e.message}")
        }
    }

    private fun JSONArray.toList() = List(length()) { getJSONObject(it) }

    private fun bladeOf(mat: JSONObject): JSONObject =
        blades.firstOrNull { it.getString("id") == mat.optString("blade_id") } ?: blades.first { it.getString("id") == "ratchet" }

    private fun applyMaterial(pos: Int) {
        val mat = materials.getOrNull(pos) ?: return
        val b = bladeOf(mat)
        m.prefs.edit().putString("material", mat.optString("id")).apply()
        v<EditText>(R.id.speed).setText(mat.getInt("speed").toString())
        v<EditText>(R.id.pressure).setText(mat.getInt("pressure").toString())
        v<EditText>(R.id.passes).setText(mat.optInt("passes", 1).toString())
        v<TextView>(R.id.bladeName).text = b.getString("name") + if (b.optInt("tool", 1) == 2) " · carro 2" else ""
        v<EditText>(R.id.offset).setText(b.getDouble("offset_mm").toString())
        refreshOffsetNote()
    }

    private fun refreshOffsetNote() {
        val mat = materials.getOrNull(v<Spinner>(R.id.material).selectedItemPosition) ?: return
        val base = bladeOf(mat).getDouble("offset_mm")
        val now = v<EditText>(R.id.offset).text.toString().replace(',', '.').toDoubleOrNull()
        v<TextView>(R.id.offsetNote).text = "Perfil: ${nf(base, 2)} mm" +
            (if (now != null && Math.abs(now - base) > 1e-9) " · cambiado solo para este corte" else "") +
            ". Si las esquinas salen redondeadas, sube de a 0,05 mm."
    }

    // ------------------------------------------------------------------ cortar
    private fun settings(): Gpgl.CutSettings? {
        val mat = materials.getOrNull(v<Spinner>(R.id.material).selectedItemPosition)
        val blade = mat?.let { bladeOf(it) }
        fun int(id: Int) = v<EditText>(id).text.toString().toIntOrNull()
        val s = Gpgl.CutSettings(
            speed = int(R.id.speed) ?: -1,
            pressure = int(R.id.pressure) ?: -1,
            passes = int(R.id.passes) ?: 1,
            mat = support?.tg ?: 0,
            tool = blade?.optInt("tool", 1) ?: 1,
            bladeOffsetMm = v<EditText>(R.id.offset).text.toString().replace(',', '.').toDoubleOrNull() ?: Gpgl.RATCHET_OFFSET_MM,
            sharpenCorners = v<Switch>(R.id.swSharpen).isChecked,
            // TF (profundidad) solo para AutoBlade, igual que el bridge
            depth = if (blade?.optString("type") == "auto" && mat != null && !mat.isNull("depth")) mat.getInt("depth") else null,
        )
        return try { s.validate(); s } catch (e: IllegalArgumentException) { toast(e.message ?: "valores inválidos"); null }
    }

    private fun confirmCut() {
        val d = doc ?: return
        val sup = support ?: return
        if (!m.connected) {
            toast("Primero conecta la Cameo")
            startActivity(Intent(this, MainActivity::class.java))
            return
        }
        val s = settings() ?: return
        val pnc = v<Switch>(R.id.swPnc).isChecked
        val r = if (pnc) regmarks() ?: run { toast("Revisa los valores de las marcas"); return } else null
        val inside = d.items.filter { !it.isImage && !canvas.isOutside(it) }.map { it.id }.toSet()
        v<Button>(R.id.bCut).isEnabled = false
        // orden de corte recalculado con lo editado (interiores primero, ordering.py): con miles de
        // trazados son muchas cuentas, así que va fuera del hilo de UI
        m.files.execute {
            val items = d.cutOrder { it.id in inside }
            val paths = items.map { it.world().let { w -> List(w.size / 2) { k -> w[2 * k] to w[2 * k + 1] } } }
            runOnUiThread { refreshPanel(); confirmCut2(d, sup, s, r, items, paths) }
        }
    }

    private fun confirmCut2(d: Doc, sup: Support, s: Gpgl.CutSettings, r: Gpgl.Regmarks?,
                            items: List<Item>, paths: List<List<Pair<Double, Double>>>) {
        val mat = materials.getOrNull(v<Spinner>(R.id.material).selectedItemPosition)
        val len = paths.sumOf { p -> p.zipWithNext().sumOf { (a, b) -> Math.hypot(b.first - a.first, b.second - a.second) } } * s.passes
        val rows = mutableListOf(
            "Soporte" to sup.label,
            "Material" to (mat?.getString("name") ?: "—"),
            "Velocidad · presión · pasadas" to "${s.speed} · ${s.pressure} · ${s.passes}",
            "Cuchilla" to "${v<TextView>(R.id.bladeName).text} · offset ${nf(s.bladeOffsetMm, 2)} mm${if (s.sharpenCorners) " · esquinas afiladas" else ""}",
            "Largo de corte" to "${nf(len / 10)} cm",
            "Al terminar" to "vuelve al origen",
        )
        // solo con datos reales de tus cortes; sin mediciones no se inventa un número
        m.estimateSeconds(s.speed, len)?.let { sec ->
            rows.add(rows.size - 1, "Tiempo aproximado" to if (sec < 90) "≈ $sec s" else "≈ ${(sec + 30) / 60} min")
        }
        if (r != null) rows.add(1, "Print & Cut" to if (r.manual) "manual · cuchilla sobre el cuadrado"
            else "automático · búsqueda ${nf(r.searchX)} / ${nf(r.searchY)} mm · inset ${nf(r.inset)}/${nf(r.bottom)}")
        val go = {
            AlertDialog.Builder(this)
                .setTitle("${if (r != null) "Escanear y cortar" else "Cortar"} ${paths.size} trazados")
                .setMessage(d.name + "\n\n" + rows.joinToString("\n") { (k, v) -> "$k:  $v" })
                .setPositiveButton(if (r != null) "Escanear y cortar" else "Cortar") { _, _ -> startCut(s, items, paths, r) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        // Print & Cut: si se movió algo después de imprimir, la hoja ya no calza
        if (r != null && printedSig != null && printedSig != signature(d)) {
            AlertDialog.Builder(this)
                .setTitle("La hoja impresa ya no calza")
                .setMessage("Moviste, giraste o cambiaste algo después de imprimir. El corte saldrá corrido respecto de lo impreso.\n\nVuelve a imprimir, o deshaz los cambios.")
                .setPositiveButton("Cortar igual") { _, _ -> go() }
                .setNeutralButton("Imprimir de nuevo") { _, _ -> printSheet() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else go()
    }

    private fun startCut(s: Gpgl.CutSettings, items: List<Item>, paths: List<List<Pair<Double, Double>>>, r: Gpgl.Regmarks?) {
        // Print & Cut: antes del trazado la máquina busca las marcas; el origen pasa a la marca superior izquierda
        val preCut: (() -> Pair<Double, Double>)? = r?.let { rm -> {
            val (ok, resp) = m.findRegmarks(rm)
            if (!ok) throw IllegalStateException(
                if (m.stopRequested) "Detenido durante la búsqueda de marcas"
                else "No se encontraron las marcas de registro (respuesta ${resp ?: "ninguna"}). " +
                    "Revisa que la hoja esté cargada con el cuadrado arriba a la izquierda, o ajusta la búsqueda.")
            rm.origin
        } }
        m.cut(s, paths, preCut)?.let { toast(it); return }
        doc?.sel?.clear()
        cutSizes = paths.map { it.size }
        cutPasses = s.passes
        canvas.cutOrder = items.map { it.id }
        canvas.cutAt = 0 to 0.0
        refreshPanel()
    }

    // ------------------------------------------------------------------ Print & Cut
    private fun regmarks(): Gpgl.Regmarks? {
        val s = support ?: return null
        fun num(id: Int) = v<EditText>(id).text.toString().replace(',', '.').toDoubleOrNull()
        val inset = num(R.id.pInset) ?: return null
        val r = Gpgl.Regmarks(
            // inferior ≤ 0 o vacío -> igual al inset, como cameoAddRegmarks del panel
            pageW = s.w, pageH = s.h, inset = inset, insetBottom = num(R.id.pInsetB)?.takeIf { it > 0 } ?: inset,
            thick = num(R.id.pThick) ?: return null,
            manual = v<RadioButton>(R.id.pncManual).isChecked,
            searchX = num(R.id.pSearchX) ?: 0.0, searchY = num(R.id.pSearchY) ?: 0.0,
            calibX = num(R.id.pCalX) ?: 0.0, calibY = num(R.id.pCalY) ?: 0.0,
        )
        return if (r.inset <= 0 || r.thick <= 0 || r.width <= 0 || r.height <= 0) null else r
    }

    /** Soporte + marcas + posiciones de todo (imágenes y cortes): si cambia después de imprimir,
     *  la hoja impresa ya no calza. Solo cuenta si la impresión no se canceló ni falló. */
    private fun signature(d: Doc): String = (support?.label ?: "") + "|" + regmarks()?.let {
        "${it.inset},${it.bottom},${it.thick},${it.length}" } + "|" +
        d.items.joinToString(";") { "${it.id}:" + it.t.joinToString(",") { v -> "%.2f".format(java.util.Locale.US, v) } }

    private var printJob: android.print.PrintJob? = null
    private val printedSig: String?
        get() = printedSignature?.takeIf { printJob?.let { j -> !j.isCancelled && !j.isFailed } ?: false }

    /** La hoja tal cual: el diseño (imágenes) SIN las líneas de corte, al tamaño exacto del soporte.
     *  Con Print & Cut activo se agregan las marcas; sin él es una hoja normal (diseño de fondo con el
     *  corte encima, alineada al origen a mano). El PDF se arma fuera del hilo de UI. */
    private fun printSheet() {
        val d = doc ?: return
        val sup = support ?: return
        if (sup.mat) { toast("Elige el tamaño de la hoja a imprimir (A4, Carta o Personalizado)"); return }
        if (d.items.none { it.isImage }) { toast("Este archivo no tiene imágenes para imprimir"); return }
        val r = if (v<Switch>(R.id.swPnc).isChecked) regmarks() ?: run { toast("Revisa los valores de las marcas"); return } else null
        val sig = signature(d)
        val images = d.items.filter { it.isImage }.map { Triple(A.mul(it.t, it.m!!), it.imagePath, it.bitmap) }
        v<Button>(R.id.btnPrint).apply { isEnabled = false; text = "Preparando…" }
        m.files.execute {
            try {
                val pdfFile = buildPrintPdf(sup, r, images)
                runOnUiThread { launchPrint(d, sup, r, pdfFile, sig) }
            } catch (e: Throwable) {
                m.onLog("No se pudo preparar la hoja: ${e.message}")
                m.toast("No se pudo preparar la hoja: ${e.message}")
            } finally {
                runOnUiThread { v<Button>(R.id.btnPrint).text = "🖨 Imprimir"; refreshPanel() }
            }
        }
    }

    private fun buildPrintPdf(sup: Support, r: Gpgl.Regmarks?, images: List<Triple<Aff, String?, Bitmap?>>): File {
        val k = 72.0 / 25.4                                                // mm -> puntos PDF
        val pdfFile = File(cacheDir, "print").apply { mkdirs() }.resolve("hoja-con-marcas.pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(
            Math.round(sup.w * k).toInt(), Math.round(sup.h * k).toInt(), 1).create())
        val c = page.canvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        // de a una imagen a la vez: varias fotos a 3000 px no caben juntas en memoria. Las copias de
        // «Rellenar» van seguidas y comparten archivo: se decodifica una vez y se reutiliza.
        var lastPath: String? = null
        var last: Bitmap? = null
        for ((world, path, preview) in images) {
            if (path != lastPath) { last?.recycle(); last = path?.let { loadBitmap(it, 3000) }; lastPath = path }
            val bmp = last ?: preview ?: continue
            val px = doubleArrayOf(2.0 / bmp.width, 0.0, 0.0, 2.0 / bmp.height, -1.0, -1.0)
            val mt = A.mul(doubleArrayOf(k, 0.0, 0.0, k, 0.0, 0.0), A.mul(world, px))
            val mx = android.graphics.Matrix()
            mx.setValues(floatArrayOf(mt[0].toFloat(), mt[2].toFloat(), mt[4].toFloat(),
                mt[1].toFloat(), mt[3].toFloat(), mt[5].toFloat(), 0f, 0f, 1f))
            c.drawBitmap(bmp, mx, paint)
        }
        last?.recycle()
        paint.color = android.graphics.Color.BLACK
        if (r != null) for ((x, y, w, h) in Gpgl.regmarkRects(r))
            c.drawRect((x * k).toFloat(), (y * k).toFloat(), ((x + w) * k).toFloat(), ((y + h) * k).toFloat(), paint)
        pdf.finishPage(page)
        pdfFile.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return pdfFile
    }

    private fun launchPrint(d: Doc, sup: Support, r: Gpgl.Regmarks?, pdfFile: File, sig: String) {
        val media = when (sup.id) {
            "a4" -> android.print.PrintAttributes.MediaSize.ISO_A4
            "carta" -> android.print.PrintAttributes.MediaSize.NA_LETTER
            else -> android.print.PrintAttributes.MediaSize("cameo_${sup.w}x${sup.h}", "Personalizado ${nf(sup.w)} × ${nf(sup.h)} mm",
                Math.round(sup.w / 25.4 * 1000).toInt(), Math.round(sup.h / 25.4 * 1000).toInt())
        }
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(media)
            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(android.print.PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val adapter = object : android.print.PrintDocumentAdapter() {
            private var warnedPaper = false
            override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes?,
                                  cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                // si en el diálogo se cambia el papel, Android escala la hoja y las marcas ya no miden lo que busca la máquina
                val chosen = new?.mediaSize
                if (chosen != null && !warnedPaper && (chosen.asPortrait().widthMils != media.widthMils || chosen.asPortrait().heightMils != media.heightMils)) {
                    warnedPaper = true
                    m.toast("Ese papel no es ${sup.name}: la hoja se escalaría y el corte no calzaría. Elige ${sup.name}.")
                }
                cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder("cameo-marcas.pdf")
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor,
                                 cancel: android.os.CancellationSignal?, cb: WriteResultCallback) {
                try {
                    pdfFile.inputStream().use { inp -> java.io.FileOutputStream(dest.fileDescriptor).use { inp.copyTo(it) } }
                    cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }
        printJob = getSystemService(android.print.PrintManager::class.java).print("Cameo Link · ${d.name}", adapter, attrs)
        // vale solo con marcas y si el trabajo no se cancela ni falla (printedSig)
        printedSignature = if (r != null) sig else null
        m.onLog("Hoja para imprimir: ${nf(sup.w)} × ${nf(sup.h)} mm" +
            (r?.let { ", con marcas (inset ${nf(it.inset)}/${nf(it.bottom)})" } ?: ", sin marcas"))
        toast("Imprime al 100 % (tamaño real), sin ajustar a la página")
    }

    /** Solo busca las marcas, sin cortar (como «Probar búsqueda» del panel). */
    private fun testSearch() {
        val r = regmarks() ?: run { toast("Revisa los valores de las marcas"); return }
        val matTg = support?.tg ?: 0
        m.run("Probar búsqueda") {
            val ses = m.session
            ses.initialize()
            if (ses.status() == "sin material") { m.setMachine("sin material"); m.toast("No hay material cargado"); return@run }
            ses.sendCommands(listOf("TG$matTg", "FN0", "TB50,0"))
            m.setMachine("buscando marcas…")
            val (ok, resp) = m.findRegmarks(r)
            if (ok) ses.sendCommands(listOf("SO0", "FN0"))     // si falla, findRegmarks ya lo mandó
            m.setMachine(if (ok) "marcas encontradas" else "marcas NO encontradas")
            m.toast(if (ok) "Marcas encontradas ✓" else "No encontró las marcas (respuesta ${resp ?: "ninguna"})")
        }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_OPEN = 10
        private const val REQ_IMAGE = 11
        private const val AUTOSAVE = "_ultimo"
    }
}
