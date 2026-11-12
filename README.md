# CutLink

Imprimir y cortar sin Silhouette Studio. Hoy maneja la Silhouette CAMEO 4; la idea es sumar
más máquinas. Tres piezas:

- `android/` — **CutLink**, app para tablet Android: abre SVG, DXF y .studio3, acomoda,
  imprime y corta por Bluetooth (también Print & Cut). Ver `DESIGN.md` para su estilo.

- `bridge/` — servicio Python local (`http://127.0.0.1:7474`) que habla GPGL con la máquina
  a través de `usbprint.sys` (el driver que Windows ya usa; **no** hace falta Zadig/libusb y
  Silhouette Studio sigue funcionando).
- `panel/` — **Cameo Cut**, extensión CEP de Illustrator 2025 (HTML/JS + ExtendScript) que exporta la
  geometría y llama al bridge.

## Instalación

```powershell
# 1. Entorno Python del bridge (una sola vez)
C:\Users\DigitalFox\AppData\Local\Programs\Python\Python314\python.exe -m venv .venv
.venv\Scripts\python.exe -m pip install -r bridge\requirements.txt

# 2. Panel en Illustrator (symlink a %APPDATA%\Adobe\CEP\extensions)
.\install.ps1
```

Abrir Illustrator → **Ventana > Extensiones > Cameo Cut**. El botón "Iniciar bridge" lanza el
servicio; también se puede a mano:

```powershell
.venv\Scripts\python.exe -m bridge.server
```

## CLI (sin Illustrator)

```powershell
.venv\Scripts\python.exe -m bridge.cli status
.venv\Scripts\python.exe -m bridge.cli materials
.venv\Scripts\python.exe -m bridge.cli cut diseño.svg --material vinilo --dry-run --preview prev.svg
.venv\Scripts\python.exe -m bridge.cli cut diseño.svg --material vinilo
.venv\Scripts\python.exe -m bridge.cli gpgl diseño.svg      # ver comandos GPGL
```

## Print & Cut

1. En el panel: Print & Cut → "Agregar marcas al documento" (capa bloqueada `Regmarks`:
   cuadrado 5 mm + dos L de 20 mm, a 10 mm del borde del artboard).
2. Imprimir el artboard completo (con marcas) en la hoja adhesiva.
3. Cargar la hoja en la Cameo con el cuadrado arriba a la izquierda, activar Print & Cut y **Cortar**.
   La máquina escanea las marcas y corta solo la geometría de corte.
4. Si hay desfase constante, ajustar Calibración X/Y (mm).

## Tests

```powershell
.venv\Scripts\python.exe -m pytest bridge\tests -q
```

## Referencias del protocolo
- Comandos GPGL: https://github.com/fablabnbg/inkscape-silhouette/blob/main/Commands.md
- Unidades: 1 mm = 20 SU; coordenadas `y,x`. Cameo 4: velocidad 1–30, presión 1–33, AutoBlade 1–10.
