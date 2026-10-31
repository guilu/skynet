# Probar EN-A - Subir la rama y abrir la PR

EN-A (#200) es la primera PR del hito EN (entrega por PR). Trae la fase **`github-pr`**: sube el worktree que hereda a GitHub y abre una PR con lo que ha hecho el workflow.

- **YAML:**
  - `type: github-pr`, con un bloque opcional `pullRequest`: `title`, `body`, `base`, `draft` y `labels`;
  - la condición `repository.github`, que es cierta si el repositorio está en GitHub con acceso. Así una plantilla con PR sigue funcionando en un repositorio local.
- **Runner:**
  - las órdenes de push (`StartAgent.push`) confirman lo pendiente y suben `HEAD` a `skynet/<clave del trabajo>`;
  - el token lo pide justo antes (`POST /api/runner/agent-runs/{id}/push-token`) y se lo da a git como cabecera;
  - no se sube nada que toque `.github/workflows/`.
- **Control plane:**
  - cuando el push termina bien, la fase sigue en marcha hasta que se abre la PR (o se actualiza la que ya había);
  - la descripción lleva el trabajo, las fases anteriores con su resumen y sus comprobaciones, y el coste;
  - eventos `agent.git.pushed`, `stage.github.pr.opened`, `stage.github.pr.updated` y `stage.github.pr.failed`;
  - `GET /api/workflow-runs/{id}/pull-requests`;
  - variable nueva `SKYNET_PUBLIC_URL`, opcional, para enlazar la ejecución desde la PR.
- **Migración `V32`** (`github_pull_request`), que se aplica sola al arrancar.

Esperar la CI llega con EN-B. Fusionar, la tarjeta de la PR en la web y las plantillas con las fases nuevas, con EN-C.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Las secciones 1 y 2 no llaman al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :runner:test --tests '*GitPushTest' --tests '*AgentExecutorTest.aPush*'
./gradlew :control-plane:test --tests '*PullRequestDeliveryIT' --tests '*DefinitionParserTest'
```

- `GitPushTest` sube a un repositorio *bare* local. Comprueba que:
  - confirma lo pendiente y la rama remota queda en `HEAD`;
  - un segundo push añade commits a la misma rama;
  - si la historia cambia, hace falta `force`;
  - un cambio en `.github/workflows/` se rechaza sin subir nada;
  - sin commits nuevos es un error;
  - solo admite https o un remoto local, sin credenciales en la URL;
  - **el token no aparece en ningún fichero de `.git` ni en la salida**.
- `AgentExecutorTest`:
  - una fase de push continúa el worktree de la anterior, pide el token una vez y emite `agent.git.pushed` con el commit subido;
  - sin token, falla con «No se pudo subir la rama».
- `PullRequestDeliveryIT`, con un runner simulado y el GitHub falso:
  - la orden de push lleva la URL, la rama `skynet/<clave>` y la base, pero no el token;
  - el token solo lo consigue el runner de esa invocación, limitado a `contents: write` y al repositorio. Otro runner recibe 404, y la invocación terminada, 409;
  - la fase sigue «En curso» hasta que se abre la PR. Entonces termina bien, con el título de la plantilla, la etiqueta, la rama base por defecto y las evidencias;
  - sin GitHub, la condición omite la fase. Sin la condición, la fase falla con el motivo;
  - si GitHub responde 422, la fase falla. Si responde 502, se reintenta más tarde;
  - una segunda ejecución del mismo trabajo actualiza la misma PR.
- `DefinitionParserTest`: el bloque `pullRequest` y sus errores, y la condición `repository.github`.

## 2. Skynet desplegado, sin GitHub

Con Skynet en marcha, como siempre. Da igual si tiene la App configurada, porque `demo-repo` se usa sin URL remota. El runner usa fake-claude con `02-tools` y `FAKE_CLAUDE_APPLY=1`, que arregla `add()` y hace un commit.

```bash
api() { curl -s -u admin:<contraseña> -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

1. En **Workflows → Nuevo**, pega este YAML, que tiene errores a propósito:

   ```yaml
   id: en-a-errores
   stages:
     - id: implement
       type: agent
       prompt: Arregla add()
       pullRequest:
         title: no
     - id: pr
       type: github-pr
       agent: dev
       pullRequest:
         base: -f
         labels: skynet
   ```

   La validación muestra estos errores, y **Publicar** no está disponible:
   - «`pullRequest` solo vale en fases `type: github-pr`; esta es `agent`»;
   - «Una fase `github-pr` sube la rama y abre la PR, no lanza un agente: quita `agent`»;
   - «`-f` no es un nombre de rama válido»;
   - «`labels` debe ser una lista, p. ej. `labels: [skynet]`»;
   - «Una fase `github-pr` sube el worktree de la fase de la que depende…».

   Descarta el borrador.
2. Publica este workflow:

   ```yaml
   id: en-a-entrega
   stages:
     - id: implement
       type: agent
       prompt: Arregla add()
     - id: pr
       type: github-pr
       dependsOn: [implement]
       condition: repository.github
       pullRequest:
         labels: [skynet]
     - id: cerrar
       type: command
       dependsOn: [implement, "pr?"]
       workspaceFrom: implement
       command: "grep -q 'a + b' calc.py"
   ```

   Sale publicado, sin el aviso «todavía no se ejecuta» en `pr`.
3. Lánzalo en un trabajo nuevo sobre `demo-repo`, que **no tiene URL remota**:
   - termina «Completada»;
   - `pr` sale **Omitida**. En el timeline, la condición `repository.github` vale `false`;
   - `cerrar` termina bien.

   En la API:

   ```bash
   R=<id de la ejecución>
   api $B/workflow-runs/$R/pull-requests      # []
   ```
4. Publica la versión 2 sin la línea `condition: repository.github` y lánzala en otro trabajo sobre `demo-repo`:
   - `pr` sale **Falló**. El motivo, en el detalle de la fase y en el evento `stage.start.failed`, es uno de estos:
     - «La GitHub App no está configurada…», si la App no lo está;
     - «El repositorio demo-repo no está enlazado con GitHub…», si lo está;
   - la ejecución termina «Fallida», y `cerrar` no arranca.
5. En el checkout de `demo-repo`, `git branch -a` no tiene ninguna rama `skynet/…` remota, y `git remote -v` no ha cambiado.

## 3. Con la App real y `guilu/demo-repo` (manual, opcional)

Esta parte abre una PR de verdad en `guilu/demo-repo`. Hermes solo la ejecuta si su despliegue tiene la App `skynet-guilu` configurada y `demo-repo` registrado con la URL remota `https://github.com/guilu/demo-repo.git`. Si no, salta a la sección 4.

1. En la página del proyecto, la sección GitHub de `demo-repo` dice «Con acceso».
2. Lanza `en-a-entrega` (versión 1, con la condición) en un trabajo nuevo sobre ese repositorio, con título «EN-A: prueba de PR» y una descripción corta:
   - `implement` termina bien;
   - `pr` pasa por «En curso» y termina bien en unos segundos;
   - `cerrar` termina bien, en el mismo worktree.
3. En la invocación de `pr`, en Progreso:
   - el proveedor es `git-push`;
   - la herramienta `git push` dice algo como `refs/heads/skynet/<clave>` y `[new branch]`, **sin ningún `ghs_`**.
4. En la API:

   ```bash
   api $B/workflow-runs/$R/pull-requests | jq '.[0] | {status, branch, number, url, headSha}'
   ```

   Sale `status: "OPEN"`, la rama `skynet/<clave en minúsculas>`, el número y la URL.
5. Abre la URL. La PR:
   - va de `skynet/<clave>` a `main`;
   - se titula `<CLAVE> - EN-A: prueba de PR` y tiene la etiqueta `skynet`;
   - en la descripción tiene el trabajo (con la descripción citada), «Evidencias» con `implement` «terminada bien» y el coste;
   - en «Files changed» tiene el cambio de `calc.py`.
6. En el worktree de la ejecución (la ruta de `agent.workspace.ready`):

   ```bash
   grep -r "ghs_" "$(git -C <worktree> rev-parse --git-common-dir)" || echo "sin token"
   ```

   Escribe `sin token`.
7. **Deja GitHub como estaba:** cierra la PR sin fusionarla y borra la rama `skynet/<clave>` desde la propia PR.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
