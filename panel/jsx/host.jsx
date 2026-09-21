/*
 * Cameo Cut — host.jsx (ExtendScript para Adobe Illustrator)
 *
 * IMPORTANTE: ExtendScript es ES3. Nada de let/const, arrow functions, template
 * strings, JSON nativo, Array.prototype.forEach/map/indexOf, etc.
 * Todas las funciones públicas devuelven un STRING JSON.
 *
 * Funciones públicas (llamadas desde main.js con csInterface.evalScript):
 *   cameoGetDocInfo()
 *   cameoExportCutSVG(mode, layerName, outPath)
 *   cameoAddRegmarks(insetMm)
 *   cameoRemoveRegmarks()
 *   cameoGetSelectionStrokeColors()
 */

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------
var CAMEO_REGMARKS_LAYER = "Regmarks";
var CAMEO_PT_PER_MM = 72 / 25.4;   // 2.8346...
var CAMEO_MM_PER_PT = 25.4 / 72;   // 0.3527...

// ---------------------------------------------------------------------------
// Mini serializador JSON (ES3)
// ---------------------------------------------------------------------------
function cameoJsonEscapeString(s) {
  s = String(s);
  var out = "";
  for (var i = 0; i < s.length; i++) {
    var c = s.charAt(i);
    var code = s.charCodeAt(i);
    if (c === '"') out += '\\"';
    else if (c === "\\") out += "\\\\";
    else if (c === "\n") out += "\\n";
    else if (c === "\r") out += "\\r";
    else if (c === "\t") out += "\\t";
    else if (code < 0x20) {
      var hex = code.toString(16);
      while (hex.length < 4) hex = "0" + hex;
      out += "\\u" + hex;
    } else out += c;
  }
  return '"' + out + '"';
}

function cameoJson(v) {
  if (v === null || v === undefined) return "null";
  var t = typeof v;
  if (t === "number") return isFinite(v) ? String(v) : "null";
  if (t === "boolean") return v ? "true" : "false";
  if (t === "string") return cameoJsonEscapeString(v);
  if (v instanceof Array) {
    var parts = [];
    for (var i = 0; i < v.length; i++) parts.push(cameoJson(v[i]));
    return "[" + parts.join(",") + "]";
  }
  if (t === "object") {
    var kv = [];
    for (var k in v) {
      if (v.hasOwnProperty(k)) kv.push(cameoJsonEscapeString(k) + ":" + cameoJson(v[k]));
    }
    return "{" + kv.join(",") + "}";
  }
  return "null";
}

function cameoError(msg) {
  return cameoJson({ ok: false, error: String(msg) });
}

// ---------------------------------------------------------------------------
// Helpers genéricos
// ---------------------------------------------------------------------------
function cameoRound(n, dec) {
  var f = Math.pow(10, dec || 3);
  return Math.round(n * f) / f;
}

function cameoPtToMm(pt) { return cameoRound(pt * CAMEO_MM_PER_PT, 3); }
function cameoMmToPt(mm) { return mm * CAMEO_PT_PER_MM; }

function cameoIndexOf(arr, val) {
  for (var i = 0; i < arr.length; i++) if (arr[i] === val) return i;
  return -1;
}

function cameoHex2(n) {
  n = Math.max(0, Math.min(255, Math.round(n)));
  var h = n.toString(16);
  return h.length < 2 ? "0" + h : h;
}

function cameoFindLayer(doc, name) {
  for (var i = 0; i < doc.layers.length; i++) {
    if (doc.layers[i].name === name) return doc.layers[i];
  }
  return null;
}

/** Artboard activo como {left, top, right, bottom, w, h} en pt (coordenadas Illustrator: Y crece hacia arriba). */
function cameoActiveArtboardRect(doc) {
  var ab = doc.artboards[doc.artboards.getActiveArtboardIndex()];
  var r = ab.artboardRect; // [left, top, right, bottom]
  return {
    left: r[0], top: r[1], right: r[2], bottom: r[3],
    w: r[2] - r[0],
    h: r[1] - r[3]
  };
}

/**
 * Convierte un color de Illustrator a "#rrggbb".
 * - RGBColor: directo.
 * - CMYKColor: conversión aproximada naive (sin perfil ICC):
 *     R = 255 * (1-C/100) * (1-K/100), etc.
 * - GrayColor: gris invertido (gray 0 = blanco).
 * - SpotColor: usa color.spot.color recursivamente.
 * - NoColor u otro: null.
 */
function cameoColorToHex(color) {
  if (!color) return null;
  var tn = color.typename;
  if (tn === "RGBColor") {
    return "#" + cameoHex2(color.red) + cameoHex2(color.green) + cameoHex2(color.blue);
  }
  if (tn === "CMYKColor") {
    var c = color.cyan / 100, m = color.magenta / 100, y = color.yellow / 100, k = color.black / 100;
    return "#" + cameoHex2(255 * (1 - c) * (1 - k)) + cameoHex2(255 * (1 - m) * (1 - k)) + cameoHex2(255 * (1 - y) * (1 - k));
  }
  if (tn === "GrayColor") {
    var g = 255 * (1 - color.gray / 100);
    return "#" + cameoHex2(g) + cameoHex2(g) + cameoHex2(g);
  }
  if (tn === "SpotColor") {
    return cameoColorToHex(color.spot.color);
  }
  return null;
}

/** Recorre recursivamente pageItems (grupos, compound paths) y llama fn(item) sobre los path/compound. */
function cameoWalkItems(container, fn) {
  var items = container.pageItems;
  if (!items) return;
  for (var i = 0; i < items.length; i++) {
    var it = items[i];
    var tn = it.typename;
    if (tn === "GroupItem") {
      cameoWalkItems(it, fn);
    } else if (tn === "CompoundPathItem") {
      for (var j = 0; j < it.pathItems.length; j++) fn(it.pathItems[j]);
    } else {
      fn(it);
    }
  }
}

// ---------------------------------------------------------------------------
// 1. cameoGetDocInfo
// ---------------------------------------------------------------------------
function cameoGetDocInfo() {
  try {
    if (app.documents.length === 0) return cameoError("No hay documento abierto");
    var doc = app.activeDocument;
    var ab = cameoActiveArtboardRect(doc);

    var layers = [];
    for (var i = 0; i < doc.layers.length; i++) {
      layers.push(doc.layers[i].name);
    }

    var hasSel = false;
    try { hasSel = doc.selection && doc.selection.length > 0; } catch (e1) { hasSel = false; }

    var regLayer = cameoFindLayer(doc, CAMEO_REGMARKS_LAYER);
    var regmarks = null;
    if (regLayer) {
      var inset = 10, thick = 0.5;
      // Se guardan en el note del cuadrado como "inset_mm=10;thick_mm=0.5"
      try {
        for (var k = 0; k < regLayer.pageItems.length; k++) {
          var note = regLayer.pageItems[k].note;
          if (note && note.indexOf("inset_mm=") === 0) {
            var parts = note.split(";");
            for (var q = 0; q < parts.length; q++) {
              var kv = parts[q].split("=");
              if (kv[0] === "inset_mm") inset = parseFloat(kv[1]);
              else if (kv[0] === "thick_mm") thick = parseFloat(kv[1]);
            }
            break;
          }
        }
      } catch (e2) {}
      regmarks = { inset_mm: inset, thick_mm: thick };
    }

    return cameoJson({
      ok: true,
      name: doc.name,
      artboard: { w_mm: cameoPtToMm(ab.w), h_mm: cameoPtToMm(ab.h) },
      layers: layers,
      hasSelection: !!hasSel,
      hasRegmarks: !!regLayer,
      regmarks: regmarks
    });
  } catch (e) {
    return cameoError("cameoGetDocInfo: " + e);
  }
}

// ---------------------------------------------------------------------------
// 2. cameoExportCutSVG(mode, layerName, outPath)
// ---------------------------------------------------------------------------

/** Recolecta los ítems a exportar según el modo. Devuelve array de PageItem (nivel superior). */
function cameoCollectItems(doc, mode, layerName) {
  var result = [];
  var i;
  if (mode === "selection") {
    var sel = doc.selection;
    if (!sel || sel.length === 0) throw new Error("No hay nada seleccionado");
    for (i = 0; i < sel.length; i++) result.push(sel[i]);
  } else if (mode === "layer") {
    var layer = cameoFindLayer(doc, layerName);
    if (!layer) throw new Error("No existe la capa '" + layerName + "'");
    for (i = 0; i < layer.pageItems.length; i++) {
      var it = layer.pageItems[i];
      if (it.hidden) continue;
      result.push(it);
    }
    // Sub-capas de la capa elegida
    for (var s = 0; s < layer.layers.length; s++) {
      var sub = layer.layers[s];
      if (!sub.visible) continue;
      for (i = 0; i < sub.pageItems.length; i++) {
        if (!sub.pageItems[i].hidden) result.push(sub.pageItems[i]);
      }
    }
  } else { // "all"
    for (var L = 0; L < doc.layers.length; L++) {
      var ly = doc.layers[L];
      if (ly.name === CAMEO_REGMARKS_LAYER) continue;
      if (!ly.visible || ly.locked) continue;
      cameoCollectFromLayerRecursive(ly, result);
    }
  }
  if (result.length === 0) throw new Error("No hay ítems para exportar");
  return result;
}

function cameoCollectFromLayerRecursive(layer, out) {
  var i;
  for (i = 0; i < layer.pageItems.length; i++) {
    var it = layer.pageItems[i];
    if (it.hidden || it.locked) continue;
    out.push(it);
  }
  for (i = 0; i < layer.layers.length; i++) {
    var sub = layer.layers[i];
    if (!sub.visible || sub.locked) continue;
    cameoCollectFromLayerRecursive(sub, out);
  }
}

/** Suelta máscaras de recorte recursivamente en un contenedor (documento o grupo). */
function cameoReleaseClippingMasks(doc) {
  // Estrategia: mientras existan grupos con clipped=true, seleccionarlos y ejecutar "releaseMask".
  var guard = 0;
  while (guard++ < 20) {
    var found = false;
    doc.selection = null;
    for (var i = 0; i < doc.groupItems.length; i++) {
      var g = doc.groupItems[i];
      if (g.clipped) { g.selected = true; found = true; }
    }
    if (!found) break;
    try { app.executeMenuCommand("releaseMask"); } catch (e) { break; }
  }
  doc.selection = null;
}

function cameoExportCutSVG(mode, layerName, outPath) {
  var tmpDoc = null;
  try {
    if (app.documents.length === 0) return cameoError("No hay documento abierto");
    var srcDoc = app.activeDocument;
    var ab = cameoActiveArtboardRect(srcDoc);

    var items = cameoCollectItems(srcDoc, mode, layerName);
    var count = items.length;

    // --- Documento temporal del mismo tamaño que el artboard activo ---
    var preset = new DocumentPreset();
    preset.width = ab.w;
    preset.height = ab.h;
    preset.colorMode = DocumentColorSpace.RGB;
    preset.units = RulerUnits.Millimeters;
    preset.title = "cameo-cut-tmp";
    tmpDoc = app.documents.addDocument(DocumentColorSpace.RGB, preset);

    // Artboard del temporal: normalmente [0, h, w, 0]; lo leemos para no asumir.
    var tab = cameoActiveArtboardRect(tmpDoc);

    // --- Duplicar ítems preservando posición relativa al artboard activo ---
    // Illustrator duplica con el mismo "position" en coordenadas de documento; como
    // los orígenes pueden diferir, ajustamos por la diferencia de artboards.
    var dx = tab.left - ab.left;
    var dy = tab.top - ab.top;
    var newItems = [];
    for (var i = 0; i < count; i++) {
      var src = items[i];
      var pos = src.position; // [x, y] esquina superior izquierda en coords de srcDoc
      var dup = src.duplicate(tmpDoc, ElementPlacement.PLACEATEND);
      dup.position = [pos[0] + dx, pos[1] + dy];
      dup.hidden = false;
      dup.locked = false;
      newItems.push(dup);
    }

    app.activeDocument = tmpDoc;

    // --- Expandir apariencias, contornear texto, soltar máscaras ---
    tmpDoc.selection = null;
    app.executeMenuCommand("selectall");
    try { app.executeMenuCommand("expandStyle"); } catch (e1) {}
    tmpDoc.selection = null;

    // Texto a contornos (repetir hasta que no queden textFrames; createOutline puede anidar)
    var guard = 0;
    while (tmpDoc.textFrames.length > 0 && guard++ < 50) {
      try { tmpDoc.textFrames[0].createOutline(); } catch (e2) { break; }
    }

    // Soltar máscaras de recorte
    cameoReleaseClippingMasks(tmpDoc);

    // --- Exportar SVG ---
    var opts = new ExportOptionsSVG();
    opts.embedRasterImages = false;
    opts.fontType = SVGFontType.OUTLINEFONT;
    opts.cssProperties = SVGCSSPropertyLocation.STYLEATTRIBUTES;
    opts.coordinatePrecision = 4;
    opts.DTD = SVGDTDVersion.SVG1_1;
    opts.preserveEditability = false;
    opts.fontSubsetting = SVGFontSubsetting.None;
    opts.documentEncoding = SVGDocumentEncoding.UTF8;
    // Usar artboard como área de exportación: viewBox = 0 0 W H
    try { opts.saveMultipleArtboards = false; } catch (e3) {}

    var outFile = new File(outPath);
    if (outFile.parent && !outFile.parent.exists) outFile.parent.create();
    tmpDoc.exportFile(outFile, ExportType.SVG, opts);

    var w_mm = cameoPtToMm(ab.w);
    var h_mm = cameoPtToMm(ab.h);

    tmpDoc.close(SaveOptions.DONOTSAVECHANGES);
    tmpDoc = null;
    app.activeDocument = srcDoc;

    return cameoJson({ ok: true, path: outFile.fsName, w_mm: w_mm, h_mm: h_mm, items: count });
  } catch (e) {
    try { if (tmpDoc) tmpDoc.close(SaveOptions.DONOTSAVECHANGES); } catch (e9) {}
    return cameoError("cameoExportCutSVG: " + e);
  }
}

// ---------------------------------------------------------------------------
// 3. cameoAddRegmarks(insetMm)
// ---------------------------------------------------------------------------

/** Dibuja un rectángulo relleno negro sin trazo. (x, y) = esquina superior izquierda en coords de Illustrator. */
function cameoBlackRect(layer, x, y, w, h, note) {
  var r = layer.pathItems.rectangle(y, x, w, h); // rectangle(top, left, width, height)
  var black = new RGBColor();
  black.red = 0; black.green = 0; black.blue = 0;
  r.filled = true;
  r.fillColor = black;
  r.stroked = false;
  if (note) r.note = note;
  return r;
}

function cameoAddRegmarks(insetMm, thickMm) {
  try {
    if (app.documents.length === 0) return cameoError("No hay documento abierto");
    var doc = app.activeDocument;
    insetMm = parseFloat(insetMm);
    if (isNaN(insetMm) || insetMm <= 0) insetMm = 10;
    thickMm = parseFloat(thickMm);
    if (isNaN(thickMm) || thickMm <= 0) thickMm = 0.5;

    // Reemplazar capa existente
    var old = cameoFindLayer(doc, CAMEO_REGMARKS_LAYER);
    if (old) { old.locked = false; old.remove(); }

    var ab = cameoActiveArtboardRect(doc);
    var inset = cameoMmToPt(insetMm);
    var sq = cameoMmToPt(5);      // cuadrado 5 mm
    var arm = cameoMmToPt(20);    // brazo L 20 mm
    var thick = cameoMmToPt(thickMm); // grosor de las L (la máquina lo busca con TB53)

    var layer = doc.layers.add();
    layer.name = CAMEO_REGMARKS_LAYER;
    layer.zOrder(ZOrderMethod.BRINGTOFRONT);

    var note = "inset_mm=" + insetMm + ";thick_mm=" + thickMm;

    // Cuadrado superior izquierdo
    cameoBlackRect(layer, ab.left + inset, ab.top - inset, sq, sq, note);

    // L superior derecha: brazos hacia la izquierda y hacia abajo
    //   horizontal: desde (right - inset - arm) hasta (right - inset), en y = top - inset
    cameoBlackRect(layer, ab.right - inset - arm, ab.top - inset, arm, thick, null);
    //   vertical: x = right - inset - thick, desde top - inset hacia abajo arm
    cameoBlackRect(layer, ab.right - inset - thick, ab.top - inset, thick, arm, null);

    // L inferior izquierda: brazos hacia la derecha y hacia arriba
    //   horizontal: desde (left + inset), y = bottom + inset + thick (top del rect)
    cameoBlackRect(layer, ab.left + inset, ab.bottom + inset + thick, arm, thick, null);
    //   vertical: x = left + inset, top = bottom + inset + arm
    cameoBlackRect(layer, ab.left + inset, ab.bottom + inset + arm, thick, arm, null);

    layer.locked = true;
    return cameoJson({ ok: true });
  } catch (e) {
    return cameoError("cameoAddRegmarks: " + e);
  }
}

// ---------------------------------------------------------------------------
// 4. cameoRemoveRegmarks()
// ---------------------------------------------------------------------------
function cameoRemoveRegmarks() {
  try {
    if (app.documents.length === 0) return cameoError("No hay documento abierto");
    var doc = app.activeDocument;
    var layer = cameoFindLayer(doc, CAMEO_REGMARKS_LAYER);
    if (layer) {
      layer.locked = false;
      layer.remove();
    }
    return cameoJson({ ok: true, removed: !!layer });
  } catch (e) {
    return cameoError("cameoRemoveRegmarks: " + e);
  }
}

// ---------------------------------------------------------------------------
// 5. cameoGetSelectionStrokeColors()
// ---------------------------------------------------------------------------
function cameoGetSelectionStrokeColors() {
  try {
    if (app.documents.length === 0) return "[]";
    var doc = app.activeDocument;
    var sel = doc.selection;
    var colors = [];
    if (!sel || sel.length === 0) return "[]";

    var visit = function (item) {
      try {
        if (item.typename === "PathItem" && item.stroked) {
          var hex = cameoColorToHex(item.strokeColor);
          if (hex && cameoIndexOf(colors, hex) < 0) colors.push(hex);
        } else if (item.typename === "TextFrame") {
          // Trazo del texto (character attributes)
          try {
            var ca = item.textRange.characterAttributes;
            if (ca.strokeColor && ca.strokeColor.typename !== "NoColor") {
              var h2 = cameoColorToHex(ca.strokeColor);
              if (h2 && cameoIndexOf(colors, h2) < 0) colors.push(h2);
            }
          } catch (e1) {}
        }
      } catch (e2) {}
    };

    for (var i = 0; i < sel.length; i++) {
      var it = sel[i];
      if (it.typename === "GroupItem") {
        cameoWalkItems(it, visit);
      } else if (it.typename === "CompoundPathItem") {
        for (var j = 0; j < it.pathItems.length; j++) visit(it.pathItems[j]);
      } else {
        visit(it);
      }
    }
    return cameoJson(colors);
  } catch (e) {
    return "[]";
  }
}
