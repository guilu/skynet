# Probar DS-C - Cron, cola por repositorio y web

DS-C (#208) es la tercera y última PR del hito DS (disparadores). Lo cuentan las §6, §7 y §8 de [`docs/disparadores.md`](../disparadores.md).

- **Disparadores programados**:
  - una regla `SCHEDULE` lanza su workflow a su hora (cron de Spring, con segundos, hora de Madrid), siempre sobre el mismo trabajo;
  - si la anterior sigue en marcha, ese disparo se salta;
  - tras una caída, dispara una sola vez;
  - «Disparar ya» lo adelanta.
- **Cola por repositorio**:
  - como mucho 2 ejecuciones a la vez por repositorio (`SKYNET_RUNS_MAX_CONCURRENT_PER_REPOSITORY`, o el suyo en su ficha). Vale para todas, también las de la web;
  - las demás esperan «En cola» y entran por orden de llegada;
  - una ejecución que solo espera a una persona no ocupa hueco.
- **Freno diario**: como mucho 10 ejecuciones al día lanzadas desde GitHub; se cambia en Ajustes → GitHub.
- **Aviso en la issue** cuando la ejecución espera tu aprobación: «Esperando tu aprobación en Skynet: **Aprobar el plan** de **DEMO-7**», con el enlace.
- **Web**:
  - **Ajustes → GitHub → Disparadores**: interruptor, quién puede lanzar, repositorios de órdenes, freno diario y últimas peticiones;
  - en la **ficha de cada repositorio**: «Ejecuciones a la vez» y «Disparadores» (lista, nuevo, activar, desactivar, borrar, «Disparar ya», próxima hora y último disparo);
  - en el trabajo, de dónde viene («Desde guilu/demo-repo#7» o «Programado»);
  - en la ejecución, «En cola: puesto N»;
  - en Actividad, frases para `trigger.*` y `workflow.queued`.
- **Migración `V37`**:
  - `repository.max_concurrent_runs`;
  - en `trigger_rule`, el tipo `SCHEDULE` y sus columnas (`cron`, `time_zone`, `work_item_title`, `work_item_id`, `next_fire_at`, `last_*`);
  - índices de la cola.

  Se aplica sola al arrancar.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Las secciones 1 a 3 no llaman al GitHub real; la 4 sí, solo sobre `guilu/demo-repo`. La sección 5 retira el puente `guilu/skynet-commands` y solo la hace Diego.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*RunQueueIT' --tests '*TriggerScheduleIT' \
  --tests '*IssueTriggerIT' --tests '*TriggerCommandIT' --tests '*TriggerContractIT' \
  --tests '*RunQueueContractIT' --tests '*ModularityTest'
cd web && npx vitest run src/Triggers.test.tsx src/contracts.test.ts src/format.test.ts
```

Los IT necesitan PostgreSQL, con Docker o con `SKYNET_TEST_DB_URL`.

- `RunQueueIT`:
  - con límite 1, la segunda y la tercera ejecución quedan `PENDING` con `workflow.queued`, y la de otro repositorio no espera;
  - el puesto en la cola es 2 para la tercera;
  - al cancelar la primera, entra la segunda; cancelar una en cola la cierra;
  - subir el límite deja entrar enseguida;
  - `null` vuelve al general y 0 es un 400.
- `TriggerScheduleIT`:
  - un programado lanza a su hora y crea su trabajo;
  - el siguiente disparo se salta si la anterior sigue en marcha;
  - con el interruptor apagado queda `REJECTED`;
  - un cron mal escrito, una zona que no existe o una etiqueta en un programado dan 400.
- `IssueTriggerIT`: además, el freno diario rechaza el lanzamiento número 11 y lo dice en la issue.
- `TriggerCommandIT`: además, cuando la puerta espera, la issue recibe «Esperando tu aprobación en Skynet: **Aprobar el plan** de **DEMO-1**» con el enlace a la aprobación.
- `TriggerContractIT` y `RunQueueContractIT`: las vistas nuevas coinciden con `fixtures/contracts/trigger-*.json` y `run-queue*.json`, que también usa la web.
- `Triggers.test.tsx`:
  - en Ajustes, el interruptor y «Hoy van 3 de 10»;
  - en la ficha, la cola, los disparadores con su próxima hora, «Disparar ya» y cambiar el límite.

La E2E `web/e2e/triggers.e2e.ts` hace lo mismo de punta a punta contra el GitHub falso: enciende, una etiqueta lanza y avisa, un programado se crea y se dispara, y con límite 1 la segunda ejecución espera «En cola». Corre en CI.

## 2. La cola, en la web (sin GitHub)

1. Abre un proyecto con `demo-repo` → **Repositorios**. En la ficha de `demo-repo` hay dos secciones nuevas:
   - **Ejecuciones a la vez**: «Como mucho 2 · ahora 0 en marcha», con la etiqueta «general»;
   - **Disparadores**.
2. **Ejecuciones a la vez → Cambiar**, escribe `1` y **Guardar**. La etiqueta pasa a «propio».
3. Publica este workflow en **Workflows → Nuevo**. Tarda 60 segundos y no usa agentes:

   ```yaml
   id: ds-c-espera
   name: Espera de DS-C
   stages:
     - id: esperar
       type: command
       command: sleep 60
   ```

4. Crea un trabajo y lánzale **Espera de DS-C** dos veces seguidas en `demo-repo`.
   - La primera está «En curso».
   - La segunda dice en la cabecera: «**En cola**: puesto 1. Su repositorio tiene 1 de 1 ejecución a la vez en marcha; entra sola cuando quede un hueco.»
   - En **Ejecuciones**, la segunda sale «Pendiente» (el filtro «En cola» la encuentra). En su Actividad, «En cola: el repositorio ya tiene 1 en marcha (límite 1)».
   - En la ficha del repositorio: «ahora 1 en marcha y 1 en cola (la siguiente)».
5. Cuando la primera termina, la segunda empieza sola: la cabecera deja de decir «En cola».
6. Vuelve a lanzarla dos veces y **cancela la que está en cola**: queda «Cancelada» sin haber empezado, y la otra sigue.
7. **Ejecuciones a la vez → Cambiar**, deja el campo vacío y **Guardar**: vuelve a «general» (2).

## 3. Disparadores programados y Ajustes (sin GitHub)

1. **Ajustes → GitHub → Disparadores**. Sin App configurada, la sección sale igual:
   - «Apagados»;
   - «Hoy van 0 de 10»;
   - el formulario de quién puede lanzar, repositorios de órdenes y «Ejecuciones al día desde GitHub»;
   - «Últimas peticiones».
2. Pulsa **Encender**: sale «Activos».
3. En la ficha de `demo-repo`, **Disparadores → Nuevo disparador**:
   - «A una hora (cron)»;
   - workflow **Espera de DS-C**;
   - cuándo `0 0 3 * * *`;
   - título «Limpieza de DS-C»;
   - **Crear disparador**.

   En la lista sale `0 0 3 * * *` (Europe/Madrid) con «Próxima:» mañana a las 3:00.
4. Pulsa **Disparar ya**. En unos 15 segundos, recarga: «Última: <ahora>, **lanzó** (ver)». «ver» abre la ejecución, en el trabajo «Limpieza de DS-C». En el trabajo, la cabecera dice «Programado».
5. Mientras esa ejecución sigue (60 s), vuelve a pulsar **Disparar ya** y recarga a los 15 s: «**se saltó** La ejecución anterior sigue en marcha». No hay ejecución nueva.
6. **Apagar** en Ajustes, **Disparar ya** otra vez y recarga a los 15 s: «**no lanzó** Los disparadores están apagados (Ajustes → GitHub)».
7. En **Actividad**, las frases «Disparador programado (0 0 3 * * *) lanzó ds-c-espera», «… saltado: la ejecución anterior sigue en marcha» y «… no lanzó: Los disparadores están apagados…».
8. **Desactivar** el disparador: sale «Desactivado» y «Sin próxima hora», y ya no ofrece «Disparar ya». **Borrar** lo quita de la lista. El trabajo «Limpieza de DS-C» se queda (archívalo si quieres).
9. **El freno diario**: en Ajustes, pon «Ejecuciones al día desde GitHub» a `0` y **Guardar**. Sale «Hoy van 0 de 0». Con la API:

   ```bash
   api() { curl -s -u admin:<contraseña> -H 'Content-Type: application/json' "$@"; }
   api http://localhost:8080/api/triggers/settings | jq '{enabled, dailyLimit, launchedToday}'
   ```

   Ponlo otra vez a `10`.

Para comprobar que la etiqueta respeta el freno sin GitHub, repite los pasos 1 a 5 de la sección 2 de [`probar-ds-a.md`](probar-ds-a.md) con `dailyLimit: 0`. La petición queda `REJECTED` con «Límite diario alcanzado: ya van 0 ejecuciones lanzadas desde GitHub hoy…».

## 4. De punta a punta con la App real y `guilu/demo-repo` (manual)

Esta parte crea una issue y una PR de verdad, **solo en `guilu/demo-repo`**. Hermes la ejecuta si su despliegue tiene:

- la App `skynet-guilu` configurada con el túnel;
- `SKYNET_PUBLIC_URL`;
- `demo-repo` registrado con la URL remota `https://github.com/guilu/demo-repo.git`;
- publicada la plantilla con PR y puerta «Especificar, planificar e implementar».

Si no, salta a la sección 6.

1. En Skynet:
   - **Ajustes → GitHub → Disparadores**: **Encender**. Deja «Quién puede lanzar» vacío (las cuentas de la instalación, `guilu`) y el freno en 10.
   - En la ficha de `demo-repo` → **Disparadores → Nuevo disparador**: «Desde GitHub», la plantilla de especificar, planificar e implementar, sin etiqueta. Sale `skynet:<clave del workflow>`.
2. En `guilu/demo-repo`, abre una issue «DS-C: añadir resta()» con «Añade resta(a, b) a calc.py con un test». Ponle la etiqueta `skynet:<clave del workflow>`.
3. En unos segundos, en la issue:
   - 👀;
   - «En marcha: **<CLAVE>**…» con el enlace;
   - la etiqueta `skynet:en-curso`.
   
   En Skynet:
   - Ajustes → GitHub → **Últimas peticiones**: «Lanzada», con «Ver la ejecución»;
   - el trabajo dice «Desde guilu/demo-repo#<n>», con el enlace a la issue.
4. Cuando la ejecución llega a la puerta del plan, la issue recibe «Esperando tu aprobación en Skynet: **Aprobar el plan** de **<CLAVE>**», con el enlace a la aprobación, que abre la página de la aprobación.
5. En la issue, comenta `/skynet corrige Añade también un test con negativos`. La puerta corrige y, cuando vuelve a preguntar, la issue recibe otra vez el aviso, ahora con «(otra vez, tras la corrección)».
6. Aprueba en la web. La ejecución sigue hasta abrir la PR, con `Closes #<n>`.
7. **Deja GitHub como estaba**:
   - comenta `/skynet cancela` en la PR;
   - cierra la PR sin fusionarla;
   - borra la rama `skynet/<clave>`;
   - cierra la issue.
   
   En Skynet, apaga el interruptor y borra el disparador.

## 5. Retirar el puente `guilu/skynet-commands` (manual, solo Diego)

Solo cuando la sección 4 haya salido bien. Son los pasos de la §12 de [`docs/disparadores.md`](../disparadores.md):

1. **Apaga el lanzamiento del puente**: `SKYNET_LAUNCH_ENABLED=false` (o pídeselo a Hermes) y reinícialo. A partir de aquí solo lanza Skynet.
2. En Skynet:
   - **Ajustes → GitHub → Repositorios de órdenes**: `guilu/skynet-commands`, y **Guardar**;
   - en la ficha de cada repositorio destino, un disparador «Desde GitHub» por cada workflow que se lanzaba con el puente;
   - **Encender**.
3. **Prueba una orden** en `guilu/skynet-commands`: una issue con la etiqueta `skynet:run` y solo el bloque `skynet-order` (§4 de `disparadores.md`). Skynet comenta como la App («En marcha…»), y en Últimas peticiones sale «Lanzada». Cancélala si no la quieres.
4. En `guilu/skynet-commands` → Settings → Webhooks, **borra el webhook del puente**. La App ya recibe los eventos.
5. **Para el servicio del puente** y quítalo del arranque del equipo. Revoca su token de GitHub. Si usaba la contraseña del administrador de Skynet, cámbiala.
6. **Archiva** `guilu/skynet-commands` en GitHub (Settings → Archive this repository) si ya no hace falta como repositorio de órdenes; si sigue siéndolo, déjalo sin archivar, porque las órdenes llegan por la App.

## 6. Al terminar

- En Skynet, apaga el interruptor si no lo vas a usar.
- En la ficha de `demo-repo`, «Ejecuciones a la vez» vuelve a «general».
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
