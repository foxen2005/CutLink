"""
Servidor HTTP local para el panel de Illustrator.

  python -m bridge.server [--port 7474]

Endpoints (JSON):
  GET  /status     estado de conexión y de la máquina
  GET  /materials  presets
  POST /dry-run    {svg_path, material_id, overrides, only_stroke_color}
  POST /cut        {svg_path, material_id, overrides, only_stroke_color, print_and_cut}
  GET  /job        progreso del trabajo actual
  POST /abort
"""
from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .jobs import JobRunner, dry_run, load_materials

runner = JobRunner()


class Handler(BaseHTTPRequestHandler):
    server_version = "cameo-bridge/0.1"

    # -- utilidades ---------------------------------------------------------
    def _json(self, code: int, payload) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n == 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    def log_message(self, fmt, *args):  # menos ruido en consola
        if "/status" in (args[0] if args else "") or "/job" in (args[0] if args else ""):
            return
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # -- rutas -----------------------------------------------------------------
    def do_OPTIONS(self):
        self._json(204, {})

    def do_GET(self):
        if self.path == "/status":
            return self._json(200, runner.machine_status())
        if self.path == "/materials":
            return self._json(200, load_materials())
        if self.path == "/job":
            return self._json(200, runner.state.to_dict())
        self._json(404, {"ok": False, "error": "ruta desconocida"})

    def do_POST(self):
        try:
            data = self._body()
        except (ValueError, UnicodeDecodeError) as e:
            return self._json(400, {"ok": False, "error": f"JSON inválido: {e}"})
        try:
            if self.path == "/dry-run":
                return self._json(200, dry_run(
                    data["svg_path"], data.get("material_id"),
                    data.get("overrides"), data.get("only_stroke_color")))
            if self.path == "/cut":
                job_id = runner.start(
                    data["svg_path"], data.get("material_id"), data.get("overrides"),
                    data.get("only_stroke_color"), data.get("print_and_cut"))
                return self._json(200, {"ok": True, "job_id": job_id})
            if self.path == "/abort":
                runner.abort()
                return self._json(200, {"ok": True})
            self._json(404, {"ok": False, "error": "ruta desconocida"})
        except KeyError as e:
            self._json(400, {"ok": False, "error": f"falta el campo {e}"})
        except (ValueError, RuntimeError, FileNotFoundError) as e:
            self._json(400, {"ok": False, "error": str(e)})
        except Exception as e:  # noqa: BLE001
            self._json(500, {"ok": False, "error": f"{type(e).__name__}: {e}"})


def main(argv=None):
    ap = argparse.ArgumentParser(description="cameo-bridge HTTP server")
    ap.add_argument("--port", type=int, default=7474)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args(argv)
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"cameo-bridge escuchando en http://{args.host}:{args.port}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
