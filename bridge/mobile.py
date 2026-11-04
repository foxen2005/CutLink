"""
Entrada única para la app Android (Chaquopy): abre un SVG, DXF o .studio3 y devuelve lo que
el visor necesita, ya listo para cortar.

    load(ruta, carpeta_cache) -> dict
    load_json(...)            -> el mismo dict como texto JSON (lo que lee Kotlin)

El tipo se detecta por el contenido y no solo por la extensión: los archivos que llegan por
WhatsApp o Drive suelen venir renombrados. No importa nada de Windows (usbprint/gpgl): el
corte lo arma la app con su propio Gpgl.kt.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
from pathlib import Path

from . import studio3
from .dxfpaths import dxf_to_polylines
from .ordering import order_paths
from .svgpaths import Polyline, svg_to_polylines

KINDS = ("svg", "dxf", "studio3")
# un DXF puede empezar con comentarios 999 (LibreCAD, QCAD…) antes del primer SECTION
DXF_START_RE = re.compile(r"(?:^|\n)\s*0\s*\r?\n\s*SECTION\s*\r?\n")


def detect_kind(data: bytes, name: str = "") -> str:
    head = data[:65536]
    if head.startswith(studio3.MAGIC):
        return "studio3"
    if head.startswith(b"AutoCAD Binary DXF"):
        return "dxf"
    if re.search(rb"<svg[\s>]", head, re.IGNORECASE):
        return "svg"
    if DXF_START_RE.search(head.decode("utf-8", "ignore")):
        return "dxf"
    ext = Path(name).suffix.lower().lstrip(".")
    if ext in KINDS:
        return ext
    raise ValueError("Formato no reconocido: abre un SVG, DXF o .studio3")


def _length(p: Polyline) -> float:
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(p.points, p.points[1:]))


def _image_dir(path: str, cache: str | os.PathLike | None) -> Path:
    """Una subcarpeta por archivo (ruta + tamaño + fecha): dos .studio3 con imágenes del mismo
    nombre no se pisan, y reabrir el mismo archivo reutiliza lo ya extraído."""
    if not cache:
        return Path(tempfile.mkdtemp(prefix="cameo_"))
    st = os.stat(path)
    key = hashlib.sha1(f"{os.path.abspath(path)}|{st.st_size}|{st.st_mtime_ns}".encode()).hexdigest()[:16]
    return Path(cache) / key


def load(path: str | os.PathLike, cache_dir: str | os.PathLike | None = None,
         tolerance_mm: float = 0.1) -> dict:
    """cache_dir: dónde dejar las imágenes de un .studio3 (la app pasa su caché)."""
    path = os.fspath(path)
    with open(path, "rb") as fh:
        data = fh.read()                     # se lee una sola vez
    kind = detect_kind(data, path)
    warnings: list[str] = []
    images: list[dict] = []
    if kind == "svg":
        geom = svg_to_polylines(data, tolerance_mm)
        page = (geom.width_mm, geom.height_mm)
        paths = geom.paths
        if re.search(rb"<image[\s>]", data, re.IGNORECASE):
            warnings.append("El SVG trae imágenes: por ahora solo se muestran las líneas de corte")
    elif kind == "dxf":
        geom, warnings = dxf_to_polylines(data, tolerance_mm)
        page = (geom.width_mm, geom.height_mm)
        paths = geom.paths
    else:
        doc = studio3.parse(path)
        # sin el marco de página: en Illustrator alinea, aquí no es un corte
        geom = svg_to_polylines(studio3.to_svg(doc, page_frame=False).encode("utf-8"), tolerance_mm)
        page = doc.page_mm
        paths = geom.paths
        images = [{"file": im["file"], "name": im["name"], "matrix": im["matrix"]}
                  for im in studio3.write_images(doc, _image_dir(path, cache_dir))]
        studio3.check_page(doc)
        warnings = list(doc.warnings)

    ordered = order_paths(paths)
    xs = [x for p in ordered for x, _ in p.points]
    ys = [y for p in ordered for _, y in p.points]
    bbox = [min(xs), min(ys), max(xs), max(ys)] if xs else [0.0, 0.0, 0.0, 0.0]
    if not ordered and kind != "dxf":         # el lector DXF ya avisa
        warnings.append("No hay trazados para cortar")

    def group_of(p: Polyline) -> str | None:
        # Grupo = se selecciona junto en el editor. En SVG el <g> de primer nivel es la CAPA de
        # Illustrator (Capa_1): si contara, tocar una forma seleccionaría todo el dibujo.
        if kind == "svg":
            return p.group_ids[-1] if len(p.group_ids) >= 2 else None
        return p.group_ids[-1] if p.group_ids else None    # DXF: una inserción de bloque

    return {
        "ok": True,
        "kind": kind,
        "name": os.path.basename(path),
        "page_mm": [round(page[0], 3), round(page[1], 3)],
        "bbox_mm": [round(v, 3) for v in bbox],
        "paths": [{
            "pts": [[round(x, 3), round(y, 3)] for x, y in p.points],
            "closed": p.closed,
            "group": group_of(p),
        } for p in ordered],
        "images": images,
        "warnings": warnings,
        "stats": {"paths": len(ordered), "points": sum(len(p.points) for p in ordered),
                  "length_mm": round(sum(_length(p) for p in ordered), 1)},
    }


def presets() -> dict:
    """Materiales y cuchillas: los mismos JSON que usa el panel de Illustrator. Si junto a este
    módulo hay un user_presets.json (los propios del PC, copiados al compilar la app), sus
    materiales van primero y marcados como propios, como en el panel."""
    base = Path(__file__).parent
    materials = json.loads((base / "materials.json").read_text(encoding="utf-8"))
    blades = json.loads((base / "blades.json").read_text(encoding="utf-8"))
    user_file = base / "user_presets.json"
    if user_file.exists():
        user = json.loads(user_file.read_text(encoding="utf-8"))

        def merge(builtin, own):
            # como presets._merge del panel: el propio reemplaza al de fábrica con el mismo id
            out = {b["id"]: dict(b) for b in builtin}
            for u in own:
                out[u["id"]] = dict(u, own=True)
            return list(out.values())

        materials = merge(materials, user.get("materials", []))
        blades = merge(blades, user.get("blades", []))
        # los materiales propios primero en la lista, como en el panel
        materials.sort(key=lambda m: not m.get("own"))
    return {"materials": materials, "blades": blades}


def presets_json() -> str:
    return json.dumps(presets(), ensure_ascii=False)


def load_json(path: str, cache_dir: str | None = None, tolerance_mm: float = 0.1) -> str:
    """Para Kotlin: nunca lanza; los errores vuelven como {"ok": false, "error": ...}."""
    try:
        return json.dumps(load(path, cache_dir, tolerance_mm), ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001 — el mensaje va a la pantalla
        return json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, ensure_ascii=False)
