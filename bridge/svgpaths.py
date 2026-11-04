"""Conversión de SVG (exportado por Illustrator) a polilíneas en milímetros.

Usa svgelements para parsear, resolver transformaciones (reify) y estilos
heredados. Aquí sólo se aplana la geometría (Bézier/arcos → segmentos) con
subdivisión adaptativa y se normalizan colores/unidades.

Convención de salida: mm, origen en la esquina superior izquierda del
viewBox, Y hacia abajo (igual que el SVG).
"""

from __future__ import annotations

import importlib.util
import io
import math
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Iterator

# svgelements hace `import numpy` dentro de CADA punto que evalúa (CubicBezier.npoint, Arc…).
# Sin numpy —la app Android— cada intento fallido recorre todo el path de importación (y Python
# no recuerda los fallos): 4.500 puntos de un .studio3 = 5,4 s en la tablet. Marcarlo como
# ausente hace que el ImportError sea inmediato. Donde numpy existe (el PC) no cambia nada.
if "numpy" not in sys.modules and importlib.util.find_spec("numpy") is None:
    sys.modules["numpy"] = None

from svgelements import (  # noqa: E402
    SVG,
    Arc,
    Close,
    Color,
    CubicBezier,
    Group,
    Line,
    Matrix,
    Move,
    Path,
    QuadraticBezier,
    Shape,
    Text,
    Use,
)

# Illustrator exporta a 72 ppi: 1 unidad (px/pt) = 25.4/72 mm.
PPI = 72
PX_TO_MM = 25.4 / PPI

# Puntos consecutivos más cercanos que esto se consideran duplicados.
_DUP_EPS_MM = 0.001
# Distancia máxima entre primer y último punto para considerar el subpath cerrado.
_CLOSE_EPS_MM = 0.01
# Profundidad máxima de la subdivisión recursiva (2**16 segmentos por curva).
_MAX_DEPTH = 16


@dataclass
class Polyline:
    points: list[tuple[float, float]]  # mm, origen esquina superior izquierda, Y hacia abajo
    closed: bool
    stroke: str | None  # "#rrggbb" en minúsculas o None
    fill: str | None  # idem
    id: str | None  # atributo id del elemento
    group_ids: list[str] = field(default_factory=list)  # ids de los <g> ancestros, externo → interno


@dataclass
class SvgGeometry:
    width_mm: float
    height_mm: float
    paths: list[Polyline]
    bbox: tuple[float, float, float, float]  # (minx, miny, maxx, maxy) en mm; (0,0,0,0) si no hay


# ---------------------------------------------------------------------------
# API pública
# ---------------------------------------------------------------------------


def svg_to_polylines(source: str | bytes | os.PathLike, tolerance_mm: float = 0.1) -> SvgGeometry:
    """Parsea un SVG (markup, bytes o ruta) y devuelve sus polilíneas en mm."""
    if tolerance_mm <= 0:
        raise ValueError("tolerance_mm debe ser > 0")

    svg = SVG.parse(_preprocess(_as_stream(source)), ppi=PPI, reify=True)
    width_mm, height_mm = _page_size_mm(svg)

    paths: list[Polyline] = []
    for shape, group_ids in _walk(svg, []):
        paths.extend(_shape_to_polylines(shape, group_ids, tolerance_mm))

    return SvgGeometry(width_mm=width_mm, height_mm=height_mm, paths=paths, bbox=_bbox(paths))


def to_dict(geom: SvgGeometry) -> dict:
    """Serializa a un dict apto para JSON (points como listas [x, y])."""
    return {
        "width_mm": geom.width_mm,
        "height_mm": geom.height_mm,
        "bbox": list(geom.bbox),
        "paths": [
            {
                "points": [[x, y] for x, y in p.points],
                "closed": p.closed,
                "stroke": p.stroke,
                "fill": p.fill,
                "id": p.id,
                "group_ids": list(p.group_ids),
            }
            for p in geom.paths
        ],
    }


def total_length_mm(geom: SvgGeometry) -> float:
    """Suma de la longitud de todas las polilíneas (mm)."""
    total = 0.0
    for p in geom.paths:
        pts = p.points
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            total += math.hypot(x1 - x0, y1 - y0)
    return total


# ---------------------------------------------------------------------------
# Entrada y unidades
# ---------------------------------------------------------------------------


def _as_stream(source: str | bytes | os.PathLike):
    """Acepta markup SVG (str/bytes) o una ruta de archivo."""
    if isinstance(source, bytes):
        return io.BytesIO(source)
    if isinstance(source, os.PathLike):
        return os.fspath(source)
    if isinstance(source, str):
        # Markup inline si empieza con '<'; en otro caso, ruta de archivo.
        if source.lstrip().startswith("<"):
            return io.StringIO(source)
        return source
    raise TypeError(f"source no soportado: {type(source).__name__}")


_SVG_NS = "http://www.w3.org/2000/svg"
_XLINK_NS = "http://www.w3.org/1999/xlink"
# Contenedores que svgelements NO omite (aplana sus hijos como hermanos);
# se mueven a <defs>, que sí omite, sin romper la resolución de <use>.
_HIDE_TAGS = {"mask", "symbol", "marker", "pattern"}


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def _preprocess(stream):
    """Mueve mask/symbol/marker/pattern sueltos a un <defs>.

    svgelements omite <defs> y <clipPath>, pero aplana otros contenedores y
    dejaría pasar su geometría. Si el XML no se puede parsear o no hace
    falta tocarlo, se devuelve la entrada intacta.
    """
    if not isinstance(stream, (io.StringIO, io.BytesIO)):
        try:
            with open(stream, "rb") as fh:
                stream = io.BytesIO(fh.read())
        except OSError:
            return stream
    raw = stream.getvalue()
    text = raw.decode("utf-8", errors="replace") if isinstance(raw, bytes) else raw
    if not any(f"<{t}" in text for t in _HIDE_TAGS):
        return io.StringIO(text)

    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return io.StringIO(text)

    parents = {child: parent for parent in root.iter() for child in parent}
    to_move = []
    for el in root.iter():
        if _local(el.tag) not in _HIDE_TAGS:
            continue
        # Se omiten los que ya cuelgan de <defs>.
        p = parents.get(el)
        inside_defs = False
        while p is not None:
            if _local(p.tag) == "defs":
                inside_defs = True
                break
            p = parents.get(p)
        if not inside_defs:
            to_move.append(el)
    if not to_move:
        return io.StringIO(text)

    defs = ET.SubElement(root, f"{{{_SVG_NS}}}defs" if root.tag.startswith("{") else "defs")
    for el in to_move:
        parents[el].remove(el)
        defs.append(el)

    ET.register_namespace("", _SVG_NS)
    ET.register_namespace("xlink", _XLINK_NS)
    return io.StringIO(ET.tostring(root, encoding="unicode"))


def _page_size_mm(svg: SVG) -> tuple[float, float]:
    """Tamaño del documento en mm.

    svgelements (ppi=72) ya convierte width/height con unidad explícita a px y,
    si no hay width/height, toma el viewBox como px. En ambos casos basta
    multiplicar por 25.4/72.
    """
    w = _finite(getattr(svg, "width", None))
    h = _finite(getattr(svg, "height", None))
    vb = getattr(svg, "viewbox", None)
    if (w is None or h is None) and vb is not None:
        w = w if w is not None else _finite(getattr(vb, "width", None))
        h = h if h is not None else _finite(getattr(vb, "height", None))
    return (w or 0.0) * PX_TO_MM, (h or 0.0) * PX_TO_MM


def _finite(v) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) and f > 0 else None


# ---------------------------------------------------------------------------
# Recorrido del árbol
# ---------------------------------------------------------------------------


def _walk(node, group_ids: list[str]) -> Iterator[tuple[Shape, list[str]]]:
    """Recorre el árbol en orden de documento, llevando los ids de <g> ancestros.

    svgelements ya descarta <defs>, <clipPath>, <mask>, <symbol> no usados y
    display:none al parsear; aquí se filtra visibility:hidden y <text>.
    """
    if _hidden(node):
        return
    if isinstance(node, Text):
        return
    if isinstance(node, Group):
        # El id del <svg> raíz no es una capa; los <g> sí (Illustrator: <g id="Capa_1">).
        ids = group_ids
        if not isinstance(node, SVG):
            gid = getattr(node, "id", None)
            if gid:
                ids = group_ids + [gid]
        for child in node:
            yield from _walk(child, ids)
        return
    if isinstance(node, Use):
        # <use> ya trae sus hijos resueltos y transformados; no aporta id de capa.
        for child in node:
            yield from _walk(child, group_ids)
        return
    if isinstance(node, Shape):
        yield node, group_ids


def _hidden(node) -> bool:
    values = getattr(node, "values", None) or {}
    if str(values.get("display", "")).strip().lower() == "none":
        return True
    if str(values.get("visibility", "")).strip().lower() in ("hidden", "collapse"):
        return True
    return False


# ---------------------------------------------------------------------------
# Aplanado
# ---------------------------------------------------------------------------


def _shape_to_polylines(shape: Shape, group_ids: list[str], tol: float) -> list[Polyline]:
    """Convierte una forma en una o más polilíneas (una por subpath)."""
    path = Path(shape)
    path.reify()
    # Pasar a mm antes de aplanar, así la tolerancia se aplica en mm.
    path *= Matrix.scale(PX_TO_MM)
    path.reify()

    stroke = _color(getattr(shape, "stroke", None))
    fill = _color(getattr(shape, "fill", None))
    elem_id = getattr(shape, "id", None) or None

    result: list[Polyline] = []
    current: list[tuple[float, float]] = []
    closed = False

    def flush() -> None:
        nonlocal current, closed
        pts = _dedupe(current)
        if len(pts) >= 2:
            result.append(_finish(pts, closed, stroke, fill, elem_id, group_ids))
        current = []
        closed = False

    for seg in path:
        if isinstance(seg, Move):
            flush()
            if seg.end is not None:
                current.append(_pt(seg.end))
            continue
        if isinstance(seg, Close):
            if seg.end is not None:
                current.append(_pt(seg.end))
            closed = True
            flush()
            continue
        if isinstance(seg, Line):
            if not current and seg.start is not None:
                current.append(_pt(seg.start))
            current.append(_pt(seg.end))
            continue
        if isinstance(seg, (CubicBezier, QuadraticBezier, Arc)):
            if not current and seg.start is not None:
                current.append(_pt(seg.start))
            current.extend(_flatten(seg, tol))
            continue
        # Segmento desconocido: se ignora.
    flush()
    return result


def _flatten(seg, tol: float) -> list[tuple[float, float]]:
    """Subdivisión adaptativa por error de cuerda (sin el punto inicial)."""
    out: list[tuple[float, float]] = []
    p0 = _pt(seg.point(0.0))
    p1 = _pt(seg.point(1.0))
    _subdivide(seg, 0.0, 1.0, p0, p1, tol, 0, out)
    out.append(p1)
    return out


def _subdivide(seg, t0, t1, p0, p1, tol, depth, out) -> None:
    """Divide [t0, t1] mientras algún punto interior se aleje más de tol de la cuerda."""
    tm = (t0 + t1) / 2
    pm = _pt(seg.point(tm))
    if depth < _MAX_DEPTH:
        # Se evalúan tres puntos interiores para no perder curvas en S simétricas.
        pa = _pt(seg.point(t0 + (t1 - t0) * 0.25))
        pb = _pt(seg.point(t0 + (t1 - t0) * 0.75))
        err = max(_dist_to_segment(q, p0, p1) for q in (pa, pm, pb))
        if err > tol:
            _subdivide(seg, t0, tm, p0, pm, tol, depth + 1, out)
            out.append(pm)
            _subdivide(seg, tm, t1, pm, p1, tol, depth + 1, out)


def _dist_to_segment(q, a, b) -> float:
    """Distancia de q al segmento a-b."""
    ax, ay = a
    bx, by = b
    qx, qy = q
    dx, dy = bx - ax, by - ay
    d2 = dx * dx + dy * dy
    if d2 == 0.0:
        return math.hypot(qx - ax, qy - ay)
    t = max(0.0, min(1.0, ((qx - ax) * dx + (qy - ay) * dy) / d2))
    return math.hypot(qx - (ax + t * dx), qy - (ay + t * dy))


def _pt(p) -> tuple[float, float]:
    return (float(p.x), float(p.y))


def _dedupe(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """Elimina puntos consecutivos a menos de _DUP_EPS_MM."""
    out: list[tuple[float, float]] = []
    for p in points:
        if out and math.hypot(p[0] - out[-1][0], p[1] - out[-1][1]) < _DUP_EPS_MM:
            continue
        out.append(p)
    return out


def _finish(pts, closed, stroke, fill, elem_id, group_ids) -> Polyline:
    """Resuelve el cierre: último punto == primero (exacto) si está cerrado."""
    first, last = pts[0], pts[-1]
    if closed or math.hypot(last[0] - first[0], last[1] - first[1]) <= _CLOSE_EPS_MM:
        closed = True
        if math.hypot(last[0] - first[0], last[1] - first[1]) < _DUP_EPS_MM:
            pts[-1] = first
        else:
            pts.append(first)
    return Polyline(points=pts, closed=closed, stroke=stroke, fill=fill, id=elem_id, group_ids=list(group_ids))


# ---------------------------------------------------------------------------
# Colores y bbox
# ---------------------------------------------------------------------------


def _color(c) -> str | None:
    """Normaliza un Color de svgelements a '#rrggbb' minúsculas; none → None."""
    if c is None:
        return None
    if not isinstance(c, Color):
        try:
            c = Color(str(c))
        except Exception:
            return None
    if c.value is None:
        return None
    try:
        return "#%02x%02x%02x" % (c.red, c.green, c.blue)
    except (TypeError, ValueError):
        return None


def _bbox(paths: list[Polyline]) -> tuple[float, float, float, float]:
    xs = [x for p in paths for x, _ in p.points]
    ys = [y for p in paths for _, y in p.points]
    if not xs:
        return (0.0, 0.0, 0.0, 0.0)
    return (min(xs), min(ys), max(xs), max(ys))
