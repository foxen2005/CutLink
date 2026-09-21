"""
Protocolo GPGL para Silhouette CAMEO 4.

Reimplementado desde la documentación pública de comandos (no se copia código
GPL). Unidades de la máquina: 1 mm = 20 SU. Las coordenadas van como (y, x):
la máquina trabaja en "portrait", el eje largo es el avance del papel.

Este módulo no sabe de SVG ni de HTTP: recibe polilíneas en mm con origen en la
esquina superior izquierda de la hoja/artboard y produce/ejecuta comandos.
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Iterable, Sequence

from .usbprint import UsbPrintDevice, DeviceTimeout

ESC, ENQ, EOT, ETX = b"\x1b", b"\x05", b"\x04", b"\x03"

SU_PER_MM = 20.0
CAMEO4_WIDTH_MM = 304.8
CAMEO4_LENGTH_MM = 3000.0

# Rangos válidos en Cameo 4
SPEED_RANGE = (1, 30)
PRESSURE_RANGE = (1, 33)
DEPTH_RANGE = (1, 10)
MAT_TYPES = {0: "sin mat", 1: "12x12", 2: "12x24"}

STATUS_MAP = {b"0": "ready", b"1": "moving", b"2": "unloaded"}


def mm(v: float) -> int:
    """mm -> SU (entero)."""
    return int(round(v * SU_PER_MM))


class JobAborted(Exception):
    pass


class MachineError(Exception):
    pass


BLADE_TYPES = {
    "auto": 0.0,      # AutoBlade: la máquina ajusta profundidad (TF) y compensa offset
    "ratchet": 0.25,  # cuchilla de trinquete manual: profundidad a mano, offset típico 0.25 mm
    "pen": 0.0,       # pluma/rotulador
}


@dataclass
class CutSettings:
    tool: int = 1                 # 1 = carro izquierdo (AutoBlade), 2 = derecho
    speed: int = 10               # 1-30
    pressure: int = 10            # 1-33
    depth: int | None = None      # 1-10 AutoBlade; None = no tocar
    passes: int = 1
    blade: str = "auto"           # ver BLADE_TYPES
    blade_offset_mm: float | None = None  # None = valor por defecto del tipo de cuchilla
    mat: int = 1                  # ver MAT_TYPES
    lift: bool = False            # levantar herramienta entre trazos (papel grueso)
    sharpen_corners: bool = False
    return_home: bool = True      # al terminar, devolver el papel/carro al origen
    # Con mat, la hoja no está en el origen de la máquina sino desplazada sobre la
    # cuadrícula del mat: se suma a todas las coordenadas (y al inicio de la
    # búsqueda de marcas en print & cut). Sin mat debe ser 0.
    mat_offset_x_mm: float = 0.0
    mat_offset_y_mm: float = 0.0

    def __post_init__(self):
        if self.blade_offset_mm is None:
            self.blade_offset_mm = BLADE_TYPES.get(self.blade, 0.0)
        # Solo la AutoBlade entiende TF; con trinquete/pluma sería un comando sin sentido
        if self.blade != "auto":
            self.depth = None

    def validate(self) -> None:
        if self.blade not in BLADE_TYPES:
            raise ValueError(f"blade debe ser uno de {list(BLADE_TYPES)}: {self.blade}")
        def chk(name, v, rng):
            if not (rng[0] <= v <= rng[1]):
                raise ValueError(f"{name} fuera de rango {rng}: {v}")
        chk("speed", self.speed, SPEED_RANGE)
        chk("pressure", self.pressure, PRESSURE_RANGE)
        if self.depth is not None:
            chk("depth", self.depth, DEPTH_RANGE)
        if self.tool not in (1, 2):
            raise ValueError(f"tool debe ser 1 o 2: {self.tool}")
        if self.mat not in MAT_TYPES:
            raise ValueError(f"mat inválido: {self.mat}")
        if self.passes < 1:
            raise ValueError("passes debe ser >= 1")


@dataclass
class RegmarkSettings:
    """Print & Cut. Distancias en mm; height/width = distancia entre marcas
    (sin contar el grosor), top/left = inset desde el borde de la hoja."""
    enabled: bool = False
    manual: bool = False          # True: el usuario ya puso la cuchilla sobre el cuadrado (TB23)
    inset_mm: float = 10.0
    inset_bottom_mm: float | None = None   # sin mat la marca inferior va a 25 mm; None = igual que inset
    thick_mm: float = 0.5         # grosor de las líneas de las marcas (TB53)
    length_mm: float = 20.0       # largo del brazo de las L (TB51)
    page_w_mm: float = 0.0
    page_h_mm: float = 0.0
    calib_x_mm: float = 0.0
    calib_y_mm: float = 0.0

    @property
    def bottom_mm(self) -> float:
        return self.inset_mm if self.inset_bottom_mm is None else self.inset_bottom_mm

    @property
    def height_mm(self) -> float:
        return self.page_h_mm - self.inset_mm - self.bottom_mm

    @property
    def width_mm(self) -> float:
        return self.page_w_mm - 2 * self.inset_mm


Polyline = Sequence[tuple[float, float]]


# --------------------------------------------------------------------------
# Construcción de comandos (puro, testeable sin máquina)
# --------------------------------------------------------------------------
def setup_commands(s: CutSettings) -> list[str]:
    t = s.tool
    cmds = [
        f"TG{s.mat}",
        "FN0", "TB50,0",
        f"J{t}",
        f"FX{s.pressure},{t}",
        "TJ0",
        f"!{s.speed},{t}",
        f"FC0,1,{t}",
        f"FE{1 if s.lift else 0},{t}",
        f"FF{int(s.sharpen_corners)},{int(s.sharpen_corners)},{t}",
        f"FX{s.pressure},{t}",
        "TJ3",
        f"FC{mm(s.blade_offset_mm)},1,{t}",
    ]
    if s.depth is not None:
        cmds.append(f"TF{s.depth},{t}")
    return cmds


def regmark_commands(r: RegmarkSettings) -> list[str]:
    """Secuencia previa a TB123. TB123 se envía aparte porque hay que leer la
    respuesta (la máquina escanea las marcas y puede tardar varios segundos)."""
    return ["TB50,0", "TB99", "TB52,2", f"TB51,{mm(r.length_mm)}", f"TB53,{mm(r.thick_mm)}", "TB55,1"]


def regmark_search_command(r: RegmarkSettings, mat_offset_mm=(0.0, 0.0)) -> str:
    """Automático: TB123,alto,ancho,top,left — top/left = dónde empezar a buscar
    la marca superior izquierda respecto al origen de la máquina.
    Manual: TB23,alto,ancho — la máquina parte de la posición actual del carro
    (el usuario dejó la cuchilla sobre el cuadrado)."""
    if r.manual:
        return f"TB23,{mm(r.height_mm)},{mm(r.width_mm)}"
    top = r.inset_mm + mat_offset_mm[1]
    left = r.inset_mm + mat_offset_mm[0]
    return f"TB123,{mm(r.height_mm)},{mm(r.width_mm)},{mm(top)},{mm(left)}"


def plot_commands(paths: Iterable[Polyline], bbox_mm: tuple[float, float, float, float],
                  offset_mm: tuple[float, float] = (0.0, 0.0),
                  passes: int = 1) -> list[str]:
    """Genera clip + M/D para todas las polilíneas.

    offset_mm se resta a cada punto (para print&cut, el origen pasa a ser la
    marca de registro superior izquierda).
    """
    ox, oy = offset_mm
    minx, miny, maxx, maxy = bbox_mm
    cmds = [
        f"\\{mm(miny - oy)},{mm(minx - ox)}",
        f"Z{mm(maxy - oy)},{mm(maxx - ox)}",
        "L0", "FE0,0", "FF0,0,0",
    ]
    paths = list(paths)
    for _ in range(passes):
        for pts in paths:
            if len(pts) < 2:
                continue
            x0, y0 = pts[0]
            cmds.append(f"M{mm(y0 - oy)},{mm(x0 - ox)}")
            for x, y in pts[1:]:
                cmds.append(f"D{mm(y - oy)},{mm(x - ox)}")
    return cmds


def end_commands(bbox_mm: tuple[float, float, float, float], offset_mm=(0.0, 0.0),
                 end_paper_offset_mm: float = 10.0, return_home: bool = True) -> list[str]:
    """Fin de trabajo. Con return_home el papel vuelve al origen; si no, queda
    avanzado 10 mm más allá de lo cortado (para retirar la pieza)."""
    _, _, _, maxy = bbox_mm
    park = "M0,0" if return_home else f"M{mm(maxy - offset_mm[1] + end_paper_offset_mm)},0"
    return [park, "SO0", "FN0"]


# --------------------------------------------------------------------------
# Ejecución contra la máquina
# --------------------------------------------------------------------------
class Cameo:
    """Sesión con la máquina. Un solo trabajo a la vez."""

    def __init__(self, path: str | None = None):
        self.dev = UsbPrintDevice(path)
        self.model = self.dev.model

    def close(self):
        self.dev.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # -- bajo nivel --------------------------------------------------------
    def send(self, cmd: str) -> None:
        self.dev.write(cmd.encode("ascii") + ETX)

    def send_raw(self, data: bytes) -> None:
        self.dev.write(data)

    def read_reply(self, timeout_ms: int = 3000) -> bytes:
        """Lee hasta ETX (la máquina puede fragmentar)."""
        buf = b""
        deadline = time.monotonic() + timeout_ms / 1000
        while True:
            remaining = int((deadline - time.monotonic()) * 1000)
            if remaining <= 0:
                raise DeviceTimeout("sin respuesta de la máquina")
            buf += self.dev.read(timeout_ms=remaining)
            if buf.endswith(ETX):
                return buf[:-1]

    def query(self, cmd: str, timeout_ms: int = 3000) -> bytes:
        self.send(cmd)
        return self.read_reply(timeout_ms)

    def drain(self, timeout_ms: int = 150) -> bytes:
        """Vacía respuestas pendientes (la máquina encola las respuestas a
        consultas anteriores y desalinearía la siguiente lectura)."""
        buf = b""
        try:
            while True:
                buf += self.dev.read(timeout_ms=timeout_ms)
        except DeviceTimeout:
            pass
        return buf

    # -- consultas -----------------------------------------------------------
    def status(self) -> str:
        self.drain()
        self.send_raw(ESC + ENQ)
        try:
            r = self.read_reply(2000).strip()
        except DeviceTimeout:
            return "unknown"
        return STATUS_MAP.get(r, f"raw:{r!r}")

    def firmware(self) -> str:
        try:
            return self.query("FG", 5000).decode("ascii", "replace").strip()
        except DeviceTimeout:
            return "?"

    def initialize(self) -> None:
        self.drain()
        self.send_raw(ESC + EOT)
        self.firmware()          # la máquina espera esta consulta tras el init
        for q in ("TB71", "FA", "TC"):
            try:
                self.query(q, 2000)
            except DeviceTimeout:
                pass

    def wait_for_ready(self, timeout_s: float = 30, abort: threading.Event | None = None) -> str:
        t0 = time.monotonic()
        while True:
            st = self.status()
            if st == "ready":
                return st
            if abort is not None and abort.is_set():
                raise JobAborted()
            if time.monotonic() - t0 > timeout_s:
                return st
            time.sleep(0.5)

    def jog(self, dx_mm: float, dy_mm: float) -> None:
        """Mueve el carro/papel en relativo (herramienta arriba). Sirve para
        posicionar la cuchilla sobre la marca de registro en modo manual."""
        self.send("FN0")
        self.send(f"O{mm(dy_mm)},{mm(dx_mm)}")

    # -- trabajo completo ------------------------------------------------------
    def run_job(self, paths: list[Polyline], bbox_mm, settings: CutSettings,
                regmarks: RegmarkSettings | None = None,
                progress: Callable[[float, str], None] | None = None,
                abort: threading.Event | None = None) -> None:
        settings.validate()
        report = progress or (lambda p, m: None)

        def check_abort():
            if abort is not None and abort.is_set():
                # Levanta la herramienta y vuelve al origen antes de salir
                for c in ("SO0", "FN0"):
                    self.send(c)
                raise JobAborted()

        report(0.0, "Inicializando")
        self.initialize()
        st = self.status()
        if st == "unloaded":
            raise MachineError("No hay material cargado en la máquina")
        if st != "ready":
            st = self.wait_for_ready(20, abort)
            if st != "ready":
                raise MachineError(f"Máquina no lista: {st}")

        for c in setup_commands(settings):
            self.send(c)
        check_abort()

        mat_off = (settings.mat_offset_x_mm, settings.mat_offset_y_mm) if settings.mat else (0.0, 0.0)
        # offset se RESTA a las coordenadas: con mat la hoja está más lejos del origen
        offset = (-mat_off[0], -mat_off[1])
        if regmarks and regmarks.enabled:
            report(0.05, "Buscando marcas de registro")
            for c in regmark_commands(regmarks):
                self.send(c)
            resp = self.query(regmark_search_command(regmarks, mat_off), 40000)
            if resp.strip() != b"0":
                raise MachineError(f"No se encontraron las marcas de registro (resp={resp!r})")
            # Origen pasa a ser la marca superior izquierda (+ calibración)
            offset = (regmarks.inset_mm + regmarks.calib_x_mm,
                      regmarks.inset_mm + regmarks.calib_y_mm)
            check_abort()

        cmds = plot_commands(paths, bbox_mm, offset, settings.passes)
        total = len(cmds)
        report(0.1, f"Cortando ({total} comandos)")
        # Enviamos en bloques para no saturar y poder abortar entre bloques
        block: list[str] = []
        for i, c in enumerate(cmds):
            block.append(c)
            if len(block) >= 64 or i == total - 1:
                self.send_raw(ETX.join(b.encode("ascii") for b in block) + ETX)
                block = []
                check_abort()
                report(0.1 + 0.85 * (i + 1) / total, f"Cortando {i + 1}/{total}")
        for c in end_commands(bbox_mm, offset, return_home=settings.return_home):
            self.send(c)
        # Esperar a que termine de moverse
        report(0.97, "Terminando")
        self.wait_for_ready(120, abort)
        report(1.0, "Listo")
