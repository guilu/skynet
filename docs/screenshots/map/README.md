# Mapa de navegación de Skynet

<!-- Generado por web/visual/build-map.ts a partir de web/visual/navigation-map.json: no editar a mano. -->

Las 40 pantallas de la galería, con sus relaciones de navegación, agrupadas por zona. Cada
tarjeta lleva la miniatura de escritorio en claro, el título, la ruta y su número de captura.

| Fichero | Para qué |
| --- | --- |
| `navigation-map.html` | Mapa navegable: rueda para acercar, arrastrar para moverse; al pulsar una pantalla, su ruta, notas, capturas de cada combinación y a dónde lleva. |
| `navigation-map.svg` | Vectorial con las miniaturas incrustadas: para Figma y Miro. |
| `navigation-map.png` | Vista de conjunto para compartir. |
| `navigation-map.pdf` | Una página con el mapa entero, para revisar y anotar. |
| `navigation-map.json` | El manifiesto resuelto: pantallas, ficheros, posición y conectores. |
| `thumbs/` | Las miniaturas que usa el HTML. |
| `groups/` | Un submapa SVG por zona, con sus vecinas de otras zonas atenuadas. No se versiona (repite las miniaturas): está en el ZIP y en el artifact de la Action. |

## Leyenda

| Conector | Significa |
| --- | --- |
| continuo, #5b667c | Navegación normal |
| discontinuo, #335fe0 | Cambio de pestaña |
| punteado, #8b5cf6 | Abre un diálogo |
| punteado, #d97706 | Abre un menú |
| continuo, #16a34a, grueso | Acción que crea o lanza algo |
| discontinuo, #9aa4b5 | Retorno o cierre |

## Submapas

En el ZIP, en el artifact de la Action o tras generar la galería en local:

- [Acceso](groups/01-access.svg)
- [Inicio y supervisión](groups/02-overview.svg)
- [Trabajo](groups/03-work.svg)
- [Automatización](groups/04-automation.svg)
- [Ejecución](groups/05-execution.svg)
- [Decisiones](groups/06-decisions.svg)
- [Configuración](groups/07-settings.svg)
- [Overlays y estados transversales](groups/08-overlays.svg)

## Abrir

Abre `navigation-map.html` con doble clic: funciona como fichero local, sin servidor. Si tu
navegador no carga las miniaturas desde `file://`, sirve la carpeta de la galería:

```bash
cd docs/screenshots && python3 -m http.server 8000
# http://localhost:8000/map/navigation-map.html
```

## Importar en Figma y Miro

- **Figma**: arrastra `navigation-map.svg` al lienzo (o *Archivo → Importar*). Llega como un
  grupo editable: tarjetas, textos y conectores sueltos, con las miniaturas incrustadas. Para
  trabajar una pantalla a tamaño real, arrastra también su PNG de `../desktop/light/` (o de las
  otras carpetas del ZIP) y úsalo como frame.
- **Miro**: sube `navigation-map.svg` (o el PDF / PNG para anotar encima). Para montar variantes
  de un flujo, sube las capturas sueltas que necesites.

Los nombres (`NN-id.png`) y el orden no cambian entre generaciones: al regenerar, cada imagen
sustituye a la anterior sin ambigüedad.

## Regenerar

Sale con la galería (`cd web && npm run screenshots`). Para rehacer solo el mapa a partir de las
capturas que ya hay, sin volver a capturar: `npm run screenshots:map`.
