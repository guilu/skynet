# Probar DS-B - Comentarios /skynet y revisiones

DS-B (#207) es la segunda PR del hito DS (disparadores). Con ella, un **comentario `/skynet`** en una issue o en una PR de Skynet, o una **revisión que pide cambios**, actúa sobre la ejecución sin abrir la web. Lo cuenta la §5 de [`docs/disparadores.md`](../disparadores.md).

- **Órdenes** (solo cuenta la primera línea):
  - `/skynet <workflow> [clave=valor…]` en una issue lanza ese workflow, como su etiqueta;
  - `/skynet run` en una issue de un repositorio de órdenes;
  - `/skynet corrige <qué cambiar>` pide los cambios en la puerta que espera;
  - `/skynet cancela` y `/skynet estado`.
- **Revisión «Request changes»** en una PR de Skynet = `/skynet corrige` con su texto.
- **No** se puede aprobar ni fusionar desde GitHub: contesta que se haga en la web.
- Quien comenta tiene que estar autorizado. A quien no lo está no se le contesta.
- La reacción 👀 va **al comentario**, y la respuesta es otro comentario («No puedo: <motivo>» si no se puede).
- **Migración `V36`**: modo `COMMAND`, estado `DONE` y las columnas `comment_id`, `command` y `pull_request` de `trigger_launch`. Se aplica sola al arrancar.
- **Arreglo de DS-A:** al marcar un aviso como mandado fallaba una sentencia SQL, así que cada aviso se reenviaba hasta 5 veces y quedaba con `noticeError`. Ahora se manda una vez.

El cron, la cola por repositorio y la web de los disparadores llegan con DS-C.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Las secciones 1 y 2 no llaman al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*TriggerCommandIT' --tests '*IssueTriggerIT' \
  --tests '*SkynetCommandTest' --tests '*GitHubWebhooksIT' --tests '*ModularityTest'
```

Los IT necesitan PostgreSQL, con Docker o con `SKYNET_TEST_DB_URL`.

- `TriggerCommandIT`, con el GitHub falso, entregas firmadas y un runner simulado:
  - la etiqueta lanza un workflow con puerta; el plan termina y la puerta espera;
  - `/skynet corrige Añade los tests de resta()` en la PR de la ejecución: la puerta pasa a «Pidió cambios», firmada por `github:guilu` y con ese comentario. En la PR, «Pido los cambios en **DEMO-1**…» y 👀 en el comentario;
  - otra corrección mientras corrige: `REJECTED` y «No puedo: ahora no se puede corregir…»;
  - terminada la corrección, la puerta vuelve a preguntar y una **revisión que pide cambios** la corrige con su texto;
  - una revisión en una PR que no es de Skynet no deja petición;
  - en la issue: `/skynet estado` contesta con el workflow, el estado, las fases y el enlace; `aprueba` y una orden desconocida se rechazan con el motivo; un intruso no recibe respuesta; `/skynet cancela` cancela, y la petición que lanzó la ejecución avisa «Cancelada»;
  - un comentario que no empieza por `/skynet` no deja petición;
  - `/skynet tarea alcance=tests` lanza `tarea` con ese dato; un valor no permitido se rechaza, y una issue de un intruso tampoco lanza.
- `IssueTriggerIT` (de DS-A): además, cada aviso se manda una sola vez y ninguno queda con `noticeError`.
- `SkynetCommandTest`: qué cuenta como orden, el texto de `corrige` y los `clave=valor`.
- `GitHubWebhooksIT`: las entregas de siempre siguen igual.

## 2. Entregas firmadas a mano, con una App de mentira

Como en la sección 2 de [`probar-ds-a.md`](probar-ds-a.md): Skynet recibe entregas firmadas con `curl` y no hay GitHub de verdad. **Los avisos en GitHub fallan** (se ven en `noticeError`); lo que se comprueba es la petición y su efecto en Skynet. El runner usa fake-claude.

1. Haz los pasos 1, 2 y 3 de la sección 2 de `probar-ds-a.md` (clave de usar y tirar, `deploy/.env` con el secreto `secreto-ds-a`, las ayudas `api`, `B`, `issue`, y `R` y `REPO`). Añade estas dos ayudas:

   ```bash
   envia() { # envia <fichero> <delivery> [evento]
     sig=$(openssl dgst -sha256 -hmac secreto-ds-a -binary "$1" | xxd -p -c 256)
     curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/api/github/webhooks \
       -H 'Content-Type: application/json' -H "X-GitHub-Event: ${3:-issues}" \
       -H "X-GitHub-Delivery: $2" -H "X-Hub-Signature-256: sha256=$sig" --data-binary @"$1"
   }
   comenta() { # comenta <n> <remitente> <texto> > fichero
     jq --argjson n "$1" --arg s "$2" --arg t "$3" \
       '.issue.number=$n | .issue.html_url="https://github.com/\(env.REPO)/issues/\($n)"
        | del(.issue.pull_request) | .comment.user.login=$s | .sender.login=$s
        | .comment.body=$t | .repository.full_name=env.REPO' \
       fixtures/github/issue_comment-created.json
   }
   ```

2. Publica este workflow en **Workflows → Nuevo**. Tiene una puerta que corrige el plan:

   ```yaml
   id: ds-b-puerta
   name: Puerta de DS-B
   inputs:
     alcance:
       type: string
       required: false
   stages:
     - id: plan
       type: agent
       prompt: "Planifica: {{workItem.title}}. Alcance: {{inputs.alcance}}"
     - id: aprobar
       name: Aprobar el plan
       type: human-approval
       dependsOn: [plan]
       action: "Aprobar el plan de {{workItem.key}}"
   ```

3. Crea la regla, autoriza y enciende:

   ```bash
   api -X POST $B/repositories/$R/triggers \
     -d '{"workflowKey":"ds-b-puerta","inputPolicy":{"alcance":{"type":"string","allowedValues":["tests"]}}}' | jq .label
   api -X PUT $B/triggers/settings -d '{"authorizedLogins":["guilu"],"enabled":true}' | jq '{enabled, authorizedLogins}'
   ```

   Sale `skynet:ds-b-puerta`, y `enabled: true` con `["guilu"]`.

4. **Lanzar con un comentario:**

   ```bash
   comenta 201 guilu '/skynet ds-b-puerta alcance=tests' > /tmp/ds-b-1.json && envia /tmp/ds-b-1.json ds-b-1 issue_comment
   sleep 4; api "$B/triggers/launches?limit=1" | jq '.[0] | {issueNumber, label, mode, command, status, workflowRunId}'
   ```

   Escribe `202` y sale `LAUNCHED`, con `label: "skynet:ds-b-puerta"`, `mode: "ISSUE"` y `command: "/skynet ds-b-puerta alcance=tests"`. En la web, el prompt de `plan` dice «Alcance: tests».

5. Espera a que fake-claude termine el plan (unos segundos): en **Aprobaciones** aparece «Aprobar el plan de <clave>».

6. **Estado y corrección:**

   ```bash
   comenta 201 guilu '/skynet estado' > /tmp/ds-b-2.json && envia /tmp/ds-b-2.json ds-b-2 issue_comment
   comenta 201 guilu '/skynet corrige Añade un paso para los tests' > /tmp/ds-b-3.json && envia /tmp/ds-b-3.json ds-b-3 issue_comment
   sleep 4; api "$B/triggers/launches?limit=2" | jq '.[] | {command, mode, status, reason}'
   ```

   - las dos salen `DONE` en modo `COMMAND`;
   - en la web, la puerta pasa a «Corrigiendo», y en su historial está «Pedir cambios» de `github:guilu` con «Añade un paso para los tests»;
   - el plan se reanuda con ese comentario y, al terminar, la puerta vuelve a preguntar.

7. **Lo que no se puede:**

   ```bash
   comenta 201 guilu '/skynet aprueba' > /tmp/ds-b-4.json && envia /tmp/ds-b-4.json ds-b-4 issue_comment
   comenta 201 guilu '/skynet baila' > /tmp/ds-b-5.json && envia /tmp/ds-b-5.json ds-b-5 issue_comment
   comenta 202 guilu '/skynet estado' > /tmp/ds-b-6.json && envia /tmp/ds-b-6.json ds-b-6 issue_comment
   comenta 201 intruso '/skynet cancela' > /tmp/ds-b-7.json && envia /tmp/ds-b-7.json ds-b-7 issue_comment
   sleep 4; api "$B/triggers/launches?limit=4" | jq '.[] | {issueNumber, command, status, reason, noticePending, noticeError}'
   ```

   Salen cuatro `REJECTED`:
   - «desde GitHub no se puede aprobar ni fusionar; hazlo en la web»;
   - «no conozco la orden `baila`. Admito…»;
   - la 202: «esta issue no tiene ninguna ejecución de Skynet»;
   - el intruso: «@intruso no está entre quienes pueden lanzar desde GitHub», **sin** aviso (`noticePending: false` y `noticeError: null`). Las otras tres tienen aviso.

8. **Un comentario normal:**

   ```bash
   comenta 201 guilu 'Gracias, /skynet' > /tmp/ds-b-8.json && envia /tmp/ds-b-8.json ds-b-8 issue_comment
   sleep 4; api "$B/github/deliveries?limit=1" | jq '.[0] | {deliveryId, status}'
   api "$B/triggers/launches?limit=1" | jq '.[0].command'
   ```

   La entrega sale `PROCESSED` y la última petición sigue siendo `/skynet cancela`.

9. **Cancelar:**

   ```bash
   comenta 201 guilu '/skynet cancela' > /tmp/ds-b-9.json && envia /tmp/ds-b-9.json ds-b-9 issue_comment
   sleep 4; api "$B/triggers/launches?limit=1" | jq '.[0] | {command, status}'
   ```

   Sale `DONE` y, en la web, la ejecución queda cancelada. La petición del paso 4 pasa a `FINISHED`.

10. **Una revisión en una PR que no es de Skynet:**

    ```bash
    jq '.repository.full_name=env.REPO' fixtures/github/pull_request_review-submitted.json > /tmp/ds-b-10.json
    envia /tmp/ds-b-10.json ds-b-10 pull_request_review
    sleep 4; api "$B/github/deliveries?limit=1" | jq '.[0] | {deliveryId, status}'
    api "$B/triggers/launches?limit=1" | jq '.[0].command'
    ```

    La entrega sale `PROCESSED` y no hay petición nueva: la PR 12 no es de ninguna ejecución.

11. Ningún secreto en el log: `docker compose -f deploy/docker-compose.yml logs api | grep -c secreto-ds-a` escribe `0`.

12. Deja el entorno como estaba:
    - `api -X PUT $B/triggers/settings -d '{"enabled":false}'`;
    - borra la regla: `api -X DELETE $B/triggers/$(api $B/repositories/$R/triggers | jq -r '.[0].id')`;
    - quita las tres líneas de `deploy/.env`, `rm ~/skynet-ds-a.pem /tmp/ds-b-*.json` y `docker compose -f deploy/docker-compose.yml up -d`;
    - archiva el trabajo de prueba si quieres.

## 3. Con la App real y `guilu/demo-repo` (manual, opcional)

Esta parte comenta en una issue y una PR de verdad. Hermes solo la ejecuta si su despliegue tiene la App `skynet-guilu` configurada con el túnel, y `demo-repo` registrado con la URL remota `https://github.com/guilu/demo-repo.git` y una plantilla con PR y puerta publicada (`implementar-tarea`). Si no, salta a la sección 4.

1. Con la regla de `implementar-tarea` y el interruptor encendido (sección 3 de `probar-ds-a.md`), abre en `guilu/demo-repo` una issue «DS-B: añadir resta()» y comenta `/skynet implementar-tarea`.
2. En unos segundos: 👀 en **tu comentario**, el comentario «En marcha: **<CLAVE>**…» y la etiqueta `skynet:en-curso`. Ningún comentario sale repetido.
3. Comenta `/skynet estado`: contesta con las fases y el enlace a la ejecución.
4. Cuando la PR esté abierta y la puerta espere, pide cambios en la PR con **Review changes → Request changes** y el texto «Añade un test con negativos». En Skynet, la puerta pasa a «Corrigiendo» con ese texto, y la PR recibe «Pido los cambios en **<CLAVE>**…».
5. En la PR, comenta `/skynet aprueba`: contesta «No puedo: desde GitHub no se puede aprobar ni fusionar; hazlo en la web.».
6. **Deja GitHub como estaba:** comenta `/skynet cancela` en la PR (la issue recibe «Cancelada…» y `skynet:fallido`), cierra la PR sin fusionarla, borra la rama `skynet/<clave>` y cierra la issue. Apaga el interruptor.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
