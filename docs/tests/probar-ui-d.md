# Probar UI-D en local

UI-D es la cuarta PR del rediseño de la interfaz: rehace la vista de una ejecución. Proyecto, trabajo y formularios llegan en UI-E.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

O en desarrollo: control plane y runner como siempre, y `cd web && npm ci && npm run dev`. Hay una dependencia nueva (react-resizable-panels). El backend no cambia.

Lanza un agente desde un trabajo y abre la ejecución mientras corre.

## 2. Qué mirar

1. **Tres paneles.** Debajo de la cabecera, de izquierda a derecha: los pasos (fases, agentes y herramientas), la actividad y el inspector del agente. Arrastra las barras entre paneles para cambiar su ancho; también se mueven con el teclado (Tab hasta la barra y flechas). Recarga: el reparto se mantiene.
2. **Pasos.** Cada herramienta sale bajo su agente con un punto de color (morado en curso, verde hecha, rojo con error, gris sin respuesta) y su duración. El botón con el número pliega y despliega las herramientas del agente. Al elegir una, el inspector abre Herramientas con esa llamada desplegada.
3. **Cascada.** En la actividad, la pestaña Cascada pone el agente y sus herramientas sobre el mismo eje de tiempo. Lo que sigue en curso sale rayado y crece solo, sin recargar. Pulsar una barra abre esa herramienta en el inspector.
4. **Conversación.** Es la pestaña por defecto: tu prompt y las respuestas del agente como chat, con avatar. Las herramientas aparecen entre los mensajes en el orden en que se usaron, plegadas, con su estado; al desplegar una ves la entrada y la salida. La caja para continuar la conversación sigue al final.
5. **Eventos.** El timeline de siempre, con sus filtros en la URL. Elegir un evento lo abre en «Evento original» del inspector.
6. **Visor JSON.** En «Evento original» y en la entrada de las herramientas, el JSON sale plegable por niveles, con colores por tipo, cadenas largas recortadas («Ver completo») y «Copiar JSON».
7. **Enlaces.** La URL guarda el agente, la pestaña del inspector, la vista de actividad (`view=waterfall`, `view=events`) y la herramienta elegida (`tool=…`). Los enlaces antiguos con `tab=conversation` siguen abriendo la conversación.
8. **Reconexión.** Corta la red unos segundos con la ejecución en marcha: la cabecera pasa a «Reconectando…» y, al volver, la cascada y los eventos siguen sin huecos ni duplicados.
9. **Móvil.** Con la ventana estrecha, los paneles pasan a tres pestañas (Pasos, Actividad y Detalle). Elegir una herramienta o un agente en Pasos lleva a Detalle.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

La vista de ejecución se descarga aparte (`RunPage-*.js`, unos 28 kB comprimido), así que el bundle inicial baja de 175 a 162 kB. La E2E (`scripts/e2e.sh`) sigue la cascada en vivo, elige una herramienta en el árbol y pasa axe en las tres vistas de la actividad y con una herramienta desplegada.
