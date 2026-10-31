# Probar EN-B - Esperar la CI y corregirla

EN-B (#201) es la segunda PR del hito EN (entrega por PR). Trae la fase **`github-check`**: espera a que pase la CI del último commit de la PR y, si falla, la corrige el agente de otra fase y se vuelve a subir.

- **YAML:**
  - `type: github-check`, detrás de una `github-pr`;
  - `required` (a qué checks espera; por defecto, a todos), `ifNoChecks` (`pass` o `fail`), `fix` (la fase `agent` que corrige) y `maxRounds` (por defecto 2);
  - el `timeout` de la fase, por defecto 60 m.
- **Control plane:**
  - lee los check-runs y los statuses del commit;
  - los webhooks `check_run`, `check_suite` y `status` de ese commit hacen que pregunte en el acto, y si no llega ninguno, pregunta cada 2 minutos;
  - con la CI roja, el agente de `fix` sigue en su sesión con cada check fallido, sus anotaciones y las últimas 200 líneas del log de su job; luego el runner sube la rama y se vuelve a esperar;
  - un comentario en la PR por cada ronda;
  - eventos `stage.github.ci.waiting`, `passed`, `failed` y `fix.started`;
  - `GET /api/workflow-runs/{id}/ci-checks`.
- **Permiso nuevo de la App:** Actions: lectura, para los logs. Sin él funciona igual, con las anotaciones.
- **Migración `V33`** (`github_ci_wait`), que se aplica sola al arrancar.

Fusionar, la tarjeta de la PR en la web y las plantillas con las fases nuevas llegan con EN-C.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Las secciones 1 y 2 no llaman al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*CiCheckIT' --tests '*DefinitionParserTest' --tests '*PullRequestDeliveryIT'
```

- `CiCheckIT`, con un runner simulado y el GitHub falso:
  - **verde:** un check en marcha deja la fase «En curso»; cuando termina bien y no llega el webhook, la consulta periódica la cierra bien y arranca la fase siguiente;
  - **webhook:** el webhook de otro commit no hace nada; el del commit de la PR hace que pregunte en el acto;
  - **roja que se corrige:** la fase pasa a corregir, el prompt del agente de `fix` lleva el check, la anotación y las últimas 200 líneas del log (sin la marca de tiempo), el log se descarga sin el token, se comenta en la PR, se sube la rama sin forzar y, con el commit nuevo en verde, la fase termina bien;
  - **roja otra vez:** agotadas las rondas, la fase y la ejecución fallan con «La CI falló en lint tras 1 ronda de corrección». Sin el permiso Actions, el prompt lo dice;
  - **sin CI:** a los 5 minutos sin ningún check, pasa con el aviso «no tiene CI» o, con `ifNoChecks: fail`, falla;
  - **`required`:** un check que no está en la lista y falla no cuenta;
  - **tiempo agotado:** con `timeout: 2s`, la fase falla con `stage.timed.out`.
- `DefinitionParserTest`: las claves de `github-check` y sus errores.

## 2. Skynet desplegado, sin GitHub

Con Skynet en marcha, como siempre. `demo-repo` se usa sin URL remota. El runner usa fake-claude con `02-tools` y `FAKE_CLAUDE_APPLY=1`.

```bash
api() { curl -s -u admin:<contraseña> -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

1. En **Workflows → Nuevo**, pega este YAML, que tiene errores a propósito:

   ```yaml
   id: en-b-errores
   stages:
     - id: implement
       type: agent
       prompt: Arregla add()
       fix: implement
     - id: tests
       type: command
       dependsOn: [implement]
       command: "grep -q 'a + b' calc.py"
     - id: pr
       type: github-pr
       dependsOn: [tests]
     - id: sin-pr
       type: github-check
       dependsOn: [implement]
       agent: dev
       maxRounds: 2
     - id: ci
       type: github-check
       dependsOn: [pr]
       fix: tests
       ifNoChecks: quizá
       required: test
   ```

   La validación muestra estos errores, y **Publicar** no está disponible:
   - «`fix` solo vale en fases `type: github-check`; esta es `agent`»;
   - «Una fase `github-check` espera la CI de la PR, no lanza un agente: quita `agent`…»;
   - «`maxRounds` son las rondas de corrección: sin `fix` no hay ninguna»;
   - «Una fase `github-check` espera la CI de la PR: ponla en `dependsOn` detrás de una fase `github-pr`»;
   - «`tests` es una fase `command`: la CI roja la corrige el agente de una fase `agent`»;
   - «`ifNoChecks: quizá` no se conoce. Admite: pass, fail»;
   - «`required` debe ser una lista de checks…».

   Descarta el borrador.
2. Publica este workflow:

   ```yaml
   id: en-b-entrega
   stages:
     - id: implement
       type: agent
       prompt: Arregla add()
     - id: pr
       type: github-pr
       dependsOn: [implement]
       condition: repository.github
     - id: ci
       type: github-check
       dependsOn: [pr]
       condition: repository.github
       fix: implement
     - id: cerrar
       type: command
       dependsOn: [implement, "ci?"]
       workspaceFrom: implement
       command: "grep -q 'a + b' calc.py"
   ```

   Sale publicado, sin el aviso «todavía no se ejecuta» en `ci`. En el grafo, `ci` dice «Esperar la CI · corrige implement» y «máx. 1 h».
3. Lánzalo en un trabajo nuevo sobre `demo-repo`:
   - termina «Completada»;
   - `pr` y `ci` salen **Omitidas**;
   - `cerrar` termina bien.

   En la API:

   ```bash
   R=<id de la ejecución>
   api $B/workflow-runs/$R/ci-checks      # []
   ```
4. En **Ajustes → GitHub**, la tabla de permisos tiene una fila **Actions · lectura · leer los logs de la CI que falla**.

## 3. Con la App real y `guilu/demo-repo` (manual, opcional)

Esta parte abre una PR de verdad en `guilu/demo-repo` y espera su CI. Hermes solo la ejecuta si se cumplen las tres condiciones:
- su despliegue tiene la App `skynet-guilu` configurada, con el permiso Actions: lectura aceptado;
- `demo-repo` está registrado con la URL remota `https://github.com/guilu/demo-repo.git`;
- `guilu/demo-repo` tiene un workflow de CI que pasa los tests en `.github/workflows/ci.yml`.

Si no, salta a la sección 4.

1. Lanza `en-b-entrega` en un trabajo nuevo sobre ese repositorio, con título «EN-B: prueba de CI»:
   - `pr` abre la PR;
   - `ci` pasa a «En curso» y, cuando la CI de GitHub termina en verde, termina bien. No hace falta esperar 2 minutos: el webhook la adelanta;
   - `cerrar` termina bien.
2. En la API:

   ```bash
   api $B/workflow-runs/$R/ci-checks | jq '.[0] | {status, headSha, round, checks}'
   ```

   Sale `status: "PASSED"`, el commit de la PR, `round: 0` y los checks de la CI con `state: "success"`.
3. **Deja GitHub como estaba:** cierra la PR sin fusionarla y borra la rama `skynet/<clave>` desde la propia PR.

La ronda de corrección con una CI roja de verdad se prueba de punta a punta en EN-C.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
