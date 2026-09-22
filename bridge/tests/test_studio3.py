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


def test_rejects_non_studio(tmp_path):
    p = tmp_path / "x.studio3"; p.write_bytes(b"nope")
    import pytest
    with pytest.raises(ValueError):
        studio3.parse(str(p))
