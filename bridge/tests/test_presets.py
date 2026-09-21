import pytest

from bridge import presets
from bridge.jobs import build_settings


@pytest.fixture(autouse=True)
def isolated_appdata(tmp_path, monkeypatch):
    # Los presets de usuario van a %APPDATA%\CameoCut: lo aislamos por test
    monkeypatch.setenv("APPDATA", str(tmp_path))
    yield


def test_builtin_blades_and_materials_are_listed():
    blades = {b["id"] for b in presets.list_blades()}
    assert {"autoblade", "ratchet", "pen"} <= blades
    mats = presets.list_materials()
    assert all(m["custom"] is False for m in mats)
    assert all(presets.find_blade(m["blade_id"]) for m in mats)


def test_save_custom_material_and_override_builtin():
    saved = presets.save_material({"name": "Vinilo Oracal 651", "pressure": 9, "speed": 12,
                                   "blade_id": "ratchet", "mat": 0})
    assert saved["id"] == "vinilo_oracal_651" and saved["custom"] is True
    assert presets.find_material("vinilo_oracal_651")["pressure"] == 9

    presets.save_material({"id": "papel_80", "name": "Papel 80 g (mío)", "pressure": 14,
                           "speed": 10, "blade_id": "ratchet"})
    m = presets.find_material("papel_80")
    assert m["pressure"] == 14 and m["custom"] is True
    assert presets.delete_material("papel_80")
    assert presets.find_material("papel_80")["custom"] is False   # vuelve el base


def test_material_validation():
    with pytest.raises(ValueError):
        presets.save_material({"name": "x", "pressure": 99, "speed": 10, "blade_id": "ratchet"})
    with pytest.raises(ValueError):
        presets.save_material({"name": "x", "pressure": 10, "speed": 10, "blade_id": "no_existe"})
    with pytest.raises(ValueError):
        presets.save_material({"name": "", "pressure": 10, "speed": 10, "blade_id": "ratchet"})


def test_custom_blade_flows_into_settings():
    presets.save_blade({"name": "Trinquete 60°", "type": "ratchet", "offset_mm": 0.3, "tool": 2})
    presets.save_material({"name": "Cartón", "pressure": 30, "speed": 4, "depth": 8,
                           "blade_id": "trinquete_60"})
    s = build_settings("cart_n", None)
    assert s.blade == "ratchet" and s.blade_offset_mm == 0.3 and s.tool == 2
    assert s.depth is None          # trinquete: profundidad manual, no se envía TF


def test_autoblade_material_keeps_depth():
    presets.save_material({"name": "Auto", "pressure": 10, "speed": 10, "depth": 4,
                           "blade_id": "autoblade"})
    s = build_settings("auto", None)
    assert s.blade == "auto" and s.depth == 4 and s.blade_offset_mm == 0.0


def test_delete_blade_only_custom():
    assert not presets.delete_blade("ratchet")
    presets.save_blade({"name": "Temp", "type": "pen"})
    assert presets.delete_blade("temp")
