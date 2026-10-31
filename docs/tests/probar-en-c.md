# Probar EN-C - Fusionar tras el gate, web y E2E

EN-C (#202) cierra el hito EN (entrega por PR). Trae la fase **`github-merge`**, que fusiona la PR de la ejecución tras una puerta, y lo que faltaba para usar la entrega de verdad:

- **YAML:** `type: github-merge`, detrás de una `github-pr`, con `method` (`squash` por defecto, `merge` o `rebase`), `deleteBranch` (por defecto `true`) y un `timeout` de 30 m por defecto.
- **Control plane:**
  - fusiona solo si la PR sigue abierta en el commit aprobado (el último que subió Skynet) y con la CI en verde, y pasa ese `sha` a GitHub;
  - fusionada, borra la rama, cierra el trabajo («Cerrado») y pide eliminar los worktrees que se subieron;
  - el webhook `pull_request.closed` da la fase por hecha si alguien fusionó a mano, y cancela la ejecución si cerró la PR sin fusionarla;
  - eventos `stage.github.pr.merged`, `stage.github.pr.merge.failed`, `workflow.github.pr.closed` y `workitem.closed`;
  - `GET /api/workflow-runs/{id}/merges`, y la evidencia de la puerta lleva la PR y su CI.
- **Web:** la tarjeta **Pull request** en la ejecución, la PR y su CI en la aprobación, el icono y el detalle de `github-merge` en el grafo y una frase en Actividad para cada evento de la entrega.
- **Plantillas:** `especificar-planificar-implementar`, `implementar-tarea` y `corregir-bug` terminan con `pr`, `ci`, `aprobar-fusion` y `fusionar`, todas con `condition: repository.github`.
- **Migración `V34`** (`github_merge`, y los estados `MERGED` y `CLOSED` de la PR), que se aplica sola al arrancar.
- **Permisos de la App:** ninguno nuevo. Fusionar usa Pull requests y borrar la rama, Contents, los dos de escritura desde GH.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Las secciones 1 y 2 no llaman al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*MergeIT' --tests '*DefinitionParserTest' \
  --tests '*SeedWorkflowsTest' --tests '*ContractIT'
(cd web && npx vitest run src/components/run/PullRequestCard.test.tsx src/format.test.ts)
./scripts/e2e.sh delivery.e2e.ts
```

- `MergeIT`, con un runner simulado y el GitHub falso:
  - **fusiona:** la puerta enseña la PR #12, su commit y la CI `PASSED` con el check `test`; al aprobar, la fusión pasa el commit aprobado y `squash`, borra la rama, la ejecución termina «Completada», la PR queda `MERGED` y el trabajo, `CLOSED`. El webhook de esa misma fusión, al llegar después, no repite nada;
  - **la PR cambió:** con otro commit en la PR, no se fusiona y la fase falla con «La PR cambió después de la aprobación…»;
  - **la CI:** con un check en marcha, espera; cuando falla, la fase falla con «La CI ya no está en verde: falló en lint»;
  - **GitHub dice que no:** un 405 hace fallar la fase con el mensaje de GitHub y no se borra la rama;
  - **fase terminada:** una fase que ya terminó no fusiona después;
  - **a mano:** una PR fusionada en GitHub da la fase por hecha, antes o después de que arranque; una cerrada sin fusionar cancela la ejecución con `reason: pull-request-closed`.
- `DefinitionParserTest`: las claves de `github-merge` y sus errores; el ejemplo de la especificación ya no avisa de `github-merge`.
- `SeedWorkflowsTest`: las tres plantillas con las fases nuevas se pueden publicar.
- `delivery.e2e.ts`, contra el GitHub falso y un remoto local (`SKYNET_GITHUB_GIT_URL=file://…`): una fase `command` arregla `add()`, se abre la PR, pasa la CI, la puerta enseña la PR y su CI, se aprueba y se fusiona con `squash`; la rama desaparece del remoto, la tarjeta dice «Fusionada» y Actividad, «fusionar fusionó la PR #… y borró la rama…». Pasa axe en claro y en oscuro en la ejecución y en la aprobación.

La ronda de corrección con una CI roja no está en la E2E: haría falta un agente que cree commits nuevos al seguir su sesión. La cubre `CiCheckIT` (EN-B).

## 2. Skynet desplegado, sin GitHub

Con Skynet en marcha, como siempre. `demo-repo` se usa sin URL remota.

```bash
api() { curl -s -u admin:<contraseña> -H 'Content-Type: application/json' "$@"; }
B=http://localhost:8080/api
```

1. En **Workflows → Nuevo**, pega este YAML, que tiene errores a propósito:

   ```yaml
   id: en-c-errores
   stages:
     - id: implement
       type: agent
       prompt: Arregla add()
       method: squash
     - id: fusionar
       type: github-merge
       dependsOn: [implement]
       agent: dev
       method: fast-forward
   ```

   La validación muestra estos errores, y **Publicar** no está disponible:
   - «`method` solo vale en fases `type: github-merge`; esta es `agent`»;
   - «Una fase `github-merge` fusiona la PR, no lanza un agente: quita `agent`»;
   - «`method: fast-forward` no se conoce. Admite: squash, merge, rebase»;
   - «Una fase `github-merge` fusiona la PR de la ejecución: ponla en `dependsOn` detrás de una fase `github-pr` (mejor, detrás de su `github-check` y de una `human-approval`)».

   Descarta el borrador.
2. Publica este workflow:

   ```yaml
   id: en-c-entrega
   stages:
     - id: arreglar
       type: command
       command: "sed -i 's/a - b/a + b/' calc.py; echo 'Prueba EN-C' >> NOTAS-EN-C.md; git add -A; git -c user.email=skynet@local -c user.name=Skynet commit -qm 'EN-C: prueba de fusión'"
     - id: pr
       type: github-pr
       dependsOn: [arreglar]
       condition: repository.github
     - id: ci
       type: github-check
       dependsOn: [pr]
       condition: repository.github
     - id: aprobar-fusion
       type: human-approval
       dependsOn: [ci]
       condition: repository.github
       action: "Fusionar la PR de {{workItem.key}}"
       risk: high
     - id: fusionar
       type: github-merge
       dependsOn: [aprobar-fusion]
       condition: repository.github
   ```

   Sale publicado, sin ningún aviso «todavía no se ejecuta». En el grafo, `fusionar` lleva el icono de fusión y dice «Fusionar la PR · squash» y «máx. 30 min».
3. Lánzalo en un trabajo nuevo sobre `demo-repo`:
   - termina «Completada»;
   - `pr`, `ci`, `aprobar-fusion` y `fusionar` salen **Omitidas**;
   - la ejecución no tiene tarjeta **Pull request**.

   En la API:

   ```bash
   R=<id de la ejecución>
   api $B/workflow-runs/$R/merges      # []
   ```
4. Publica las plantillas nuevas: `SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh --actualizar`. Sale «✓ … actualizado» para `especificar-planificar-implementar`, `implementar-tarea` y `corregir-bug` (o «tiene un borrador abierto, no se toca» si tienes uno). En **Workflows**, el grafo de cada una termina con el carril **deliver**: `pr` → `ci` → `aprobar-fusion` → `fusionar`. No hace falta lanzarlas.

## 3. Con la App real y `guilu/demo-repo` (manual, opcional)

Esta parte abre y **fusiona** una PR de verdad en `guilu/demo-repo`. Hermes solo la ejecuta si se cumplen las tres condiciones:
- su despliegue tiene la App `skynet-guilu` configurada;
- `demo-repo` está registrado con la URL remota `https://github.com/guilu/demo-repo.git`;
- `guilu/demo-repo` tiene `.github/workflows/ci.yml` en `main` (el de [`docs/entrega-por-pr.md`](../entrega-por-pr.md) sirve).

Si no, salta a la sección 4. No usa ningún agente: la fase `arreglar` es un comando.

1. **Fusionar.** Lanza `en-c-entrega` en un trabajo nuevo sobre ese repositorio, con título «EN-C: prueba de fusión»:
   - `pr` abre la PR y `ci` termina bien cuando la CI de GitHub pasa;
   - en la ejecución, la tarjeta **Pull request** dice «Abierta», con la rama `skynet/<clave>` → `main`, el commit y «CI · En verde» con el check `test`;
   - en **Aprobaciones**, la puerta tiene la sección «La PR que se fusiona al aprobar», con el enlace a la PR, el commit y el check `test`. **Aprobar** pide confirmarlo (riesgo alto): **Sí, aprobar**;
   - en unos segundos, la ejecución termina «Completada»; la tarjeta dice «Fusionada (<commit>)» y el trabajo, «Cerrado»;
   - en GitHub, la PR sale fusionada con *squash* y la rama `skynet/<clave>`, borrada.

   En la API:

   ```bash
   api $B/workflow-runs/$R/merges | jq '.[0] | {status, method, expectedSha, mergedSha, branchDeleted}'
   ```

   Sale `status: "MERGED"`, `method: "squash"`, el commit de la PR, el de la fusión y `branchDeleted: true`.
2. **Cerrar sin fusionar.** Lanza `en-c-entrega` otra vez, en otro trabajo nuevo. Cuando la puerta pregunte, cierra la PR en GitHub (**Close pull request**), sin fusionarla. En unos segundos la ejecución pasa a «Cancelada» y Actividad dice «La PR #… se cerró en GitHub sin fusionarla: se cancela la ejecución». La aprobación ya no está pendiente.
3. **Deja GitHub como estaba:**
   - en la PR fusionada del paso 1, pulsa **Revert**, y fusiona la PR de reversión que abre GitHub;
   - en la PR cerrada del paso 2, borra su rama (**Delete branch**).

Que la fusión se niegue con un commit nuevo en la PR después de aprobar lo prueba `MergeIT`: a mano exigiría subir a la rama justo en ese momento.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
