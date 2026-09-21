# Cameo Cut — panel CEP para Adobe Illustrator

Panel de Illustrator (CEP 9–12) que envía trabajos de corte a una Silhouette CAMEO 4 a través del bridge HTTP local en Python (`http://127.0.0.1:7474`, ver `../bridge/`).

Probado contra: Illustrator 2025 (v29.x), CEP 12, Windows 11.

## Estructura

```
panel/
  CSXS/manifest.xml   Manifiesto CEP (bundle com.digitalfox.cameocut, extensión com.digitalfox.cameocut.panel)
  index.html          UI del panel (español)
  css/style.css       Tema oscuro tipo Illustrator (se adapta a appSkinInfo)
  js/main.js          Lógica del panel: bridge HTTP, evalScript a host.jsx, Node para lanzar el bridge
  jsx/host.jsx        ExtendScript (ES3) que corre dentro de Illustrator
  lib/CSInterface.js  Librería oficial de Adobe (CEP 12.x)
  .debug              Habilita Chrome remote debugging en el puerto 8092
```

## Requisitos

1. **PlayerDebugMode = 1** para cargar el panel sin firmar (ya activo en este PC para CSXS.9–12). Si hace falta reactivarlo:
   ```powershell
   foreach ($v in 9..12) { New-Item -Path "HKCU:\Software\Adobe\CSXS.$v" -Force | Out-Null; Set-ItemProperty -Path "HKCU:\Software\Adobe\CSXS.$v" -Name PlayerDebugMode -Value "1" -Type String }
   ```
2. Python 3 en el PATH (o configurar la ruta al ejecutable en **Ajustes** del panel).
3. El bridge en `D:\SandBox\cameo-illustrator\bridge\` (se lanza con `python -m bridge.server` desde la raíz del proyecto).

## Instalación

Opción A — script (desde la raíz del proyecto, no requiere admin si el Developer Mode de Windows está activo; si no, hace copia en vez de symlink):

```powershell
powershell -ExecutionPolicy Bypass -File D:\SandBox\cameo-illustrator\install.ps1
# Forzar copia en vez de symlink:
powershell -ExecutionPolicy Bypass -File D:\SandBox\cameo-illustrator\install.ps1 -Copy
```

Opción B — manual:

```powershell
$dst = "$env:APPDATA\Adobe\CEP\extensions\com.digitalfox.cameocut"
New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
# symlink (recomendado: los cambios en panel/ se reflejan al reabrir el panel)
New-Item -ItemType SymbolicLink -Path $dst -Target "D:\SandBox\cameo-illustrator\panel"
# ...o copia
# Copy-Item -Recurse -Force "D:\SandBox\cameo-illustrator\panel" $dst
```

Luego reiniciar Illustrator.

## Abrir el panel

Illustrator → **Ventana > Extensiones > Cameo Cut**.

Si no aparece en el menú:
- Verificar que existe `%APPDATA%\Adobe\CEP\extensions\com.digitalfox.cameocut\CSXS\manifest.xml`.
- Verificar `PlayerDebugMode` (arriba).
- Ver el log de CEP en `%TEMP%\csxs12-ILST.log` (o `csxs11-…`, `csxs9-…`).

## Uso rápido

1. **Iniciar bridge** (o lanzarlo a mano: `cd D:\SandBox\cameo-illustrator && python -m bridge.server`). El punto de estado pasa a verde cuando la Cameo está lista.
2. **Qué cortar**: selección / capa / todo el documento. "Actualizar" relee el documento activo. Opcional: "Solo trazos de color" filtra por color de trazo de la selección.
3. **Material**: elegir de la lista del bridge; los campos se rellenan y se pueden sobreescribir (se envían como `overrides`).
4. **Print & Cut** (opcional): activar, ajustar inset, "Agregar marcas al documento" (crea capa bloqueada `Regmarks`), imprimir, luego cortar. La capa `Regmarks` se excluye del export en modo "Todo el documento".
5. **Vista previa**: exporta el SVG temporal (`%TEMP%\cameo-cut\<timestamp>.svg`) y pide `/dry-run` al bridge; dibuja las polilíneas en el canvas.
6. **CORTAR**: pide confirmación y envía `/cut`. Mientras `job.state == running` aparece la barra de progreso y el botón "Abortar".

## Depuración

- Con el panel abierto, ir a `http://localhost:8092` en Chrome/Edge → DevTools del panel (consola JS, red, DOM). El puerto está en `.debug`.
- Para ExtendScript: los errores se devuelven como `{"ok":false,"error":"..."}` y se muestran en el área de log del panel. Para depurar más a fondo, abrir `jsx/host.jsx` en el ExtendScript Debugger de VS Code apuntando a Illustrator.
- Si `Node: NO disponible` aparece en Ajustes, revisar que `manifest.xml` tenga `--enable-nodejs` y `--mixed-context` y que el panel se haya recargado (cerrar y reabrir el panel no basta a veces: reiniciar Illustrator).
- El panel solo recarga `host.jsx` al reiniciar Illustrator (o al reabrir el panel según versión). Para forzar recarga del JSX sin reiniciar se puede ejecutar en la consola del panel:
  `csInterface.evalScript('$.evalFile("' + csInterface.getSystemPath(SystemPath.EXTENSION).replace(/\\/g,'/') + '/jsx/host.jsx")')`

## Notas de implementación

- Coordenadas: Illustrator usa origen en la esquina inferior izquierda del artboard con Y hacia arriba. El export crea un documento temporal del tamaño exacto del artboard activo y duplica los ítems ajustando por la diferencia de `artboardRect`, así el SVG sale con `viewBox="0 0 W H"` (pt) y origen arriba-izquierda.
- En el documento temporal se expanden apariencias (`expandStyle`), se contornea el texto (`createOutline`) y se sueltan máscaras de recorte (`releaseMask`) antes de exportar.
- El inset de las marcas se guarda en el `note` del cuadrado de registro (`inset_mm=10`); `cameoGetDocInfo` lo lee de ahí.
- CMYK → hex es una conversión naive sin perfil ICC (solo se usa para identificar/filtrar colores de trazo).
