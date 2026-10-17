# Probar W7-B en local

W7-B (#105) trae el **grafo de la ejecución**: en **Progreso**, las fases se ven con el mismo grafo que el workflow (W7-A), con el estado de cada una encima y al día sin recargar.

- Cada caja dice su estado con icono y texto, lo que hace ahora (la herramienta en curso, «Verificando», «Cancelando») o cuánto lleva, quién la hace, su coste, «intento 2 de 3» y la nota de la fase («Espera a izquierda y derecha»).
- Una `parallel` lleva dentro un nodo por hijo con el estado de su agente.
- La flecha que sale de una fase omitida o cancelada va apagada.
- Pulsar una caja abre la fase debajo, como antes; pulsar un hijo abre su agente.
- Conmutador **Grafo / Lista**: la lista es el camino de siempre y la elección se recuerda en el navegador. En el móvil (menos de 720 px) y en una ejecución de una sola fase, siempre la lista.

La API añade `children` a cada fase de la ejecución (los hijos de una `parallel`). No hay migraciones.

## 1. Arrancar

Como en W6-C: control plane, web y runner con fake-claude reproduciendo `02-tools`.

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

Usa el repositorio de prueba de W4-B (`/tmp/w4b`, con `calc.py`).

## 2. Ramas que se juntan, en vivo

Publica y lanza en un trabajo sobre `/tmp/w4b`:

```yaml
id: grafo-ejecucion
stages:
  - id: izquierda
    type: agent
    prompt: Mira calc.py por la izquierda
  - id: derecha
    type: agent
    prompt: Mira calc.py por la derecha
  - id: union
    type: agent
    prompt: Une lo de las dos
    dependsOn: [izquierda, derecha]
    workspaceFrom: derecha
  - id: cerrar
    type: command
    dependsOn: [union]
    command: "true"
```

1. Al abrir la ejecución, **Progreso** enseña el grafo: `izquierda` y `derecha` en la misma columna, las dos flechas hacia `union` y luego `cerrar`.
2. Mientras corren, `izquierda` y `derecha` tienen borde morado y dicen «En curso · Read» (la herramienta en curso) o su duración; `union` dice «Pendiente» y «Espera a izquierda y derecha».
3. Sin recargar, cada caja pasa a verde («Completada») y `union` empieza. Al final, las cuatro en verde.
4. Pulsa `union`: debajo se abre su detalle con su agente, y la caja lleva un anillo azul. El agente se abre en la conversación.
5. Con el teclado, **Tab** recorre las cajas en el orden del YAML; **Intro** abre la fase.

## 3. Grafo / Lista

1. En la cabecera de **Progreso**, pulsa **Lista**: sale el camino de siempre. Recarga: sigue en **Lista**. Vuelve a **Grafo**.
2. Estrecha la ventana por debajo de 720 px: el conmutador desaparece y se ve la lista. Al ensancharla vuelve el grafo.
3. Una ejecución ad hoc (una sola fase) no tiene conmutador: se ve como siempre.

## 4. Una `parallel` con condición

Con la plantilla «Revisión en paralelo» (cambia `./gradlew test` por `true` en las dos fases `command`):

1. `review` es una caja con `calidad` y `seguridad` dentro; cada hijo dice su estado y su duración. Pulsar un hijo abre ese agente.
2. Si los revisores no encuentran nada que bloquee, `fix` queda «Omitida» con borde punteado y la flecha de `fix` a `tests-finales` va apagada.

## 5. Fallos y reintentos

1. Repite la sección 2 de `probar-w6c.md` (`reintentar-fase`): `comprobar` sale en rojo con «Falló: terminó con error» y `cerrar`, apagada («No arrancó porque falló comprobar»).
2. Pulsa `comprobar` y **Reintentar fase**: sin recargar, `comprobar` dice «intento 2» y pasa a verde, y `cerrar` también.

## 6. Comprobaciones automáticas

```bash
cd web && npx tsc -b && npx oxlint && npx vitest run
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh   # desde la raíz
```

`engine.e2e.ts` comprueba que el grafo pasa de «Espera» a completado sin recargar (tras reiniciar el control plane) y pasa axe sobre él.
