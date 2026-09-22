# Product

## Register

product

## Users
Jean (Digital Fox / PUDU): diseñador-desarrollador que produce stickers, etiquetas y flyers en Illustrator
y los corta en una Silhouette CAMEO 4 que está al lado del PC. Usa el panel dentro de Illustrator, con la
hoja ya impresa en la mano, en sesiones cortas y repetitivas: exportar → vista previa → cortar. Contexto
de taller: luz ambiente, tema oscuro de Illustrator, decisiones rápidas, cero tolerancia a diálogos que
tapan el lienzo o piden confirmaciones vagas.

## Product Purpose
Cameo Cut elimina Silhouette Studio del flujo: desde Illustrator se elige qué cortar, el material y la
cuchilla, se previsualiza la geometría real que recibirá la máquina y se envía el corte (con registro
automático o manual para print & cut). Éxito = un corte correcto a la primera, sin salir de Illustrator,
y sin desperdiciar material por un ajuste mal leído.

## Brand Personality
Preciso, directo, con identidad Digital Fox. Voz técnica en español, frases cortas, números con unidad.
El panel es una herramienta de producción: la marca aparece en acentos (cian Digital Fox para la acción
principal, magenta solo para lo destructivo/alerta), nunca en decoración.

## Anti-references
- Silhouette Studio: diálogos gigantes, iconografía infantil, colores de juguete, pasos redundantes.
- Web genérica de landing: tarjetas con sombra, gradientes, glassmorphism, tipografía de marketing.
- `confirm()` / `alert()` nativos: ventanas del sistema fuera del tema del panel, texto plano sin jerarquía.

## Design Principles
- **Lo irreversible se confirma con datos, no con prosa**: antes de cortar se muestra exactamente qué,
  con qué y cómo; la confirmación es una lectura de 3 segundos, no un párrafo.
- **Todo cabe en 360 px**: el panel vive acoplado; densidad de panel de Adobe, secciones colapsables,
  nada horizontal.
- **La máquina manda**: los estados (lista / sin material / Studio abierto / cortando) son siempre
  visibles y bloquean lo que corresponde.
- **Un acento, un significado**: cian = acción principal; magenta = destructivo o error; el resto neutro.
- **Sin fricción de mantenimiento**: presets y calibraciones se recuerdan; nunca se vuelve a teclear lo
  mismo.

## Accessibility & Inclusion
Contraste ≥ 4,5:1 sobre el gris de Illustrator (tema oscuro y claro vía `appSkinInfo`); foco visible
en todos los controles; el diálogo de confirmación se opera con Enter/Esc y atrapa el foco; respeta
`prefers-reduced-motion`. Textos en español con unidades explícitas (mm).
