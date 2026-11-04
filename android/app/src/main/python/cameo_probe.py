"""Prueba de los lectores dentro de la app (Chaquopy): abre un archivo de cada formato con
bridge.mobile.load —lo mismo que usará el visor— y devuelve un resumen JSON con tiempos."""
import json
import os
import platform
import shutil
import sys
import tempfile
import time

SAMPLES = ("square50.svg", "etiquetas_90x50.dxf", "circles_6x6.studio3")


def run() -> str:
    t0 = time.perf_counter()
    from bridge.mobile import load
    out = {"python": sys.version.split()[0], "machine": platform.machine(),
           "import_ms": round((time.perf_counter() - t0) * 1000)}
    base = os.path.join(os.path.dirname(__file__), "cameo_samples")
    tmp = tempfile.mkdtemp(prefix="cameo_probe_")
    try:
        for name in SAMPLES:
            t = time.perf_counter()
            r = load(os.path.join(base, name), tmp)
            out[name] = {"kind": r["kind"], "page_mm": r["page_mm"], "paths": r["stats"]["paths"],
                         "groups": len({p["group"] for p in r["paths"] if p["group"]}),
                         "warnings": r["warnings"], "ms": round((time.perf_counter() - t) * 1000)}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)    # cada toque no deja carpetas atrás
    return json.dumps(out, ensure_ascii=False)
