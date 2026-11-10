# Probar RH-A - Huella y desenlace de cada hallazgo

RH-A (#269) es la primera PR del hito RH (Registro de hallazgos). Cada hallazgo de un juez queda guardado con su **huella** (repositorio, fichero, lente y palabras del texto, sin la línea) y su **desenlace**. Lo explica [`docs/workflows.md`](../workflows.md), en «Registro de hallazgos (RH-A)».

- **Desenlaces del motor:**
  - `abierto`: el hallazgo acaba de salir;
  - `refutado`: lo tumbó un escéptico;
  - `corregido`: la ronda siguiente ya no lo repite;
  - `aparcado`: la fase terminó y seguía abierto.
- **Desenlaces de una persona:** `aceptado` («Es intencionado») e `irrelevante` («No aplica»). Se deshacen con «Deshacer».
- **Repeticiones:** el mismo hallazgo en rondas seguidas es una sola fila con `repeats`, y entre ejecuciones apunta a la primera vez que salió en el repositorio.
- **Web:** la fase de juez (y la de CI, por el delta de AU-B) enseña el «Registro de hallazgos». En **Actividad** sale `finding.outcome.set`.
- **API:** `GET /api/workflow-runs/{id}/findings` y `POST /api/findings/{id}/outcome`.
- **Migración `V52`:** la tabla `finding`. Se aplica sola al arrancar. Lo de antes no se rellena.
- **No cambia** lo que ve el juez ni lo que bloquea. Eso llega con RH-B.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*FindingsTest' --tests '*WorkflowEngineIT' \
  --tests '*CiCheckIT' --tests '*MigrationsIT' --tests '*PurgeIT'
cd web && npx vitest run src/components/run/StageFindings.test.tsx src/format.test.ts
```

- `FindingsTest`: las palabras, sin tildes, números ni código, el hash (el mismo con otro orden) y el parecido.
- `WorkflowEngineIT`, en `findingsKeepTheirFingerprintAndOutcomeAcrossRounds`:
  - en la ronda 1, dos que bloquean (un escéptico tumba uno) y uno menor;
  - en la ronda 2, el juez repite el primero con otra línea y una palabra más, y ya no dice el menor;
  - al final queda uno `aparcado` con `repeats: 1`, uno `refutado` con el motivo del escéptico y uno `corregido`;
  - «aceptado» y «Deshacer» funcionan por la API (vuelve a `aparcado`), y un desenlace desconocido da 400;
  - una segunda ejecución con el mismo hallazgo apunta a la primera (`firstSeenRunId`).
- `StageFindings.test.tsx`: el registro de la fase, los botones solo en lo abierto o aparcado y «Es intencionado» con su motivo.
- `format.test.ts`: `finding.outcome.set` en Actividad.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno estas tres variables, pon los tres nombres en `SKYNET_AGENT_ENV` y reinícialo:

- `FAKE_CLAUDE_TOUCH=/tmp/rh-a/tocar`
- `FAKE_CLAUDE_JUDGE=/tmp/rh-a/suspender`
- `FAKE_CLAUDE_REFUTE=/tmp/rh-a/refutar`

Haz `mkdir -p /tmp/rh-a` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo** los dos workflows siguientes. Usa como juez un agente que exista en el despliegue, p. ej. el juez de **Implementar una tarea**.

```yaml
id: rh-a-registro
name: Registro de hallazgos
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

```yaml
id: rh-a-sin-fix
name: Registro de hallazgos sin corregir
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

En cada paso, antes de lanzar: `echo 'calc_rh.py=VALOR = 1' > /tmp/rh-a/tocar`.

### 2.1 Un hallazgo que se corrige

1. `echo 'calc_rh.py:1 Divide entre cero sin comprobarlo' > /tmp/rh-a/suspender` y lanza `rh-a-registro` en un trabajo nuevo de `demo-repo`.
2. La ronda 1 suspende, `implementar` corrige y la ronda 2 aprueba (el testigo se borra al leerlo). En la fase `juzgar`, comprueba:
   - «Registro de hallazgos · 1 hallazgo: 1 corregido»;
   - la línea «Corregido `calc_rh.py:1` Divide entre cero sin comprobarlo (juez · …)», con «La ronda 2 ya no lo señala»;
   - que no tiene botones.

### 2.2 Un hallazgo refutado

1. `echo '!calc_rh.py Redondea mal los negativos' > /tmp/rh-a/suspender` y `echo 'Redondea' > /tmp/rh-a/refutar`, y lanza `rh-a-registro` en un trabajo nuevo.
2. El escéptico lo refuta y la fase pasa sin corregir. El registro dice «1 hallazgo: 1 refutado», con el motivo del escéptico.
3. `rm /tmp/rh-a/refutar`.

### 2.3 Aparcado, intencionado y deshacer

1. `echo 'calc_rh.py:2 Falta validar la entrada' > /tmp/rh-a/suspender` y lanza `rh-a-sin-fix` en un trabajo nuevo.
2. La fase `juzgar` falla. El registro dice «1 hallazgo: 1 aparcado», con «La fase terminó sin corregirlo» y los botones «Es intencionado» y «No aplica».
3. Pulsa **Es intencionado**, escribe «Validado en la capa de arriba» y pulsa **Guardar**. Comprueba:
   - la línea dice «Intencionado» y el motivo lleva tu usuario delante;
   - en **Actividad**, «<tu usuario> marcó como intencionado un hallazgo en `calc_rh.py`».
4. Pulsa **Deshacer**: vuelve a «Aparcado» y a tener los dos botones.
5. Pulsa **No aplica** y **Guardar** sin motivo: la línea dice «No aplica».

### 2.4 El mismo hallazgo en otra ejecución

1. `echo 'calc_rh.py:7 Falta validar la entrada' > /tmp/rh-a/suspender` (el mismo texto que en 2.3, en otra línea) y lanza `rh-a-sin-fix` en un trabajo nuevo.
2. El hallazgo sale «Aparcado» con el enlace «ya salió en otra ejecución», que lleva a la ejecución de 2.3.

## 3. La API

```bash
api() { curl -s -u admin:"$SKYNET_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
RUN=<id de la ejecución de 2.3, en su URL>
api http://localhost:8080/api/workflow-runs/$RUN/findings | jq '.[0] | {outcome, outcomeBy, repeats, file, lens}'
# {"outcome": "irrelevante", "outcomeBy": "<tu usuario>", "repeats": 0, "file": "calc_rh.py", ...}
F=$(api http://localhost:8080/api/workflow-runs/$RUN/findings | jq -r '.[0].id')
api -X POST http://localhost:8080/api/findings/$F/outcome -d '{"outcome":"corregido"}' | jq .
# 400: «Desenlace desconocido: corregido. Admite: aceptado, irrelevante o abierto»
```

(`SKYNET_ADMIN_PASSWORD` en el entorno, no escrita en la línea de comandos.)

## 4. Al terminar

- Archiva `rh-a-registro` y `rh-a-sin-fix`.
- Quita `FAKE_CLAUDE_TOUCH`, `FAKE_CLAUDE_JUDGE` y `FAKE_CLAUDE_REFUTE` del runner, borra `/tmp/rh-a` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
