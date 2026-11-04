import json
from pathlib import Path

import pytest

from bridge import mobile

FIX = Path(__file__).parent / "fixtures"
CALENDAR = Path(__file__).resolve().parents[2] / "calendario iman grande.studio3"


def test_svg():
    r = mobile.load(FIX / "square50.svg")
    assert r["kind"] == "svg" and r["stats"]["paths"] == 2
    assert r["bbox_mm"] == [10.0, 10.0, 90.0, 90.0] and not r["images"]


def test_studio3_drops_page_frame(tmp_path):
    r = mobile.load(FIX / "circles_6x6.studio3", tmp_path)
    assert r["kind"] == "studio3" and r["page_mm"] == [210.0, 297.0]
    assert r["stats"]["paths"] == 12        # sin el marco de página de to_svg()
    assert all(p["closed"] for p in r["paths"])


def test_studio3_images_written_and_aligned(tmp_path):
    if not CALENDAR.exists():
        pytest.skip("falta el archivo real del calendario")
    r = mobile.load(CALENDAR, tmp_path)
    assert r["stats"]["paths"] == 8 and len(r["images"]) == 8 and not r["warnings"]
    assert all(Path(im["file"]).exists() for im in r["images"])


def test_dxf_detected_by_content_even_renamed(tmp_path):
    ezdxf = pytest.importorskip("ezdxf")
    doc = ezdxf.new("R2010", setup=False)
    doc.header["$INSUNITS"] = 4
    doc.modelspace().add_circle((0, 0), 10)
    p = tmp_path / "IMG-20261103-WA0007.bin"      # así llegan por WhatsApp
    doc.saveas(p)
    r = mobile.load(p)
    assert r["kind"] == "dxf" and r["stats"]["paths"] == 1 and r["paths"][0]["closed"]


def test_inner_paths_come_first(tmp_path):
    """ordering.py: el agujero se corta antes que el contorno que lo rodea (si no, la pieza
    se suelta antes de cortar lo de adentro)."""
    ezdxf = pytest.importorskip("ezdxf")
    from bridge.ordering import depth_of_each
    from bridge.svgpaths import Polyline
    doc = ezdxf.new("R2010", setup=False)
    doc.header["$INSUNITS"] = 4
    msp = doc.modelspace()
    msp.add_lwpolyline([(0, 0), (90, 0), (90, 50), (0, 50)], close=True)   # contorno primero en el archivo
    msp.add_circle((20, 25), 4)
    msp.add_circle((70, 25), 4)
    p = tmp_path / "etiqueta.dxf"
    doc.saveas(p)
    r = mobile.load(p)
    depths = depth_of_each([Polyline([tuple(q) for q in x["pts"]], x["closed"], None, None, None) for x in r["paths"]])
    assert depths == sorted(depths, reverse=True) == [1, 1, 0]


def test_dxf_with_leading_999_comment_detected_when_renamed(tmp_path):
    ezdxf = pytest.importorskip("ezdxf")
    doc = ezdxf.new("R2010", setup=False)
    doc.modelspace().add_line((0, 0), (10, 0))
    p = tmp_path / "plano.dxf"
    doc.saveas(p)
    q = tmp_path / "DOC-WA0003.bin"
    q.write_text("999\nLibreCAD\n" + p.read_text(encoding="utf-8"), encoding="utf-8")
    assert mobile.load(q)["kind"] == "dxf"


def test_svg_layer_is_not_a_group_but_nested_groups_are(tmp_path):
    assert all(p["group"] is None for p in mobile.load(FIX / "square50.svg")["paths"])   # todo en Capa_1
    svg = tmp_path / "grupos.svg"
    svg.write_text('<svg xmlns="http://www.w3.org/2000/svg" width="100mm" height="100mm" viewBox="0 0 100 100">'
                   '<g id="Capa_1"><g id="pieza"><rect x="10" y="10" width="20" height="20" fill="none" stroke="red"/>'
                   '<circle cx="20" cy="20" r="3" fill="none" stroke="red"/></g>'
                   '<rect x="50" y="50" width="20" height="20" fill="none" stroke="red"/></g></svg>', encoding="utf-8")
    groups = sorted(str(p["group"]) for p in mobile.load(svg)["paths"])
    assert groups == ["None", "pieza", "pieza"]


def test_each_studio3_gets_its_own_image_folder(tmp_path):
    a, b = tmp_path / "a" / "x.studio3", tmp_path / "b" / "x.studio3"
    for p in (a, b):
        p.parent.mkdir()
        p.write_bytes((FIX / "circles_6x6.studio3").read_bytes())
    da, db = mobile._image_dir(str(a), tmp_path / "cache"), mobile._image_dir(str(b), tmp_path / "cache")
    assert da != db and da == mobile._image_dir(str(a), tmp_path / "cache")   # estable al reabrir


def test_load_json_never_raises(tmp_path):
    bad = tmp_path / "x.txt"
    bad.write_text("hola")
    out = json.loads(mobile.load_json(str(bad)))
    assert out["ok"] is False and "Formato no reconocido" in out["error"]
