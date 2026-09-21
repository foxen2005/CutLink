"""Pone la raíz del proyecto en sys.path para poder importar `bridge.*` desde cualquier cwd."""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
