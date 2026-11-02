# Disparadores

Con la GitHub App conectada ([`github.md`](github.md)), Skynet lanza workflows **desde GitHub, sin abrir la web** (hito DS): con una **etiqueta en una issue** (DS-A) y con **comentarios `/skynet`** y **revisiones que piden cambios** (DS-B, §5). Y **a una hora**, sin GitHub (DS-C, §6). Cada repositorio tiene además una **cola**: como mucho tantas ejecuciones a la vez, vengan de donde vengan (§7).

Todo se maneja desde la web: el interruptor, quién puede lanzar y el freno diario en **Ajustes → GitHub**; las reglas, los programados y la cola en la **ficha de cada repositorio** (Proyecto → Repositorios). La API (§11) hace lo mismo.

La App no necesita permisos nuevos: **Issues: lectura y escritura** y los eventos **Issues**, **Issue comment** y **Pull request review** se pidieron con GH.

## 1. Cómo funciona

1. En una issue de `guilu/demo-repo` pones la etiqueta `skynet:implementar-tarea`.
2. GitHub manda el webhook `issues.labeled`. Si `demo-repo` tiene una **regla** activa con esa etiqueta, Skynet apunta la petición.
3. Unos segundos después, la decide:
   - comprueba el **interruptor** y que **quien pone la etiqueta** y **quien escribió la issue** puedan lanzar;
   - busca el **trabajo de la issue** (`externalRef = github:guilu/demo-repo#7`) o lo crea con el título y el texto de la issue;
   - si ese trabajo ya tiene una ejecución en marcha, no lanza otra;
   - lanza la última versión publicada del workflow en ese repositorio.
4. En la issue, como la App: una reacción 👀, el comentario «En marcha: **DEMO-7** con *Implementar una tarea*», con el enlace a la ejecución si está `SKYNET_PUBLIC_URL`, y la etiqueta `skynet:en-curso`.
5. Si el workflow se para en una puerta, otro comentario: «Esperando tu aprobación en Skynet: **Aprobar el plan** de **DEMO-7**», con el enlace a la aprobación y el recordatorio de que desde GitHub se puede pedir cambios con `/skynet corrige`. Tras una corrección, cuando la puerta vuelve a preguntar, dice «(otra vez, tras la corrección)».
6. Si el workflow abre una PR (EN), su descripción lleva `Closes #7`: al fusionarla, GitHub cierra la issue.
7. Al terminar, otro comentario con el resultado, el coste y la PR, y la etiqueta cambia a `skynet:hecho` o `skynet:fallido`.

Volver a poner la etiqueta cuando la ejecución anterior ya terminó lanza otra **en el mismo trabajo**: va a la misma rama `skynet/<clave>` y actualiza la misma PR.

## 2. Activarlo

Todo empieza **apagado**. Hacen falta tres cosas. En la web: **Ajustes → GitHub → Disparadores** (interruptor y autorizados) y, en la ficha del repositorio, **Disparadores → Nuevo disparador**. Con la API:

**1. Una regla por repositorio y workflow.**

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/repositories/<id de demo-repo>/triggers \
  -d '{"workflowKey": "implementar-tarea"}'
```

| Campo | Por defecto | Qué es |
|---|---|---|
| `workflowKey` | (obligatorio) | el `id` del workflow; se lanza su última versión publicada |
| `label` | `skynet:<workflowKey>` | la etiqueta que lo lanza; empieza por `skynet:` |
| `workItemType` | `FEATURE` | el tipo del trabajo que se crea |
| `inputs` | `{}` | datos fijos del workflow; la issue no los puede cambiar |
| `inputPolicy` | `{}` | los datos que **sí** puede dar la issue (§3) |
| `enabled` | `true` | si está activa |

Las etiquetas `skynet:run`, `skynet:en-curso`, `skynet:hecho` y `skynet:fallido` las usa Skynet y no valen para una regla.

**2. Quién puede lanzar.** Por defecto, las cuentas donde está instalada la App (`guilu`). Si fijas una lista, solo valen esos logins:

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X PUT http://localhost:8080/api/triggers/settings -d '{"authorizedLogins": ["guilu"]}'
```

**3. El interruptor general.**

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X PUT http://localhost:8080/api/triggers/settings -d '{"enabled": true}'
```

Con `{"enabled": false}` se para todo de golpe: las peticiones que llegan quedan rechazadas y las ejecuciones ya lanzadas siguen.

## 3. Datos del workflow desde la issue

Si el workflow pide `inputs`, la issue los puede dar en un bloque `skynet`, en cualquier parte del texto:

````markdown
Hace falta revisar la cobertura del módulo de pagos.

```skynet
{"foco": "pagos"}
```
````

Solo se admiten los datos que nombra el `inputPolicy` de la regla, con su tipo (`string`, `text`, `number` o `boolean`), su largo máximo (`maxLength`, 1000 por defecto) y, si los tiene, sus valores permitidos (`allowedValues`):

```json
{"workflowKey": "investigar-mejoras",
 "inputPolicy": {"foco": {"type": "string", "maxLength": 40}}}
```

Un nombre que parezca una instrucción (`prompt`, `instruction`, `command`, `script`, `shell` o `message`) no se admite en una política. Después, el lanzamiento comprueba los datos otra vez contra los `inputs` del workflow.

## 4. Repositorio de órdenes

Es el contrato del puente `guilu/skynet-commands`, ahora dentro de Skynet. Un repositorio de órdenes **no tiene que estar en Skynet**, solo con la App instalada. Se marca en los ajustes:

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X PUT http://localhost:8080/api/triggers/settings -d '{"orderRepositories": ["guilu/skynet-commands"]}'
```

En él, la etiqueta es `skynet:run` y el cuerpo de la issue tiene que ser **solo** este bloque, con estos cinco campos y ninguno más:

````markdown
```skynet-order
{"skynet_project":"DEMO","target_repository":"demo-repo","workflow":"revisar-codigo","parameters":{"foco":"tests"},"description":"Revisa los tests"}
```
````

- `skynet_project` es la clave del proyecto y `target_repository`, el nombre del repositorio dentro de él.
- El repositorio destino tiene que tener una regla activa de ese `workflow`. Su `inputPolicy` decide qué `parameters` valen.
- El trabajo toma `description` como descripción, no el bloque. Su `externalRef` es la issue de órdenes, así que la PR lleva `Closes guilu/skynet-commands#n`.
- Los avisos (👀, comentarios y etiquetas) van a la issue de órdenes.

## 5. Comentarios `/skynet` y revisiones

Un comentario cuya **primera línea** empieza por `/skynet` es una orden. Vale en una issue y en una PR que abrió Skynet.

| Orden | Dónde | Qué hace |
|---|---|---|
| `/skynet <workflow> [clave=valor…]` | issue | lanza ese workflow, como su etiqueta; el repositorio necesita una regla activa de él |
| `/skynet run` | issue de un repositorio de órdenes | lanza la orden del cuerpo, como la etiqueta `skynet:run` |
| `/skynet corrige <qué cambiar>` | PR o issue | si la ejecución espera en una puerta, le pide los cambios (lo mismo que «Pedir cambios» en la web) |
| `/skynet cancela` | PR o issue | cancela la ejecución |
| `/skynet estado` | PR o issue | contesta con el estado, las fases, la PR y el enlace |

- En una PR, la ejecución es la que abrió la PR. En una issue, la última del trabajo de la issue.
- El texto de `corrige` es el resto de la primera línea y las líneas siguientes. Llega a la fase que corrige (el `revise` de la puerta), con el redactor de secretos aplicado.
- Los datos `clave=valor` de `/skynet <workflow>` se suman a los del bloque `skynet` de la issue, y pasan por el mismo `inputPolicy`. `alcance=tests` vale; con espacios en el valor, usa el bloque.
- **Una revisión que pide cambios** («Request changes») en una PR de Skynet es un `/skynet corrige` con el texto de la revisión. Si la revisión no tiene texto, la corrección enlaza la revisión.
- **No se puede aprobar ni fusionar desde GitHub**: `/skynet aprueba` o `fusiona` contestan que se haga en la web (hasta AU).
- Skynet reacciona con 👀 **al comentario** y contesta con otro. Si no puede, contesta «No puedo: <motivo>», por ejemplo «ahora no se puede corregir» cuando la puerta no está esperando.

Quien comenta o revisa tiene que estar autorizado, como quien pone la etiqueta. Para `corrige`, `cancela` y `estado` no cuenta el autor de la issue o la PR (las PR las abre la App). Para lanzar con `/skynet <workflow>`, sí, porque la issue entra en el trabajo.

Un comentario que no empieza por `/skynet` y una revisión en una PR que no es de Skynet no son para Skynet: quedan como un evento más en Actividad.

## 6. Disparadores programados (cron)

Un disparador **programado** lanza su workflow **a una hora**, siempre **sobre el mismo trabajo**: cada disparo es una ejecución nueva de ese trabajo. Sirve para una revisión semanal, una limpieza nocturna o un informe.

En la web: ficha del repositorio → **Disparadores** → **Nuevo disparador** → «A una hora (cron)». Con la API:

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/repositories/<id>/triggers \
  -d '{"kind": "SCHEDULE", "workflowKey": "revisar-codigo", "cron": "0 0 9 * * MON",
       "inputs": {"foco": "tests"}}'
```

| Campo | Por defecto | Qué es |
|---|---|---|
| `kind` | `ISSUE` | `SCHEDULE` para un programado |
| `cron` | (obligatorio) | expresión cron de Spring, **con segundos**: `0 0 9 * * MON` es los lunes a las 9:00 |
| `timeZone` | `Europe/Madrid` | la zona de la hora |
| `workItemTitle` | `<workflow> (programado)` | el título del trabajo, que se crea en el primer disparo |
| `inputs` | `{}` | los datos del workflow, fijos |

Un programado no lleva etiqueta ni `inputPolicy`: no recibe datos de fuera.

Cómo se comporta:

- **El interruptor manda**: con los disparadores apagados, el disparo queda `REJECTED` («Los disparadores están apagados») y espera a la hora siguiente.
- **Si la anterior sigue en marcha**, ese disparo **se salta** (`SKIPPED`). No se acumulan.
- **Si Skynet estaba apagado** a la hora, al volver dispara **una sola vez**, aunque se perdiera más de una hora: la siguiente se calcula desde ese momento.
- **Varias instancias** del control plane se reparten los disparos sin repetirlos (`FOR UPDATE SKIP LOCKED`). La hora siguiente se guarda antes de lanzar: si el control plane cae a medias, ese disparo se pierde, pero nunca se lanza dos veces.
- **«Disparar ya»** (`POST /api/triggers/{id}/fire`) lo adelanta a ahora; lo recoge el planificador en unos segundos (`skynet.triggers.schedule-interval`, 15 s).
- El freno diario (§8) **no** cuenta los programados: es para lo que llega de GitHub.

Cada regla enseña su **próxima hora** (`schedule.nextFireAt`) y el **último disparo**: cuándo, qué pasó (`lastOutcome`: `LAUNCHED`, `SKIPPED` o `REJECTED`), el motivo y la ejecución. El trabajo lleva `externalRef = skynet:schedule:<id de la regla>` y en la web sale como «Programado».

## 7. Cola por repositorio

Cada repositorio admite **como mucho N ejecuciones a la vez** (2 por defecto). Vale para **todas**: las de la web, las de GitHub y las programadas. La que llega cuando ya están todas ocupadas **espera en cola** (`PENDING`, «En cola» en la web) y entra sola, **por orden de llegada**, en cuanto queda un hueco.

- **Una ejecución que espera a una persona no ocupa hueco**: si todas sus fases vivas esperan una aprobación o una respuesta, la siguiente puede entrar.
- Cancelar una ejecución en cola la cierra sin que llegue a empezar.
- Las ejecuciones hijas (las que lanza otra ejecución) no hacen cola: van con su madre.
- En la ejecución, la cabecera dice «**En cola**: puesto 1. Su repositorio tiene 2 de 2 ejecuciones a la vez en marcha». En Actividad, el evento `workflow.queued`.

El límite general se cambia con `SKYNET_RUNS_MAX_CONCURRENT_PER_REPOSITORY` (de 1 a 50). Cada repositorio puede tener el suyo, en su ficha (**Ejecuciones a la vez → Cambiar**) o con la API:

```bash
curl -s -u admin:<contraseña> -H 'Content-Type: application/json' \
  -X PUT http://localhost:8080/api/repositories/<id>/run-queue -d '{"maxConcurrentRuns": 1}'
```

Con `{"maxConcurrentRuns": null}` vuelve al general. Al subirlo, las que esperan entran enseguida.

## 8. Freno diario

Como mucho **10 ejecuciones al día lanzadas desde GitHub** (etiquetas, `/skynet <workflow>` y repositorios de órdenes), contadas desde las 00:00 en Madrid. La siguiente queda rechazada y se contesta en la issue: «Límite diario alcanzado: ya van 10 ejecuciones lanzadas desde GitHub hoy». Las órdenes `corrige`, `cancela` y `estado` no cuentan, ni los programados ni lo que se lanza desde la web.

Se cambia en **Ajustes → GitHub → Ejecuciones al día desde GitHub** (de 0 a 1000; 0 es ninguna), o con `PUT /api/triggers/settings` y `{"dailyLimit": 20}`. La web enseña «Hoy van N de M».

## 9. Seguridad

El texto de una issue **no es de confianza**. Hasta que llegue el hito SE, que lo marcará como tal dentro del prompt:

- Solo lanzan **los autorizados**, y la issue también la tiene que haber escrito uno: una issue de un desconocido no lanza nada aunque tú le pongas la etiqueta.
- A quien no está autorizado **no se le contesta**. La petición queda rechazada, con el motivo, en `GET /api/triggers/launches`.
- El título y el cuerpo pasan por el redactor de secretos y se recortan (300 y 20.000 caracteres). Solo cuenta el cuerpo en el momento de la etiqueta o del comentario. De los comentarios solo entra el texto de `/skynet corrige`, de un autorizado.
- Nada se fusiona solo: las puertas de los workflows siguen esperando tu aprobación en la web.
- Los avisos usan un token de la App limitado al repositorio y a `issues: write`.

## 10. Qué puede salir mal

Cada petición queda en `GET /api/triggers/launches` con su estado:

| Estado | Qué pasó |
|---|---|
| `QUEUED` | recibida, sin decidir todavía |
| `LAUNCHED` | lanzada; la ejecución está en marcha |
| `FINISHED` | la ejecución terminó |
| `DUPLICATE` | el trabajo ya tenía una ejecución en marcha; se comenta y no se lanza otra |
| `DONE` | una orden `corrige`, `cancela` o `estado` hecha |
| `REJECTED` | no se lanzó o no se hizo; `reason` dice por qué |

Las órdenes `/skynet` van con `mode = COMMAND` (salvo las que lanzan: `ISSUE` u `ORDER`), su primera línea en `command` y `pullRequest` si son de una PR.

Los rechazos por el interruptor o por permisos no se comentan en GitHub. Los demás (workflow sin publicar, datos que no valen, orden mal escrita, trabajo archivado…) sí, con «No lo lanzo: <motivo>», o «No puedo: <motivo>» en una orden.

Si GitHub falla al avisar, el aviso se reintenta con espera creciente, hasta 5 veces, y queda su error en `noticeError`. El lanzamiento nunca se repite por eso. Una etiqueta sin regla es un evento `github.issues.labeled` más, en Actividad.

## 11. API

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/triggers/settings` | interruptor, autorizados (y si son los de la instalación), repositorios de órdenes, `dailyLimit` y `launchedToday` |
| `PUT` | `/api/triggers/settings` | cambia lo que llegue: `enabled`, `authorizedLogins`, `orderRepositories`, `dailyLimit` |
| `GET` | `/api/repositories/{id}/triggers` | las reglas del repositorio, con `schedule` en los programados |
| `POST` | `/api/repositories/{id}/triggers` | crea una regla (409 si la etiqueta ya está en ese repositorio) |
| `GET`, `PUT`, `DELETE` | `/api/triggers/{id}` | lee, cambia o borra una regla |
| `POST` | `/api/triggers/{id}/fire` | adelanta un programado a ahora (400 si no es programado, 409 si está desactivado) |
| `GET` | `/api/triggers/launches?limit=&repositoryId=` | las últimas peticiones, sin el cuerpo de la issue |
| `GET`, `PUT` | `/api/repositories/{id}/run-queue` | el límite del repositorio (`maxConcurrentRuns`, `custom`, `defaultLimit`), las que ocupan hueco (`running`) y las que esperan (`queued`) |
| `GET` | `/api/workflow-runs/{id}/queue` | el puesto de una ejecución en la cola (`position`, 0 si no está en cola) |

Eventos en el agregado `repository`: `trigger.launched`, `trigger.duplicate` y `trigger.rejected` (este último solo cuando se sabe el repositorio). Las órdenes: `trigger.command.corrige`, `trigger.command.cancela`, `trigger.command.estado` y `trigger.command.rejected`. Los programados: `trigger.schedule.launched`, `trigger.schedule.skipped` y `trigger.schedule.rejected`. En la ejecución, `workflow.queued` cuando entra en cola. Todos tienen su frase en Actividad.

## 12. Del puente `skynet-commands` a Skynet

El puente en Python hacía lo mismo desde fuera, con su propio webhook, su cola en SQLite, la contraseña de Skynet y un token de GitHub. Ahora:

| Puente | Skynet |
|---|---|
| `AUTHORIZED_ACTORS` | `authorizedLogins` (y además el autor de la issue) |
| `WORKFLOW_INPUT_POLICY` | el `inputPolicy` de cada regla |
| `SKYNET_LAUNCH_ENABLED` | el interruptor `enabled` |
| repositorio fijo `guilu/skynet-commands` | `orderRepositories` |
| etiqueta `skynet:run` y bloque `skynet-order` | igual |
| comentario con `GITHUB_TOKEN` | comentario como la App |
| aviso por Telegram | no está en Skynet |

El puente se retira cuando la guía de DS-C pasa de punta a punta en `guilu/demo-repo` ([`tests/probar-ds-c.md`](tests/probar-ds-c.md), parte B):

1. En el puente, `SKYNET_LAUNCH_ENABLED=false` y reinícialo. A partir de aquí, solo Skynet lanza.
2. En Skynet, `guilu/skynet-commands` en **Ajustes → GitHub → Repositorios de órdenes**, y una regla activa del workflow en cada repositorio destino.
3. Comprueba con una orden de prueba (`skynet:run`) que Skynet la lanza y comenta como la App.
4. En `guilu/skynet-commands` → Settings → Webhooks, borra el webhook del puente. La App ya recibe los eventos con el suyo.
5. Para el servicio del puente y quítalo del arranque del equipo. Revoca su token de GitHub y cambia la contraseña de Skynet que usaba, si era la del administrador.
6. Archiva el repositorio del puente en GitHub (Settings → Archive this repository).
