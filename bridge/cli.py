"""
CLI del bridge (Opción B: cortar sin Illustrator ni Silhouette Studio).

  python -m bridge.cli status
  python -m bridge.cli materials
  python -m bridge.cli cut archivo.svg --material vinilo [--pressure 12 --speed 8 --depth 2 --passes 1 --tool 1 --mat 1] [--dry-run] [--preview out.svg]
  python -m bridge.cli gpgl archivo.svg   -> imprime los comandos que se enviarían
"""
from __future__ import annotations

import argparse
import sys
import time

from .gpgl import Cameo, setup_commands, plot_commands, end_commands
from .jobs import JobRunner, build_settings, dry_run, load_geometry, load_materials


def _overrides(a) -> dict:
    return {k: getattr(a, k) for k in ("pressure", "speed", "depth", "passes", "tool", "mat")
            if getattr(a, k, None) is not None}


def write_preview_svg(geom_dict: dict, out: str) -> None:
    """SVG simple con las polilíneas resultantes (mm) para revisar antes de cortar."""
    w, h = geom_dict["width_mm"], geom_dict["height_mm"]
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}mm" height="{h}mm" '
             f'viewBox="0 0 {w} {h}"><rect width="{w}" height="{h}" fill="white" stroke="#ccc" stroke-width="0.2"/>']
    for p in geom_dict["paths"]:
        pts = " ".join(f"{x:.3f},{y:.3f}" for x, y in p["points"])
        tag = "polygon" if p["closed"] else "polyline"
        parts.append(f'<{tag} points="{pts}" fill="none" stroke="#e00" stroke-width="0.2"/>')
    parts.append("</svg>")
    with open(out, "w", encoding="utf-8") as f:
        f.write("".join(parts))


def main(argv=None):
    ap = argparse.ArgumentParser(prog="cameo")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    sub.add_parser("materials")

    for name in ("cut", "gpgl"):
        p = sub.add_parser(name)
        p.add_argument("svg")
        p.add_argument("--material", default=None)
        p.add_argument("--pressure", type=int)
        p.add_argument("--speed", type=int)
        p.add_argument("--depth", type=int)
        p.add_argument("--passes", type=int)
        p.add_argument("--tool", type=int)
        p.add_argument("--mat", type=int)
        p.add_argument("--color", default=None, help="solo trazos con este color #rrggbb")
        if name == "cut":
            p.add_argument("--dry-run", action="store_true")
            p.add_argument("--preview", default=None, help="escribe un SVG de vista previa")
            p.add_argument("--yes", action="store_true", help="no pedir confirmación")

    a = ap.parse_args(argv)

    if a.cmd == "status":
        print(JobRunner().machine_status())
        return
    if a.cmd == "materials":
        for m in load_materials():
            tag = "*" if m.get("custom") else " "
            print(f"{tag}{m['id']:14} {m['name']:40} P{m['pressure']:>2} V{m['speed']:>2} "
                  f"D{m['depth']} x{m['passes']} cuchilla={m.get('blade_id')} tool{m['tool']}")
        return

    d = dry_run(a.svg, a.material, _overrides(a), a.color)
    print(f"Hoja {d['width_mm']:.1f} x {d['height_mm']:.1f} mm | {d['stats']['paths']} trazos | "
          f"{d['stats']['length_mm']} mm de corte | bbox {tuple(round(v, 1) for v in d['bbox'])}")
    print("Ajustes:", d["settings"])

    if a.cmd == "gpgl":
        s = build_settings(a.material, _overrides(a))
        geom = load_geometry(a.svg, a.color)
        for c in setup_commands(s) + plot_commands([p.points for p in geom.paths], geom.bbox, passes=s.passes) + end_commands(geom.bbox):
            print(c)
        return

    if a.preview:
        write_preview_svg(d, a.preview)
        print("Vista previa:", a.preview)
    if a.dry_run:
        return
    if not a.yes:
        if input("¿Cortar ahora? [s/N] ").strip().lower() not in ("s", "si", "sí", "y"):
            return

    runner = JobRunner()
    runner.start(a.svg, a.material, _overrides(a), a.color)
    last = ""
    while runner.busy:
        msg = f"{runner.state.progress * 100:5.1f}% {runner.state.message}"
        if msg != last:
            print(msg)
            last = msg
        time.sleep(0.3)
    print(runner.state.state.upper(), runner.state.message)
    sys.exit(0 if runner.state.state == "done" else 1)


if __name__ == "__main__":
    main()
