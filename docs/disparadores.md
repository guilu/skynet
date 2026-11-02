# Disparadores

Con la GitHub App conectada ([`github.md`](github.md)), Skynet lanza workflows **desde GitHub, sin abrir la web** (hito DS): con una **etiqueta en una issue** (DS-A) y con **comentarios `/skynet`** y **revisiones que piden cambios** (DS-B, §5). El cron, la cola por repositorio y la web de los disparadores llegan con DS-C.

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
5. Si el workflow abre una PR (EN), su descripción lleva `Closes #7`: al fusionarla, GitHub cierra la issue.
6. Al terminar, otro comentario con el resultado, el coste y la PR, y la etiqueta cambia a `skynet:hecho` o `skynet:fallido`.

Volver a poner la etiqueta cuando la ejecución anterior ya terminó lanza otra **en el mismo trabajo**: va a la misma rama `skynet/<clave>` y actualiza la misma PR.

## 2. Activarlo

Todo empieza **apagado**. Hacen falta tres cosas.

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

## 6. Seguridad

El texto de una issue **no es de confianza**. Hasta que llegue el hito SE, que lo marcará como tal dentro del prompt:

- Solo lanzan **los autorizados**, y la issue también la tiene que haber escrito uno: una issue de un desconocido no lanza nada aunque tú le pongas la etiqueta.
- A quien no está autorizado **no se le contesta**. La petición queda rechazada, con el motivo, en `GET /api/triggers/launches`.
- El título y el cuerpo pasan por el redactor de secretos y se recortan (300 y 20.000 caracteres). Solo cuenta el cuerpo en el momento de la etiqueta o del comentario. De los comentarios solo entra el texto de `/skynet corrige`, de un autorizado.
- Nada se fusiona solo: las puertas de los workflows siguen esperando tu aprobación en la web.
- Los avisos usan un token de la App limitado al repositorio y a `issues: write`.

## 7. Qué puede salir mal

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

## 8. API

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/triggers/settings` | interruptor, autorizados (y si son los de la instalación) y repositorios de órdenes |
| `PUT` | `/api/triggers/settings` | cambia lo que llegue: `enabled`, `authorizedLogins`, `orderRepositories` |
| `GET` | `/api/repositories/{id}/triggers` | las reglas del repositorio |
| `POST` | `/api/repositories/{id}/triggers` | crea una regla (409 si la etiqueta ya está en ese repositorio) |
| `GET`, `PUT`, `DELETE` | `/api/triggers/{id}` | lee, cambia o borra una regla |
| `GET` | `/api/triggers/launches?limit=&repositoryId=` | las últimas peticiones, sin el cuerpo de la issue |

Eventos en el agregado `repository`: `trigger.launched`, `trigger.duplicate` y `trigger.rejected` (este último solo cuando se sabe el repositorio). Las órdenes: `trigger.command.corrige`, `trigger.command.cancela`, `trigger.command.estado` y `trigger.command.rejected`.

## 9. Del puente `skynet-commands` a Skynet

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

Cuando DS-A pase su guía, se apaga el puente (`SKYNET_LAUNCH_ENABLED=false`), se quita su webhook del repositorio y se archiva. Los pasos están en la guía de DS-C.
