"""
Lector DXF (texto) -> polilíneas en mm, sin dependencias externas.

Va sin ezdxf a propósito: ezdxf arrastra numpy y fonttools, y este módulo también corre
dentro de la app Android (Chaquopy). ezdxf se usa solo en los tests, para generar DXF reales.

Soporta: LINE, LWPOLYLINE y POLYLINE (con arcos por "bulge"), ARC, CIRCLE, ELLIPSE, SPLINE
(NURBS, también racionales), INSERT de bloques (escala, giro, matrices de filas/columnas,
bloques anidados) y entidades espejadas por OCS (extrusión 0,0,-1). Respeta $INSUNITS.
Ignora con aviso lo que no se puede cortar tal cual (textos, cotas, sombreados…), las capas
apagadas o congeladas y el espacio papel. Las líneas sueltas que se tocan se unen en un solo
trazado (un rectángulo hecho con 4 LINE queda cerrado, así ordering.py sabe qué va dentro).

Salida: misma geometría que svgpaths (mm, origen arriba a la izquierda, Y hacia abajo). El
DXF no tiene página: la página es la caja del dibujo, con el dibujo pegado a (0, 0).
"""
from __future__ import annotations

import math
import os
from collections import Counter

from .svgpaths import Polyline, SvgGeometry, _bbox

# $INSUNITS -> mm (0 = sin unidades)
UNITS_MM = {1: 25.4, 2: 304.8, 3: 1609344.0, 4: 1.0, 5: 10.0, 6: 1000.0, 7: 1e6, 8: 25.4e-6,
            9: 0.0254, 10: 914.4, 11: 1e-7, 12: 1e-6, 13: 1e-3, 14: 100.0, 15: 1e4, 16: 1e5,
            21: 304.8006096, 22: 25.4000508, 23: 914.4018288}
CUTTABLE = {"LINE", "LWPOLYLINE", "POLYLINE", "ARC", "CIRCLE", "ELLIPSE", "SPLINE", "INSERT"}
NOISE = {"VERTEX", "SEQEND", "ATTRIB", "ENDBLK", "BLOCK"}      # partes de otra entidad
JOIN_EPS_MM = 0.01
MAX_BLOCK_DEPTH = 16
MAX_COPIES = 20000          # inserciones totales (matrices enormes o bloques que se repiten sin fin)

Affine = tuple  # (a, b, c, d, e, f): x' = a·x + c·y + e ; y' = b·x + d·y + f
IDENTITY: Affine = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
MIRROR_X: Affine = (-1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def _mul(A: Affine, B: Affine) -> Affine:
    """A∘B (primero B)."""
    return (A[0] * B[0] + A[2] * B[1], A[1] * B[0] + A[3] * B[1],
            A[0] * B[2] + A[2] * B[3], A[1] * B[2] + A[3] * B[3],
            A[0] * B[4] + A[2] * B[5] + A[4], A[1] * B[4] + A[3] * B[5] + A[5])


def _ap(T: Affine, x: float, y: float) -> tuple[float, float]:
    return (T[0] * x + T[2] * y + T[4], T[1] * x + T[3] * y + T[5])


def _scale_of(T: Affine) -> float:
    return math.sqrt(abs(T[0] * T[3] - T[1] * T[2])) or 1.0


class _Ent:
    """Una entidad: tipo + etiquetas (código, valor) en orden."""
    __slots__ = ("type", "tags", "children")

    def __init__(self, etype: str, tags: list[tuple[int, str]]):
        self.type = etype
        self.tags = tags
        self.children: list[_Ent] = []     # VERTEX de una POLYLINE

    def get(self, code: int, default=None):
        for c, v in self.tags:
            if c == code:
                return v
        return default

    def f(self, code: int, default: float = 0.0) -> float:
        v = self.get(code)
        return float(v) if v is not None else default

    def i(self, code: int, default: int = 0) -> int:
        v = self.get(code)
        return int(float(v)) if v is not None else default

    def all(self, code: int) -> list[str]:
        return [v for c, v in self.tags if c == code]

    @property
    def ocs_mirrored(self) -> bool:
        return self.f(230, 1.0) < 0


# ---------------------------------------------------------------------------
# lectura
# ---------------------------------------------------------------------------
def _read_text(source) -> str:
    if isinstance(source, (bytes, bytearray)):
        raw = bytes(source)
    elif isinstance(source, str) and "\n" in source:     # texto DXF (una ruta no tiene saltos de línea)
        return source
    else:
        with open(os.fspath(source), "rb") as fh:
            raw = fh.read()
    if raw.startswith(b"AutoCAD Binary DXF"):
        raise ValueError("DXF binario no soportado: guárdalo como DXF de texto (ASCII)")
    try:
        return raw.decode("utf-8-sig")        # R2007+ siempre es UTF-8
    except UnicodeDecodeError:
        return raw.decode("cp1252", errors="replace")


def _records(text: str) -> list[_Ent]:
    """Cada código 0 abre un registro nuevo. Los valores de texto conservan su contenido."""
    # Solo \n separa líneas en DXF: splitlines() también corta en U+2028, \x0c… dentro de
    # un MTEXT y desfasa todos los pares código/valor del archivo
    lines = text.split("\n")
    recs: list[_Ent] = []
    cur: _Ent | None = None
    for k in range(0, len(lines) - 1, 2):
        try:
            code = int(lines[k].strip())
        except ValueError:
            raise ValueError(f"DXF dañado cerca de la línea {k + 1}") from None
        val = lines[k + 1].strip()
        if code == 0:
            cur = _Ent(val, [])
            recs.append(cur)
        elif cur is not None:
            cur.tags.append((code, val))
    return recs


class _Doc:
    def __init__(self, recs: list[_Ent]):
        self.header: dict[str, str] = {}
        self.hidden_layers: set[str] = set()
        self.blocks: dict[str, tuple[tuple[float, float], list[_Ent]]] = {}
        self.entities: list[_Ent] = []
        section = None
        block_name = None
        block_base = (0.0, 0.0)
        block_ents: list[_Ent] = []
        last_poly: _Ent | None = None
        for r in recs:
            t = r.type
            if t == "SECTION":
                section = r.get(2)
                if section == "HEADER":
                    tags = r.tags
                    for n, (c, v) in enumerate(tags):
                        if c == 9 and n + 1 < len(tags):
                            self.header[v] = tags[n + 1][1]
                continue
            if t == "ENDSEC":
                section = None
                continue
            if section == "TABLES" and t == "LAYER":
                name = r.get(2, "").upper()                 # los nombres DXF no distinguen mayúsculas
                if r.i(62, 7) < 0 or (r.i(70) & 1):        # apagada (color negativo) o congelada
                    self.hidden_layers.add(name)
                continue
            if section == "BLOCKS":
                if t == "BLOCK":
                    block_name, block_base, block_ents = r.get(2, "").upper(), (r.f(10), r.f(20)), []
                    last_poly = None
                    continue
                if t == "ENDBLK":
                    if block_name is not None:
                        self.blocks[block_name] = (block_base, block_ents)
                    block_name = None
                    continue
                target = block_ents
            elif section == "ENTITIES":
                target = self.entities
            else:
                continue
            # VERTEX/SEQEND pertenecen a la POLYLINE anterior
            if t == "VERTEX":
                if last_poly is not None:
                    last_poly.children.append(r)
                continue
            if t == "SEQEND":
                last_poly = None
                continue
            last_poly = r if t == "POLYLINE" else None
            target.append(r)

    def unit_mm(self, warnings: list[str]) -> float:
        u = int(float(self.header.get("$INSUNITS", "0") or 0))
        if u in UNITS_MM:
            return UNITS_MM[u]
        if self.header.get("$MEASUREMENT", "1").strip() == "0":
            warnings.append("El DXF no indica unidades y está en sistema imperial: se asumen pulgadas")
            return 25.4
        warnings.append("El DXF no indica unidades: se asumen milímetros")
        return 1.0


# ---------------------------------------------------------------------------
# geometría
# ---------------------------------------------------------------------------
def _arc_points(cx, cy, r, a0, sweep, tol) -> list[tuple[float, float]]:
    """Arco desde el ángulo a0 (rad) barriendo `sweep` (rad, + = antihorario)."""
    r = abs(r)
    if r <= 0:
        return [(cx, cy)]
    step = 2 * math.acos(max(-1.0, 1.0 - min(tol, r) / r)) if tol < r else math.pi / 2
    n = max(2, min(4000, math.ceil(abs(sweep) / max(step, 1e-6))))
    return [(cx + r * math.cos(a0 + sweep * k / n), cy + r * math.sin(a0 + sweep * k / n)) for k in range(n + 1)]


def _bulge_points(p1, p2, b, tol) -> list[tuple[float, float]]:
    """Segmento p1->p2 con "bulge" (tan(θ/4)): devuelve los puntos sin repetir p1."""
    if abs(b) < 1e-9:
        return [p2]
    dx, dy = p2[0] - p1[0], p2[1] - p1[1]
    c = math.hypot(dx, dy)
    if c < 1e-12:
        return [p2]
    d = c / 2
    s = b * d                                  # sagita con signo
    r = (d * d + s * s) / (2 * s)
    nx, ny = -dy / c, dx / c                   # normal izquierda
    mx, my = (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2
    cx, cy = mx + (r - s) * nx, my + (r - s) * ny
    theta = 4 * math.atan(b)
    a0 = math.atan2(p1[1] - cy, p1[0] - cx)
    pts = _arc_points(cx, cy, r, a0, theta, tol)
    pts[-1] = p2                               # exacto al vértice
    return pts[1:]


def _vertex_path(verts: list[tuple[float, float, float]], closed: bool, tol: float) -> list[tuple[float, float]]:
    """Vértices (x, y, bulge) -> puntos; el bulge de cada vértice es el del tramo que sale de él."""
    if not verts:
        return []
    pts = [verts[0][:2]]
    n = len(verts)
    for k in range(n if closed else n - 1):
        a, b = verts[k], verts[(k + 1) % n]
        pts.extend(_bulge_points(a[:2], b[:2], a[2], tol))
    return pts


def _nurbs_points(deg, knots, ctrl, weights, tol) -> list[tuple[float, float]]:
    """Muestreo adaptativo de una NURBS con de Boor en coordenadas homogéneas."""
    n = len(ctrl)
    if n < 2 or len(knots) != n + deg + 1:
        return list(ctrl)
    w = weights if len(weights) == n else [1.0] * n
    hom = [(x * wi, y * wi, wi) for (x, y), wi in zip(ctrl, w)]
    t0, t1 = knots[deg], knots[n]

    def ev(t: float) -> tuple[float, float]:
        k = deg
        while k < n - 1 and knots[k + 1] <= t:
            k += 1
        d = [list(hom[j + k - deg]) for j in range(deg + 1)]
        for r in range(1, deg + 1):
            for j in range(deg, r - 1, -1):
                i = j + k - deg
                den = knots[i + deg + 1 - r] - knots[i]
                a = (t - knots[i]) / den if den else 0.0
                d[j] = [(1 - a) * d[j - 1][q] + a * d[j][q] for q in range(3)]
        X, Y, W = d[deg]
        return (X / W, Y / W) if W else (X, Y)

    def sub(ta, pa, tb, pb, depth, out):
        tm = (ta + tb) / 2
        pm = ev(tm)
        ex, ey = pb[0] - pa[0], pb[1] - pa[1]
        L = math.hypot(ex, ey)
        dist = abs((pm[0] - pa[0]) * ey - (pm[1] - pa[1]) * ex) / L if L > 1e-12 else math.hypot(pm[0] - pa[0], pm[1] - pa[1])
        if depth < 10 and dist > tol:
            sub(ta, pa, tm, pm, depth + 1, out)
            sub(tm, pm, tb, pb, depth + 1, out)
        else:
            out.append(pb)

    seeds = max(8, n * 4)
    ts = [t0 + (t1 - t0) * k / seeds for k in range(seeds + 1)]
    pts = [ev(ts[0])]
    for a, b in zip(ts, ts[1:]):
        sub(a, ev(a), b, ev(b), 0, pts)
    return pts


class _Flattener:
    def __init__(self, doc: _Doc, tol_units: float, warnings: list[str]):
        self.doc = doc
        self.tol = tol_units
        self.warnings = warnings
        self.out: list[tuple[list[tuple[float, float]], bool, str | None]] = []   # (puntos, cerrado, grupo)
        self.skipped: Counter = Counter()
        self.hidden = 0
        self.inserts = 0
        self.stack: list[str] = []          # bloques en curso: detecta bloques que se insertan a sí mismos
        self.notes: set[str] = set()        # avisos de bloques, una sola vez cada uno

    def note(self, msg: str):
        if msg not in self.notes:
            self.notes.add(msg)
            self.warnings.append(msg)

    def run(self, ents: list[_Ent], T: Affine = IDENTITY, group: str | None = None, depth: int = 0):
        for e in ents:
            if e.type in NOISE:
                continue
            if depth == 0 and e.i(67) == 1:          # espacio papel
                continue
            if e.i(60) == 1:                         # invisible (p. ej. estados de un bloque dinámico)
                self.hidden += 1
                continue
            if e.get(8, "0").upper() in self.doc.hidden_layers:
                self.hidden += 1
                continue
            if e.type not in CUTTABLE:
                self.skipped[e.type] += 1
                continue
            try:
                self._entity(e, T, group, depth)
            except (ValueError, ZeroDivisionError, IndexError) as exc:
                self.warnings.append(f"{e.type} ilegible, se omite ({exc})")

    def _emit(self, pts, closed, T, group):
        if len(pts) < 2:
            return
        self.out.append(([_ap(T, x, y) for x, y in pts], closed, group))

    def _entity(self, e: _Ent, T: Affine, group, depth):
        tol = self.tol / _scale_of(T)
        t = e.type
        To = _mul(T, MIRROR_X) if e.ocs_mirrored and t in ("ARC", "CIRCLE", "LWPOLYLINE", "POLYLINE", "INSERT") else T
        if t == "LINE":
            self._emit([(e.f(10), e.f(20)), (e.f(11), e.f(21))], False, T, group)
        elif t == "CIRCLE":
            cx, cy, r = e.f(10), e.f(20), e.f(40)
            pts = _arc_points(cx, cy, r, 0.0, 2 * math.pi, tol)
            pts[-1] = pts[0]
            self._emit(pts, True, To, group)
        elif t == "ARC":
            a0, a1 = math.radians(e.f(50)), math.radians(e.f(51))
            sweep = (a1 - a0) % (2 * math.pi) or 2 * math.pi
            self._emit(_arc_points(e.f(10), e.f(20), e.f(40), a0, sweep, tol), False, To, group)
        elif t == "ELLIPSE":
            cx, cy, mx, my, ratio = e.f(10), e.f(20), e.f(11), e.f(21), e.f(40, 1.0)
            s, en = e.f(41, 0.0), e.f(42, 2 * math.pi)
            if e.f(230, 1.0) < 0:                     # extrusión invertida: el eje menor va al otro lado
                ratio = -ratio
            while en <= s:
                en += 2 * math.pi
            R = math.hypot(mx, my)
            step = 2 * math.acos(max(-1.0, 1.0 - min(tol, R) / R)) if 0 < tol < R else math.pi / 8
            n = max(8, min(4000, math.ceil((en - s) / max(step, 1e-6))))
            pts = [(cx + mx * math.cos(u) - my * ratio * math.sin(u), cy + my * math.cos(u) + mx * ratio * math.sin(u))
                   for u in (s + (en - s) * k / n for k in range(n + 1))]
            full = abs((en - s) - 2 * math.pi) < 1e-6
            if full:
                pts[-1] = pts[0]
            self._emit(pts, full, T, group)
        elif t == "LWPOLYLINE":
            xs, ys = e.all(10), e.all(20)
            bulges: list[float] = []
            # el 42 (bulge) va después del 10/20 de su vértice y es opcional
            for c, v in e.tags:
                if c == 10:
                    bulges.append(0.0)
                elif c == 42 and bulges:
                    bulges[-1] = float(v)
            verts = [(float(x), float(y), b) for x, y, b in zip(xs, ys, bulges)]
            closed = bool(e.i(70) & 1)
            self._emit(_vertex_path(verts, closed, tol), closed, To, group)
        elif t == "POLYLINE":
            flags = e.i(70)
            if flags & (16 | 64):                     # mallas: no son trazados de corte
                self.skipped["POLYLINE (malla)"] += 1
                return
            verts = [(v.f(10), v.f(20), v.f(42)) for v in e.children if not (v.i(70) & 16)]
            closed = bool(flags & 1)
            self._emit(_vertex_path(verts, closed, tol), closed, To if not flags & 8 else T, group)
        elif t == "SPLINE":
            deg = e.i(71, 3)
            knots = [float(v) for v in e.all(40)]
            ctrl = list(zip(map(float, e.all(10)), map(float, e.all(20))))
            weights = [float(v) for v in e.all(41)]
            closed = bool(e.i(70) & 1)
            if ctrl:
                pts = _nurbs_points(deg, knots, ctrl, weights, tol)
            else:
                pts = list(zip(map(float, e.all(11)), map(float, e.all(21))))
                self.warnings.append("SPLINE definida solo por puntos de ajuste: se corta como polilínea por esos puntos")
            if closed and pts and pts[0] != pts[-1]:
                pts.append(pts[0])
            self._emit(pts, closed, T, group)
        elif t == "INSERT":
            self._insert(e, To, group, depth)

    def _insert(self, e: _Ent, T: Affine, group, depth):
        raw = e.get(2, "")
        name = raw.upper()
        blk = self.doc.blocks.get(name)
        if blk is None:
            self.note(f"Bloque '{raw}' no encontrado")
            return
        if name in self.stack:
            self.note(f"El bloque '{raw}' se inserta a sí mismo: se omite esa repetición")
            return
        if depth >= MAX_BLOCK_DEPTH:
            self.note(f"Bloques anidados más de {MAX_BLOCK_DEPTH} niveles en '{raw}': se corta ahí")
            return
        (bx, by), ents = blk
        ix, iy = e.f(10), e.f(20)
        sx, sy = e.f(41, 1.0), e.f(42, 1.0)
        rot = math.radians(e.f(50))
        cols, rows = max(1, e.i(70, 1)), max(1, e.i(71, 1))
        cs, rs = e.f(44), e.f(45)
        c, s = math.cos(rot), math.sin(rot)
        self.stack.append(name)
        try:
            self._insert_cells(ents, T, group, depth, (bx, by), (ix, iy), (sx, sy), (c, s), (cols, rows), (cs, rs))
        finally:
            self.stack.pop()

    def _insert_cells(self, ents, T, group, depth, base, ins, scale, cs_rot, grid, spacing):
        (bx, by), (ix, iy), (sx, sy), (c, s) = base, ins, scale, cs_rot
        (cols, rows), (cs, rs) = grid, spacing
        for ci in range(cols):
            for ri in range(rows):
                if self.inserts >= MAX_COPIES:
                    self.note(f"Más de {MAX_COPIES} copias de bloques: se cortó ahí (revisa el DXF)")
                    return
                # T(inserción)·R(giro)·T(celda de la matriz)·S(escala)·T(−base)
                M = (1.0, 0.0, 0.0, 1.0, ix, iy)
                M = _mul(M, (c, s, -s, c, 0.0, 0.0))
                M = _mul(M, (1.0, 0.0, 0.0, 1.0, ci * cs, ri * rs))
                M = _mul(M, (sx, 0.0, 0.0, sy, 0.0, 0.0))
                M = _mul(M, (1.0, 0.0, 0.0, 1.0, -bx, -by))
                self.inserts += 1
                # cada inserción de primer nivel es un grupo (se mueve junta en el editor)
                g = group or f"dxf-insert-{self.inserts}"
                self.run(ents, _mul(T, M), g, depth + 1)


def _join(pieces: list[tuple[list, bool, str | None]], eps: float) -> list[tuple[list, bool, str | None]]:
    """Une trazados abiertos que comparten extremo (dentro del mismo grupo)."""
    closed = [p for p in pieces if p[1]]
    open_ = [[list(p[0]), p[2]] for p in pieces if not p[1]]

    def cell(pt):
        return (math.floor(pt[0] / eps), math.floor(pt[1] / eps))

    def near(tip, g):
        # dos extremos a 1e-9 pueden caer en celdas vecinas: se miran las 9 y se confirma la distancia
        cx, cy = cell(tip)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j, jside in ends.get(((cx + dx, cy + dy), g), ()):
                    end = open_[j][0][0 if jside == 0 else -1]
                    if math.hypot(end[0] - tip[0], end[1] - tip[1]) <= eps:
                        yield j, jside

    merged = True
    while merged and len(open_) > 1:
        merged = False
        ends: dict = {}
        for idx, (pts, g) in enumerate(open_):
            ends.setdefault((cell(pts[0]), g), []).append((idx, 0))
            ends.setdefault((cell(pts[-1]), g), []).append((idx, 1))
        used = set()
        out = []
        for idx, (pts, g) in enumerate(open_):
            if idx in used:
                continue
            used.add(idx)
            grew = True
            while grew:
                grew = False
                for side in (1, 0):
                    tip = pts[-1] if side else pts[0]
                    for j, jside in near(tip, g):
                        if j in used:
                            continue
                        other = open_[j][0]
                        seg = other if jside == (0 if side else 1) else other[::-1]
                        pts = pts + seg[1:] if side else seg[:-1] + pts
                        used.add(j)
                        grew = merged = True
                        break
                    if grew:
                        break
            out.append([pts, g])
        open_ = out
    result = closed[:]
    for pts, g in open_:
        is_closed = len(pts) > 2 and math.hypot(pts[0][0] - pts[-1][0], pts[0][1] - pts[-1][1]) <= eps
        if is_closed:
            pts[-1] = pts[0]
        result.append((pts, is_closed, g))
    return result


def dxf_to_polylines(source, tolerance_mm: float = 0.1) -> tuple[SvgGeometry, list[str]]:
    """Lee un DXF (ruta, bytes o texto) y devuelve (geometría en mm, avisos)."""
    if tolerance_mm <= 0:
        raise ValueError("tolerance_mm debe ser > 0")
    warnings: list[str] = []
    doc = _Doc(_records(_read_text(source)))
    unit = doc.unit_mm(warnings)
    fl = _Flattener(doc, tolerance_mm / unit, warnings)
    fl.run(doc.entities)
    if fl.skipped:
        what = ", ".join(f"{n} {t}" for t, n in fl.skipped.most_common())
        warnings.append(f"Se ignoraron entidades que no son trazados de corte: {what}")
    if fl.hidden:
        warnings.append(f"Se ignoraron {fl.hidden} entidades en capas apagadas o congeladas")
    pieces = [([(x * unit, y * unit) for x, y in pts], closed, g) for pts, closed, g in fl.out]
    pieces = _join(pieces, JOIN_EPS_MM)
    if not pieces:
        warnings.append("El DXF no tiene trazados que se puedan cortar")
        return SvgGeometry(0.0, 0.0, [], (0.0, 0.0, 0.0, 0.0)), warnings
    xs = [x for pts, _, _ in pieces for x, _ in pts]
    ys = [y for pts, _, _ in pieces for _, y in pts]
    minx, maxy = min(xs), max(ys)
    paths = []
    for pts, closed, g in pieces:
        # DXF tiene la Y hacia arriba: se invierte y se pega el dibujo a (0, 0)
        page_pts = [(x - minx, maxy - y) for x, y in pts]
        paths.append(Polyline(points=page_pts, closed=closed, stroke=None, fill=None, id=None,
                              group_ids=[g] if g else []))
    w, h = max(xs) - minx, maxy - min(ys)
    return SvgGeometry(width_mm=w, height_mm=h, paths=paths, bbox=_bbox(paths)), warnings
