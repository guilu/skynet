# Probar AP-B - Lecciones propuestas

AP-B (#285) es la segunda PR del hito AP (Aprendizaje continuo). Lo explica [`docs/workflows.md`](../workflows.md), en «Lecciones propuestas (AP-B)».

- **Skynet ve lo que se repite.** Cuenta, por repositorio y clase de hallazgo, las ejecuciones distintas:
  - hallazgos `corregido` en 2 ejecuciones → lección `prevenir`;
  - hallazgos `aceptado`, `irrelevante` o `refutado` en 2 ejecuciones → lección `no-senalar`.
  - La clase `otro` no cuenta, y dos rondas de la misma ejecución cuentan como una.
- **Una lección por repositorio, clase y tipo.** Si ya existe, se le suman los hallazgos nuevos.
- **El texto lo escribe una plantilla**, sin LLM, con hasta 3 hallazgos de ejemplo.
- **Tú decides:**
  - aplicar `prevenir` crea una restricción **fijada** en la memoria del repositorio, con el tema `leccion/{clase}`;
  - aplicar `no-senalar` la deja como criterio aprendido, que AP-C llevará al juez;
  - descartar pide un motivo, y la lección no vuelve hasta que haya casos en 3 ejecuciones nuevas.
- **Migración V54:** tablas `lesson` y `lesson_finding`.
- **API:**
  - `GET /api/projects/{id}/lessons?status=`;
  - `GET /api/lessons/{id}`;
  - `POST /api/lessons/{id}/apply` con `{text}`;
  - `POST /api/lessons/{id}/dismiss` con `{reason}`.
- **Eventos:** `lesson.proposed` (en Actividad y como aviso de Skyny), `lesson.applied` y `lesson.dismissed`.
- **La página Fábrica → Lecciones llega en AP-C.** Esta guía usa `curl` para ver y decidir.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT'
cd web && npx vitest run src/format.test.ts src/components/companion/notices.test.ts
```

- `WorkflowEngineIT.theSameClassInTwoRunsProposesALessonThatCanBeAppliedOrDismissed`:
  - tras la primera ejecución no hay lecciones;
  - tras la segunda hay dos propuestas: `prevenir` de `caso-limite` (2 ejecuciones, 2 hallazgos en ficheros distintos) y `no-senalar` de `estilo`;
  - el texto de `prevenir` empieza por «En este repositorio, los jueces han tenido que pedir en 2 ejecuciones:» y trae los dos hallazgos;
  - sale `lesson.proposed` en la segunda ejecución y no en la primera;
  - aplicar con texto editado crea una observación `CONSTRAINT` fijada, de origen HUMAN, con el tema `leccion/caso-limite`; aplicarla otra vez da 409;
  - descartar sin motivo da 400, y con motivo la deja `descartada`;
  - `?status=magia` da 400;
  - dos ejecuciones más no la vuelven a proponer; la tercera sí, con 5 ejecuciones;
  - la aplicada suma las pruebas nuevas y sigue aplicada.
- `WorkflowEngineIT.findingsKeepTheirFingerprintAndOutcomeAcrossRounds`: el registro de hallazgos sigue igual.
- `format.test.ts`: «Lección propuesta para demo-repo: los jueces han pedido `caso-limite` en 2 ejecuciones», «propuesta de nuevo», «aplicó» y «descartó».
- `notices.test.ts`: el aviso de Skyny «He aprendido algo».

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_JUDGE=/tmp/ap-b/suspender` y `FAKE_CLAUDE_REFUTE=/tmp/ap-b/refutar`, pon los dos nombres en `SKYNET_AGENT_ENV` y reinícialo. Haz `mkdir -p /tmp/ap-b` en esa máquina. `demo-repo` se usa sin URL remota.

Con `SKYNET_ADMIN_PASSWORD` en el entorno, define:

```bash
api() { curl -s -u "admin:$SKYNET_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
S=http://localhost:8080
P=<id del proyecto de demo-repo>   # sale en la URL de su página
```

Publica en **Workflows → Nuevo**. Usa como juez un agente que exista en el despliegue, p. ej. el juez de **Implementar una tarea**.

```yaml
id: ap-b-lecciones
name: Lecciones propuestas
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [juez]
      refute: 1
    fix: implementar
    maxRounds: 2
```

### 2.1 La primera vez no propone nada

1. Escribe el testigo y lanza `ap-b-lecciones` en un trabajo nuevo de `demo-repo`:

   ```bash
   echo '[caso-limite] src/calc.py:3 No trata la lista vacía' > /tmp/ap-b/suspender
   ```

2. La ronda 1 suspende, `implementar` corrige y la ronda 2 aprueba. El registro de `juzgar` dice «Corregido Caso límite src/calc.py:3 No trata la lista vacía».
3. `api $S/api/projects/$P/lessons` da `[]`.

### 2.2 La segunda vez, en otro fichero, propone

1. `echo '[caso-limite] src/export.py:8 No prueba la lista vacía' > /tmp/ap-b/suspender` y lanza en un trabajo nuevo.
2. Al terminar la ronda 2:
   - **Actividad** tiene «Lección propuesta para demo-repo: los jueces han pedido `caso-limite` en 2 ejecuciones»;
   - Skyny avisa «He aprendido algo», con el mismo texto.
3. `api "$S/api/projects/$P/lessons?status=propuesta" | jq '.[] | {id, kind, category, runs, findings, suggestedText}'` da una lección:
   - `prevenir`, `caso-limite`, `runs` 2 y `findings` 2;
   - el texto empieza por «En este repositorio, los jueces han tenido que pedir en 2 ejecuciones: *caso-limite*» y lista los dos hallazgos, cada uno con su fichero.
4. Guarda su id en `L`. `api $S/api/lessons/$L | jq '.findings[] | {runId, stageKey, file, outcome}'` da los dos, uno de cada ejecución, `corregido`.

### 2.3 Aplicarla la fija en la memoria

1. Aplica con un texto tuyo:

   ```bash
   api -X POST $S/api/lessons/$L/apply -d '{"text":"Prueba siempre la lista vacía"}' | jq '{status, appliedText, decidedBy, observationId}'
   ```

   Da `aplicada`, tu texto y tu usuario.
2. En la página del proyecto, pestaña **Memoria**, con `demo-repo`, hay una restricción **fijada**: «Lección: caso-limite», con tu texto y de origen humano.
3. **Actividad** tiene «admin aplicó la lección `caso-limite` de demo-repo en la memoria».
4. Repetir el `POST` da 409: la lección ya está decidida.

### 2.4 El ruido propone «no señalar», y se puede descartar

1. Crea el testigo de los escépticos, que refutan todo mientras exista: `touch /tmp/ap-b/refutar`.
2. Lanza dos trabajos nuevos, cada uno con su testigo antes de lanzar:

   ```bash
   echo '![estilo] src/calc.py:5 El nombre no se entiende' > /tmp/ap-b/suspender    # primer trabajo
   echo '![estilo] src/export.py:2 Nombre poco claro' > /tmp/ap-b/suspender         # segundo trabajo
   ```

   En los dos, el escéptico refuta el hallazgo y `juzgar` aprueba sin corregir. El registro dice «Refutado Estilo …».
3. Tras el segundo, `api "$S/api/projects/$P/lessons?status=propuesta"` da una lección `no-senalar` de `estilo`, con el texto «No señales como fallo lo que en este repositorio ya se dio por bueno en 2 ejecuciones…». Guarda su id en `N`.
4. `api -X POST $S/api/lessons/$N/dismiss -d '{}'` da 400 («Falta `reason`»).
5. `api -X POST $S/api/lessons/$N/dismiss -d '{"reason":"Sí queremos verlo"}' | jq '{status, reason}'` da `descartada`. **Actividad** tiene «admin descartó la lección `estilo` de demo-repo: Sí queremos verlo».

### 2.5 Una descartada vuelve con 3 ejecuciones nuevas

1. Repite el paso 2.4.2 dos veces más, con textos distintos (p. ej. `src/load.py:1 Variable con nombre raro` y `src/save.py:1 Nombre de método confuso`). `api $S/api/lessons/$N | jq .lesson.status` sigue en `descartada`.
2. Una tercera vez (`src/show.py:1 Nombre demasiado corto`): ahora da `propuesta`, con `runs` 5 y sin motivo. **Actividad** tiene «Lección propuesta de nuevo para demo-repo…».

## 3. Al terminar

- Archiva `ap-b-lecciones`.
- En **Memoria**, archiva la restricción «Lección: caso-limite».
- Quita `FAKE_CLAUDE_JUDGE` y `FAKE_CLAUDE_REFUTE` del runner, borra `/tmp/ap-b` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
