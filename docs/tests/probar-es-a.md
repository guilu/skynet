# Probar ES-A - Comprobación spec

ES-A (#276) es la primera PR del hito ES (Especificación que se demuestra). Lo explica [`docs/workflows.md`](../workflows.md), en «`spec` (ES-A)» dentro de `checks` y en «Reintentos».

- **Check nuevo `spec`:** la propuesta (`openspec/proposal.md`, u otro fichero con `{spec: <fichero>}`) tiene que tener cinco secciones `##` con texto:
  - Qué se podrá hacer;
  - Qué resultado se obtiene;
  - Qué debe respetar;
  - Cómo se demuestra;
  - Fuera de alcance.
- **Títulos flexibles:** sin tildes ni mayúsculas, con números delante y en inglés.
- **Si falla:** dice qué falta y qué está vacío.
- **Reintento:** si una fase falla por sus checks, el intento siguiente recibe en su prompt cuáles no pasaron y por qué. Vale para cualquier check, no solo `spec`.
- **Workflow inicial:** `especificar-planificar-implementar` pide las cinco secciones y lleva `checks: [spec]` con un reintento. Su juez gana el criterio «Cumple lo que dice "Cómo se demuestra"». Se publica con `scripts/seed-workflows.sh --actualizar`.
- **fake-claude:** con `FAKE_CLAUDE_SPEC=<fichero>`, si el prompt pide la propuesta, la escribe. Usa el contenido del testigo y lo borra; sin testigo, escribe una completa.
- **Sin migración.**

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :runner:test --tests '*SpecSectionsTest' --tests '*StageChecksTest' \
  :control-plane:test --tests '*DefinitionParserTest' --tests '*WorkflowEngineIT' --tests '*SeedWorkflowsTest' \
  :tools:fake-claude:test --tests '*FakeClaudeTest'
cd web && npx vitest run src/contracts.test.ts src/Workflows.test.tsx
```

- `SpecSectionsTest`:
  - una propuesta completa pasa;
  - los títulos valen sin tildes, con números y en inglés;
  - el motivo dice lo que falta y lo que está vacío;
  - una subsección cuenta como texto;
  - el título `#` del documento, el código y los comentarios no cuentan;
  - un fichero que falta o que sale del worktree «no existe».
- `StageChecksTest`: `spec` mira su fichero y da su motivo.
- `DefinitionParserTest`: `checks: [spec]` mira `openspec/proposal.md`, `{spec: docs/propuesta.md}` mira ese fichero y `{spec: ../fuera.md}` es un error.
- `WorkflowEngineIT.aRetryAfterFailedChecksIsToldWhatDidNotPass`: el segundo intento lleva «Nota: un intento anterior … no pasó estas comprobaciones de la fase» con «`spec`: …».
- `SeedWorkflowsTest`: los workflows iniciales se siguen publicando.
- `FakeClaudeTest`: el testigo de `FAKE_CLAUDE_SPEC` se usa una vez y después sale una propuesta completa.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_SPEC=/tmp/es-a/propuesta`, pon el nombre en `SKYNET_AGENT_ENV` y reinícialo. Haz `mkdir -p /tmp/es-a` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo**:

```yaml
id: es-a-spec
name: Comprobación spec
stages:
  - id: spec
    type: agent
    prompt: |
      Escribe la propuesta en openspec/proposal.md con: Qué se podrá hacer, Qué resultado se obtiene, Qué debe respetar, Cómo se demuestra y Fuera de alcance.
    checks: [spec]
    retry: {maxAttempts: 2, on: [verification]}
```

### 2.1 Una propuesta incompleta se repite con lo que falta

1. Escribe el testigo y lanza `es-a-spec` en un trabajo nuevo de `demo-repo`:

   ```bash
   printf '## Qué se podrá hacer\nExportar a CSV.\n\n## Fuera de alcance\nTODO\n' > /tmp/es-a/propuesta
   ```

2. El primer intento de `spec` falla. En su pestaña **Verificación**, el check `spec` dice: «A la propuesta `openspec/proposal.md` le falta «Qué resultado se obtiene», «Qué debe respetar» y «Cómo se demuestra»; está vacía «Fuera de alcance»».
3. Sale el intento 2 («intento 2 de 2»). En su conversación, el prompt termina con «Nota: un intento anterior de esta tarea no pasó estas comprobaciones de la fase…» y «- `spec`: A la propuesta…».
4. Sin testigo, el intento 2 escribe una propuesta completa. El check `spec` pasa y la ejecución termina bien.

### 2.2 Sin la propuesta

1. Quita `FAKE_CLAUDE_SPEC` del runner y reinícialo. Lanza `es-a-spec` en un trabajo nuevo.
2. Los dos intentos fallan con «`openspec/proposal.md` no existe» y la ejecución falla.

### 2.3 El workflow inicial

1. Con `SKYNET_ADMIN_PASSWORD` en el entorno, `scripts/seed-workflows.sh --actualizar` publica la versión nueva de `especificar-planificar-implementar`.
2. En **Workflows → Especificar, planificar e implementar**, la fase `spec` dice «Comprueba artifacts openspec/proposal.md, spec openspec/proposal.md» y que se reintenta. El juez tiene el criterio `demuestra`.
3. No hace falta lanzarlo: el flujo es el de 2.1.

## 3. Al terminar

- Archiva `es-a-spec`.
- Si aún está, quita `FAKE_CLAUDE_SPEC` del runner, borra `/tmp/es-a` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
