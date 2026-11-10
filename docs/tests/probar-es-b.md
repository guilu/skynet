# Probar ES-B - Criterios de revisión del repositorio

ES-B (#277) es la segunda PR del hito ES. Lo explica [`docs/workflows.md`](../workflows.md), en «Criterios del repositorio (ES-B)», dentro de `judge`.

- **El juez recibe `REVIEW.md`:** si el worktree tiene `.skynet/review.md` (que gana) o `REVIEW.md`, su texto va en «## Criterios de este repositorio», debajo de la rúbrica. No la sustituye.
- **También** los escépticos y los jueces del delta.
- **Lo lee el runner** al arrancar el juez, hasta 8 KB. Un enlace simbólico o un fichero vacío no cuentan.
- **Web:** la línea del worktree del juez dice «…, con los criterios de REVIEW.md».
- **Sin migración** y sin cambios en los workflows iniciales.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. `REVIEW.md` lo crea el agente en el worktree de la ejecución, no en `demo-repo`. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :runner:test --tests '*RepositoryReviewTest' --tests '*AgentExecutorTest' \
  :control-plane:test --tests '*JudgePromptTest' --tests '*WorkflowEngineIT'
cd web && npx vitest run src/format.test.ts
```

- `RepositoryReviewTest`:
  - sin fichero, el prompt queda como antes;
  - `REVIEW.md` va debajo de la rúbrica y `.skynet/review.md` gana;
  - un fichero largo se corta en 8 KB sin partir un carácter;
  - un fichero vacío, un enlace que sale del worktree o un prompt sin sitio no cambian nada.
- `AgentExecutorTest.aJudgeGetsTheRepositorysReviewCriteriaAndTheWorkspaceSaysWhichFile`: con `REVIEW.md` en el repositorio, `agent.workspace.ready` lleva `review: REVIEW.md`.
- `JudgePromptTest`: el juez, el escéptico y el delta tienen el sitio de los criterios, debajo de la rúbrica y antes de los hallazgos.
- `format.test.ts`: «Worktree listo en la rama …, con los criterios de REVIEW.md».

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_TOUCH=/tmp/es-b/tocar`, pon el nombre en `SKYNET_AGENT_ENV` y reinícialo. Haz `mkdir -p /tmp/es-b` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo**. Usa como juez un agente que exista en el despliegue, p. ej. el juez de **Implementar una tarea**.

```yaml
id: es-b-criterios
name: Criterios del repositorio
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [juez]
```

### 2.1 Con REVIEW.md

1. `echo 'REVIEW.md=No señales los nombres de variables.' > /tmp/es-b/tocar` y lanza `es-b-criterios` en un trabajo nuevo de `demo-repo`.
2. Abre el agente juez de `juzgar` y su pestaña de eventos. Comprueba que la línea del worktree dice «Worktree listo en la rama …, con los criterios de REVIEW.md».
3. La ejecución sigue como siempre: el criterio solo cambia lo que el juez tiene en cuenta.

### 2.2 `.skynet/review.md` gana

1. Escribe el testigo y lanza en un trabajo nuevo:

   ```bash
   printf 'REVIEW.md=No señales los nombres.\n.skynet/review.md=Solo fallos de seguridad.\n' > /tmp/es-b/tocar
   ```

2. La línea del worktree del juez dice «…, con los criterios de .skynet/review.md».

### 2.3 Sin fichero

1. Sin `/tmp/es-b/tocar`, lanza en un trabajo nuevo.
2. La línea del worktree del juez dice solo «Worktree listo en la rama …». El juez puntúa como siempre.

## 3. Al terminar

- Archiva `es-b-criterios`.
- Quita `FAKE_CLAUDE_TOUCH` del runner, borra `/tmp/es-b` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
