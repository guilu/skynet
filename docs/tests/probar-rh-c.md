# Probar RH-C - Ruido por juez y lente

RH-C (#271) es la tercera y última PR del hito RH. **Fábrica → Jueces** cuenta, con el registro de hallazgos de RH-A, cuánto ruido mete cada juez y cuánto acierta. Lo explica [`docs/workflows.md`](../workflows.md), en «Ruido por juez (RH-C)».

- **Ruido:** (refutados + que no aplican) / hallazgos.
- **Acierto:** corregidos / hallazgos.
- **Filas:** una por proveedor, modelo, juez y lente, de más a menos ruido, en 7, 30 o 90 días. Con menos de 10 hallazgos, «pocos datos».
- **Al elegir una fila:** sus últimos 5 hallazgos refutados o que no aplicaban, con el motivo y el enlace a la ejecución.
- **API:** `GET /api/factory/judges?days=30`. `days` va de 1 a 365.
- **Sin migración** ni cambios en lo que ve el juez. Solo lee el registro.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT'
cd web && npx vitest run src/JudgesPage.test.tsx
```

- `WorkflowEngineIT`, en `findingsKeepTheirFingerprintAndOutcomeAcrossRounds`: con un refutado, un corregido y un aparcado, la API da una fila de `juez` con 3 hallazgos, ruido y acierto de 0,333, «pocos datos» y el refutado entre sus hallazgos con ruido. `days=0` da 400.
- `JudgesPage.test.tsx`: la tabla con ruido, acierto y «pocos datos», el detalle de una fila y el cambio de periodo.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno estas tres variables, pon los tres nombres en `SKYNET_AGENT_ENV` y reinícialo:

- `FAKE_CLAUDE_TOUCH=/tmp/rh-c/tocar`
- `FAKE_CLAUDE_JUDGE=/tmp/rh-c/suspender`
- `FAKE_CLAUDE_REFUTE=/tmp/rh-c/refutar`

Haz `mkdir -p /tmp/rh-c` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo** los dos workflows siguientes. Usa como juez un agente que exista en el despliegue, p. ej. el juez de **Implementar una tarea**.

```yaml
id: rh-c-ruido
name: Ruido por juez
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
id: rh-c-sin-fix
name: Ruido por juez sin corregir
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

En cada paso, antes de lanzar: `echo 'calc_rh.py=VALOR = 1' > /tmp/rh-c/tocar`.

### 2.1 Un hallazgo refutado

1. `echo '!calc_rh.py Redondea mal los negativos' > /tmp/rh-c/suspender` y `echo 'Redondea' > /tmp/rh-c/refutar`, y lanza `rh-c-ruido` en un trabajo nuevo de `demo-repo`.
2. El escéptico lo refuta y la fase pasa.
3. `rm /tmp/rh-c/refutar`.

### 2.2 Un hallazgo corregido

1. `echo 'calc_rh.py:1 Divide entre cero sin comprobarlo' > /tmp/rh-c/suspender` y lanza en un trabajo nuevo.
2. La ronda 1 suspende, `implementar` corrige y la ronda 2 aprueba (el testigo se borra al leerlo).

### 2.3 La página

1. Abre **Fábrica → Jueces**. Con «30 días» marcado, comprueba la fila del juez:
   - «2 hallazgos», ruido «50 %», acierto «50 %» y «pocos datos»;
   - en desenlaces, «1 refutados · 0 no aplica · 1 corregidos».
2. Pulsa el nombre del juez. Sale «Ruido de <juez> · …» con «Refutado `calc_rh.py` Redondea mal los negativos», el motivo del escéptico y un enlace a la ejecución de 2.1.
3. Pulsa «7 días»: la misma fila, porque todo es de hoy. Si en el despliegue hay hallazgos de antes, en 90 días salen más.

### 2.4 «No aplica» también es ruido

1. `echo 'calc_rh.py:2 Falta validar la entrada' > /tmp/rh-c/suspender` y lanza `rh-c-sin-fix` en un trabajo nuevo.
2. La fase `juzgar` falla y el hallazgo queda «Aparcado». En su registro, pulsa **No aplica**, escribe «Se valida fuera» y pulsa **Guardar**.
3. Vuelve a **Fábrica → Jueces**. La fila del juez dice «3 hallazgos», ruido «67 %», acierto «33 %» y «1 no aplica». En su detalle, el nuevo sale primero, con «No aplica» y «<tu usuario>: Se valida fuera».

## 3. La API

```bash
api() { curl -s -u admin:"$SKYNET_ADMIN_PASSWORD" "$@"; }
api 'http://localhost:8080/api/factory/judges?days=30' | jq '.[] | {judge, lens, findings, noise, hits, fewData}'
api -o /dev/null -w '%{http_code}\n' 'http://localhost:8080/api/factory/judges?days=0'
# 400
```

(`SKYNET_ADMIN_PASSWORD` en el entorno, no escrita en la línea de comandos.)

## 4. Al terminar

- Archiva `rh-c-ruido` y `rh-c-sin-fix`.
- Quita `FAKE_CLAUDE_TOUCH`, `FAKE_CLAUDE_JUDGE` y `FAKE_CLAUDE_REFUTE` del runner, borra `/tmp/rh-c` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
