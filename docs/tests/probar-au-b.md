# Probar AU-B - Lo que se fusiona es lo juzgado

AU-B (#264) es la segunda PR del hito AU. Si una fase `judge` aprueba el cambio y después la corrección de la CI lo toca, Skynet **vuelve a juzgar solo lo que cambió desde el commit aprobado**, con más o menos jueces según el riesgo de ese delta. Lo explica [`docs/workflows.md`](../workflows.md), en «Lo que se fusiona es lo juzgado (AU-B)».

- **El commit juzgado**: al aprobar, la fase `judge` guarda `judgedSha` (la instantánea de la copia del juez, o el commit del que partió si no había nada sin commitear). Las fases siguientes lo leen como `<fase>.judgedSha`.
- **El riesgo del delta**: el agente que corrige la CI mide su riesgo (AU-A) desde `judgedSha`, no desde la rama base. `stage.risk.assessed` lleva `base`.
- **Al pasar la CI**:
  - sin cambios desde lo juzgado: `stage.judge.delta.unchanged`, nadie juzga;
  - nivel 0: `stage.judge.delta.skipped`, nadie juzga;
  - nivel 1: el primer juez de la fase juzga solo el delta;
  - nivel 2, o sin medida: todo el panel.
- **Mientras juzgan**: la espera de la CI está en `JUDGING` («En verde · juzgando lo corregido» en la tarjeta de la PR). Si suspenden, la fase de CI falla con «El juez suspende lo que se cambió después de aprobar: …».
- **La puerta de fusión** dice «Juzgado en `abc1234` · se fusiona `def5678` · delta revisado (nivel 1, 5 / 5)».
- **Migración `V50`**: `stage_verdict.head_sha`, `stage_judge.judged_sha`, el estado `JUDGING` en `github_ci_wait` y la tabla `judged_delta`. Se aplica sola al arrancar.
- **Lo que aún no hace** (llega con AU-C o más adelante): no llama a los escépticos en el delta, no repite las fases `guide` y no cubre los cambios que pide una persona después del juez, solo las correcciones de la CI.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :runner:test --tests '*WorkspaceManagerTest' --tests '*AgentExecutor*' \
  :control-plane:test --tests '*CiCheckIT' --tests '*DefinitionParserTest' \
  --tests '*ContractIT' --tests '*MigrationsIT' --tests '*WorkflowEngineIT' --tests '*VerificationAndArtifactsIT'
cd web && npx vitest run src/format.test.ts src/contracts.test.ts
```

- `WorkspaceManagerTest`: la copia de un worktree con cambios sin commitear lleva una instantánea cuyo padre es el commit del que parte; una copia limpia no lleva ninguna.
- `CiCheckIT`, con un workflow implementar → juzgar (dos jueces) → pr → ci (corrige implementar) → aprobar → fusionar:
  - **nivel 1**: tras la corrección, la CI en verde lanza un solo juez en una copia, la espera pasa por `JUDGING`, la fase de CI termina bien y la puerta dice «Juzgado en … · se fusiona … · delta revisado (nivel 1, …)»;
  - **nivel 2**: juzga el panel; si uno suspende, la fase de CI falla con «El juez suspende lo que se cambió después de aprobar» y no llega a la aprobación;
  - **nivel 0**: no se juzga otra vez (`stage.judge.delta.skipped`) y la puerta lo dice;
  - **medido desde otro commit**: si el riesgo del delta no se midió desde `judgedSha`, juzga todo el panel.
- `DefinitionParserTest`: `juzgar.judgedSha` vale en las condiciones como texto.
- `format.test.ts`: los eventos `stage.judge.delta.*` se describen en Actividad.

## 2. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

Esta parte abre una PR de verdad en `guilu/demo-repo` y espera su CI. Solo se ejecuta si:
- el despliegue tiene la App `skynet-guilu` configurada;
- `demo-repo` está registrado con la URL remota `https://github.com/guilu/demo-repo.git`;
- `guilu/demo-repo` tiene una CI que pasa los tests (`.github/workflows/ci.yml`).

Si no, salta a la sección 3.

El runner omarchy usa fake-claude. Añade a su entorno `FAKE_CLAUDE_TOUCH=/tmp/au-b/tocar` y `FAKE_CLAUDE_JUDGE=/tmp/au-b/suspender` (y los dos nombres en `SKYNET_AGENT_ENV`) y reinícialo. `mkdir -p /tmp/au-b` en esa máquina.

Los ejemplos de abajo suponen que `demo-repo` es Python con pytest en la raíz. Si no, adapta los ficheros al lenguaje de su CI: lo que importa es que el primer cambio rompa un test y el segundo lo arregle.

Publica en **Workflows → Nuevo**:

```yaml
id: au-b-delta
name: Juzgar el delta
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
  - id: juzgar
    type: judge
    dependsOn: [implementar]
    agents: [juez, revisor]
    rubric:
      - {id: correcto, description: El cambio hace lo que pide la tarea, weight: 1}
  - id: pr
    type: github-pr
    dependsOn: [juzgar]
  - id: ci
    type: github-check
    dependsOn: [pr]
    fix: implementar
    maxRounds: 1
  - id: aprobar
    type: human-approval
    dependsOn: [ci]
  - id: fusionar
    type: github-merge
    dependsOn: [aprobar]
```

La evidencia de la PR solo sale en una puerta que desbloquea una fase `github-merge`; en esta guía la aprobación siempre se rechaza, así que nunca se fusiona.

(Usa como `agents` dos agentes que existan en el despliegue, p. ej. los jueces de **Implementar una tarea**.)

### 2.1 Delta de nivel 1: lo juzga un juez

1. Antes de lanzar, el cambio que rompe la CI:

   ```bash
   printf 'tests/test_au_b.py=from au_b import LISTO\ntests/test_au_b.py=def test_au_b(): assert LISTO\n' > /tmp/au-b/tocar
   ```

2. Lanza `au-b-delta` en un trabajo nuevo de `demo-repo` titulado «AU-B: delta de nivel 1».
3. Cuando `juzgar` termine aprobado (y `/tmp/au-b/tocar` ya no exista), escribe la corrección, que es código:

   ```bash
   echo 'au_b.py=LISTO = True' > /tmp/au-b/tocar
   ```

4. La CI de la PR falla (no existe `au_b`), `implementar` corrige y la CI pasa. Comprueba:
   - en la fase `implementar`, la insignia **Riesgo 1** de la corrección; en **Actividad**, su `stage.risk.assessed` lleva `base` = el commit juzgado;
   - en la tarjeta de la PR, «En verde · juzgando lo corregido» un momento, y en **Actividad** `stage.judge.delta.started` con `tier: 1` y **un** juez, y luego `stage.judge.delta.verdict` aprobado;
   - la fase `ci` termina bien;
   - en la aprobación, la evidencia de la PR dice «Juzgado en `xxxxxxx` · se fusiona `yyyyyyy` · delta revisado (nivel 1, 5 / 5)», con los dos commits distintos.
5. Rechaza la aprobación.

### 2.2 Delta de nivel 2 que suspende: lo juzga el panel

1. Como en 2.1, pero en el paso 3 la corrección toca una ruta delicada y, además, el juez del delta suspende:

   ```bash
   printf 'au_b.py=LISTO = True\n.github/workflows/au-b.yml=# nada\n' > /tmp/au-b/tocar
   echo 'au_b.py fuera de lo pedido' > /tmp/au-b/suspender
   ```

   Escribe `/tmp/au-b/suspender` **después** de que `juzgar` apruebe: si no, suspende en `juzgar`.
2. Comprueba:
   - la corrección tiene **Riesgo 2**;
   - `stage.judge.delta.started` con `tier: 2` y **dos** jueces;
   - la fase `ci` falla con «El juez suspende lo que se cambió después de aprobar: …» y la ejecución no llega a `aprobar`.

### 2.3 Sin corrección: nada que juzgar otra vez

1. `echo 'README.md=Probando AU-B' > /tmp/au-b/tocar` y lanza `au-b-delta` en un trabajo nuevo. La CI pasa a la primera, sin ronda de corrección.
2. Comprueba:
   - en **Actividad**, `stage.judge.delta.unchanged` y ningún `stage.judge.delta.started`;
   - la puerta dice «Juzgado en `xxxxxxx` · se fusiona `yyyyyyy` · sin cambios desde el juicio».
3. Rechaza la aprobación.

### 2.4 Deja todo como estaba

- Cierra sin fusionar las PRs que se abrieron y borra sus ramas `skynet/…` desde la propia PR.
- Archiva `au-b-delta`.
- Quita `FAKE_CLAUDE_TOUCH` y `FAKE_CLAUDE_JUDGE` del runner, borra `/tmp/au-b` y reinícialo.

## 3. La API

```bash
api() { curl -s -u admin:<contraseña> -H 'Content-Type: application/json' "$@"; }
RUN=<id de la ejecución de 2.1>
api http://localhost:8080/api/workflow-runs/$RUN/ci-checks | jq '.[0] | {status, round}'
# {"status": "PASSED", "round": 1}
A=<id de la aprobación de esa ejecución, en la URL de la puerta>
api http://localhost:8080/api/approvals/$A | jq '.evidence.pullRequest.judged'
# {"stageKey": "juzgar", "judgedSha": "…", "mergeSha": "…", "status": "PASSED", "tier": 1, "score": 5, "text": "Juzgado en `…` · se fusiona `…` · delta revisado (nivel 1, 5 / 5)"}
```

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
