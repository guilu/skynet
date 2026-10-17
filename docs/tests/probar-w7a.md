# Probar W7-A en local

W7-A (#104) trae el **grafo del workflow**: junto a la pestaña **YAML** del workflow hay una pestaña **Grafo** que dibuja las fases de izquierda a derecha según sus dependencias.

- Cada caja lleva un icono por tipo, su número y nombre, su tipo y una línea con lo que hace (agente, comando, condición, «Aprobar: …», comprobaciones), sus reintentos y su tiempo máximo.
- Una `parallel` es una caja con sus hijos dentro. Una dependencia opcional (`fix?`) va discontinua.
- Zoom y ajustar a pantalla; minimapa solo con más de 8 fases. Es de solo lectura.
- Funciona también en un borrador mientras se escribe.

Solo cambia la web: no hay migraciones ni cambios en el runner. Las ejecuciones siguen con su camino de fases de siempre; el grafo de la ejecución llega en W7-B.

## 1. Arrancar

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm install && npm run dev
```

`npm install` hace falta una vez: trae `@xyflow/react` y `@dagrejs/dagre`. No hace falta runner.

## 2. La plantilla «Revisión en paralelo»

1. **Workflows → Nuevo workflow**, plantilla **Revisión en paralelo**.
2. Pulsa la pestaña **Grafo**. La URL lleva `?view=graph` y, en las herramientas de desarrollo, se descarga un `DefinitionGraph-*.js` aparte (solo la primera vez).
3. Se ven cinco cajas en fila: `1. implement` → `2. tests` → `3. review` → `4. fix` → `5. tests-finales`.
   - `implement` dice `developer`; `tests`, `./gradlew test`.
   - `review` es una caja punteada con «2 a la vez» y dentro `calidad` y `seguridad`.
   - `fix` dice `developer · si review.hasBlockingFindings`.
   - La flecha de `fix` a `tests-finales` es discontinua (`fix?`); la de `review` a `tests-finales`, continua.
4. Los botones **+**, **−** y **ajustar** (arriba a la derecha) acercan, alejan y vuelven a encajar el grafo. La rueda desplaza el lienzo, no hace zoom. Las cajas no se pueden arrastrar.
5. Debajo del grafo siguen **Problemas** y **Qué define**.
6. Vuelve a **YAML**: el editor está como lo dejaste (cursor y deshacer incluidos).

## 3. Un borrador mientras se escribe

1. En el YAML de la plantilla, cambia `dependsOn: [tests]` de `review` por `dependsOn: [implement]` y vuelve a **Grafo**: `tests` y `review` salen ahora en la misma columna, las dos detrás de `implement`.
2. Rompe el YAML (borra los dos puntos de `stages:`): el grafo dice «El YAML no se puede leer: corrige los problemas para ver el grafo.».
3. Arréglalo y **Crear borrador**. En la página del workflow, **Grafo** dibuja el borrador; en una versión publicada (`?version=…`), lo mismo, de solo lectura.

## 4. Tipos y avisos

Crea un borrador con este YAML y abre **Grafo**:

```yaml
id: tipos-grafo
inputs:
  deploy:
    type: boolean
    required: false
    default: false
stages:
  - id: spec
    type: agent
    prompt: Escribe la especificación en docs/spec.md
    retry: {maxAttempts: 3, on: [error]}
    timeout: 30m
  - id: revisar
    type: verification
    dependsOn: [spec]
    checks: [diff, commit]
  - id: decidir
    type: conditional
    dependsOn: [revisar]
    condition: inputs.deploy
  - id: aprobar
    type: human-approval
    dependsOn: [decidir]
    action: Desplegar
    risk: high
```

- `spec`: icono de robot, «Agente del repositorio · hasta 3 intentos · máx. 30 min».
- `revisar`: icono de lista, «2 comprobaciones».
- `decidir`: icono de rama, «Si inputs.deploy».
- `aprobar`: icono de mano, borde ámbar y «Aprobar: Desplegar».

## 5. Minimapa y tema oscuro

1. Con 9 o más fases (añade a `tipos-grafo` fases `type: command` con `command: "true"` e ids distintos), aparece el **minimapa** abajo a la derecha; con 8 o menos, no.
2. Cambia a tema oscuro en **Ajustes**: cajas, flechas, controles y minimapa usan los colores del tema.

## 6. Comprobaciones automáticas

```bash
cd web && npx tsc -b && npx oxlint && npx vitest run
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh workflows.e2e.ts   # desde la raíz
```

El E2E abre la plantilla «Revisión en paralelo» en **Grafo**, comprueba las cajas, los hijos y las flechas, pasa axe y guarda una captura en `build/e2e/w7a-grafo.png`.
