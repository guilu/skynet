# Probar SB-A - Skyny en las píldoras de estado

SB-A cambia el icono de las píldoras de estado (`StatusBadge`) por la expresión de Skyny que corresponda (issue #216). Solo cambia la web: la API, la base de datos y los eventos siguen igual.

- **Píldora más grande:** Skyny va a 40 px, con su dibujo completo y sus accesorios («?», lupa, «!», confeti…). La píldora mide unos 42 px de alto y Skyny queda dentro con margen alrededor.
- **Mapeo** (`statusFace` en `web/src/components/ui/statusIcons.ts`):

  | Estado | Skyny |
  |---|---|
  | Pendiente, En cola, Lista | En espera |
  | Arrancando, Pensando | Pensando |
  | En curso, Ejecutando | Ejecutando |
  | Verificación en curso | Revisando |
  | Esperando aprobación / información | Te necesita |
  | Completada, Completado, Pasa | Éxito |
  | Fallida, Error | Error |
  | Cancelada, Cancelando, Omitida | Cancelado |
  | Sin actividad, Sin latido, Sin respuesta | Sin conexión |
  | En línea | Reposo |

  Abierto, Cerrado y los demás estados que no son de trabajo siguen con su icono.
- **Color:** cuando Skyny y el tono del estado no casan, la píldora toma el color de Skyny. Ejecutando sale en naranja, Revisando y En línea en el azul del acento, y Sin actividad y Sin latido en gris.
- **Tabla de ejecuciones:** la columna Estado ya no lleva una cara suelta al lado de la píldora. La cara va dentro y es la expresión más fuerte de la ejecución. Por ejemplo, una ejecución «En curso» con una fase esperando aprobación sale amarilla, con Te necesita.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/StatusBadge.test.tsx
npm run typecheck && npm run lint && npm run format:check
npm test
```

`StatusBadge.test.tsx` comprueba cuatro cosas:

- el mapeo estado → expresión;
- que la píldora dibuja a Skyny (`svg.companion` con su `data-state`) y conserva el texto;
- que se puede forzar «Revisando» en una verificación;
- que Abierto sigue con su icono.

## 2. En la web

Con Skynet en marcha y alguna ejecución ya hecha:

1. **Dashboard → Ejecuciones recientes:** cada fila lleva en Estado y en Agente una píldora con Skyny dentro y no un icono. Skyny no toca el borde de la píldora y no hay otra cara suelta al lado.
2. Una ejecución **Completada** lleva a Skyny verde con confeti; una **Fallida**, a Skyny rojo con la cara de error; una **Cancelada**, a Skyny gris.
3. Abre una ejecución en marcha (por ejemplo, del proyecto DEMO sobre `demo-repo`, o una con `fake-claude`). En la cabecera, la píldora «En curso» lleva a Skyny y se mueve mientras está en vivo. En la conversación, la píldora del agente pasa por Pensando (morado) y Ejecutando (Skyny naranja y píldora naranja).
4. Si la ejecución espera una aprobación, la píldora del agente dice «Esperando aprobación» con Skyny amarillo y el «!». En la tabla, esa ejecución sale amarilla aunque diga «En curso».
5. **Runners:** un runner en línea lleva a Skyny en Reposo, con la píldora azul. Uno sin latido lleva a Skyny Sin conexión, con la píldora gris.
6. Cambia a **tema oscuro** (Ajustes → Apariencia) y repite el paso 1: los colores se leen igual de bien.
7. En **móvil** (o con una ventana de unos 390 px), la tabla de Ejecuciones recientes se desplaza en horizontal como antes y las píldoras no se cortan.

## 3. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
