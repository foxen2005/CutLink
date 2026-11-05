---
name: Cameo Link
description: Plancha a dos tintas para abrir, acomodar, imprimir y cortar en la Silhouette CAMEO desde una tablet Android.
# Unidades: el formato solo admite dimensiones CSS. 1px de token = 1dp en Android (1sp en fontSize).
colors:
  cyan: "#00B8D9"
  mag: "#E0218A"
  mag-solid: "#C4176F"
  mag-ink: "#AD0D67"
  mag-12: "#FCE6F2"
  ink: "#001876"
  ink-2: "#47588F"
  ink-3: "#8E9AC0"
  paper: "#FAFCFD"
  sheet: "#FFFFFF"
  rule: "#00187624"
  cyan-12: "#E1F6FA"
  cyan-20: "#CCF1F7"
  cyan-35: "#A6E7F2"
  trama-dot: "#AEE9F3"
  mat: "#F1F9FB"
  mat-grid: "#00187621"
  frame: "#00187673"
  veil: "#00187657"
  veil-paper: "#A5AECF"
  printed-mark: "#111111"
typography:
  display:
    fontFamily: "Barlow"
    fontSize: "26px"
    fontWeight: 700
  headline:
    fontFamily: "Barlow"
    fontSize: "24px"
    fontWeight: 700
  dialog-title:
    fontFamily: "Barlow"
    fontSize: "22px"
    fontWeight: 700
  title:
    fontFamily: "Barlow"
    fontSize: "20px"
    fontWeight: 700
  data-value:
    fontFamily: "Barlow"
    fontSize: "20px"
    fontWeight: 700
    fontFeature: "tnum"
  action:
    fontFamily: "Barlow"
    fontSize: "17px"
    fontWeight: 700
  section:
    fontFamily: "Barlow"
    fontSize: "16px"
    fontWeight: 700
  control:
    fontFamily: "Barlow"
    fontSize: "15px"
    fontWeight: 600
  number:
    fontFamily: "Barlow"
    fontSize: "15px"
    fontWeight: 600
    fontFeature: "tnum"
  control-small:
    fontFamily: "Barlow"
    fontSize: "14px"
    fontWeight: 600
  body:
    fontFamily: "Barlow"
    fontSize: "14px"
    fontWeight: 500
  muted-strong:
    fontFamily: "Barlow"
    fontSize: "13px"
    fontWeight: 600
  muted:
    fontFamily: "Barlow"
    fontSize: "13px"
    fontWeight: 500
  label:
    fontFamily: "Barlow"
    fontSize: "12px"
    fontWeight: 600
rounded:
  sheet: "2px"
  handle: "4px"
  progress: "5px"
  control: "10px"
  note: "12px"
  action: "14px"
  dock: "16px"
  strip: "18px"
  pill: "22px"
spacing:
  hair: "3px"
  dock: "4px"
  tight: "6px"
  gap: "8px"
  float: "10px"
  section: "14px"
  panel: "16px"
  dialog: "24px"
components:
  button-primary:
    backgroundColor: "{colors.cyan}"
    textColor: "{colors.ink}"
    typography: "{typography.section}"
    rounded: "{rounded.action}"
    padding: "0 22px"
    height: "52px"
  button-primary-disabled:
    backgroundColor: "{colors.cyan-20}"
    textColor: "{colors.ink-2}"
  button-cut:
    backgroundColor: "{colors.cyan}"
    textColor: "{colors.ink}"
    typography: "{typography.action}"
    rounded: "{rounded.action}"
    padding: "0 18px"
    height: "60px"
    width: "216px"
  button-cut-demoted:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
  button-surface:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.action}"
  button-tool:
    backgroundColor: "{colors.cyan-12}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "48px"
  button-tool-active:
    backgroundColor: "{colors.cyan-35}"
    textColor: "{colors.ink}"
  button-tool-disabled:
    textColor: "{colors.ink-3}"
  button-flat:
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 12px 0 8px"
    height: "48px"
  button-flat-active:
    backgroundColor: "{colors.cyan-20}"
    textColor: "{colors.ink}"
  button-danger:
    backgroundColor: "{colors.mag-solid}"
    textColor: "{colors.sheet}"
    typography: "{typography.section}"
    rounded: "{rounded.action}"
    height: "64px"
    width: "132px"
  cell:
    backgroundColor: "{colors.cyan-12}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    height: "54px"
  cell-danger:
    backgroundColor: "{colors.mag-12}"
    textColor: "{colors.mag-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    height: "54px"
  field:
    backgroundColor: "{colors.cyan-12}"
    textColor: "{colors.ink}"
    typography: "{typography.number}"
    rounded: "{rounded.control}"
    padding: "0 6px 0 10px"
    height: "48px"
  field-focus:
    backgroundColor: "{colors.sheet}"
    textColor: "{colors.ink}"
  segment-track:
    backgroundColor: "{colors.cyan-12}"
    rounded: "{rounded.note}"
    padding: "3px"
  segment-item:
    textColor: "{colors.ink}"
    typography: "{typography.control-small}"
    rounded: "{rounded.control}"
    height: "48px"
  segment-item-checked:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.sheet}"
  status-pill:
    backgroundColor: "{colors.cyan-12}"
    textColor: "{colors.ink}"
    typography: "{typography.number}"
    rounded: "{rounded.pill}"
    padding: "0 14px 0 9px"
    height: "48px"
  status-pill-off:
    backgroundColor: "{colors.mag-12}"
    textColor: "{colors.mag-ink}"
  chip-support:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.control-small}"
    rounded: "{rounded.pill}"
    padding: "0 14px"
    height: "48px"
  tag:
    backgroundColor: "{colors.cyan}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "3px 10px"
  tile:
    backgroundColor: "{colors.cyan-12}"
    textColor: "{colors.ink}"
    rounded: "{rounded.action}"
    padding: "10px 4px 8px"
  tile-active:
    backgroundColor: "{colors.cyan}"
    textColor: "{colors.ink}"
  bar:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.action}"
    padding: "2px"
  dock:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.dock}"
    padding: "4px"
    width: "176px"
  strip:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.strip}"
    padding: "6px 6px 6px 16px"
    height: "76px"
  strip-fail:
    backgroundColor: "{colors.mag-12}"
    textColor: "{colors.mag-ink}"
  dialog:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
  notice:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.sheet}"
    typography: "{typography.control-small}"
    rounded: "{rounded.note}"
    padding: "10px 16px"
  note:
    backgroundColor: "{colors.cyan-12}"
    textColor: "{colors.ink}"
    rounded: "{rounded.note}"
  warn:
    backgroundColor: "{colors.mag-12}"
    textColor: "{colors.mag-ink}"
    typography: "{typography.muted-strong}"
    rounded: "{rounded.control}"
    padding: "10px"
---

# Design System: Cameo Link

## Overview

**Creative North Star: "La plancha a dos tintas"**

La pantalla es una plancha de risografía y el estado es el registro. Hay dos tintas reales, cian y magenta, sobre papel frío; el índigo no se elige, aparece solo donde las dos coinciden. Jean, en el taller junto a la Cameo y con la hoja impresa en la mano, ve de un vistazo si la máquina está lista (el registro cierra), qué se va a cortar (líneas índigo sobre la hoja) y cuál es el siguiente paso (un solo botón cian según el estado).

El sistema funciona como una leyenda de mapa: cada tinta tiene un significado y la jerarquía la lleva la tipografía, no las cajas. Es denso y sereno, una herramienta de producción con superficies de trama de cian en vez de grises. Rechaza el editor gris oscuro con un acento y los colores de juguete de Silhouette Studio.

Alcance: este sistema rige la app Android (`android/`). El panel de Illustrator conserva su mundo oscuro propio, adaptado al tema de la aplicación anfitriona, y no se rige por este archivo. La tablet real mide 960 × 600 dp.

**Key Characteristics:**
- Papel frío claro, tramas de cian como superficie y un índigo multiplicado de verdad para texto y líneas.
- Leyenda estricta: cian actúa, magenta advierte, índigo registra.
- Un gesto único: fuera de registro → en registro, en la marca, la cruz de estado y las líneas de corte.
- Barlow en tres pesos, sin mayúsculas forzadas; todo número con unidad y cifras tabulares.
- Solo flota lo que está sobre la mesa, con sombra difusa teñida de índigo.

## Colors

Dos tintas y su sobreimpresión sobre papel: la paleta se lee como separación de impresión, no como escala de marca.

### Primary
- **Cian de Plancha** (#00B8D9): la acción. Botón principal, interruptor encendido, tile elegido, selección y lo ya cortado sobre la hoja. Es superficie y línea, nunca texto: sobre blanco queda en 2,37:1.

### Secondary
- **Magenta de Plancha** (#E0218A): el trazo que se sale del soporte y la segunda plancha de la separación.
- **Magenta Macizo** (#C4176F): relleno de lo destructivo o fallido, con texto blanco (5,68:1). Detener y el círculo de fallo.
- **Magenta Tinta** (#AD0D67): texto e iconos de error y destrucción, sobre su trama o sobre papel (5,85:1 y 6,73:1).
- **Trama Magenta 12 %** (#FCE6F2): fondo de aviso, celda Eliminar, pastilla sin conexión y tira de fallo.

### Tertiary
- **Índigo Sobreimpreso** (#001876): cian × magenta. Texto, iconos, líneas en registro, foco de campo, segmento marcado, barra de progreso y pulgar del interruptor. 14,66:1 sobre papel y 6,36:1 sobre cian.

### Neutral
- **Papel Frío** (#FAFCFD): barra superior, panel, flotantes, diálogos y barras del sistema.
- **Hoja Blanca** (#FFFFFF): la hoja de corte, el campo enfocado y el texto sobre índigo o magenta macizo.
- **Índigo Medio** (#47588F): texto secundario, rótulos y nombre de archivo. 6,67:1 sobre papel.
- **Índigo Claro** (#8E9AC0): solo controles deshabilitados.
- **Filete** (#00187624): separadores de 1 dp.
- **Trama Cian 12 %** (#E1F6FA): celdas, campos, botones de herramienta, pastilla de estado y notas.
- **Trama Cian 20 %** (#CCF1F7): la mesa de trabajo, el botón plano activo y el primario deshabilitado.
- **Trama Cian 35 %** (#A6E7F2): onda táctil, herramienta activa, selección de texto y barra de desplazamiento.
- **Punto de Trama** (#AEE9F3): los puntos de la mesa, en una rejilla de 14 dp a 45°.
- **Mat** (#F1F9FB) y **Cuadrícula del Mat** (#00187621): el mat de corte con su rejilla de 1 pulgada.
- **Marco** (#00187673): perímetro punteado de la página y del área de trabajo.
- **Velo** (#00187657) y **Papel Velado** (#A5AECF): el velo detrás de los diálogos y su resultado sobre papel, que tiñe las barras del sistema. Cambian juntos.
- **Marca Impresa** (#111111): las marcas de registro tal como salen en la hoja.

### Named Rules

**La Regla de la Leyenda.** Cada tinta significa una sola cosa. Cian es acción o lo que la mano opera; magenta es destructivo, error o fuera del soporte; índigo es texto y toda línea en registro.

**La Regla del Multiplicado.** El índigo es el producto de cian y magenta, no un color elegido. Si cambia una tinta, se recalcula el índigo y toda su familia.

**La Regla de las Planchas.** Cian y magenta se ven juntos y sin significado de leyenda en un solo caso: cuando las planchas se separan. La separación es el dato; ahí el magenta no significa error por sí mismo.

**La Regla de lo Ajeno.** El arte del usuario y las marcas de registro son material físico, no interfaz. Conservan sus colores reales, incluido el negro de las marcas, y nunca se reentintan.

## Typography

**Display Font:** Barlow Bold (empaquetada en `res/font`, sin alternativa)
**Body Font:** Barlow Medium
**Label/Mono Font:** Barlow SemiBold, con cifras tabulares (`tnum`) en los datos

**Character:** Barlow nace de las placas y letreros de carretera: técnica, compacta y legible de lejos, justo lo que pide una tablet leída de pie en el taller. Una sola familia; la jerarquía sale del peso y el tamaño.

### Hierarchy
- **Display** (Bold, 26 sp): el mensaje del estado vacío.
- **Headline** (Bold, 24 sp): nombre de la máquina en Máquina.
- **Dialog Title** (Bold, 22 sp, una línea): título de diálogo.
- **Title** (Bold, 20 sp): marca y título de pantalla.
- **Data Value** (Bold, 20 sp, tabular): valores de la lectura de corte.
- **Action** (Bold, 17 sp): el botón de corte.
- **Section** (Bold, 16 sp): títulos del panel, botones principales y Detener.
- **Control** (SemiBold, 15 sp): botones, pastilla de estado, listas desplegables y nombre de archivo.
- **Number** (SemiBold, 15 sp, tabular): campos numéricos.
- **Control Small** (SemiBold, 14 sp): segmentado, interruptores, chip de soporte y aviso índigo.
- **Body** (Medium, 14 sp): filas de datos y mensajes de diálogo.
- **Muted Strong** (SemiBold, 13 sp): texto de los avisos magenta.
- **Muted** (Medium, 13 sp): líneas secundarias y rótulos de la lectura de corte.
- **Label** (SemiBold, 12 sp): rótulos sobre campos y celdas de herramienta.

### Named Rules

**La Regla de la Jerarquía Tipográfica.** El orden lo dan peso y tamaño. Nada de mayúsculas forzadas, color de acento ni cajas para marcar un título.

**La Regla de la Unidad.** Todo número lleva su unidad, cifras tabulares y coma decimal (210,0 mm). Los rótulos de campo se ven siempre encima; el texto de ayuda dentro del campo desaparece al escribir.

## Layout

Una sola pantalla de trabajo, apaisada y pensada para 960 × 600 dp, sin variantes por tamaño. De arriba abajo: barra superior de 56 dp en papel, filete de 1 dp y la zona de trabajo. La zona se divide en la mesa flexible y un panel de 320 dp con su filete; el panel se pliega y sin documento no existe.

Los flotantes viven en las cuatro esquinas de la mesa, a 10 dp del borde: soporte, deshacer y escala arriba a la izquierda; rejilla de herramientas de 176 dp arriba a la derecha; vista y selección abajo a la izquierda; botón de panel y botón de corte abajo a la derecha. El encuadre de la hoja reserva ese espacio para que la rejilla no la tape al encuadrar.

La pantalla Máquina se parte en dos mitades iguales: estado sobre trama plana de 20 % con relleno de 24 dp, lista y opciones en papel.

El ritmo se apoya en 8 dp entre campos, 14 dp alrededor de los filetes y 16 dp de margen en el panel. La rejilla de herramientas muestra todas las acciones a la vez, en dos columnas y sin desplazamiento. Las tiras de corte y resultado cruzan la parte inferior de la mesa.

**La Regla de los 48.** Todo control táctil mide al menos 48 dp. La barra inferior de vista, a 40 dp por pedido de Jean, es la única excepción y no se hereda.

## Elevation & Depth

Sistema híbrido: la profundidad base es tonal (papel sobre trama de cian) y solo lo que flota sobre la mesa suma sombra. Barra superior y panel son planos, separados por filetes. Los flotantes usan elevación de 6 dp y las tiras de 8 dp, con la sombra direccional teñida de índigo. Los diálogos no oscurecen: un velo índigo reemplaza al atenuado negro del sistema y entra en 160 ms.

### Shadow Vocabulary
- **Flotante** (elevación 6 dp, sombra direccional en índigo): chip de soporte, barras, rejilla, resumen y botones de la mesa.
- **Tira** (elevación 8 dp, sombra direccional en índigo): tiras de progreso y resultado.
- **Hoja** (desenfoque 16 dp, desplazamiento 0 / 4 dp, índigo al 16 %): la hoja o el mat apoyados en la mesa.

### Named Rules

**La Regla de la Hoja Suelta.** La sombra dice que algo está suelto sobre la mesa. Siempre es difusa y teñida de índigo; lo que pertenece a la estructura de la pantalla queda plano.

## Shapes

Esquinas suaves y escalonadas por tamaño, sobre rectángulos llenos: 10 dp en controles, 14 dp en acciones y barras, 16 a 18 dp en contenedores flotantes y 22 dp en pastillas y diálogos. Un control anidado siempre tiene menos radio que su contenedor. La hoja conserva 2 dp porque representa papel, y sobre el lienzo dibujado la hoja es recta. Los círculos quedan para la cruz de registro, el resultado y el tirador de giro.

**La Regla del Borde como Estado.** Las superficies se separan por tono, no por contorno. Un trazo aparece solo para decir algo: foco de campo (2 dp índigo), botón de corte relegado (2 dp cian), tamaño sin definir (punteado 1,5 dp índigo) y las líneas del propio lienzo.

## Components

### Buttons
Rellenos, sin sombra propia y sin mayúsculas; la onda táctil es el único movimiento al tocar.
- **Shape:** acciones con esquinas de 14 dp, herramientas de 10 dp.
- **Primary:** cian con texto índigo, nunca blanco. 52 dp en el estado vacío y 48 dp en diálogos. Deshabilitado pasa a trama 20 % con índigo medio.
- **Corte:** el primario flotante de 60 dp, ancho mínimo 216 dp. Su texto dice lo que falta: Conectar Cameo, Carga el material, Cortar 3 de 9 trazados.
- **Corte relegado:** papel con trazo cian de 2 dp, cuando imprimir va primero. Imprimir toma entonces el relleno cian.
- **Herramienta:** trama 12 %; activa, trama 35 %; deshabilitada, transparente con índigo claro.
- **Plano:** barra superior e icono, transparente hasta tocarlo; activo en trama 20 %. Los botones de solo icono llevan descripción y ayuda emergente.
- **Superficie:** papel, 14 dp, para el secundario sobre mesa o trama.
- **Destructivo:** magenta macizo con texto blanco. Detener mide 64 dp y ancho mínimo 132 dp.

### Chips
- **Soporte:** papel, 22 dp, flotante. Nombre y medidas tabulares en dos tonos de índigo.
- **Etiqueta:** cian con índigo Bold de 13 sp, 10 dp, para el estado Conectada.

### Cards / Containers
- **Corner Style:** barras 14 dp, rejilla 16 dp, tiras 18 dp, diálogo 22 dp.
- **Background:** papel. La tira de fallo y los avisos pasan a trama magenta 12 %.
- **Shadow Strategy:** flotantes y tiras según Elevation & Depth; el panel es plano.
- **Border:** ninguno.
- **Internal Padding:** 2 dp en barras, 4 dp en rejilla, 16 dp a la izquierda en tiras.

### Inputs / Fields
- **Style:** trama 12 %, 48 dp, 10 dp de radio, sin línea inferior, con rótulo siempre visible encima.
- **Focus:** pasa a hoja blanca con trazo índigo de 2 dp, y selecciona todo el valor.
- **Listas:** misma superficie con cheurón; el desplegable es papel de 10 dp.
- **Segmentado:** riel trama 12 % con 3 dp de relleno; el elegido es índigo con texto blanco.
- **Interruptor:** 52 × 28 dp, riel cian o trama 20 %, pulgar índigo en ambos estados.

### Navigation
No hay navegación por pestañas. La barra superior reúne marca, Abrir, Agregar, Guardar, archivo, Imprimir, Herramientas y estado; los botones aparecen cuando hay documento. La pastilla de estado abre Máquina, y Máquina vuelve con un botón plano.

### Celda de herramienta
Icono sobre rótulo en 54 dp, trama 12 %, en la rejilla de dos columnas. Eliminar es la única celda magenta y va al final de la rejilla.

### Cruz de registro y marca
La firma del sistema. Dos planchas, cian y magenta, multiplicadas; la distancia entre ellas es el estado. Lista: en registro, se ve índigo. Ocupada: oscila en 1100 ms sin llegar a cerrar. Sin máquina: separación completa. El cierre dura 460 ms con desaceleración fuerte. La cruz mide 28 dp en la pastilla, 76 dp en Máquina y 88 dp en el estado vacío; la marca Cameo Link se separa solo 1,5 dp para seguir legible.

### Mesa y líneas de corte
La mesa es trama 20 % con puntos; la hoja se apoya encima a escala real.
- **Pendiente:** índigo, 1,5 dp.
- **Seleccionada:** cian, 2,6 dp, con marco punteado índigo y tiradores cian de borde blanco (18 dp, y 28 dp el de giro).
- **Cortándose:** cian de 3 dp avanzando sobre la línea; **cortada:** cian, 1,5 dp.
- **Fuera del soporte:** magenta.
- **Tapada por una imagen:** punteada encima, con halo blanco de 3 dp.
- **Buscando marcas:** las líneas se separan 6 dp en sus dos planchas y cierran al encontrarlas.

### Lectura de corte
El diálogo que confirma lo irreversible: rótulo Muted sobre valor Data Value, en dos columnas, 640 dp de ancho. Sin párrafos.

### Tira de progreso y resultado
Tira de 76 dp al pie de la mesa. Corte: texto tabular, barra índigo de 10 dp sobre trama 20 % y Detener. Resultado: círculo cian o magenta macizo, conteo y tiempo, Repetir o Reintentar. Queda fija hasta cerrarla.

### Aviso
Pastilla índigo de texto blanco, arriba al centro de la pantalla, 2,6 s. Es el aviso de la app; el del sistema queda solo para lo que debe verse sobre el diálogo de impresión.

### Tile de soporte
Trama 12 % con la hoja dibujada a escala, nombre y medidas tabulares. Elegido pasa a cian.

**La Regla del Único Cian.** En cada estado hay una sola acción principal rellena de cian: lo que falta para cortar. Si la acción cambia, el relleno se mueve.

**La Regla de la Lectura.** Lo irreversible se confirma con datos, no con prosa: se lee en 3 segundos y el resultado se queda a la vista.

## Do's and Don'ts

### Do:
- **Do** usa índigo para el texto sobre cian (6,36:1).
- **Do** dibuja las superficies con las tramas de cian al 12, 20 y 35 %, no con grises.
- **Do** reserva el relleno magenta macizo y su trama para destruir, detener o avisar un fallo.
- **Do** expresa el estado de la máquina con la separación de planchas, siempre junto a su texto.
- **Do** tiñe de índigo el velo de los diálogos y las barras del sistema con Papel Velado.
- **Do** escribe números con unidad, coma decimal y cifras tabulares.
- **Do** usa iconos vectoriales de 24 dp teñidos con la tinta del control.
- **Do** conserva el negro de las marcas de registro y los colores del arte del usuario.

### Don't:
- **Don't** vuelvas al editor gris oscuro con un acento en Android.
- **Don't** pongas texto blanco sobre cian ni texto cian sobre papel.
- **Don't** uses magenta para decorar o marcar novedades; fuera de la separación de planchas, siempre significa destructivo, error o fuera del soporte.
- **Don't** pongas dos rellenos cian compitiendo en un mismo estado.
- **Don't** uses sombras duras ni sombras en la estructura de la pantalla.
- **Don't** contornees superficies en reposo; el trazo se reserva para estados y líneas del lienzo.
- **Don't** fuerces mayúsculas ni marques títulos con color de acento.
- **Don't** uses el atenuado negro del sistema detrás de un diálogo.
- **Don't** uses Índigo Claro en texto que se deba leer; es solo para deshabilitado.
- **Don't** uses gradientes ni vidrio esmerilado: la tinta es plana.
