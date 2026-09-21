import json
import sys
import threading
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from bridge import server as srv


@pytest.fixture
def http(monkeypatch, tmp_path):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{httpd.server_port}"
    httpd.shutdown()


def _get(url):
    with urllib.request.urlopen(url, timeout=5) as r:
        return r.status, json.loads(r.read())


def test_materials_and_blades_work_without_stderr(http, monkeypatch):
    # pythonw.exe: sin consola, sys.stderr es None. Antes, el log de acceso rompía la petición.
    monkeypatch.setattr(sys, "stderr", None)
    code, mats = _get(http + "/materials")
    assert code == 200 and len(mats) > 0
    code, blades = _get(http + "/blades")
    assert code == 200 and any(b["id"] == "ratchet" for b in blades)


def test_unknown_route_is_404_json(http):
    with pytest.raises(urllib.error.HTTPError) as ei:
        _get(http + "/nada")
    assert ei.value.code == 404
    assert json.loads(ei.value.read())["ok"] is False
