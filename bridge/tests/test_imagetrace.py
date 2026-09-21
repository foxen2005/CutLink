import base64, io
import numpy as np
from PIL import Image

from bridge.imagetrace import trace_images, rect_images


def _png_circle_b64(size=200, r=80):
    yy, xx = np.mgrid[:size, :size]
    m = ((xx - size / 2) ** 2 + (yy - size / 2) ** 2) <= r * r
    img = np.zeros((size, size, 4), np.uint8)
    img[m] = (200, 30, 30, 255)
    buf = io.BytesIO()
    Image.fromarray(img, "RGBA").save(buf, "PNG")
    return base64.b64encode(buf.getvalue()).decode()


def _svg(tmp_path, extra=""):
    # imagen de 200 px colocada a escala 0.5 en (100,50) px de hoja -> 100x100 px = 35.28 mm
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
           f'viewBox="0 0 400 400"><image width="200" height="200" transform="translate(100 50) scale(0.5)" {extra} '
           f'xlink:href="data:image/png;base64,{_png_circle_b64()}"/></svg>')
    p = tmp_path / "t.svg"
    p.write_text(svg, encoding="utf-8")
    return str(p)


def test_trace_alpha_circle_position_and_size(tmp_path):
    pl = trace_images(_svg(tmp_path), margin_mm=0)
    assert len(pl) == 1 and pl[0].closed
    xs = [p[0] for p in pl[0].points]; ys = [p[1] for p in pl[0].points]
    mm = 25.4 / 72
    # círculo de radio 80 px de imagen * 0.5 = 40 px de hoja = 14.1 mm, centro (150,150) px = (52.9,52.9) mm
    assert abs((max(xs) - min(xs)) - 80 * mm) < 1.0
    assert abs((min(xs) + max(xs)) / 2 - 150 * mm) < 0.5
    assert abs((min(ys) + max(ys)) / 2 - 100 * mm) < 0.5   # y: 50 + 100*0.5


def test_trace_margin_grows_outline(tmp_path):
    a = trace_images(_svg(tmp_path), margin_mm=0)[0]
    b = trace_images(_svg(tmp_path), margin_mm=2)[0]
    w = lambda pl: max(p[0] for p in pl.points) - min(p[0] for p in pl.points)
    assert abs((w(b) - w(a)) - 4) < 0.6


def test_rect_images_follows_transform(tmp_path):
    r = rect_images(_svg(tmp_path))[0]
    mm = 25.4 / 72
    xs = [p[0] for p in r.points]; ys = [p[1] for p in r.points]
    assert abs(min(xs) - 100 * mm) < 0.01 and abs(max(xs) - 200 * mm) < 0.01
    assert abs(min(ys) - 50 * mm) < 0.01 and abs(max(ys) - 150 * mm) < 0.01
