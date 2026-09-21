"""
Orden de corte.

1. Interiores antes que exteriores: si un trazado cerrado A está dentro de un
   trazado cerrado B, A se corta primero — si B se cortara antes, la pieza
   quedaría suelta y podría moverse al cortar A.
2. Dentro del mismo nivel, vecino más cercano: minimiza los desplazamientos
   en vacío (y el tiempo total).
"""
from __future__ import annotations

from .svgpaths import Polyline


def _bbox(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def _point_in_polygon(pt, poly) -> bool:
    """Ray casting; poly es una lista de puntos cerrada o no."""
    x, y = pt
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y):
            xint = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < xint:
                inside = not inside
        j = i
    return inside


def _sample_points(pts):
    """Puntos de prueba de un trazado: primero, medio y centroide de vértices.
    Un punto solo puede caer justo sobre el borde del contenedor (círculo
    inscrito en un cuadrado); con tres y mayoría se evita."""
    n = len(pts)
    cx = sum(p[0] for p in pts) / n
    cy = sum(p[1] for p in pts) / n
    return [pts[0], pts[n // 2], (cx, cy)]


def _contains(outer: Polyline, ob, inner: Polyline, ib) -> bool:
    """outer contiene a inner (ob/ib = bboxes precalculados)."""
    if not outer.closed or len(outer.points) < 3:
        return False
    if not (ob[0] <= ib[0] and ob[1] <= ib[1] and ib[2] <= ob[2] and ib[3] <= ob[3]):
        return False
    if outer.points == inner.points:      # mismo trazado duplicado
        return False
    votes = sum(1 for s in _sample_points(inner.points) if _point_in_polygon(s, outer.points))
    return votes >= 2


def depth_of_each(paths: list[Polyline]) -> list[int]:
    """Número de trazados cerrados que contienen a cada uno (0 = exterior)."""
    boxes = [_bbox(p.points) for p in paths]
    depths = [0] * len(paths)
    for i, p in enumerate(paths):
        for j, q in enumerate(paths):
            if i != j and _contains(q, boxes[j], p, boxes[i]):
                depths[i] += 1
    return depths


def order_paths(paths: list[Polyline]) -> list[Polyline]:
    if len(paths) <= 1:
        return list(paths)
    depths = depth_of_each(paths)
    # Más profundo primero; dentro de cada nivel, vecino más cercano
    remaining = sorted(range(len(paths)), key=lambda i: -depths[i])
    ordered: list[Polyline] = []
    cur = (0.0, 0.0)
    while remaining:
        level = depths[remaining[0]]
        group = [i for i in remaining if depths[i] == level]
        while group:
            best = min(group, key=lambda i: (paths[i].points[0][0] - cur[0]) ** 2
                       + (paths[i].points[0][1] - cur[1]) ** 2)
            group.remove(best)
            remaining.remove(best)
            ordered.append(paths[best])
            cur = paths[best].points[-1]
    return ordered
