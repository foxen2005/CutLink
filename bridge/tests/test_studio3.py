import math
from pathlib import Path

from bridge import studio3

FIX = Path(__file__).parent / "fixtures" / "circles_6x6.studio3"


def test_parse_circles_fixture():
    doc = studio3.parse(str(FIX))
    assert doc.page_mm == (210.0, 297.0)
    assert len(doc.shapes) == 12 and not doc.texts and not doc.images
    centers = set()
    for s in doc.shapes:
        assert len(s.points) == 8 and s.closed
        xs = [p["x"] for p in s.points]; ys = [p["y"] for p in s.points]   # ya en coordenadas de página
        cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
        r = [math.hypot(x - cx, y - cy) for x, y in zip(xs, ys)]
        assert abs(sum(r) / 8 - 30.0) < 0.05           # Ø 60 mm
        assert all(p["in"] and p["out"] for p in s.points)   # anclas curvas con manejadores
        centers.add((round(cx, 1), round(cy, 1)))
    assert {c[0] for c in centers} == {42.6, 104.8, 167.0}
    assert {c[1] for c in centers} == {47.5, 110.0, 172.5, 235.0}


def test_convert_writes_svg(tmp_path):
    res = studio3.convert(str(FIX), str(tmp_path))
    assert res["ok"] and res["stats"]["shapes"] == 12
    svg = Path(res["svg_path"]).read_text(encoding="utf-8")
    assert svg.startswith("<svg") and 'width="210.0mm"' in svg
    assert svg.count("<path") == 12 and " C " in svg     # curvas Bézier, no polígonos


CALENDAR = Path(__file__).resolve().parents[2] / "calendario iman grande.studio3"


def _box(pts):
    return (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))


def test_calendar_images_follow_group_transform():
    """Las imágenes están dentro de un grupo escalado en Studio: su matriz es local y antes
    quedaban al 95,5 % y corridas hasta 5 mm de su línea de corte. Ahora cada una calza con la
    forma que la recorta, una imagen por celda y sin cambiar su giro."""
    import pytest
    if not CALENDAR.exists():
        pytest.skip("falta el archivo real del calendario")
    doc = studio3.parse(str(CALENDAR))
    assert len(doc.images) == 8 and len(doc.shapes) == 8 and not doc.warnings
    cells = [_box([(p["x"], p["y"]) for p in s.points]) for s in doc.shapes]
    used = set()
    for im in doc.images:
        ib = _box(studio3._matrix_corners(im.matrix))
        err, cell = min((max(abs(u - v) for u, v in zip(ib, cb)), i) for i, cb in enumerate(cells))
        assert err <= 0.05, (im.name, ib)
        assert cell not in used, f"dos imágenes sobre la misma celda ({im.name})"
        used.add(cell)
        # el grupo solo escala: la imagen sigue girada ±90° como en Studio (a = d = 0)
        a, b, c, d = im.matrix[:4]
        assert abs(a) < 1e-3 and abs(d) < 1e-3 and abs(abs(c / b) - 47.7497 / 33.4369) < 1e-3


def test_group_correction_scale_move_and_refuses_to_guess():
    # imagen girada 90° (como las del calendario) dentro de un grupo ×2 movido (+10, +20)
    m = (0.0, -3.0, 4.0, 0.0, 50.0, 40.0)
    q = studio3._matrix_corners(m)
    fixed, note = studio3._group_corrected(m, [(2 * x + 10, 2 * y + 20) for x, y in q])
    assert note is None
    assert all(abs(u - v) < 1e-6 for u, v in zip(fixed, (0.0, -6.0, 8.0, 0.0, 110.0, 100.0)))
    # sin grupo (esquinas = cuadrado de la matriz): no cambia y no avisa
    same, note = studio3._group_corrected(m, q)
    assert note is None and all(abs(u - v) < 1e-9 for u, v in zip(same, m))
    # grupo girado 100°: la orientación es ambigua -> no adivina, deja la matriz y avisa
    r = math.radians(100)
    rot = [(math.cos(r) * x - math.sin(r) * y, math.sin(r) * x + math.cos(r) * y) for x, y in q]
    kept, note = studio3._group_corrected(m, rot)
    assert kept == m and note and "girado" in note
    # caja con margen distinto por lado (no es semejanza): no se toca y avisa
    kept, note = studio3._group_corrected(m, [(x + (3 if x > 50 else -1), y) for x, y in q])
    assert kept == m and note
    assert studio3._group_corrected(m, []) == (m, None)


def test_rejects_non_studio(tmp_path):
    p = tmp_path / "x.studio3"; p.write_bytes(b"nope")
    import pytest
    with pytest.raises(ValueError):
        studio3.parse(str(p))
