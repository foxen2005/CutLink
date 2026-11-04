"""Paso 0: comprobar que el bridge corre dentro de la app (Chaquopy) en la tablet.

Lee un SVG (el mismo square50.svg de los tests del bridge) y un .studio3 real, y devuelve
un resumen en JSON con los tiempos, para compararlo con lo que da el PC.
"""
import json
import os
import platform
import sys
import time

# bridge/tests/fixtures/square50.svg: cuadrado de 50 mm + círculo Ø 30 mm
SQUARE50 = b"""<?xml version="1.0" encoding="utf-8"?>
<svg version="1.1" xmlns="http://www.w3.org/2000/svg" x="0px" y="0px" viewBox="0 0 283.465 283.465">
<g id="Capa_1">
  <rect x="28.346" y="28.346" width="141.732" height="141.732" style="fill:none;stroke:#FF0000;stroke-width:1;"/>
  <circle cx="212.598" cy="212.598" r="42.52" style="fill:none;stroke:#FF0000;stroke-width:1;"/>
</g>
</svg>"""


def run() -> str:
    t0 = time.perf_counter()
    import svgelements
    from bridge.ordering import order_paths
    from bridge.studio3 import parse
    from bridge.svgpaths import svg_to_polylines
    t_import = time.perf_counter() - t0

    t1 = time.perf_counter()
    g = svg_to_polylines(SQUARE50, 0.1)
    ordered = order_paths(g.paths)
    t_svg = time.perf_counter() - t1

    t2 = time.perf_counter()
    sample = os.path.join(os.path.dirname(__file__), "cameo_samples", "circles_6x6.studio3")
    doc = parse(sample)
    t_s3 = time.perf_counter() - t2

    return json.dumps({
        "python": sys.version.split()[0],
        "machine": platform.machine(),
        "svgelements": svgelements.SVGELEMENTS_VERSION,
        "svg": {
            "page_mm": [round(g.width_mm, 2), round(g.height_mm, 2)],
            "paths": len(ordered),
            "bbox_mm": [round(v, 2) for v in g.bbox],
            "points": [len(p.points) for p in ordered],
        },
        "studio3": {"page_mm": list(doc.page_mm), "shapes": len(doc.shapes), "warnings": doc.warnings},
        "ms": {"import": round(t_import * 1000), "svg": round(t_svg * 1000), "studio3": round(t_s3 * 1000)},
    })
