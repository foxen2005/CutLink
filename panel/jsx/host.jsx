/*
 * Cameo Cut — host.jsx (ExtendScript para Adobe Illustrator)
 *
 * IMPORTANTE: ExtendScript es ES3. Nada de let/const, arrow functions, template
 * strings, JSON nativo, Array.prototype.forEach/map/indexOf, etc.
 * Todas las funciones públicas devuelven un STRING JSON.
 *
 * Funciones públicas (llamadas desde main.js con csInterface.evalScript):
 *   cameoGetDocInfo()
 *   cameoExportCutSVG(mode, layerName, outPath, artboardIndex)
 *   cameoAddRegmarks(insetMm, thickMm, insetBottomMm)
 *   cameoRemoveRegmarks()
 *   cameoGetSelectionStrokeColors()
 *   cameoImportSvg(svgPath, newDoc, outlineText, pageWmm, pageHmm, imagesSpec, embedImages)
 *   cameoPickStudioFile()
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
      var inset = 10, thick = 0.5, insetBottom = null;
      // Se guardan en el note del cuadrado como "inset_mm=10;thick_mm=0.5;inset_bottom_mm=25"
      try {
        for (var k = 0; k < regLayer.pageItems.length; k++) {
          var note = regLayer.pageItems[k].note;
          if (note && note.indexOf("inset_mm=") === 0) {
            var parts = note.split(";");
            for (var q = 0; q < parts.length; q++) {
              var kv = parts[q].split("=");
              if (kv[0] === "inset_mm") inset = parseFloat(kv[1]);
              else if (kv[0] === "thick_mm") thick = parseFloat(kv[1]);
              else if (kv[0] === "inset_bottom_mm") insetBottom = parseFloat(kv[1]);
            }
            break;
          }
        }
      } catch (e2) {}
      regmarks = { inset_mm: inset, thick_mm: thick, inset_bottom_mm: insetBottom };
    }

    // Mesas de trabajo: nombre y tamaño de cada una, para elegir cuál se corta
    var artboards = [];
    for (var n = 0; n < doc.artboards.length; n++) {
      var r = doc.artboards[n].artboardRect;
      artboards.push({
        name: doc.artboards[n].name,
        w_mm: cameoPtToMm(r[2] - r[0]),
        h_mm: cameoPtToMm(r[1] - r[3])
      });
    }

    return cameoJson({
      ok: true,
      name: doc.name,
      artboard: { w_mm: cameoPtToMm(ab.w), h_mm: cameoPtToMm(ab.h) },
      artboards: artboards,
      activeArtboard: doc.artboards.getActiveArtboardIndex(),
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

/** ¿El ítem toca la mesa de trabajo? (geometricBounds: [left, top, right, bottom], Y hacia arriba) */
function cameoItemInArtboard(item, ab) {
  try {
    var b = item.geometricBounds;
    return !(b[2] < ab.left || b[0] > ab.right || b[3] > ab.top || b[1] < ab.bottom);
  } catch (e) {
    return true;   // si no se puede medir, no lo descartamos
  }
}

function cameoExportCutSVG(mode, layerName, outPath, artboardIndex) {
  var tmpDoc = null;
  try {
    if (app.documents.length === 0) return cameoError("No hay documento abierto");
    var srcDoc = app.activeDocument;
    // Mesa de trabajo elegida en el panel (por defecto, la activa)
    artboardIndex = parseInt(artboardIndex, 10);
    if (!isNaN(artboardIndex) && artboardIndex >= 0 && artboardIndex < srcDoc.artboards.length) {
      srcDoc.artboards.setActiveArtboardIndex(artboardIndex);
    }
    var ab = cameoActiveArtboardRect(srcDoc);

    var items = cameoCollectItems(srcDoc, mode, layerName);
    // Capa / documento completo: solo lo que está sobre esa mesa de trabajo
    if (mode !== "selection") {
      var onBoard = [];
      for (var f = 0; f < items.length; f++) {
        if (cameoItemInArtboard(items[f], ab)) onBoard.push(items[f]);
      }
      if (onBoard.length === 0) throw new Error("No hay ítems sobre la mesa de trabajo elegida");
      items = onBoard;
    }
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

function cameoAddRegmarks(insetMm, thickMm, insetBottomMm) {
  try {
    if (app.documents.length === 0) return cameoError("No hay documento abierto");
    var doc = app.activeDocument;
    insetMm = parseFloat(insetMm);
    if (isNaN(insetMm) || insetMm <= 0) insetMm = 10;
    thickMm = parseFloat(thickMm);
    if (isNaN(thickMm) || thickMm <= 0) thickMm = 0.5;
    // Sin mat la máquina necesita más margen abajo (rodillos): la marca inferior va a 25 mm
    insetBottomMm = parseFloat(insetBottomMm);
    if (isNaN(insetBottomMm) || insetBottomMm <= 0) insetBottomMm = insetMm;

    // Reemplazar capa existente
    var old = cameoFindLayer(doc, CAMEO_REGMARKS_LAYER);
    if (old) { old.locked = false; old.remove(); }

    var ab = cameoActiveArtboardRect(doc);
    var inset = cameoMmToPt(insetMm);
    var insetB = cameoMmToPt(insetBottomMm);
    var sq = cameoMmToPt(5);      // cuadrado 5 mm
    var arm = cameoMmToPt(20);    // brazo L 20 mm
    var thick = cameoMmToPt(thickMm); // grosor de las L (la máquina lo busca con TB53)

    var layer = doc.layers.add();
    layer.name = CAMEO_REGMARKS_LAYER;
    layer.zOrder(ZOrderMethod.BRINGTOFRONT);

    var note = "inset_mm=" + insetMm + ";thick_mm=" + thickMm + ";inset_bottom_mm=" + insetBottomMm;

    // Cuadrado superior izquierdo
    cameoBlackRect(layer, ab.left + inset, ab.top - inset, sq, sq, note);

    // L superior derecha: brazos hacia la izquierda y hacia abajo
    //   horizontal: desde (right - inset - arm) hasta (right - inset), en y = top - inset
    cameoBlackRect(layer, ab.right - inset - arm, ab.top - inset, arm, thick, null);
    //   vertical: x = right - inset - thick, desde top - inset hacia abajo arm
    cameoBlackRect(layer, ab.right - inset - thick, ab.top - inset, thick, arm, null);

    // L inferior izquierda: brazos hacia la derecha y hacia arriba, a insetB del borde inferior
    //   horizontal: desde (left + inset), y = bottom + insetB + thick (top del rect)
    cameoBlackRect(layer, ab.left + inset, ab.bottom + insetB + thick, arm, thick, null);
    //   vertical: x = left + inset, top = bottom + insetB + arm
    cameoBlackRect(layer, ab.left + inset, ab.bottom + insetB + arm, thick, arm, null);

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

// ---------------------------------------------------------------------------
// 6. cameoImportSvg(svgPath, newDoc, outlineText, pageWmm, pageHmm)
// ---------------------------------------------------------------------------
// Coloca el SVG generado por el bridge (bridge/studio3 → SVG en mm, origen arriba-izquierda)
// en Illustrator. Los <image href> relativos se resuelven respecto a la carpeta del SVG.

/** Nombre de archivo sin extensión (para el título del documento nuevo). */
function cameoBaseName(file) {
  var n = String(file.displayName || file.name || "importado");
  try { n = decodeURI(n); } catch (e0) {}
  var dot = n.lastIndexOf(".");
  return dot > 0 ? n.substring(0, dot) : n;
}

/**
 * Busca en el nivel superior del grupo un "marco de página": path sin relleno ni trazo cuyo
 * tamaño coincide con la página (en pt). El bridge puede emitirlo como primer elemento para
 * que los límites del grupo sean exactamente la página (así el offset del contenido se conserva).
 * Devuelve el PathItem o null.
 */
function cameoFindPageFrame(group, wPt, hPt, tol) {
  try {
    for (var i = 0; i < group.pathItems.length; i++) {
      var p = group.pathItems[i];
      if (p.filled || p.stroked) continue;
      var gb = p.geometricBounds; // [left, top, right, bottom]
      var w = gb[2] - gb[0], h = gb[1] - gb[3];
      if (Math.abs(w - wPt) <= tol && Math.abs(h - hPt) <= tol) return p;
    }
  } catch (e) {}
  return null;
}

/** Fuentes usadas por un TextFrame que no están instaladas (Illustrator las sustituye). */
function cameoMissingFonts(tf, out) {
  var name = null;
  try { name = tf.textRange.characterAttributes.textFont.name; } catch (e1) { name = null; }
  if (!name) return;
  var ok = true;
  try { app.textFonts.getByName(name); } catch (e2) { ok = false; }
  if (!ok && cameoIndexOf(out, name) < 0) out.push(name);
}

/**
 * Contornea todos los textFrames de un contenedor (recursivo en grupos).
 * Devuelve el número de textos contorneados; los fallos van a warnings.
 */
function cameoOutlineTextFrames(container, warnings, missing) {
  var count = 0;
  var i;
  // Primero los hijos: createOutline() reemplaza el TextFrame por un GroupItem en el padre,
  // así que se toma una foto de los grupos hijos antes de tocar nada.
  var children = [];
  try { for (i = 0; i < container.groupItems.length; i++) children.push(container.groupItems[i]); } catch (e0) {}
  for (i = 0; i < children.length; i++) count += cameoOutlineTextFrames(children[i], warnings, missing);

  // Luego los textFrames de este nivel, de atrás hacia adelante (la colección es viva).
  var tfs = container.textFrames;
  if (!tfs) return count;
  for (i = tfs.length - 1; i >= 0; i--) {
    var tf = tfs[i];
    cameoMissingFonts(tf, missing);
    var preview = "";
    try { preview = String(tf.contents).substring(0, 20); } catch (e1) {}
    try {
      tf.createOutline();
      count++;
    } catch (e2) {
      warnings.push("No se pudo contornear el texto \"" + preview + "\": " + e2);
    }
  }
  return count;
}

/**
 * Coloca las imágenes del .studio3 con su matriz, simplemente debajo de su línea de corte
 * (sin máscaras ni marcos). `spec` es una línea por imagen, con campos separados por
 * tabulador: ruta \t nombre \t a \t b \t c \t d \t tx \t ty  (ExtendScript no tiene JSON.parse).
 * La matriz lleva el cuadrado unitario [-1,1]² a mm de página (Y hacia abajo); Illustrator
 * usa pt y Y hacia arriba.
 */
function cameoPlaceImages(doc, ab, spec, embed, warnings) {
  var PT = CAMEO_PT_PER_MM;
  var n = 0;
  var lines = String(spec).split("\n");
  for (var i = 0; i < lines.length; i++) {
    if (!lines[i]) continue;
    var col = lines[i].split("\t");
    if (col.length < 8) continue;
    var info = { file: col[0], name: col[1],
                 matrix: [parseFloat(col[2]), parseFloat(col[3]), parseFloat(col[4]),
                          parseFloat(col[5]), parseFloat(col[6]), parseFloat(col[7])] };
    try {
      var f = new File(info.file);
      if (!f.exists) { warnings.push("Imagen no encontrada: " + info.name); continue; }
      var it = doc.placedItems.add();
      it.file = f;
      it.name = info.name;

      // Cuadro actual del PNG colocado, en coordenadas del documento
      var gb = it.geometricBounds;                 // [left, top, right, bottom]
      var w0 = gb[2] - gb[0], h0 = gb[1] - gb[3];
      if (w0 === 0 || h0 === 0) { warnings.push("Imagen vacía: " + info.name); continue; }
      var cx0 = (gb[0] + gb[2]) / 2, cy0 = (gb[1] + gb[3]) / 2;

      var a = info.matrix[0], b = info.matrix[1], c = info.matrix[2];
      var d = info.matrix[3], tx = info.matrix[4], ty = info.matrix[5];

      // Composición de: (píxeles → cuadrado unitario) · (matriz de Studio) · (mm → pt, Y invertida)
      var m = app.getIdentityMatrix();
      m.mValueA = PT * a * 2 / w0;
      m.mValueC = -PT * c * 2 / h0;
      m.mValueB = -PT * b * 2 / w0;
      m.mValueD = PT * d * 2 / h0;
      m.mValueTX = ab.left + PT * (tx - a * 2 * cx0 / w0 + c * 2 * cy0 / h0);
      m.mValueTY = ab.top - PT * (ty - b * 2 * cx0 / w0 + d * 2 * cy0 / h0);
      it.transform(m, true, true, true, true, 1, Transformation.DOCUMENTORIGIN);

      if (embed) {
        try { it.embed(); } catch (eEmb) { warnings.push("No se pudo incrustar " + info.name + ": " + eEmb); }
      }
      n++;
    } catch (e) {
      warnings.push("Imagen " + (info && info.name) + ": " + e);
    }
  }
  return n;
}

function cameoImportSvg(svgPath, newDoc, outlineText, pageWmm, pageHmm, imagesSpec, embedImages) {
  var doc = null;
  var created = false;
  var tmpDoc = null;
  var group = null;
  try {
    var svgFile = new File(svgPath);
    if (!svgFile.exists) return cameoError("No existe el SVG: " + svgPath);
    var warnings = [];
    newDoc = (newDoc === true || newDoc === "true");
    outlineText = (outlineText === true || outlineText === "true");
    pageWmm = parseFloat(pageWmm);
    pageHmm = parseFloat(pageHmm);
    var hasPage = !isNaN(pageWmm) && pageWmm > 0 && !isNaN(pageHmm) && pageHmm > 0;

    // --- Documento destino ---
    if (newDoc) {
      if (!hasPage) return cameoError("Tamaño de página inválido para el documento nuevo (" + pageWmm + " × " + pageHmm + " mm)");
      var preset = new DocumentPreset();
      preset.width = cameoMmToPt(pageWmm);
      preset.height = cameoMmToPt(pageHmm);
      preset.colorMode = DocumentColorSpace.RGB;
      preset.units = RulerUnits.Millimeters;
      preset.title = cameoBaseName(svgFile);
      doc = app.documents.addDocument(DocumentColorSpace.RGB, preset);
      created = true;
    } else {
      if (app.documents.length === 0) return cameoError("No hay documento abierto: abre uno o marca \"Nuevo documento\"");
      doc = app.activeDocument;
    }
    app.activeDocument = doc;
    var ab = cameoActiveArtboardRect(doc);

    // --- Colocar el SVG ---
    var placeErr = null;
    try {
      group = doc.groupItems.createFromFile(svgFile);
    } catch (e1) {
      group = null;
      placeErr = e1;
    }
    if (!group) {
      // Alternativa: abrir el SVG como documento y duplicar sus ítems de nivel superior
      warnings.push("createFromFile falló (" + placeErr + "); se usó app.open()");
      tmpDoc = app.open(svgFile);
      var target = doc.groupItems.add();
      for (var L = 0; L < tmpDoc.layers.length; L++) {
        var ly = tmpDoc.layers[L];
        for (var k = 0; k < ly.pageItems.length; k++) {
          ly.pageItems[k].duplicate(target, ElementPlacement.PLACEATEND);
        }
      }
      tmpDoc.close(SaveOptions.DONOTSAVECHANGES);
      tmpDoc = null;
      app.activeDocument = doc;
      if (target.pageItems.length === 0) {
        // group sigue en null: el bloque de limpieza cierra el documento nuevo vacío
        target.remove();
        throw new Error("El SVG no contiene ítems importables");
      }
      group = target;
    }
    group.name = cameoBaseName(svgFile);

    // --- Escala: si el SVG llegó sin width/height en mm, Illustrator lee el viewBox como px (=pt)
    // y todo queda 2,83× más chico. Se detecta con el marco de página (si el bridge lo emite).
    var wPt = hasPage ? cameoMmToPt(pageWmm) : 0;
    var hPt = hasPage ? cameoMmToPt(pageHmm) : 0;
    var frame = null;
    if (hasPage) {
      frame = cameoFindPageFrame(group, wPt, hPt, 1.0);
      if (!frame) {
        var small = cameoFindPageFrame(group, pageWmm, pageHmm, 0.5);
        if (small) {
          var pct = CAMEO_PT_PER_MM * 100;
          group.resize(pct, pct, true, true, true, true, pct, Transformation.TOPLEFT);
          warnings.push("El SVG venía sin unidades en mm: se reescaló ×" + cameoRound(CAMEO_PT_PER_MM, 3));
          frame = cameoFindPageFrame(group, wPt, hPt, 1.0);
        }
      }
    }

    // --- Posicionar: esquina superior izquierda del grupo = esquina superior izquierda del artboard ---
    // Si hay marco de página se alinea por él (el contenido puede salirse de la página, como
    // en Studio); si no, por los límites del grupo.
    var gb = frame ? frame.geometricBounds : group.geometricBounds; // [left, top, right, bottom], Y hacia arriba
    group.translate(ab.left - gb[0], ab.top - gb[1]);

    // El marco de página ya cumplió su función: fuera
    if (frame) { try { frame.remove(); } catch (e3) {} }

    // --- Texto a contornos ---
    var outlined = 0;
    if (outlineText) {
      var missing = [];
      outlined = cameoOutlineTextFrames(group, warnings, missing);
      if (missing.length) warnings.push("Fuentes no instaladas (Illustrator sustituyó): " + missing.join(", "));
    }

    // --- Imágenes: se colocan nativamente (el importador de SVG descarta los <image> enlazados) ---
    var placed = 0;
    if (imagesSpec) {
      embedImages = (embedImages === true || embedImages === "true");
      placed = cameoPlaceImages(doc, ab, imagesSpec, embedImages, warnings);
    }

    var items = 0;
    try { items = group.pageItems.length + placed; } catch (e4) { items = placed; }
    if (hasPage && !newDoc && (Math.abs(ab.w - wPt) > 1 || Math.abs(ab.h - hPt) > 1)) {
      warnings.push("La página del .studio3 (" + cameoRound(pageWmm, 1) + " × " + cameoRound(pageHmm, 1) +
        " mm) no coincide con el artboard (" + cameoPtToMm(ab.w) + " × " + cameoPtToMm(ab.h) + " mm)");
    }

    // Dejar el grupo seleccionado: el modo "Selección" del panel lo toma directo
    try { doc.selection = null; group.selected = true; } catch (e5) {}

    return cameoJson({ ok: true, items: items, outlined: outlined, placed: placed,
                       warnings: warnings, newDoc: created });
  } catch (e) {
    try { if (tmpDoc) tmpDoc.close(SaveOptions.DONOTSAVECHANGES); } catch (e8) {}
    // Un documento nuevo que quedó vacío solo estorba; si ya se colocó algo, se conserva
    try { if (created && doc && !group) doc.close(SaveOptions.DONOTSAVECHANGES); } catch (e9) {}
    return cameoError("cameoImportSvg: " + e);
  }
}

// ---------------------------------------------------------------------------
// 7. cameoPickStudioFile — respaldo del diálogo de archivo cuando window.cep.fs no está
// ---------------------------------------------------------------------------
function cameoPickStudioFile() {
  try {
    var f = File.openDialog("Elige un archivo de Silhouette Studio", "*.studio3;*.studio", false);
    if (!f) return cameoJson({ ok: true, path: null });   // cancelado
    return cameoJson({ ok: true, path: f.fsName });
  } catch (e) {
    return cameoError("cameoPickStudioFile: " + e);
  }
}
