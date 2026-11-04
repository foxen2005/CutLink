"""El lector DXF propio contra archivos generados con ezdxf (la referencia del formato).
ezdxf solo hace falta para los tests: el lector no lo usa (también corre en la tablet)."""
import io
import math

import pytest

from bridge.dxfpaths import dxf_to_polylines

ezdxf = pytest.importorskip("ezdxf")


def _doc(units=4):
    doc = ezdxf.new("R2010", setup=False)
    doc.header["$INSUNITS"] = units
    return doc, doc.modelspace()


def _read(doc, tol=0.05):
    buf = io.StringIO()
    doc.write(buf)
    return dxf_to_polylines(buf.getvalue(), tol)


def _length(p):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(p.points, p.points[1:]))


def _frame(msp, x0=-10, y0=-10, x1=110, y1=110):
    """Marco de referencia: fija la caja del dibujo, así se sabe dónde cae (0,0) del DXF."""
    msp.add_lwpolyline([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], close=True)


def to_page(x, y, x0=-10, y1=110):
    return (x - x0, y1 - y)          # DXF Y arriba -> página Y abajo, pegado a (0,0)


def test_four_lines_join_into_one_closed_rectangle():
    doc, msp = _doc()
    for a, b in [((0, 0), (50, 0)), ((50, 0), (50, 30)), ((0, 30), (50, 30)), ((0, 0), (0, 30))]:
        msp.add_line(a, b)        # el 3.º va al revés: igual se une
    g, w = _read(doc)
    assert not w
    assert len(g.paths) == 1 and g.paths[0].closed
    assert (round(g.width_mm, 3), round(g.height_mm, 3)) == (50.0, 30.0)
    assert g.bbox == (0.0, 0.0, 50.0, 30.0)


def test_lwpolyline_bulges_make_a_stadium():
    doc, msp = _doc()
    # 40 mm de recta + dos semicírculos de r 10 (bulge 1 = semicírculo hacia afuera)
    msp.add_lwpolyline([(0, 0, 0), (40, 0, 1), (40, 20, 0), (0, 20, 1)], format="xyb", close=True)
    g, _ = _read(doc, 0.01)
    p = g.paths[0]
    assert p.closed
    assert abs(g.width_mm - 60) < 0.02 and abs(g.height_mm - 20) < 0.02
    assert abs(_length(p) - (80 + 2 * math.pi * 10)) < 0.1


def test_circle_arc_and_tolerance():
    doc, msp = _doc()
    msp.add_circle((0, 0), 10)
    msp.add_arc((100, 0), 10, 0, 90)
    g, _ = _read(doc, 0.01)
    circle = next(p for p in g.paths if p.closed)
    arc = next(p for p in g.paths if not p.closed)
    assert abs(_length(circle) - 2 * math.pi * 10) < 0.05
    assert abs(_length(arc) - math.pi * 5) < 0.05
    # todos los puntos del círculo a 10 mm de su centro: caja del dibujo x −10…110, y −10…10
    # -> el centro (0,0) del DXF queda en (10, 10) de la página. La caja sale de los puntos
    # muestreados (no tocan justo el extremo), así que el centro puede correrse hasta la tolerancia.
    assert all(abs(math.hypot(x - 10, y - 10) - 10) < 0.02 for x, y in circle.points)


def test_full_ellipse_box():
    doc, msp = _doc()
    msp.add_ellipse((0, 0), major_axis=(30, 0), ratio=0.5)
    g, _ = _read(doc)
    assert g.paths[0].closed
    assert abs(g.width_mm - 60) < 0.05 and abs(g.height_mm - 30) < 0.05


def test_spline_matches_ezdxf_evaluation():
    doc, msp = _doc()
    _frame(msp)
    sp = msp.add_open_spline([(0, 0), (20, 80), (60, -20), (100, 60), (90, 100)], degree=3)
    ref = [to_page(v.x, v.y) for v in sp.construction_tool().approximate(400)]
    g, _ = _read(doc, 0.02)
    curve = next(p for p in g.paths if not p.closed)

    def dist(q):
        best = 1e9
        for a, b in zip(curve.points, curve.points[1:]):
            ex, ey = b[0] - a[0], b[1] - a[1]
            L = ex * ex + ey * ey
            t = max(0, min(1, ((q[0] - a[0]) * ex + (q[1] - a[1]) * ey) / L)) if L else 0
            best = min(best, math.hypot(q[0] - a[0] - t * ex, q[1] - a[1] - t * ey))
        return best

    assert max(dist(q) for q in ref) < 0.05
    assert curve.points[0] == pytest.approx(to_page(0, 0), abs=1e-6)
    assert curve.points[-1] == pytest.approx(to_page(90, 100), abs=1e-6)


def test_rational_spline_quarter_circle():
    doc, msp = _doc()
    _frame(msp)
    w = math.sqrt(2) / 2   # cuarto de círculo exacto como NURBS racional de grado 2
    msp.add_rational_spline([(50, 0), (50, 50), (0, 50)], [1, w, 1], degree=2)
    g, _ = _read(doc, 0.01)
    curve = next(p for p in g.paths if not p.closed)
    cx, cy = to_page(0, 0)
    assert all(abs(math.hypot(x - cx, y - cy) - 50) < 0.02 for x, y in curve.points)


def test_block_insert_scale_rotation_and_array():
    doc, msp = _doc()
    _frame(msp, -10, -10, 210, 210)
    blk = doc.blocks.new(name="PIEZA", base_point=(5, 5))
    blk.add_lwpolyline([(5, 5), (15, 5), (15, 10), (5, 10)], close=True)   # 10x5 desde la base
    msp.add_blockref("PIEZA", (100, 100), dxfattribs={"xscale": 2, "yscale": 2, "rotation": 90})
    msp.add_blockref("PIEZA", (0, 0), dxfattribs={"column_count": 3, "row_count": 2,
                                                  "column_spacing": 30, "row_spacing": 20})
    g, w = _read(doc)
    pieces = [p for p in g.paths if p.group_ids]
    assert len(pieces) == 1 + 6
    def size(p):
        xs, ys = [x for x, _ in p.points], [y for _, y in p.points]
        return (round(max(xs) - min(xs), 6), round(max(ys) - min(ys), 6))

    # pieza 10×5; la inserción ×2 y girada 90° queda 10 de ancho × 20 de alto; las 6 de la matriz, 10×5
    sizes = sorted(size(p) for p in pieces)
    assert sizes == [(10.0, 5.0)] * 6 + [(10.0, 20.0)]
    # matriz: columnas cada 30 y filas cada 20 desde (0,0) -> esquinas en x 0/30/60
    lefts = sorted({round(min(x for x, _ in p.points), 6) for p in pieces if size(p) == (10.0, 5.0)})
    assert lefts == [10.0, 40.0, 70.0]           # +10 por el marco que arranca en x = −10
    assert len({tuple(p.group_ids) for p in pieces}) == 7      # cada inserción es su propio grupo


def test_inches_are_converted():
    doc, msp = _doc(units=1)
    msp.add_lwpolyline([(0, 0), (2, 0), (2, 1), (0, 1)], close=True)
    g, w = _read(doc)
    assert not w
    assert (round(g.width_mm, 3), round(g.height_mm, 3)) == (50.8, 25.4)


def test_unitless_assumes_mm_and_warns():
    doc, msp = _doc(units=0)
    msp.add_line((0, 0), (10, 0))
    g, w = _read(doc)
    assert round(g.width_mm, 3) == 10.0 and any("unidades" in x for x in w)


def test_y_axis_is_flipped():
    doc, msp = _doc()
    msp.add_line((0, 0), (0, 10))
    msp.add_circle((0, 10), 1)          # arriba en el DXF -> arriba (Y chica) en la página
    g, _ = _read(doc)
    circle = next(p for p in g.paths if p.closed)
    assert max(y for _, y in circle.points) < 2.01


def test_hidden_layers_text_and_paper_space_are_ignored():
    doc, msp = _doc()
    doc.layers.add("APAGADA").off()
    doc.layers.add("CONGELADA").freeze()
    msp.add_line((0, 0), (10, 0))
    msp.add_line((0, 5), (10, 5), dxfattribs={"layer": "APAGADA"})
    msp.add_line((0, 9), (10, 9), dxfattribs={"layer": "CONGELADA"})
    msp.add_text("hola")
    doc.layout("Layout1").add_line((0, 0), (99, 99))
    g, w = _read(doc)
    assert len(g.paths) == 1
    assert any("TEXT" in x for x in w) and any("capas apagadas" in x for x in w)


def test_ocs_mirrored_circle():
    doc, msp = _doc()
    _frame(msp)
    # extrusión (0,0,−1): el centro en OCS (−50, 50) es (50, 50) en coordenadas reales
    msp.add_circle((-50, 50), 5, dxfattribs={"extrusion": (0, 0, -1)})
    g, _ = _read(doc, 0.01)
    circle = min(g.paths, key=lambda p: max(x for x, _ in p.points) - min(x for x, _ in p.points))  # el marco mide 120
    cx, cy = to_page(50, 50)
    assert all(abs(math.hypot(x - cx, y - cy) - 5) < 1e-6 for x, y in circle.points)


def test_unicode_line_separator_in_text_does_not_break_file():
    doc, msp = _doc()
    msp.add_line((0, 0), (10, 0))
    msp.add_mtext("a b\x0cc")       # splitlines() cortaría aquí y desfasaría todo el archivo
    g, w = _read(doc)
    assert len(g.paths) == 1 and any("MTEXT" in x for x in w)


def test_self_inserting_block_is_cut_once_and_fast():
    import time
    doc, msp = _doc()
    blk = doc.blocks.new(name="A")
    blk.add_line((0, 0), (10, 0))
    blk.add_blockref("A", (0, 5))
    blk.add_blockref("A", (0, 10))
    msp.add_blockref("A", (0, 0))
    t = time.perf_counter()
    g, w = _read(doc)
    assert time.perf_counter() - t < 1.0
    assert len(g.paths) == 1
    assert sum("a sí mismo" in x for x in w) == 1      # un aviso, no 65.536


def test_invisible_entities_are_not_cut():
    doc, msp = _doc()
    msp.add_line((0, 0), (10, 0))
    msp.add_line((0, 5), (10, 5), dxfattribs={"invisible": 1})
    g, _ = _read(doc)
    assert len(g.paths) == 1


def test_layer_and_block_names_ignore_case():
    doc, msp = _doc()
    doc.layers.add("OFF").off()
    blk = doc.blocks.new(name="PIEZA")
    blk.add_circle((0, 0), 5)
    msp.add_line((0, 0), (10, 0), dxfattribs={"layer": "off"})      # misma capa, otra caja
    msp.add_blockref("pieza", (50, 0))
    g, w = _read(doc)
    assert len(g.paths) == 1 and g.paths[0].closed and not any("no encontrado" in x for x in w)


def test_join_endpoints_on_neighbouring_grid_cells():
    from bridge.dxfpaths import _join
    # 10.005/0.01 y 10.0050000001/0.01 caen en celdas distintas de la grilla, pero están a 1e-10
    a = [(0.0, 0.0), (10.005, 0.0)]
    b = [(10.0050000001, 0.0), (10.005, 5.0)]
    out = _join([(a, False, None), (b, False, None)], 0.01)
    assert len(out) == 1 and len(out[0][0]) == 3


def test_binary_dxf_is_rejected():
    with pytest.raises(ValueError, match="binario"):
        dxf_to_polylines(b"AutoCAD Binary DXF\r\n\x1a\x00rest")
