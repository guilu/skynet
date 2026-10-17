# Probar W7-C en local

W7-C (#106) cierra el hito W7 con **carriles por macrofase**, accesibilidad del grafo y la E2E de aceptación.

- Clave nueva `phase` en cada fase: `spec`, `plan`, `implement`, `test`, `review` o `deliver`. Es opcional y solo visual: no cambia cómo se ejecuta.
- Si alguna fase la dice, el grafo (del workflow y de la ejecución) se separa en carriles, uno por macrofase usada y en su orden: Especificación, Planificación, Implementación, Tests, Revisión, Entrega. Una fase sin `phase` va en el carril más avanzado de las fases de las que depende o, sin dependencias, en el primero.
- Las plantillas «Revisión en paralelo», «Revisión con Codex y Gemini» y «Especificar, planificar e implementar» ya la llevan.
- Con el teclado, la caja que recibe el foco se trae a la vista si estaba fuera; con `prefers-reduced-motion`, sin animación.

La API añade `phase` a cada fase de la definición y de la ejecución. No hay migraciones.

## 1. Arrancar

Como en W7-B: control plane, web y runner con fake-claude reproduciendo `02-tools`, y el repositorio de prueba de W4-B (`/tmp/w4b`).

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm install && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

## 2. Carriles en el grafo del workflow

1. **Nuevo workflow** → plantilla «Revisión en paralelo» → pestaña **Grafo**.
2. Se ven tres bandas con su nombre arriba: **Implementación** (`implement`), **Tests** (`tests`) y **Revisión** (`review` con sus dos hijos, `fix` y `tests-finales`, que no dicen `phase` y heredan el carril de `review`).
3. Plantilla «Especificar, planificar e implementar»: carriles **Especificación**, **Planificación** (`plan` y la puerta `aprobar-plan`) e **Implementación**.
4. Plantilla «Dos fases»: no dice `phase`, así que se ve sin carriles, como en W7-A.

## 3. Validación de `phase`

En el YAML de una fase escribe `phase: implemnt`. En **Problemas** sale «`phase: implemnt` no se conoce; ¿quisiste decir `implement`?. Admite: spec, plan, implement, test, review, deliver». Corrígelo y desaparece.

## 4. Carriles en la ejecución, en vivo

Con «Revisión en paralelo» (cambia `./gradlew test` por `true` en las dos fases `command`), publícala y lánzala en un trabajo sobre `/tmp/w4b`.

1. En **Progreso**, el grafo tiene los mismos tres carriles.
2. Sin recargar, cada caja pasa de «Pendiente» a «Completada» dentro de su carril; `fix` queda «Omitida» si los revisores no encuentran nada que bloquee.

## 5. Teclado y movimiento reducido

1. Haz zoom (**+** en los controles) hasta que no quepa el grafo. Con **Tab**, recorre las cajas: cuando el foco llega a una caja fuera de la vista, el grafo se desplaza hasta ella sin cambiar el zoom, con una animación corta.
2. Activa «Reducir movimiento» en el sistema (o en las DevTools: _Rendering → Emulate CSS prefers-reduced-motion: reduce_) y repite: el grafo salta a la caja sin animación.

## 6. Comprobaciones automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test ./gradlew check
cd web && npx tsc -b && npx oxlint && npx vitest run
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh   # desde la raíz
```

`workflows.e2e.ts` comprueba los carriles del grafo del workflow y `nodes.e2e.ts` lanza «Revisión en paralelo» desde la web y ve su grafo pasar a completado sin recargar, con sus carriles y axe.
