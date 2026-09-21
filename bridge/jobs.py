"""
Orquestación de trabajos: SVG -> polilíneas -> máquina, con estado compartido
para el servidor HTTP y la CLI. Un solo trabajo a la vez.
"""
from __future__ import annotations

import subprocess
import threading
import time
import uuid
from dataclasses import dataclass, asdict

from . import presets
from .gpgl import Cameo, CutSettings, RegmarkSettings, JobAborted, MachineError
from .ordering import order_paths
from .svgpaths import svg_to_polylines, to_dict, total_length_mm, SvgGeometry
from .usbprint import DeviceNotFound, DeviceTimeout

STUDIO_EXE = "Silhouette Studio.exe"


def studio_running() -> bool:
    """Silhouette Studio abierto se queda con las respuestas de la máquina
    (comprobado: con Studio abierto ENQ/FG devuelven basura o nada)."""
    try:
        out = subprocess.run(["tasklist", "/FI", f"IMAGENAME eq {STUDIO_EXE}", "/NH"],
                             capture_output=True, text=True, timeout=5,
                             creationflags=subprocess.CREATE_NO_WINDOW).stdout
        return STUDIO_EXE.lower() in out.lower()
    except (OSError, subprocess.SubprocessError):
        return False


load_materials = presets.list_materials
find_material = presets.find_material


def build_settings(material_id: str | None, overrides: dict | None) -> CutSettings:
    """Material (+ overrides del panel) -> CutSettings.

    La cuchilla se resuelve por `blade_id` (perfil): de ahí salen tipo y offset.
    `blade`/`blade_offset` sueltos en overrides siguen funcionando (CLI)."""
    m = dict(find_material(material_id))
    m.update({k: v for k, v in (overrides or {}).items() if v is not None and v != ""})
    blade = presets.find_blade(m.get("blade_id")) or {}
    blade_type = str(m.get("blade") or blade.get("type") or "ratchet")
    offset = m.get("blade_offset")
    if offset in (None, ""):
        offset = blade.get("offset_mm")  # None -> valor por defecto del tipo
    return CutSettings(
        tool=int(m.get("tool") or blade.get("tool") or 1),
        speed=int(m.get("speed", 10)),
        pressure=int(m.get("pressure", 10)),
        depth=None if m.get("depth") in (None, "") else int(m["depth"]),
        passes=int(m.get("passes", 1)),
        blade=blade_type,
        blade_offset_mm=None if offset is None else float(offset),
        mat=int(m.get("mat", 1)),
        return_home=bool(m.get("return_home", True)),
        mat_offset_x_mm=float(m.get("mat_offset_x_mm") or 0.0),
        mat_offset_y_mm=float(m.get("mat_offset_y_mm") or 0.0),
    )


def build_regmarks(d: dict | None) -> RegmarkSettings | None:
    if not d or not d.get("enabled"):
        return None
    return RegmarkSettings(
        enabled=True,
        inset_mm=float(d.get("inset_mm", 10.0)),
        inset_bottom_mm=None if d.get("inset_bottom_mm") in (None, "") else float(d["inset_bottom_mm"]),
        thick_mm=float(d.get("thick_mm", 0.5)),
        length_mm=float(d.get("length_mm", 20.0)),
        page_w_mm=float(d.get("page_w_mm", 0.0)),
        page_h_mm=float(d.get("page_h_mm", 0.0)),
        calib_x_mm=float(d.get("calib_x_mm", 0.0)),
        calib_y_mm=float(d.get("calib_y_mm", 0.0)),
    )


def load_geometry(svg_path: str, only_stroke_color: str | None = None,
                  tolerance_mm: float = 0.1) -> SvgGeometry:
    geom = svg_to_polylines(svg_path, tolerance_mm=tolerance_mm)
    if only_stroke_color:
        want = only_stroke_color.lower()
        geom.paths = [p for p in geom.paths if (p.stroke or "").lower() == want]
        geom.bbox = _bbox(geom)
    # Descartamos geometría de las marcas de registro si vino en el SVG
    geom.paths = [p for p in geom.paths if "Regmarks" not in p.group_ids]
    geom.paths = order_paths(geom.paths)
    geom.bbox = _bbox(geom)
    return geom


def _bbox(geom: SvgGeometry):
    pts = [pt for p in geom.paths for pt in p.points]
    if not pts:
        return (0.0, 0.0, 0.0, 0.0)
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return (min(xs), min(ys), max(xs), max(ys))


def dry_run(svg_path: str, material_id=None, overrides=None, only_stroke_color=None) -> dict:
    settings = build_settings(material_id, overrides)
    geom = load_geometry(svg_path, only_stroke_color)
    d = to_dict(geom)
    d["ok"] = True
    d["stats"] = {"paths": len(geom.paths), "length_mm": round(total_length_mm(geom), 1)}
    d["settings"] = asdict(settings)
    return d


@dataclass
class JobState:
    id: str | None = None
    state: str = "idle"        # idle | running | done | error | aborted
    progress: float = 0.0
    message: str = ""
    started: float | None = None
    finished: float | None = None

    def to_dict(self):
        return asdict(self)


class JobRunner:
    """Ejecuta un trabajo en un hilo; expone estado y abort."""

    def __init__(self):
        self.state = JobState()
        self._lock = threading.Lock()
        self._abort = threading.Event()
        self._thread: threading.Thread | None = None

    @property
    def busy(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def machine_status(self) -> dict:
        """Estado de la máquina sin interferir con un trabajo en curso."""
        studio = studio_running()
        if self.busy:
            return {"connected": True, "model": "Silhouette CAMEO 4", "state": "moving",
                    "busy": True, "studio_running": studio}
        try:
            with Cameo() as cam:
                return {"connected": True, "model": cam.model, "state": cam.status(),
                        "busy": False, "studio_running": studio}
        except DeviceNotFound:
            return {"connected": False, "model": None, "state": "unknown", "busy": False,
                    "studio_running": studio}
        except (OSError, DeviceTimeout) as e:
            return {"connected": False, "model": None, "state": "unknown", "busy": False,
                    "studio_running": studio, "error": str(e)}

    def start(self, svg_path: str, material_id=None, overrides=None,
              only_stroke_color=None, print_and_cut=None) -> str:
        with self._lock:
            if self.busy:
                raise RuntimeError("Ya hay un trabajo en curso")
            if studio_running():
                raise RuntimeError("Cierra Silhouette Studio antes de cortar: "
                                   "se queda con la comunicación de la máquina")
            settings = build_settings(material_id, overrides)
            settings.validate()
            regmarks = build_regmarks(print_and_cut)
            geom = load_geometry(svg_path, only_stroke_color)
            if not geom.paths:
                raise ValueError("El SVG no contiene trazados para cortar")
            if regmarks and (regmarks.page_w_mm <= 0 or regmarks.page_h_mm <= 0):
                regmarks.page_w_mm, regmarks.page_h_mm = geom.width_mm, geom.height_mm
            job_id = uuid.uuid4().hex[:8]
            self.state = JobState(id=job_id, state="running", message="Preparando",
                                  started=time.time())
            self._abort.clear()
            self._thread = threading.Thread(
                target=self._run, args=(geom, settings, regmarks), daemon=True)
            self._thread.start()
            return job_id

    def abort(self) -> None:
        self._abort.set()

    def _run(self, geom: SvgGeometry, settings: CutSettings, regmarks):
        def progress(p, msg):
            self.state.progress, self.state.message = p, msg
        try:
            with Cameo() as cam:
                cam.run_job([p.points for p in geom.paths], geom.bbox, settings,
                            regmarks, progress, self._abort)
            self.state.state, self.state.message = "done", "Corte terminado"
        except JobAborted:
            self.state.state, self.state.message = "aborted", "Abortado por el usuario"
        except (MachineError, DeviceNotFound, DeviceTimeout, OSError, ValueError) as e:
            self.state.state, self.state.message = "error", str(e)
        except Exception as e:  # noqa: BLE001 - lo mostramos en el panel
            self.state.state, self.state.message = "error", f"{type(e).__name__}: {e}"
        finally:
            self.state.finished = time.time()
