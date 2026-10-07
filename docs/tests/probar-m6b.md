# Probar M6-B en local

M6-B da a cada repositorio su política de agentes (herramientas, modo de permisos, entorno y máximos de turnos, presupuesto y tiempo), y el runner corta una invocación en cuanto su coste estimado pasa del presupuesto.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude. El runner permite al agente dos variables de fake-claude, para poder ver el efecto de la política en el entorno:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_DELAY_MS \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=200 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado (o créalos).

## 2. La política global

1. En el proyecto, bajo el repositorio, aparece «Política de agentes: global · dontAsk · Read, Glob, Grep, Edit, Write».
2. Ábrela: el formulario parte de los valores globales (presupuesto 2 US$, 30 min, turnos sin límite, todas las variables que permite el runner).
3. En un trabajo, «Lanzar agente» muestra «Política global: herramientas Read, Glob, Grep, Edit, Write, modo dontAsk, entorno el que permite el runner.» y cada límite dice su máximo («hasta 2», «hasta 30»).

## 3. Una política propia

1. Cambia la política del repositorio:
   - herramientas `Read` y `Bash(git:*)`, una por línea;
   - modo `acceptEdits`;
   - presupuesto `1` y tiempo `10`;
   - deja marcada «todas las variables que permite el runner».
2. Guarda. El resumen pasa a «Política de agentes: propia · acceptEdits · Read, Bash(git:*)».
3. Prueba a guardar una herramienta con coma (`Bash(a,b)`): el servidor la rechaza con un mensaje que lo explica.
4. Lanza un agente: la política que se ve es «del repositorio» y los máximos son 1 US$ y 10 min.
5. Lanza con presupuesto `3`: error «El presupuesto (3 US$) supera el máximo del repositorio (1 US$)». Con `0.5` sí se lanza.
6. Comprueba lo que recibió el agente en el evento `agent.spawned` de esa ejecución (el id está en la URL de la ejecución):

   ```bash
   curl -s -u admin:secreto "http://localhost:8080/api/events?workflowRunId=<id>" \
     | jq '.[] | select(.type=="agent.spawned") | .payload | {allowedTools, permissionMode, limits}'
   ```

   Salen `["Read","Bash(git:*)"]`, `acceptEdits` y los límites `maxBudgetUsd: 0.5`, `timeoutMinutes: 10`.

## 4. El entorno del agente

1. En la política, desmarca «todas las variables» y escribe solo `FAKE_CLAUDE_DELAY_MS` y `OTRA`. Guarda.
2. Lanza un agente. Como ya no recibe `FAKE_CLAUDE_FIXTURE`, fake-claude reproduce su fixture por defecto (`01-simple-text`: un solo mensaje y sin herramientas) en vez de `02-tools`.
3. En el log del runner aparece que la invocación pide variables que el runner no permite: `[OTRA]`. `OTRA` no llega al agente.
4. Vuelve a marcar «todas las variables» y guarda.

## 5. El presupuesto lo corta el runner

1. Lanza con presupuesto `0.01`. El primer mensaje de `02-tools` ya cuesta unos 0,03 US$.
2. El agente termina en «Fallida» con «Presupuesto agotado: el coste estimado (… US$) supera el máximo de 0.01 US$», sin esperar al final del turno.
3. En la línea de tiempo, el fin del proceso (`agent.process.exited`) lleva `estimatedCostUsd`.
4. Con un límite holgado, una ejecución normal de `02-tools` termina bien y su `estimatedCostUsd` coincide con el coste del resultado (0,0574 US$).

## 6. Reintentos y continuaciones

1. Sube el presupuesto de la política a `1.5`, lanza con `1.2` y deja que termine.
2. Baja la política a `1` y pulsa «Reintentar» en el agente: el reintento lleva 1 US$, el límite del lanzamiento rebajado al de la política. Se ve en su `agent.spawned`, con el `curl` del paso 3.6 y el id de la ejecución nueva.
3. Envía un mensaje al agente terminado: la reanudación usa las herramientas y el modo de la política actual.

## 7. Volver a la global

En la política del repositorio, «Volver a la política global». El resumen dice «global» otra vez y el formulario vuelve a los valores globales.

## 8. Con Claude de verdad (opcional)

Con el runner apuntando al `claude` real y una política con `Read` y `Bash(git:*)` en modo `dontAsk`, pide al agente que ejecute `ls` y después `git status`. El primero aparece como permiso denegado en la línea de tiempo y el segundo funciona.
