# Probar SV-D: el Skyny 2D con el aspecto del 3D

SV-D redibuja el Skyny 2D (el SVG de `Companion.tsx`) para que sea el mismo personaje que el Skyny 3D del estudio v2. Ahora el cuerpo es más bajo y redondeado, ya no tiene el nodo de la cabeza, las manos son grandes y van en las esquinas de abajo, y el visor lleva un aro metálico. Las bandas laterales suben en diagonal. Siguen igual los colores por estado, el tema, la paleta, las expresiones, los gestos, los sombreros y las poses. Solo toca la web.

## 1. Comprobaciones automáticas

```bash
cd web
npm ci
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run
```

Todo en verde. `Companion.test.tsx` comprueba que ya no hay cabeza: dos nodos (las manos) y el aro del visor.

## 2. Ajustes › Skyny, tu companion

1. Con el sistema en «reducir movimiento» (para ver el 2D en todas partes), abre **Ajustes** y baja a «Skyny, tu companion».
2. Las 12 expresiones salen con el dibujo nuevo y cada una con su color. Los ojos cambian como antes; en Pensando, el izquierdo guiña.
3. Prueba Saltito, Asentir, Bostezo, Taparse los ojos (las dos manos tapan el visor), De reojo y Sudando.
4. Cambia a la paleta **Frambuesa**: Skyny en reposo se pone del nuevo color de acento.
5. En oscuro, el aro del visor es algo más apagado.

## 3. Tamaños pequeños

Recorre la app con el dibujo nuevo: barra lateral (Skyny como logo, 54 px), icono de la barra superior, píldoras de estado, nodos del grafo de una ejecución y la cabecera de Consumo. Se reconoce incluso a 16–24 px.

## 4. Sombreros

En Navidad (o forzándolo en el código con `season="christmas"`) el gorro va en la punta del cuerpo, ladeado a la izquierda, y no choca con los bocadillos.

## 5. Lo que no cambia

- El Skyny 3D (login, ejecución e Inicio) sigue igual.
- El favicon y los iconos de la app (`lib/brand.ts`, `public/`) siguen con el dibujo anterior, con cabeza: se pueden regenerar en otra PR.
- Las capturas de la guía de estilo (`docs/ui/capturas`) están regeneradas con el dibujo nuevo. `scripts/capturas.mjs` simula ahora los supuestos de la ejecución, que dejaban en blanco la página de la ejecución.
