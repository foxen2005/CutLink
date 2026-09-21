"""
Auto-trazado de siluetas de imágenes colocadas ("como el plugin de GIMP", pero
en el bridge): para cada <image> del SVG exportado por Illustrator se lee el
archivo original, se obtiene la silueta (canal alfa si lo hay; si no, umbral de
blanco) y se convierte en una polilínea en mm respetando posición, escala y
rotación de la imagen en la hoja. Opcionalmente se aplica un margen (sangrado).

Requiere opencv-python-headless + numpy + pillow (ver requirements.txt).
"""
from __future__ import annotations

import base64
import io
import os
import re
from urllib.parse import unquote, urlparse

import cv2
import numpy as np
import svgelements as se
from PIL import Image as PILImage

from .svgpaths import Polyline, SvgGeometry, _bbox as _geom_bbox  # noqa: F401  (reutilizamos bbox)

MM_PER_PX = 25.4 / 72.0
MAX_SIDE_PX = 1500          # rasterizamos la máscara a este tamaño máximo (rapidez)


def _load_image(url: str, base_dir: str | None) -> PILImage.Image | None:
    if not url:
        return None
    if url.startswith("data:"):
        m = re.match(r"data:[^;]+;base64,(.*)", url, re.S)
        if not m:
            return None
        return PILImage.open(io.BytesIO(base64.b64decode(m.group(1))))
    path = url
    if url.startswith("file:"):
        p = urlparse(url)
        path = unquote(p.path)
        if re.match(r"^/[A-Za-z]:", path):
            path = path[1:]
    path = unquote(path)
    if not os.path.isabs(path) and base_dir:
        path = os.path.join(base_dir, path)
    if not os.path.exists(path):
        return None
    return PILImage.open(path)


def _mask(img: PILImage.Image, white_threshold: int = 240) -> np.ndarray:
    """Máscara binaria (uint8 0/255) de lo que "es sticker"."""
    img.load()
    w, h = img.size
    scale = min(1.0, MAX_SIDE_PX / max(w, h))
    if scale < 1.0:
        img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), PILImage.BILINEAR)
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        alpha = np.array(img.convert("RGBA"))[:, :, 3]
        mask = (alpha > 16).astype(np.uint8) * 255
        # Si el PNG es opaco por completo, el alfa no sirve: caemos al umbral de blanco
        if mask.min() == 255:
            mask = None
    else:
        mask = None
    if mask is None:
        gray = np.array(img.convert("L"))
        mask = (gray < white_threshold).astype(np.uint8) * 255
    # Cerrar huecos pequeños y quedarnos con la forma sólida
    k = np.ones((3, 3), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, k, iterations=2)
    return mask


def _contours_px(mask: np.ndarray, margin_px: float, simplify_px: float) -> list[np.ndarray]:
    if margin_px > 0:
        r = int(round(margin_px))
        if r > 0:
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
            mask = cv2.dilate(mask, kernel)
    # Rellenar huecos interiores: solo nos interesa la silueta exterior
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    out = []
    for c in contours:
        if cv2.contourArea(c) < 25:      # ruido
            continue
        c = cv2.approxPolyDP(c, simplify_px, True)
        if len(c) >= 3:
            out.append(c.reshape(-1, 2).astype(float))
    return out


def trace_images(svg_path: str, margin_mm: float = 0.0, tolerance_mm: float = 0.1,
                 white_threshold: int = 240, warnings: list[str] | None = None) -> list[Polyline]:
    """Polilíneas (mm, origen sup. izq. del viewBox) de las siluetas de todas las
    imágenes del SVG. Las imágenes cuyo archivo no se encuentra se reportan en
    `warnings` (no se ocultan: el usuario vería 0 trazos sin saber por qué)."""
    svg = se.SVG.parse(svg_path, ppi=72, reify=True)
    base_dir = os.path.dirname(os.path.abspath(svg_path))
    result: list[Polyline] = []
    for idx, el in enumerate(svg.elements()):
        if not isinstance(el, se.Image):
            continue
        img = _load_image(el.url, base_dir)
        if img is None:
            if warnings is not None:
                warnings.append(f"Imagen no encontrada, sin silueta: {(el.url or '?')[:120]}")
            continue
        mask = _mask(img, white_threshold)
        mh, mw = mask.shape
        # px de máscara -> unidades de usuario del <image> (width/height del elemento)
        sx = float(el.width) / mw
        sy = float(el.height) / mh
        m = el.transform  # Matrix a b c d e f: unidades del elemento -> unidades del viewBox (px a 72 ppi)
        # Escala real de un px de máscara sobre la hoja, para expresar el margen en px de máscara
        unit_scale = ((m.a ** 2 + m.b ** 2) ** 0.5) * sx * MM_PER_PX   # mm por px de máscara (eje x)
        margin_px = (margin_mm / unit_scale) if unit_scale > 0 else 0.0
        simplify_px = (tolerance_mm / unit_scale) if unit_scale > 0 else 1.0
        for c in _contours_px(mask, margin_px, simplify_px):
            pts = []
            for px, py in c:
                ux, uy = float(el.x) + px * sx, float(el.y) + py * sy
                X = m.a * ux + m.c * uy + m.e
                Y = m.b * ux + m.d * uy + m.f
                pts.append((X * MM_PER_PX, Y * MM_PER_PX))
            pts.append(pts[0])
            result.append(Polyline(points=pts, closed=True, stroke="#ff00ff", fill=None,
                                   id=f"image_{idx}", group_ids=["auto_trace"]))
    return result


def rect_images(svg_path: str, margin_mm: float = 0.0) -> list[Polyline]:
    """Rectángulo exacto de cada imagen (respeta rotación: son sus 4 esquinas
    transformadas). El margen se aplica en unidades de la imagen."""
    svg = se.SVG.parse(svg_path, ppi=72, reify=True)
    result: list[Polyline] = []
    for idx, el in enumerate(svg.elements()):
        if not isinstance(el, se.Image):
            continue
        m = el.transform
        unit_scale = ((m.a ** 2 + m.b ** 2) ** 0.5) * MM_PER_PX   # mm por unidad del elemento
        mg = (margin_mm / unit_scale) if unit_scale > 0 else 0.0
        x0, y0 = float(el.x) - mg, float(el.y) - mg
        x1, y1 = float(el.x) + float(el.width) + mg, float(el.y) + float(el.height) + mg
        pts = []
        for ux, uy in ((x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0)):
            X = m.a * ux + m.c * uy + m.e
            Y = m.b * ux + m.d * uy + m.f
            pts.append((X * MM_PER_PX, Y * MM_PER_PX))
        result.append(Polyline(points=pts, closed=True, stroke="#ff00ff", fill=None,
                               id=f"image_{idx}", group_ids=["auto_rect"]))
    return result


def add_image_outlines(geom: SvgGeometry, svg_path: str, mode: str = "ignore",
                       margin_mm: float = 0.0, tolerance_mm: float = 0.1) -> list[str]:
    """mode: 'ignore' | 'rect' | 'trace'. Devuelve avisos (imágenes no resueltas)."""
    warnings: list[str] = []
    if mode == "trace":
        geom.paths = geom.paths + trace_images(svg_path, margin_mm, tolerance_mm, warnings=warnings)
    elif mode == "rect":
        geom.paths = geom.paths + rect_images(svg_path, margin_mm)
    geom.bbox = _geom_bbox(geom.paths)
    return warnings
