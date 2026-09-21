/* Cameo Cut — main.js (lado CEP / Chromium, vanilla JS)
 * Habla con:
 *   - host.jsx via csInterface.evalScript (Illustrator)
 *   - bridge Python via fetch (http://127.0.0.1:7474)
 *   - Node (child_process, fs, os, path) para lanzar el bridge y escribir el SVG temporal
 */
(function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // Node (disponible gracias a --enable-nodejs --mixed-context)
  // ---------------------------------------------------------------------------
  var nodeReq = null;
  try {
    if (typeof require === 'function') nodeReq = require;
    else if (window.cep_node && typeof window.cep_node.require === 'function') nodeReq = window.cep_node.require;
  } catch (e) { nodeReq = null; }

  var nodeFs = null, nodeOs = null, nodePath = null, nodeCp = null;
  if (nodeReq) {
    try {
      nodeFs = nodeReq('fs');
      nodeOs = nodeReq('os');
      nodePath = nodeReq('path');
      nodeCp = nodeReq('child_process');
    } catch (e) { nodeFs = nodeOs = nodePath = nodeCp = null; }
  }
  var hasNode = !!(nodeFs && nodeOs && nodePath && nodeCp);

  // ---------------------------------------------------------------------------
  // CSInterface
  // ---------------------------------------------------------------------------
  var csInterface = new CSInterface();

  function evalScript(script) {
    return new Promise(function (resolve, reject) {
      csInterface.evalScript(script, function (res) {
        if (res === 'EvalScript error.' || res === undefined || res === null) {
          reject(new Error('Error en ExtendScript: ' + script.slice(0, 60)));
          return;
        }
        try { resolve(JSON.parse(res)); }
        catch (e) { reject(new Error('Respuesta ExtendScript no es JSON: ' + String(res).slice(0, 120))); }
      });
    });
  }

  function jsxStr(s) {
    return JSON.stringify(String(s));
  }

  // ---------------------------------------------------------------------------
  // Ajustes persistidos
  // ---------------------------------------------------------------------------
  var DEFAULTS = {
    // venv del proyecto: el `python` global de esta máquina está incompleto
    pythonExe: 'D:\\SandBox\\cameo-illustrator\\.venv\\Scripts\\python.exe',
    bridgeRoot: 'D:\\SandBox\\cameo-illustrator',
    bridgeUrl: 'http://127.0.0.1:7474'
  };

  function loadSettings() {
    var s = {};
    try { s = JSON.parse(localStorage.getItem('cameocut.settings') || '{}'); } catch (e) { s = {}; }
    return {
      pythonExe: s.pythonExe || DEFAULTS.pythonExe,
      bridgeRoot: s.bridgeRoot || DEFAULTS.bridgeRoot,
      bridgeUrl: (s.bridgeUrl || DEFAULTS.bridgeUrl).replace(/\/+$/, '')
    };
  }

  function saveSettings(s) {
    try { localStorage.setItem('cameocut.settings', JSON.stringify(s)); } catch (e) {}
  }

  var settings = loadSettings();

  // ---------------------------------------------------------------------------
  // DOM
  // ---------------------------------------------------------------------------
  function $(id) { return document.getElementById(id); }

  var el = {
    statusDot: $('status-dot'),
    statusText: $('status-text'),
    btnStartBridge: $('btn-start-bridge'),
    cfgPython: $('cfg-python'),
    cfgRoot: $('cfg-root'),
    cfgUrl: $('cfg-url'),
    btnSaveSettings: $('btn-save-settings'),
    nodeStatus: $('node-status'),

    btnRefreshDoc: $('btn-refresh-doc'),
    docName: $('doc-name'),
    selLayer: $('sel-layer'),
    chkColor: $('chk-color'),
    selColor: $('sel-color'),
    colorSwatch: $('color-swatch'),

    selMaterial: $('sel-material'),
    inPressure: $('in-pressure'),
    inSpeed: $('in-speed'),
    inDepth: $('in-depth'),
    lblDepth: $('lbl-depth'),
    selBlade: $('sel-blade'),
    inPasses: $('in-passes'),
    selTool: $('sel-tool'),
    selMat: $('sel-mat'),
    chkHome: $('chk-home'),

    chkPnc: $('chk-pnc'),
    pncBadge: $('pnc-badge'),
    inInset: $('in-inset'),
    inCalX: $('in-calx'),
    inCalY: $('in-caly'),
    btnAddRegmarks: $('btn-add-regmarks'),
    btnRemoveRegmarks: $('btn-remove-regmarks'),
    regmarksInfo: $('regmarks-info'),

    btnPreview: $('btn-preview'),
    canvas: $('preview-canvas'),
    previewStats: $('preview-stats'),

    btnCut: $('btn-cut'),
    jobBox: $('job-box'),
    progressBar: $('progress-bar'),
    jobMsg: $('job-msg'),
    btnAbort: $('btn-abort'),

    log: $('log')
  };

  // ---------------------------------------------------------------------------
  // Log (últimas 5 líneas)
  // ---------------------------------------------------------------------------
  var logLines = [];
  function log(msg, kind) {
    var t = new Date();
    var hh = ('0' + t.getHours()).slice(-2), mm = ('0' + t.getMinutes()).slice(-2), ss = ('0' + t.getSeconds()).slice(-2);
    logLines.push({ text: '[' + hh + ':' + mm + ':' + ss + '] ' + msg, kind: kind || '' });
    if (logLines.length > 5) logLines.shift();
    el.log.innerHTML = '';
    logLines.forEach(function (l) {
      var d = document.createElement('div');
      if (l.kind) d.className = l.kind;
      d.textContent = l.text;
      el.log.appendChild(d);
    });
    el.log.scrollTop = el.log.scrollHeight;
  }
  function logErr(msg) { log(msg, 'err'); }
  function logOk(msg) { log(msg, 'ok'); }

  // ---------------------------------------------------------------------------
  // Tema desde Illustrator (appSkinInfo)
  // ---------------------------------------------------------------------------
  function applyTheme() {
    try {
      var env = csInterface.getHostEnvironment();
      var skin = env && env.appSkinInfo;
      if (!skin || !skin.panelBackgroundColor) return;
      var c = skin.panelBackgroundColor.color;
      var bg = 'rgb(' + Math.round(c.red) + ',' + Math.round(c.green) + ',' + Math.round(c.blue) + ')';
      var lum = (c.red + c.green + c.blue) / 3;
      var root = document.documentElement.style;
      root.setProperty('--bg', bg);
      var lighter = function (v, d) { return Math.max(0, Math.min(255, Math.round(v + d))); };
      var d1 = lum > 128 ? -10 : 10;
      root.setProperty('--bg-card', 'rgb(' + lighter(c.red, d1) + ',' + lighter(c.green, d1) + ',' + lighter(c.blue, d1) + ')');
      var d2 = lum > 128 ? 12 : -14;
      root.setProperty('--bg-input', 'rgb(' + lighter(c.red, d2) + ',' + lighter(c.green, d2) + ',' + lighter(c.blue, d2) + ')');
      root.setProperty('--fg', lum > 128 ? '#1e1e1e' : '#d6d6d6');
      root.setProperty('--fg-dim', lum > 128 ? '#555' : '#9a9a9a');
      root.setProperty('--border', lum > 128 ? '#b5b5b5' : '#1f1f1f');
    } catch (e) { /* mantener tema por defecto */ }
  }

  // ---------------------------------------------------------------------------
  // Bridge HTTP
  // ---------------------------------------------------------------------------
  var bridgeOnline = false;

  function bridgeFetch(path, opts, timeoutMs) {
    var url = settings.bridgeUrl + path;
    var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    var o = opts || {};
    if (controller) o.signal = controller.signal;
    var timer = controller ? setTimeout(function () { controller.abort(); }, timeoutMs || 5000) : null;
    return fetch(url, o).then(function (r) {
      if (timer) clearTimeout(timer);
      return r.json();
    }).catch(function (e) {
      if (timer) clearTimeout(timer);
      throw new Error('Bridge no responde');
    });
  }

  function bridgeGet(path, timeoutMs) { return bridgeFetch(path, { method: 'GET' }, timeoutMs); }
  function bridgePost(path, body, timeoutMs) {
    return bridgeFetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    }, timeoutMs);
  }

  // ---------------------------------------------------------------------------
  // Estado / polling
  // ---------------------------------------------------------------------------
  var lastStatus = null;
  var lastJob = null;
  var materialsLoaded = false;

  function setStatus(dotClass, text) {
    el.statusDot.className = 'dot ' + dotClass;
    el.statusText.textContent = text;
  }

  function renderStatus() {
    if (!bridgeOnline) {
      setStatus('off', 'Bridge no iniciado');
      el.btnStartBridge.hidden = false;
      el.btnCut.disabled = true;
      return;
    }
    el.btnStartBridge.hidden = true;
    var st = lastStatus || {};
    var job = lastJob || {};
    if (job.state === 'running') {
      setStatus('busy', 'Cortando…');
      el.btnCut.disabled = true;
      return;
    }
    // Silhouette Studio abierto se queda con las respuestas de la máquina
    if (st.studio_running) {
      setStatus('err', 'Cierra Silhouette Studio para usar el panel');
      el.btnCut.disabled = true;
      return;
    }
    if (!st.connected) {
      setStatus('err', 'Bridge activo — Cameo no conectada');
      el.btnCut.disabled = true;
      return;
    }
    if (st.state === 'unloaded') {
      setStatus('warn', 'Sin material cargado');
      el.btnCut.disabled = false; // se permite; el bridge decidirá
      return;
    }
    if (st.state === 'moving' || st.busy) {
      setStatus('busy', (st.model || 'Cameo 4') + ' ocupada');
      el.btnCut.disabled = true;
      return;
    }
    setStatus('ok', (st.model || 'Cameo 4') + ' lista');
    el.btnCut.disabled = false;
  }

  function renderJob() {
    var job = lastJob || { state: 'idle' };
    if (job.state === 'running') {
      el.jobBox.hidden = false;
      el.btnAbort.hidden = false;
      el.progressBar.style.width = Math.round((job.progress || 0) * 100) + '%';
      el.jobMsg.textContent = job.message || 'Cortando…';
    } else if (job.state === 'done' || job.state === 'error') {
      el.jobBox.hidden = false;
      el.btnAbort.hidden = true;
      el.progressBar.style.width = job.state === 'done' ? '100%' : Math.round((job.progress || 0) * 100) + '%';
      el.jobMsg.textContent = (job.state === 'done' ? 'Listo: ' : 'Error: ') + (job.message || '');
    } else {
      el.jobBox.hidden = true;
      el.btnAbort.hidden = true;
    }
  }

  var prevJobState = null;

  function poll() {
    bridgeGet('/status', 2500).then(function (st) {
      var wasOffline = !bridgeOnline;
      bridgeOnline = true;
      lastStatus = st;
      if (wasOffline) {
        logOk('Bridge conectado en ' + settings.bridgeUrl);
      }
      if (!materialsLoaded) loadMaterials();
      return bridgeGet('/job', 2500).then(function (job) {
        lastJob = job;
        if (prevJobState === 'running' && job.state === 'done') logOk('Corte terminado: ' + (job.message || ''));
        if (prevJobState === 'running' && job.state === 'error') logErr('Corte con error: ' + (job.message || ''));
        prevJobState = job.state;
      }).catch(function () { lastJob = null; });
    }).catch(function () {
      if (bridgeOnline) logErr('Bridge no responde');
      bridgeOnline = false;
      lastStatus = null;
      lastJob = null;
      materialsLoaded = false;
    }).then(function () {
      renderStatus();
      renderJob();
    });
  }

  function startPolling() {
    poll();
    setInterval(poll, 2000);
  }

  // ---------------------------------------------------------------------------
  // Lanzar bridge con Node
  // ---------------------------------------------------------------------------
  function startBridge() {
    if (!hasNode) {
      logErr('Node no disponible en el panel (revisa --enable-nodejs en manifest). Inicia el bridge a mano: python -m bridge.server');
      return;
    }
    try {
      var child = nodeCp.spawn(settings.pythonExe, ['-m', 'bridge.server'], {
        cwd: settings.bridgeRoot,
        detached: true,
        stdio: 'ignore',
        windowsHide: true
      });
      child.on('error', function (err) {
        logErr('No se pudo lanzar el bridge: ' + err.message);
      });
      child.unref();
      log('Bridge lanzado: ' + settings.pythonExe + ' -m bridge.server (cwd ' + settings.bridgeRoot + ')');
      setStatus('busy', 'Iniciando bridge…');
      setTimeout(poll, 1500);
      setTimeout(poll, 3500);
    } catch (e) {
      logErr('Error al lanzar el bridge: ' + e.message);
    }
  }

  // ---------------------------------------------------------------------------
  // Materiales
  // ---------------------------------------------------------------------------
  var materials = [];

  function loadMaterials() {
    bridgeGet('/materials').then(function (list) {
      if (!Array.isArray(list)) throw new Error('Respuesta /materials inválida');
      materials = list;
      materialsLoaded = true;
      var prev = el.selMaterial.value;
      el.selMaterial.innerHTML = '';
      if (list.length === 0) {
        var o0 = document.createElement('option');
        o0.value = ''; o0.textContent = '(sin materiales en el bridge)';
        el.selMaterial.appendChild(o0);
      }
      list.forEach(function (m) {
        var o = document.createElement('option');
        o.value = m.id;
        o.textContent = m.name + ' (P' + m.pressure + ' V' + m.speed + ')';
        el.selMaterial.appendChild(o);
      });
      var restore = prev || (localStorage.getItem('cameocut.material') || '');
      if (restore && list.some(function (m) { return m.id === restore; })) {
        el.selMaterial.value = restore;
      }
      applyMaterial();
      log('Materiales cargados: ' + list.length);
    }).catch(function (e) {
      logErr('No se pudieron cargar materiales: ' + e.message);
    });
  }

  function currentMaterial() {
    var id = el.selMaterial.value;
    for (var i = 0; i < materials.length; i++) if (materials[i].id === id) return materials[i];
    return null;
  }

  function applyMaterial() {
    var m = currentMaterial();
    if (!m) return;
    el.inPressure.value = m.pressure;
    el.inSpeed.value = m.speed;
    el.inDepth.value = (m.depth === null || m.depth === undefined) ? '' : m.depth;
    el.inPasses.value = m.passes || 1;
    el.selTool.value = String(m.tool || 1);
    el.selBlade.value = m.blade || 'ratchet';
    updateDepthLabel();
    try { localStorage.setItem('cameocut.material', m.id); } catch (e) {}
  }

  // Con trinquete la profundidad se fija a mano en la cuchilla: el número es solo una sugerencia
  function updateDepthLabel() {
    var b = el.selBlade.value;
    el.lblDepth.textContent = b === 'auto' ? 'Prof. AutoBlade (1–10)'
      : b === 'pen' ? 'Profundidad (no aplica)' : 'Prof. sugerida en cuchilla (1–10)';
    el.inDepth.disabled = (b !== 'auto');
  }

  function numOrNull(input, min, max) {
    var v = input.value;
    if (v === '' || v === null || v === undefined) return null;
    var n = parseInt(v, 10);
    if (isNaN(n)) return null;
    if (n < min) n = min;
    if (n > max) n = max;
    return n;
  }

  function buildOverrides() {
    var o = {};
    var p = numOrNull(el.inPressure, 1, 33); if (p !== null) o.pressure = p;
    var s = numOrNull(el.inSpeed, 1, 30); if (s !== null) o.speed = s;
    var d = numOrNull(el.inDepth, 1, 10); if (d !== null) o.depth = d;
    var n = numOrNull(el.inPasses, 1, 5); if (n !== null) o.passes = n;
    o.tool = parseInt(el.selTool.value, 10) || 1;
    o.mat = parseInt(el.selMat.value, 10) || 0;
    o.blade = el.selBlade.value || 'ratchet';
    if (o.blade !== 'auto') delete o.depth;
    o.return_home = !!el.chkHome.checked;
    return o;
  }

  // ---------------------------------------------------------------------------
  // Documento de Illustrator
  // ---------------------------------------------------------------------------
  var docInfo = null;

  function refreshDoc() {
    return evalScript('cameoGetDocInfo()').then(function (info) {
      if (!info.ok) {
        docInfo = null;
        el.docName.textContent = info.error || 'Sin documento';
        el.selLayer.innerHTML = '';
        el.regmarksInfo.textContent = 'Sin documento.';
        return null;
      }
      docInfo = info;
      el.docName.textContent = info.name + ' — ' + info.artboard.w_mm.toFixed(1) + ' × ' + info.artboard.h_mm.toFixed(1) + ' mm' + (info.hasSelection ? ' — hay selección' : '');
      var prevLayer = el.selLayer.value;
      el.selLayer.innerHTML = '';
      info.layers.forEach(function (name) {
        var o = document.createElement('option');
        o.value = name; o.textContent = name;
        el.selLayer.appendChild(o);
      });
      if (prevLayer && info.layers.indexOf(prevLayer) >= 0) el.selLayer.value = prevLayer;
      if (info.hasRegmarks) {
        el.regmarksInfo.textContent = 'El documento tiene marcas (inset ' + (info.regmarks ? info.regmarks.inset_mm : '?') + ' mm).';
        if (info.regmarks && info.regmarks.inset_mm) el.inInset.value = info.regmarks.inset_mm;
      } else {
        el.regmarksInfo.textContent = 'El documento no tiene marcas.';
      }
      return refreshColors().then(function () { return info; });
    }).catch(function (e) {
      docInfo = null;
      logErr(e.message);
      return null;
    });
  }

  function refreshColors() {
    return evalScript('cameoGetSelectionStrokeColors()').then(function (colors) {
      var prev = el.selColor.value;
      el.selColor.innerHTML = '';
      if (!Array.isArray(colors) || colors.length === 0) {
        var o0 = document.createElement('option');
        o0.value = ''; o0.textContent = '(sin trazos en la selección)';
        el.selColor.appendChild(o0);
      } else {
        colors.forEach(function (c) {
          var o = document.createElement('option');
          o.value = c; o.textContent = c;
          el.selColor.appendChild(o);
        });
        if (prev && colors.indexOf(prev) >= 0) el.selColor.value = prev;
      }
      updateSwatch();
    }).catch(function () {
      el.selColor.innerHTML = '';
    });
  }

  function updateSwatch() {
    var c = el.selColor.value;
    el.colorSwatch.style.background = c && /^#[0-9a-f]{6}$/i.test(c) ? c : 'transparent';
  }

  function currentMode() {
    var r = document.querySelector('input[name="mode"]:checked');
    return r ? r.value : 'selection';
  }

  function onlyStrokeColor() {
    if (!el.chkColor.checked) return null;
    var c = el.selColor.value;
    return c && /^#[0-9a-f]{6}$/i.test(c) ? c : null;
  }

  // ---------------------------------------------------------------------------
  // Export SVG temporal
  // ---------------------------------------------------------------------------
  function tmpSvgPath() {
    var base;
    if (hasNode) {
      base = nodePath.join(nodeOs.tmpdir(), 'cameo-cut');
      try { nodeFs.mkdirSync(base, { recursive: true }); } catch (e) {}
    } else {
      // Fallback sin Node: File de ExtendScript crea la carpeta (outFile.parent.create()).
      var tmp = csInterface.getSystemPath(SystemPath.USER_DATA);
      base = tmp.replace(/\//g, '\\') + '\\cameo-cut';
    }
    var ts = new Date();
    var name = ts.getFullYear() + ('0' + (ts.getMonth() + 1)).slice(-2) + ('0' + ts.getDate()).slice(-2) + '-' +
      ('0' + ts.getHours()).slice(-2) + ('0' + ts.getMinutes()).slice(-2) + ('0' + ts.getSeconds()).slice(-2) + '-' + ts.getMilliseconds();
    return (hasNode ? nodePath.join(base, name + '.svg') : base + '\\' + name + '.svg');
  }

  /** Exporta el SVG y devuelve {path, w_mm, h_mm, items}. La ruta a ExtendScript va con barras normales. */
  function exportSvg() {
    var mode = currentMode();
    var layer = mode === 'layer' ? el.selLayer.value : '';
    if (mode === 'layer' && !layer) return Promise.reject(new Error('Elige una capa'));
    var outPath = tmpSvgPath().replace(/\\/g, '/');
    var script = 'cameoExportCutSVG(' + jsxStr(mode) + ',' + jsxStr(layer) + ',' + jsxStr(outPath) + ')';
    return evalScript(script).then(function (r) {
      if (!r.ok) throw new Error(r.error || 'Error exportando SVG');
      log('SVG exportado (' + r.items + ' ítems, ' + r.w_mm.toFixed(1) + '×' + r.h_mm.toFixed(1) + ' mm)');
      return r;
    });
  }

  function printAndCutPayload(exp) {
    if (!el.chkPnc.checked) {
      return { enabled: false, inset_mm: 0, page_w_mm: exp.w_mm, page_h_mm: exp.h_mm, calib_x_mm: 0, calib_y_mm: 0 };
    }
    return {
      enabled: true,
      inset_mm: parseFloat(el.inInset.value) || 10,
      page_w_mm: (docInfo && docInfo.artboard.w_mm) || exp.w_mm,
      page_h_mm: (docInfo && docInfo.artboard.h_mm) || exp.h_mm,
      calib_x_mm: parseFloat(el.inCalX.value) || 0,
      calib_y_mm: parseFloat(el.inCalY.value) || 0
    };
  }

  // ---------------------------------------------------------------------------
  // Vista previa (dry-run + canvas)
  // ---------------------------------------------------------------------------
  var lastDryRun = null;

  function doPreview() {
    if (!bridgeOnline) { logErr('Bridge no responde'); return; }
    if (!el.selMaterial.value) { logErr('Elige un material'); return; }
    el.btnPreview.disabled = true;
    refreshDoc().then(function (info) {
      if (!info) throw new Error('No hay documento abierto');
      return exportSvg();
    }).then(function (exp) {
      return bridgePost('/dry-run', {
        svg_path: exp.path,
        material_id: el.selMaterial.value,
        overrides: buildOverrides(),
        only_stroke_color: onlyStrokeColor()
      }, 30000);
    }).then(function (res) {
      if (!res.ok) throw new Error(res.error || 'dry-run falló');
      lastDryRun = res;
      drawPreview(res);
      el.previewStats.textContent =
        'Trazos: ' + res.stats.paths + '   Longitud: ' + Number(res.stats.length_mm).toFixed(1) + ' mm\n' +
        'Tamaño: ' + Number(res.width_mm).toFixed(1) + ' × ' + Number(res.height_mm).toFixed(1) + ' mm' +
        '   bbox: [' + res.bbox.map(function (v) { return Number(v).toFixed(1); }).join(', ') + ']';
      logOk('Vista previa OK (' + res.stats.paths + ' trazos)');
    }).catch(function (e) {
      logErr(e.message);
    }).then(function () {
      el.btnPreview.disabled = false;
    });
  }

  // El fondo del canvas es #1c1c1c: un trazo negro de Illustrator (#231f20, muy
  // común) sería invisible. Si el color es oscuro, se dibuja en el color de corte.
  function previewColor(hex) {
    if (!hex || !/^#[0-9a-f]{6}$/i.test(hex)) return '#ff5252';
    var r = parseInt(hex.substr(1, 2), 16), g = parseInt(hex.substr(3, 2), 16), b = parseInt(hex.substr(5, 2), 16);
    var lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    return lum < 90 ? '#ff5252' : hex;
  }

  function drawPreview(res) {
    var canvas = el.canvas;
    var ctx = canvas.getContext('2d');
    var cssW = canvas.clientWidth || 320;
    var dpr = window.devicePixelRatio || 1;
    var W = res.width_mm || 1, H = res.height_mm || 1;
    var cssH = Math.max(120, Math.round(cssW * H / W));
    canvas.width = Math.round(cssW * dpr);
    canvas.height = Math.round(cssH * dpr);
    canvas.style.height = cssH + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    ctx.fillStyle = '#1c1c1c';
    ctx.fillRect(0, 0, cssW, cssH);

    var pad = 6;
    var scale = Math.min((cssW - pad * 2) / W, (cssH - pad * 2) / H);
    var ox = pad + ((cssW - pad * 2) - W * scale) / 2;
    var oy = pad + ((cssH - pad * 2) - H * scale) / 2;

    // Página
    ctx.strokeStyle = '#444';
    ctx.lineWidth = 1;
    ctx.strokeRect(ox, oy, W * scale, H * scale);

    // bbox
    if (res.bbox && res.bbox.length === 4) {
      ctx.strokeStyle = 'rgba(74,144,226,0.6)';
      ctx.setLineDash([3, 3]);
      ctx.strokeRect(ox + res.bbox[0] * scale, oy + res.bbox[1] * scale,
        (res.bbox[2] - res.bbox[0]) * scale, (res.bbox[3] - res.bbox[1]) * scale);
      ctx.setLineDash([]);
    }

    // Polilíneas
    ctx.lineWidth = 1;
    ctx.lineJoin = 'round';
    (res.paths || []).forEach(function (p) {
      var pts = p.points || [];
      if (pts.length < 2) return;
      ctx.strokeStyle = previewColor(p.stroke);
      ctx.beginPath();
      ctx.moveTo(ox + pts[0][0] * scale, oy + pts[0][1] * scale);
      for (var i = 1; i < pts.length; i++) ctx.lineTo(ox + pts[i][0] * scale, oy + pts[i][1] * scale);
      if (p.closed) ctx.closePath();
      ctx.stroke();
    });
  }

  function clearPreview() {
    var ctx = el.canvas.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#1c1c1c';
    ctx.fillRect(0, 0, el.canvas.width, el.canvas.height);
    ctx.fillStyle = '#666';
    ctx.font = '12px "Segoe UI", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('Pulsa "Vista previa"', el.canvas.width / 2, el.canvas.height / 2);
  }

  // ---------------------------------------------------------------------------
  // Cortar / abortar
  // ---------------------------------------------------------------------------
  function doCut() {
    if (!bridgeOnline) { logErr('Bridge no responde'); return; }
    if (!el.selMaterial.value) { logErr('Elige un material'); return; }
    var m = currentMaterial();
    var ov = buildOverrides();
    var modeTxt = { selection: 'la selección', layer: 'la capa "' + el.selLayer.value + '"', all: 'todo el documento' }[currentMode()];
    var msg = '¿Enviar a cortar ' + modeTxt + '?\n\n' +
      'Material: ' + (m ? m.name : el.selMaterial.value) + '\n' +
      'Presión ' + ov.pressure + ' · Velocidad ' + ov.speed + ' · Pasadas ' + ov.passes +
      (ov.depth ? ' · Prof. ' + ov.depth : '') + ' · Herramienta ' + ov.tool + '\n' +
      'Mat: ' + ['Sin mat', '12x12', '12x24'][ov.mat] +
      (el.chkPnc.checked ? '\nPrint & Cut ACTIVO (inset ' + el.inInset.value + ' mm)' : '') +
      (onlyStrokeColor() ? '\nSolo trazos ' + onlyStrokeColor() : '');
    if (!window.confirm(msg)) { log('Corte cancelado por el usuario'); return; }

    el.btnCut.disabled = true;
    refreshDoc().then(function (info) {
      if (!info) throw new Error('No hay documento abierto');
      if (el.chkPnc.checked && !info.hasRegmarks) {
        logErr('Aviso: Print & Cut activo pero el documento no tiene marcas (agrega marcas e imprime antes).');
      }
      return exportSvg();
    }).then(function (exp) {
      return bridgePost('/cut', {
        svg_path: exp.path,
        material_id: el.selMaterial.value,
        overrides: ov,
        only_stroke_color: onlyStrokeColor(),
        print_and_cut: printAndCutPayload(exp)
      }, 30000);
    }).then(function (res) {
      if (!res.ok) throw new Error(res.error || 'El bridge rechazó el trabajo');
      logOk('Trabajo enviado: ' + res.job_id);
      prevJobState = 'running';
      lastJob = { state: 'running', progress: 0, message: 'Enviando…' };
      renderStatus();
      renderJob();
      setTimeout(poll, 500);
    }).catch(function (e) {
      logErr(e.message);
      renderStatus();
    });
  }

  function doAbort() {
    bridgePost('/abort', {}).then(function (r) {
      if (r && r.ok) log('Abort enviado'); else logErr('Abort rechazado');
      setTimeout(poll, 300);
    }).catch(function (e) { logErr(e.message); });
  }

  // ---------------------------------------------------------------------------
  // Regmarks
  // ---------------------------------------------------------------------------
  function addRegmarks() {
    var inset = parseFloat(el.inInset.value) || 10;
    evalScript('cameoAddRegmarks(' + inset + ')').then(function (r) {
      if (!r.ok) throw new Error(r.error);
      logOk('Marcas agregadas (inset ' + inset + ' mm) en capa "Regmarks"');
      return refreshDoc();
    }).catch(function (e) { logErr(e.message); });
  }

  function removeRegmarks() {
    evalScript('cameoRemoveRegmarks()').then(function (r) {
      if (!r.ok) throw new Error(r.error);
      log('Marcas quitadas');
      return refreshDoc();
    }).catch(function (e) { logErr(e.message); });
  }

  // ---------------------------------------------------------------------------
  // Eventos
  // ---------------------------------------------------------------------------
  function bindEvents() {
    el.btnStartBridge.addEventListener('click', startBridge);
    el.btnSaveSettings.addEventListener('click', function () {
      settings = {
        pythonExe: el.cfgPython.value.trim() || DEFAULTS.pythonExe,
        bridgeRoot: el.cfgRoot.value.trim() || DEFAULTS.bridgeRoot,
        bridgeUrl: (el.cfgUrl.value.trim() || DEFAULTS.bridgeUrl).replace(/\/+$/, '')
      };
      saveSettings(settings);
      materialsLoaded = false;
      log('Ajustes guardados');
      poll();
    });

    el.btnRefreshDoc.addEventListener('click', function () {
      refreshDoc().then(function (info) { if (info) log('Documento leído: ' + info.name); });
    });
    document.querySelectorAll('input[name="mode"]').forEach(function (r) {
      r.addEventListener('change', function () {
        if (r.value === 'layer') el.selLayer.focus();
      });
    });
    el.selLayer.addEventListener('focus', function () {
      var r = document.querySelector('input[name="mode"][value="layer"]');
      if (r) r.checked = true;
    });
    el.chkColor.addEventListener('change', function () {
      el.selColor.disabled = !el.chkColor.checked;
      if (el.chkColor.checked) refreshColors();
    });
    el.selColor.addEventListener('change', updateSwatch);

    el.selMaterial.addEventListener('change', applyMaterial);
    el.selBlade.addEventListener('change', updateDepthLabel);

    el.chkPnc.addEventListener('change', function () {
      el.pncBadge.hidden = !el.chkPnc.checked;
      try { localStorage.setItem('cameocut.pnc', el.chkPnc.checked ? '1' : '0'); } catch (e) {}
    });
    el.btnAddRegmarks.addEventListener('click', addRegmarks);
    el.btnRemoveRegmarks.addEventListener('click', removeRegmarks);

    el.btnPreview.addEventListener('click', doPreview);
    el.btnCut.addEventListener('click', doCut);
    el.btnAbort.addEventListener('click', doAbort);

    // Redibujar preview al cambiar tamaño del panel
    window.addEventListener('resize', function () {
      if (lastDryRun) drawPreview(lastDryRun); else clearPreview();
    });

    // Refrescar doc cuando cambia el documento activo en Illustrator
    try {
      csInterface.addEventListener('documentAfterActivate', function () { refreshDoc(); });
    } catch (e) {}
  }

  // ---------------------------------------------------------------------------
  // Init
  // ---------------------------------------------------------------------------
  function init() {
    applyTheme();
    el.cfgPython.value = settings.pythonExe;
    el.cfgRoot.value = settings.bridgeRoot;
    el.cfgUrl.value = settings.bridgeUrl;
    el.nodeStatus.textContent = hasNode ? 'disponible' : 'NO disponible';
    el.selColor.disabled = true;
    try { el.chkPnc.checked = localStorage.getItem('cameocut.pnc') === '1'; } catch (e) {}
    el.pncBadge.hidden = !el.chkPnc.checked;
    el.btnCut.disabled = true;
    clearPreview();
    bindEvents();
    log('Cameo Cut listo. Node ' + (hasNode ? 'OK' : 'no disponible') + '.');
    refreshDoc();
    startPolling();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
