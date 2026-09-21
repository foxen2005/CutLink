"""Tests de svgpaths: conversión SVG → polilíneas en mm."""

import json
import math
from pathlib import Path

import pytest

from bridge.svgpaths import Polyline, SvgGeometry, svg_to_polylines, to_dict, total_length_mm

FIXTURES = Path(__file__).parent / "fixtures"
NS = 'xmlns="http://www.w3.org/2000/svg"'
K = 25.4 / 72  # mm por unidad a 72 ppi


def _svg(body: str, attrs: str = 'viewBox="0 0 283.46 283.46"') -> str:
    return f"<svg {NS} {attrs}>{body}</svg>"


def _bbox_of(p: Polyline):
    xs = [x for x, _ in p.points]
    ys = [y for _, y in p.points]
    return min(xs), min(ys), max(xs), max(ys)


# 1. rect a 72 ppi sin unidades → 50×50 mm cerrado
def test_rect_72ppi_sin_unidades():
    geom = svg_to_polylines(_svg('<rect x="0" y="0" width="141.732" height="141.732"/>'))
    assert len(geom.paths) == 1
    p = geom.paths[0]
    assert p.closed
    assert p.points[0] == p.points[-1]
    minx, miny, maxx, maxy = _bbox_of(p)
    assert minx == pytest.approx(0, abs=0.01)
    assert miny == pytest.approx(0, abs=0.01)
    assert maxx == pytest.approx(50, abs=0.01)
    assert maxy == pytest.approx(50, abs=0.01)
    # 4 vértices + cierre
    assert len(p.points) == 5
    assert geom.width_mm == pytest.approx(283.46 * K, abs=0.01)


# 2. width/height en mm con viewBox → escala 0.5 mm/unidad
def test_viewbox_con_unidades_mm():
    geom = svg_to_polylines(
        _svg('<rect x="0" y="0" width="200" height="100"/>', 'width="100mm" height="50mm" viewBox="0 0 200 100"')
    )
    assert geom.width_mm == pytest.approx(100, abs=0.01)
    assert geom.height_mm == pytest.approx(50, abs=0.01)
    minx, miny, maxx, maxy = _bbox_of(geom.paths[0])
    assert (maxx - minx) == pytest.approx(100, abs=0.01)
    assert (maxy - miny) == pytest.approx(50, abs=0.01)
    # Un punto en (100, 50) unidades → (50, 25) mm
    geom2 = svg_to_polylines(
        _svg('<line x1="0" y1="0" x2="100" y2="50"/>', 'width="100mm" height="50mm" viewBox="0 0 200 100"')
    )
    assert geom2.paths[0].points[-1] == pytest.approx((50, 25), abs=0.01)


# 3. círculo de 10 mm de radio: error de cuerda < tolerancia, > 20 puntos
@pytest.mark.parametrize("tol", [0.1, 0.02])
def test_circulo_aplanado(tol):
    r_units = 10 / K  # 28.346
    geom = svg_to_polylines(_svg(f'<circle cx="100" cy="100" r="{r_units:.5f}"/>'), tolerance_mm=tol)
    p = geom.paths[0]
    assert p.closed
    assert len(p.points) > 20
    cx, cy = 100 * K, 100 * K
    # Vértices sobre el círculo ideal
    for x, y in p.points:
        assert math.hypot(x - cx, y - cy) == pytest.approx(10, abs=0.01)
    # Error máximo en el punto medio de cada cuerda (sagita) < tol
    max_err = 0.0
    for (x0, y0), (x1, y1) in zip(p.points, p.points[1:]):
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        max_err = max(max_err, 10 - math.hypot(mx - cx, my - cy))
    assert 0 <= max_err < tol
    # Perímetro ≈ 2πr
    assert total_length_mm(geom) == pytest.approx(2 * math.pi * 10, rel=0.01)


# 4. transform en grupo ancestro
def test_transform_grupo():
    geom = svg_to_polylines(_svg('<g transform="translate(100,0)"><rect x="0" y="0" width="10" height="10"/></g>'))
    minx, miny, maxx, maxy = _bbox_of(geom.paths[0])
    assert minx == pytest.approx(100 * K, abs=0.01)
    assert maxx == pytest.approx(110 * K, abs=0.01)
    assert miny == pytest.approx(0, abs=0.01)
    # Transform anidado + en el propio elemento
    geom2 = svg_to_polylines(
        _svg(
            '<g transform="translate(100,0)"><g transform="scale(2)">'
            '<rect x="0" y="0" width="10" height="10" transform="translate(5,5)"/></g></g>'
        )
    )
    minx, miny, maxx, maxy = _bbox_of(geom2.paths[0])
    assert minx == pytest.approx(110 * K, abs=0.01)
    assert maxx == pytest.approx(130 * K, abs=0.01)
    assert miny == pytest.approx(10 * K, abs=0.01)


# 5. group_ids e id
def test_group_ids():
    geom = svg_to_polylines(_svg('<g id="Corte"><g id="sub"><path id="p1" d="M0 0 L10 0 L10 10"/></g></g>'))
    assert len(geom.paths) == 1
    p = geom.paths[0]
    assert p.group_ids == ["Corte", "sub"]
    assert p.id == "p1"
    # Elemento fuera de grupos → sin group_ids; sin id → None
    geom2 = svg_to_polylines(_svg('<line x1="0" y1="0" x2="10" y2="0"/>'))
    assert geom2.paths[0].group_ids == []
    assert geom2.paths[0].id is None


# 6. ocultos, defs, clipPath y text se ignoran
def test_ignora_ocultos_defs_text():
    body = (
        '<defs><rect id="d" width="10" height="10"/></defs>'
        '<clipPath id="cp"><rect width="5" height="5"/></clipPath>'
        '<mask id="m"><rect width="5" height="5"/></mask>'
        '<rect id="oculto1" width="10" height="10" display="none"/>'
        '<rect id="oculto2" width="10" height="10" style="visibility:hidden"/>'
        '<g style="display:none"><rect id="oculto3" width="10" height="10"/></g>'
        '<g visibility="hidden"><rect id="oculto4" width="10" height="10"/></g>'
        '<text id="t" x="0" y="0">hola</text>'
        '<rect id="visible" width="10" height="10"/>'
    )
    geom = svg_to_polylines(_svg(body))
    assert [p.id for p in geom.paths] == ["visible"]


# <use> resuelto (con symbol y con elemento en defs), aplicando x/y
def test_use_y_symbol():
    body = (
        '<symbol id="sy"><rect id="sr" width="10" height="10"/></symbol>'
        '<defs><rect id="d" width="5" height="5"/></defs>'
        '<g id="capa"><use href="#sy" x="100" y="0"/>'
        '<use xlink:href="#d" x="0" y="100"/></g>'
    )
    geom = svg_to_polylines(_svg(body, 'xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 200 200"'))
    assert len(geom.paths) == 2  # el symbol suelto no se cuela
    a, b = geom.paths
    assert _bbox_of(a) == pytest.approx((100 * K, 0, 110 * K, 10 * K), abs=0.01)
    assert _bbox_of(b) == pytest.approx((0, 100 * K, 5 * K, 105 * K), abs=0.01)
    assert a.group_ids == ["capa"] and b.group_ids == ["capa"]


# 7. path con dos subpaths
def test_dos_subpaths():
    geom = svg_to_polylines(_svg('<path d="M0 0 L10 0 L10 10 Z M20 20 L30 20"/>'))
    assert len(geom.paths) == 2
    a, b = geom.paths
    assert a.closed and not b.closed
    assert a.points[0] == a.points[-1]
    assert len(a.points) == 4  # 3 vértices + cierre
    assert len(b.points) == 2
    assert b.points[0] == pytest.approx((20 * K, 20 * K), abs=1e-6)
    # Cierre implícito: último punto coincide con el primero sin Z
    geom2 = svg_to_polylines(_svg('<path d="M0 0 L10 0 L10 10 L0 0"/>'))
    assert geom2.paths[0].closed
    assert geom2.paths[0].points[-1] == geom2.paths[0].points[0]
    assert len(geom2.paths[0].points) == 4


# 8. bbox y longitud total de un cuadrado de 50 mm
def test_bbox_y_longitud():
    side = 50 / K
    geom = svg_to_polylines(_svg(f'<rect x="{side:.6f}" y="{side:.6f}" width="{side:.6f}" height="{side:.6f}"/>'))
    assert geom.bbox == pytest.approx((50, 50, 100, 100), abs=0.01)
    assert total_length_mm(geom) == pytest.approx(200, abs=0.01)
    # Sin geometría → bbox nulo
    vacio = svg_to_polylines(_svg(""))
    assert vacio.bbox == (0.0, 0.0, 0.0, 0.0)
    assert vacio.paths == []
    assert total_length_mm(vacio) == 0.0


# Colores normalizados y heredados de grupos
def test_colores():
    body = (
        '<g style="stroke:#FF0000;fill:none"><rect id="a" width="10" height="10"/>'
        '<rect id="b" width="10" height="10" stroke="blue" fill="rgb(0,255,0)"/>'
        '<rect id="c" width="10" height="10" stroke="none"/></g>'
    )
    geom = svg_to_polylines(_svg(body))
    by_id = {p.id: p for p in geom.paths}
    assert by_id["a"].stroke == "#ff0000" and by_id["a"].fill is None
    assert by_id["b"].stroke == "#0000ff" and by_id["b"].fill == "#00ff00"
    assert by_id["c"].stroke is None and by_id["c"].fill is None


# to_dict serializable a JSON y entradas bytes / ruta
def test_to_dict_y_fuentes(tmp_path):
    markup = _svg('<g id="g"><rect id="r" width="10" height="10" stroke="#abcdef"/></g>')
    geom = svg_to_polylines(markup)
    d = to_dict(geom)
    s = json.dumps(d)
    back = json.loads(s)
    assert back["width_mm"] == pytest.approx(geom.width_mm)
    assert back["paths"][0]["id"] == "r"
    assert back["paths"][0]["group_ids"] == ["g"]
    assert back["paths"][0]["stroke"] == "#abcdef"
    assert back["paths"][0]["points"][0] == [0.0, 0.0]
    assert back["paths"][0]["closed"] is True

    # bytes
    geom_b = svg_to_polylines(markup.encode("utf-8"))
    assert to_dict(geom_b) == d
    # ruta (str y PathLike)
    f = tmp_path / "x.svg"
    f.write_text(markup, encoding="utf-8")
    assert to_dict(svg_to_polylines(str(f))) == d
    assert to_dict(svg_to_polylines(f)) == d


# Fixture realista de Illustrator 2025
def test_fixture_illustrator():
    geom = svg_to_polylines(FIXTURES / "sample_illustrator.svg")
    assert isinstance(geom, SvgGeometry)
    # Documento 283.46 pt = 100 mm
    assert geom.width_mm == pytest.approx(100, abs=0.01)
    assert geom.height_mm == pytest.approx(100, abs=0.01)
    # rect + círculo + estrella (texto y rect oculto ignorados)
    assert len(geom.paths) == 3
    assert [p.id for p in geom.paths] == ["cuadro", "circulo", "estrella"]
    assert all(p.stroke == "#ff0000" for p in geom.paths)
    assert all(p.fill is None for p in geom.paths)
    assert all(p.closed for p in geom.paths)
    assert all(p.group_ids == ["Capa_1_1_"] for p in geom.paths)

    cuadro, circulo, estrella = geom.paths
    assert len(cuadro.points) == 5
    assert _bbox_of(cuadro) == pytest.approx((10, 10, 40, 40), abs=0.02)
    # Círculo de 10 mm de radio centrado en (70, 25) mm
    assert len(circulo.points) > 20
    for x, y in circulo.points:
        assert math.hypot(x - 70, y - 25) == pytest.approx(10, abs=0.02)
    assert len(estrella.points) == 11
    assert geom.bbox[0] == pytest.approx(10, abs=0.02)
    assert geom.bbox[2] == pytest.approx(80, abs=0.02)
