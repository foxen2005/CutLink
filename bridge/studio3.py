"""
Lector de archivos Silhouette Studio (.studio3) → SVG + PNGs.

Formato deducido por ingeniería inversa sobre archivos reales (no hay documentación):
- Cabecera "silhouette05;" + tabla de 14 enteros de 64 bits (tamaños de sección). El
  documento vive en la sección 1; las secciones 4/5 son blobs constantes.
- Geometría: listas de puntos ya en coordenadas de página (mm, incluida la transformación
  del grupo): cabecera `01 <n-1> 00 00 00` + n registros {x, y, flag, [in dx dy], flag,
  [out dx dy]}. Así van los trazados, los contornos de cada letra (soldados, "Cut Union")
  y las cajas de corte pop-out de las imágenes.
- Textos: objeto `create_textshape` + un registro "Cut Union" por carácter (fuente y la
  letra de 1 byte antes de su UUID) + caja del bloque (`01 03` + 4 esquinas). Sirven como
  metadato; la forma real son las listas anteriores.
- Imágenes: objeto con el nombre de archivo, cuadrado unitario (−1…1) y 4 esquinas en
  página; los PNG/JPEG viven en un pool al final, precedidos por `nombre_hash`.
- Miniatura PNG embebida (la primera imagen, pequeña).

Uso: python -m bridge.studio3 archivo.studio3 --out carpeta
"""
from __future__ import annotations

import argparse
import json
import re
import struct
from dataclasses import dataclass, field
from pathlib import Path

MAGIC = b"silhouette05;"
CURVE = b"\x01\x21\x00\x00\x00"
LINE = b"\x01\x11\x00\x00\x00"
NUL = b"\x00"
CR = "\r"                                             # separador de línea en los textos de Studio
MATRIX_MARK = bytes([2, 0, 0, 0, 1, 1, 0x39, 0, 0, 0])   # sigue a la matriz (tx,ty,a,b,c,d) de un objeto
QUAD_MARK = bytes([1, 3, 0, 0, 0])                    # lista de 4 esquinas en página (x,y + 2 bytes c/u)
CUTUNION_RE = rb"\x08\x00\x00\x00cutunion"            # registro por carácter de un texto
LIST_RE = rb"\x01([\x01-\xff])\x00\x00\x00"           # cabecera de lista de puntos: 01 <n-1> 00 00 00
PNG_SIG = rb"\x89PNG\r\n\x1a\n"
JPEG_SIG = rb"\xff\xd8\xff[\xdb\xe0\xe1\xe2\xee]"
JPEG_EOI = b"\xff\xd9"
IMAGE_NAME_RE = rb"([\w \-\.\(\)]+\.(?:png|jpe?g))_[0-9a-f]+"   # 'nombre.png_hash' antes de cada imagen
# letra de un registro "Cut Union": cadena de 1 byte (prefijo de longitud 1) seguida de su UUID
LETTER_RE = rb"\x01\x00\x00\x00(.)[\x00-\x20]{0,8}\x20\x00\x00\x00[0-9a-f]{32}"
PAGE_BY_NAME = {           # formatos que Studio guarda por nombre (`constant:<id>`)
    "a4": (210.0, 297.0), "a3": (297.0, 420.0), "letter": (215.9, 279.4),
    "legal": (215.9, 355.6), "12x12": (304.8, 304.8), "12x24": (304.8, 609.6),
    "8x12": (203.2, 304.8), "8_5x11": (215.9, 279.4),
}
KNOWN_PAGES = {(210.0, 297.0), (297.0, 420.0), (215.9, 279.4), (304.8, 304.8), (304.8, 609.6),
               (279.4, 431.8), (152.4, 152.4), (203.2, 254.0), (203.2, 304.8), (210.0, 297.0)}
FONT_RE = rb"\x00\x00\x00([A-Z][A-Za-z0-9 \-]{2,40})\x00"   # nombre de fuente con prefijo de longitud


def _f(b: bytes, k: int) -> float:
    return struct.unpack_from("<f", b, k)[0]


def _ok(v: float, lim: float = 5000.0) -> bool:
    return v == v and -lim <= v <= lim


@dataclass
class PathShape:
    points: list[dict]          # {x,y,in:(dx,dy)|None,out:(dx,dy)|None}
    closed: bool = True
    offset: int = 0


@dataclass
class TextObj:
    text: str
    font: str
    size_pt: float | None
    bbox: tuple[float, float, float, float] | None      # página, mm
    color: str | None = None
    offset: int = 0


@dataclass
class ImageObj:
    name: str
    corners: list[tuple[float, float]]                  # 4 esquinas en página, mm
    data: bytes = field(default=b"", repr=False)
    kind: str = "png"
    offset: int = 0
    matrix: tuple[float, ...] = (1, 0, 0, 1, 0, 0)   # a b c d tx ty: x' = a·u + c·v + tx, y' = b·u + d·v + ty, u,v ∈ [-1,1]


@dataclass
class Studio3Doc:
    path: str
    page_mm: tuple[float, float]
    shapes: list[PathShape]
    texts: list[TextObj]
    images: list[ImageObj]
    warnings: list[str]


# ---------------------------------------------------------------------------
# utilidades de escaneo
# ---------------------------------------------------------------------------
def _page_size(data: bytes) -> tuple[float, float]:
    """Tamaño de página en mm. Studio guarda el formato por nombre (`constant:a4`); solo
    cuando es un tamaño personalizado hay que buscar el par de floats."""
    m = re.search(rb"constant:([a-z0-9_]+)", data)
    if m:
        named = PAGE_BY_NAME.get(m.group(1).decode("ascii"))
        if named:
            return named
    for k in range(0, min(len(data), 0x4000) - 8):
        w, h = struct.unpack_from("<ff", data, k)
        if (round(w, 1), round(h, 1)) in KNOWN_PAGES:
            return (round(w, 2), round(h, 2))
    return (210.0, 297.0)


def _rect_points_after(data: bytes, start: int, end: int, want: int = 4) -> list[tuple[float, float]]:
    """Primeros `want` puntos rectos (01 11) entre start y end con coords de página."""
    pts = []
    k = data.find(LINE, start, end)
    while k >= 0 and len(pts) < want:
        x, y = struct.unpack_from("<ff", data, k + 5)
        if _ok(x) and _ok(y) and not (abs(abs(x) - 1.0) < 1e-6 and abs(abs(y) - 1.0) < 1e-6):
            pts.append((x, y))
        k = data.find(LINE, k + 5, end)
    return pts


def _quad_at(data: bytes, k: int) -> list[tuple[float, float]]:
    """4 esquinas (x,y float32 + 2 bytes de relleno) que siguen a QUAD_MARK."""
    pts = []
    for n in range(4):
        if k + n * 10 + 8 > len(data):
            return []
        x, y = struct.unpack_from("<ff", data, k + n * 10)
        if not (_ok(x, 5000) and _ok(y, 5000)):
            return []
        pts.append((x, y))
    return pts


# ---------------------------------------------------------------------------
# trazados
# ---------------------------------------------------------------------------
def _point_lists(data: bytes, lo: int = 2, hi: int = 2000):
    """Listas de puntos en coordenadas de página: cabecera `01 <n-1> 00 00 00` seguida de n
    registros {x, y, flag, [in dx dy], flag, [out dx dy]} (flag 01 = hay manejador, 00 = no).
    Es el formato con que Studio guarda trazados, contornos de letras y cajas de corte."""
    for m in re.finditer(LIST_RE, data):
        n = m.group(1)[0] + 1
        if not (lo <= n <= hi):
            continue
        k = m.end()
        pts = []
        ok = True
        for _ in range(n):
            if k + 10 > len(data):
                ok = False
                break
            x, y = struct.unpack_from("<ff", data, k)
            k += 8
            if not (_ok(x) and _ok(y)):
                ok = False
                break
            handles = []
            for _h in range(2):
                fl = data[k]
                k += 1
                if fl == 1:
                    if k + 8 > len(data):
                        ok = False
                        break
                    dx, dy = struct.unpack_from("<ff", data, k)
                    k += 8
                    if not (_ok(dx, 500) and _ok(dy, 500)):
                        ok = False
                        break
                    handles.append((dx, dy))
                elif fl == 0:
                    handles.append(None)
                else:
                    ok = False
                    break
            if not ok:
                break
            pts.append({"x": x, "y": y, "in": handles[0], "out": handles[1]})
        if ok and len(pts) == n:
            yield m.start(), k, pts


def _parse_paths(data: bytes, warnings: list[str]) -> list[PathShape]:
    tshapes = [m.start() for m in re.finditer(rb"create_textshape", data)]
    shapes = []
    for off, end, pts in _point_lists(data, lo=2):
        if len(pts) < 2:
            continue
        xs = [p["x"] for p in pts]; ys = [p["y"] for p in pts]
        w, h = max(xs) - min(xs), max(ys) - min(ys)
        if w < 0.05 and h < 0.05:
            continue
        # la caja de un bloque de texto (4 puntos rectos tras create_textshape) no es geometría
        if len(pts) == 4 and not any(p["in"] or p["out"] for p in pts) and any(0 < off - t < 3000 for t in tshapes):
            continue
        closed = len(pts) >= 3
        shapes.append(PathShape(points=pts, closed=closed, offset=off))
    return shapes


# ---------------------------------------------------------------------------
# textos
# ---------------------------------------------------------------------------
def _parse_texts(data: bytes, warnings: list[str]) -> list[TextObj]:
    starts = [m.start() for m in re.finditer(CUTUNION_RE, data)]
    tshapes = [m.start() for m in re.finditer(rb"create_textshape", data)]
    # separadores = registros Cut Union sin letra (abren cada bloque de texto)
    seps = []
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else min(len(data), s + 1200)
        mu = re.search(LETTER_RE, data[s:e], re.S)
        if not mu or mu.group(1) == NUL:
            seps.append(s)
    texts: list[TextObj] = []
    cur: TextObj | None = None
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else min(len(data), s + 1200)
        seg = data[s:e]
        fm = re.search(FONT_RE, seg)
        font = fm.group(1).decode() if fm else ""
        size = None
        if fm:
            # tras la fuente: 7 bytes, float (altura de línea), 1 byte, float tamaño en pt
            try:
                size = _f(seg, fm.end() + 7 + 4 + 1)
                if not (1 <= size <= 2000):
                    size = None
            except struct.error:
                size = None
        mu = re.search(LETTER_RE, seg, re.S)
        ch = mu.group(1).decode("latin-1") if mu else None
        if ch is None or ch == "\x00":
            # separador = nuevo bloque de texto; su caja (4 puntos rectos) está tras el
            # create_textshape que cierra el bloque (antes del siguiente separador)
            nxt_sep = next((t for t in seps if t > s), len(data))
            ts_in_block = [t for t in tshapes if s < t < nxt_sep]
            bbox = None
            if ts_in_block:
                q = data.find(QUAD_MARK, ts_in_block[0], min(len(data), ts_in_block[0] + 3000))
                pts = _quad_at(data, q + 5) if q >= 0 else []
                if len(pts) < 4:   # sin caja de página: usamos la caja local (4 puntos rectos)
                    pts = _rect_points_after(data, ts_in_block[0], min(len(data), ts_in_block[0] + 3000))
                if len(pts) >= 4:
                    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
                    bbox = (min(xs), min(ys), max(xs), max(ys))
                    if bbox[2] - bbox[0] < 1.0 or bbox[3] - bbox[1] < 1.0:
                        bbox = None      # caja degenerada: no es la del texto
            cur = TextObj(text="", font=font, size_pt=size, bbox=bbox, offset=s)
            texts.append(cur)
        else:
            if cur is None:
                cur = TextObj(text="", font=font, size_pt=size, bbox=None, offset=s)
                texts.append(cur)
            cur.text += ch
            if not cur.font and font:
                cur.font = font
            if cur.size_pt is None and size:
                cur.size_pt = size
    return [t for t in texts if t.text.strip()]


# ---------------------------------------------------------------------------
# imágenes
# ---------------------------------------------------------------------------
def _jpeg_end(data: bytes, off: int) -> int:
    """Fin real de un JPEG: salta los segmentos (APPn puede llevar una miniatura con su
    propio FFD9) hasta SOS y busca el EOI a partir de ahí."""
    k = off + 2
    while k + 4 <= len(data) and data[k] == 0xFF:
        marker = data[k + 1]
        if marker == 0xDA:                       # SOS: datos comprimidos hasta EOI
            e = data.find(JPEG_EOI, k + 2)
            return e + 2 if e > 0 else -1
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            k += 2
            continue
        seg = struct.unpack_from(">H", data, k + 2)[0]
        k += 2 + seg
    e = data.find(JPEG_EOI, off)
    return e + 2 if e > 0 else -1


def _image_pool(data: bytes) -> dict[str, tuple[bytes, str, str]]:
    """clave `nombre_hash` → (bytes, kind, nombre) de las imágenes embebidas. La clave incluye
    el hash: dos archivos con el mismo nombre pero distinto contenido no se confunden."""
    pool = {}
    sigs = [(m.start(), "png") for m in re.finditer(PNG_SIG, data)]
    sigs += [(m.start(), "jpeg") for m in re.finditer(JPEG_SIG, data)]
    sigs.sort()
    for off, kind in sigs:
        if kind == "png":
            end = data.find(b"IEND", off)
            end = end + 8 if end > 0 else -1
        else:
            end = _jpeg_end(data, off)
        if end < 0:
            continue
        head = data[max(0, off - 600):off]
        m = list(re.finditer(IMAGE_NAME_RE, head))
        if m:
            key = m[-1].group(0).decode("latin-1")
            name = m[-1].group(1).decode("latin-1")
        else:
            key = name = f"image_{off:x}.{kind}"
        pool.setdefault(key, (data[off:end], kind, name))
    return pool


def _parse_images(data: bytes, pool: dict, warnings: list[str]) -> list[ImageObj]:
    """Objetos imagen: nombre + cuadrado unitario + matriz 3x3 + caja de corte (01 03)."""
    images = []
    seen = set()
    for m in re.finditer(IMAGE_NAME_RE, data):
        o = m.start()
        if o in seen:
            continue
        token = m.group(0).decode("latin-1")
        # el token del registro del objeto y el del pool comparten prefijo pero el hash puede
        # llevar un carácter de más/menos según el byte siguiente: emparejamos por prefijo
        hit = next(((b, k, n) for key, (b, k, n) in pool.items()
                    if key.startswith(token) or token.startswith(key)), None)
        if hit is None:
            continue
        blob, kind, name = hit
        seg_end = min(len(data), o + 4000)
        k = data.find(LINE, o, seg_end)
        if k < 0:
            continue
        x, y = struct.unpack_from("<ff", data, k + 5)
        if not (abs(abs(x) - 1.0) < 1e-6 and abs(abs(y) - 1.0) < 1e-6):
            continue          # no es el registro de objeto (p. ej. el propio pool de bytes)
        mk = data.find(MATRIX_MARK, k, seg_end)
        if mk < 0 or mk - k > 0x400:
            warnings.append(f"Imagen {name}: sin matriz de posición, se omite")
            continue
        tx, ty, a, b, c, d = struct.unpack_from("<6f", data, mk - 24)
        if not all(_ok(v, 5000) for v in (tx, ty, a, b, c, d)):
            continue
        q = data.find(QUAD_MARK, mk, seg_end)
        corners = _quad_at(data, q + 5) if q >= 0 else []
        seen.add(o)
        images.append(ImageObj(name=name, corners=corners, data=blob, kind=kind, offset=o,
                               matrix=(a, b, c, d, tx, ty)))
    images.sort(key=lambda im: im.offset)
    return images


# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------
def parse(path: str) -> Studio3Doc:
    data = Path(path).read_bytes()
    if not data.startswith(MAGIC):
        raise ValueError("No es un archivo Silhouette Studio (.studio3)")
    warnings: list[str] = []
    page = _page_size(data)
    shapes = _parse_paths(data, warnings)
    texts = _parse_texts(data, warnings)
    pool = _image_pool(data)
    # la miniatura es la primera PNG pequeña sin nombre de archivo
    pool = {k: v for k, v in pool.items() if not k.startswith("image_") or len(v[0]) > 400_000}
    images = _parse_images(data, pool, warnings)
    return Studio3Doc(path=path, page_mm=page, shapes=shapes, texts=texts, images=images, warnings=warnings)


def _color_hex(c) -> str:
    return c or "#e00000"


def to_svg(doc: Studio3Doc, out_dir: Path) -> str:
    w, h = doc.page_mm
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
           f'width="{w}mm" height="{h}mm" viewBox="0 0 {w} {h}">',
           # marco de página: el importador lo usa para alinear y luego lo elimina
           f'<rect x="0" y="0" width="{w}" height="{h}" fill="none" stroke="none"/>']
    # Las imágenes NO van en el SVG: el importador de Illustrator no resuelve los <image>
    # enlazados con file:/// y los descarta en silencio. Se escriben como PNG/JPEG aparte y
    # host.jsx las coloca nativamente con la matriz que devuelve convert().
    for s in doc.shapes:
        d = []
        p0 = s.points[0]
        d.append(f"M {p0['x']:.3f} {p0['y']:.3f}")
        for a, b in zip(s.points, s.points[1:] + ([s.points[0]] if s.closed else [])):
            if a["out"] or b["in"]:
                c1 = (a["x"] + (a["out"][0] if a["out"] else 0), a["y"] + (a["out"][1] if a["out"] else 0))
                c2 = (b["x"] + (b["in"][0] if b["in"] else 0), b["y"] + (b["in"][1] if b["in"] else 0))
                d.append(f"C {c1[0]:.3f} {c1[1]:.3f} {c2[0]:.3f} {c2[1]:.3f} {b['x']:.3f} {b['y']:.3f}")
            else:
                d.append(f"L {b['x']:.3f} {b['y']:.3f}")
        if s.closed:
            d.append("Z")
        out.append(f'<path d="{" ".join(d)}" fill="none" stroke="#e00000" stroke-width="0.25"/>')
    # Los textos normalmente ya vienen como contornos (listas de puntos). Si un bloque de
    # texto no tiene ningún trazado dentro de su caja, se emite como <text> aproximado
    # (Illustrator lo regenera con la fuente) y se avisa.
    for t in doc.texts:
        if not t.bbox:
            continue
        x0, y0, x1, y1 = t.bbox
        inside = any(x0 - 1 <= p["x"] <= x1 + 1 and y0 - 1 <= p["y"] <= y1 + 1
                     for sh in doc.shapes for p in sh.points[:1])
        if inside:
            continue
        lines = t.text.split(CR)
        line_h = (y1 - y0) / max(1, len(lines))
        fam = (t.font or "Arial").replace('"', "")
        doc.warnings.append(f"Texto '{t.text[:24]}' sin contornos guardados: se importa como texto ({fam})")
        for i, ln in enumerate(lines):
            ln = ln.replace("&", "&amp;").replace("<", "&lt;")
            out.append(f'<text x="{x0:.3f}" y="{y0 + line_h * (i + 0.85):.3f}" font-family="{fam}" '
                       f'font-size="{line_h * 0.8:.3f}" fill="none" stroke="#e00000" stroke-width="0.2">{ln}</text>')
    out.append("</svg>")
    return "\n".join(out)


def content_bbox(doc: Studio3Doc) -> tuple[float, float, float, float]:
    """Caja de todo el contenido en mm (trazados + imágenes). Puede salirse de la página:
    en Studio el material sobrante se deja fuera de la hoja a propósito."""
    xs: list[float] = []
    ys: list[float] = []
    for s in doc.shapes:
        xs += [p["x"] for p in s.points]
        ys += [p["y"] for p in s.points]
    for im in doc.images:
        a, b, c, d, tx, ty = im.matrix
        for u, v in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            xs.append(a * u + c * v + tx)
            ys.append(b * u + d * v + ty)
    if not xs:
        return (0.0, 0.0, 0.0, 0.0)
    return (min(xs), min(ys), max(xs), max(ys))


def convert(path: str, out_dir: str | None = None) -> dict:
    src = Path(path)
    doc = parse(str(src))
    out = Path(out_dir) if out_dir else src.with_suffix("") .parent / (src.stem + "_import")
    out.mkdir(parents=True, exist_ok=True)
    svg_path = out / (src.stem + ".svg")
    svg_path.write_text(to_svg(doc, out), encoding="utf-8")

    # Imágenes: archivo aparte + matriz; Illustrator las coloca nativamente (host.jsx)
    images = []
    for im in doc.images:
        fname = re.sub(r"[^\w\.\-]", "_", im.name)
        (out / fname).write_bytes(im.data)
        images.append({"file": str((out / fname).resolve()), "name": im.name,
                       "matrix": [round(v, 5) for v in im.matrix]})

    bbox = content_bbox(doc)
    w, h = doc.page_mm
    if bbox[0] < -0.5 or bbox[1] < -0.5 or bbox[2] > w + 0.5 or bbox[3] > h + 0.5:
        doc.warnings.append(
            f"El contenido ({bbox[2] - bbox[0]:.0f} × {bbox[3] - bbox[1]:.0f} mm) se sale de la "
            f"página de Studio ({w:.0f} × {h:.0f} mm)")
    return {"ok": True, "svg_path": str(svg_path), "out_dir": str(out), "page_mm": list(doc.page_mm),
            "content_bbox_mm": [round(v, 3) for v in bbox],
            "stats": {"shapes": len(doc.shapes), "texts": len(doc.texts), "images": len(doc.images)},
            "images": images,
            "texts": [{"text": t.text, "font": t.font, "size_pt": t.size_pt, "bbox": t.bbox} for t in doc.texts],
            "warnings": doc.warnings}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    print(json.dumps(convert(a.file, a.out), ensure_ascii=False, indent=1, default=str))


if __name__ == "__main__":
    main()
