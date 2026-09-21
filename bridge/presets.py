"""
Perfiles de cuchilla y materiales: los base viven junto al código
(blades.json, materials.json); los del usuario en %APPDATA%\\CameoCut\\presets.json
para que sobrevivan a reinstalaciones y a `git pull`.

Un preset de usuario con el mismo id que uno base lo reemplaza.
"""
from __future__ import annotations

import json
import os
import re
import threading
from pathlib import Path

HERE = Path(__file__).parent
BUILTIN_MATERIALS = HERE / "materials.json"
BUILTIN_BLADES = HERE / "blades.json"

BLADE_TYPES = ("auto", "ratchet", "pen")
_lock = threading.Lock()


def user_file() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "CameoCut" / "presets.json"


def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _read_user() -> dict:
    d = _read_json(user_file(), {})
    return {"materials": list(d.get("materials", [])), "blades": list(d.get("blades", []))}


def _write_user(d: dict) -> None:
    p = user_file()
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, p)


def slugify(name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return s or "preset"


def _merge(builtin: list[dict], user: list[dict]) -> list[dict]:
    out = {b["id"]: dict(b, custom=False) for b in builtin}
    for u in user:
        out[u["id"]] = dict(u, custom=True)
    return list(out.values())


# --- cuchillas ---------------------------------------------------------------
def list_blades() -> list[dict]:
    return _merge(_read_json(BUILTIN_BLADES, []), _read_user()["blades"])


def find_blade(blade_id: str | None) -> dict | None:
    for b in list_blades():
        if b["id"] == blade_id:
            return b
    return None


def validate_blade(b: dict) -> dict:
    name = str(b.get("name", "")).strip()
    if not name:
        raise ValueError("La cuchilla necesita un nombre")
    btype = str(b.get("type", "ratchet"))
    if btype not in BLADE_TYPES:
        raise ValueError(f"Tipo de cuchilla inválido: {btype}")
    offset = float(b.get("offset_mm", 0.0))
    if not (0.0 <= offset <= 2.0):
        raise ValueError("offset_mm debe estar entre 0 y 2 mm")
    tool = int(b.get("tool", 1))
    if tool not in (1, 2):
        raise ValueError("tool debe ser 1 o 2")
    return {"id": str(b.get("id") or slugify(name)), "name": name, "type": btype,
            "offset_mm": offset, "tool": tool}


def save_blade(b: dict) -> dict:
    clean = validate_blade(b)
    with _lock:
        d = _read_user()
        d["blades"] = [x for x in d["blades"] if x["id"] != clean["id"]] + [clean]
        _write_user(d)
    return dict(clean, custom=True)


def delete_blade(blade_id: str) -> bool:
    with _lock:
        d = _read_user()
        before = len(d["blades"])
        d["blades"] = [x for x in d["blades"] if x["id"] != blade_id]
        if len(d["blades"]) != before:
            _write_user(d)
            return True
    return False


# --- materiales ------------------------------------------------------------------
def list_materials() -> list[dict]:
    return _merge(_read_json(BUILTIN_MATERIALS, []), _read_user()["materials"])


def find_material(material_id: str | None) -> dict:
    mats = list_materials()
    for m in mats:
        if m["id"] == material_id:
            return m
    return mats[0]


def validate_material(m: dict) -> dict:
    name = str(m.get("name", "")).strip()
    if not name:
        raise ValueError("El material necesita un nombre")

    def num(key, lo, hi, default=None, allow_none=False):
        v = m.get(key, default)
        if v in (None, ""):
            if allow_none:
                return None
            raise ValueError(f"Falta {key}")
        v = int(v)
        if not (lo <= v <= hi):
            raise ValueError(f"{key} fuera de rango {lo}-{hi}")
        return v

    blade_id = str(m.get("blade_id") or "ratchet")
    if find_blade(blade_id) is None:
        raise ValueError(f"Cuchilla desconocida: {blade_id}")
    return {
        "id": str(m.get("id") or slugify(name)),
        "name": name,
        "pressure": num("pressure", 1, 33),
        "speed": num("speed", 1, 30),
        "depth": num("depth", 1, 10, allow_none=True),
        "passes": num("passes", 1, 10, 1),
        "tool": num("tool", 1, 2, allow_none=True),   # None = el carro que diga la cuchilla
        "mat": num("mat", 0, 9, 1),
        "blade_id": blade_id,
        "return_home": bool(m.get("return_home", True)),
    }


def save_material(m: dict) -> dict:
    clean = validate_material(m)
    with _lock:
        d = _read_user()
        d["materials"] = [x for x in d["materials"] if x["id"] != clean["id"]] + [clean]
        _write_user(d)
    return dict(clean, custom=True)


def delete_material(material_id: str) -> bool:
    with _lock:
        d = _read_user()
        before = len(d["materials"])
        d["materials"] = [x for x in d["materials"] if x["id"] != material_id]
        if len(d["materials"]) != before:
            _write_user(d)
            return True
    return False
