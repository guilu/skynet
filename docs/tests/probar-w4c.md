# Probar W4-C en local

W4-C (#88) enseña en la web lo que W4-B comprueba: la fase «Verificando», qué no pasó y por qué, y los datos que recibe de otras fases. También trae la plantilla «Especificar, planificar e implementar» y su E2E. Con esto se cierra el hito W4. No hay migraciones nuevas.

La prueba usa fake-claude aplicando de verdad sus cambios, como la de W4-B. Al final hay un paso opcional con Claude Code de verdad.

## 1. Arrancar

En la rama de la PR, arranca el control plane, la web y el runner como en W4-B. El runner usa fake-claude con el fixture `02-tools`, que corrige `calc.py` y hace commit, y devuelve como salida estructurada el contenido de `.revision.json`:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_STRUCTURED_OUTPUT,FAKE_CLAUDE_DELAY_MS \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 FAKE_CLAUDE_DELAY_MS=500 \
FAKE_CLAUDE_STRUCTURED_OUTPUT=@.revision.json \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

`FAKE_CLAUDE_DELAY_MS=500` hace que el agente tarde unos segundos, para que dé tiempo a ver cada fase en marcha.

Usa el repositorio de prueba de W4-B (`/tmp/w4b`, con `calc.py`) y su comando de verificación: `python3 -c "import calc; assert calc.add(2, 3) == 5"`.

## 2. La plantilla

1. En **Workflows**, **Nuevo workflow**, elige la plantilla **Especificar, planificar e implementar**.
2. En **Qué define**, `spec` dice «Comprueba artifacts openspec/proposal.md» y `implement` dice «Comprueba tests, diff».
3. Para probarla con fake-claude, el analista no escribe la propuesta, así que la escribe una fase previa. Cambia el `id` por `spec-con-propuesta` y sustituye las dos primeras líneas de la fase `spec` (`- id: spec` y `type: agent`) por:

   ```yaml
     - id: preparar
       type: command
       command: mkdir -p openspec && echo '# Propuesta' > openspec/proposal.md && printf '{"summary":"Que add() sume","risks":["ninguno"]}' > .revision.json && git add -A && git -c user.email=t@t -c user.name=t commit -qm preparar
     - id: spec
       type: agent
       dependsOn: [preparar]
   ```

   Como el analista ya corrige `calc.py`, la implementación no tendría nada más que cambiar: deja `checks: [tests]` en `implement`.
4. **Crear borrador** y **Publicar…**. El aviso lista el comando de `preparar` y dice «La fase implement pasa el comando de verificación del repositorio, que tiene que estar configurado».

## 3. Con propuesta: termina y plan recibe el resumen

1. Lanza `spec-con-propuesta` en un trabajo sobre el repositorio de prueba.
2. En **Progreso**, mientras se comprueba `spec`, la fase dice «Verificando» y debajo «Verificando: artifacts». Al abrirla, la comprobación sale con «…» y luego con «✓ artifacts openspec/proposal.md».
3. Abre `plan`: dice «Recibe spec.output.summary, spec.output.risks». En su agente, el prompt efectivo (**Resumen** del inspector) incluye «Que add() sume» y «Riesgos: ["ninguno"]».
4. Abre `implement`: «✓ tests». La ejecución termina «Completada».
5. En el agente de `spec`, pestaña **Verificación**: «Declarado por el agente» enseña la **Salida declarada** (`summary` y `risks`) y «Verificado por Skynet» la verificación «Comprobaciones de la fase» con «✓ artifacts openspec/proposal.md».

## 4. Sin propuesta: spec falla con el motivo

1. Publica otra versión quitando `mkdir -p openspec && echo '# Propuesta' > openspec/proposal.md && ` del comando de `preparar`, y lánzala.
2. `spec` termina «Fallida» y debajo dice «No pasó artifacts: `openspec/proposal.md` no existe». `plan` e `implement` quedan «Cancelada» con «No arrancó porque falló spec».
3. Al abrir `spec`: «✗ artifacts openspec/proposal.md · `openspec/proposal.md` no existe».

## 5. Una fase parallel con un hijo que no pasa (opcional)

Con la `paralela-verificada` de la guía de W4-B y el runner con `FAKE_CLAUDE_FIXTURE=01-simple-text`, la fase `review` dice «No pasó commit: No hay commits nuevos; commit: No hay commits nuevos», con el motivo de cada hijo.

## 6. Con Claude Code de verdad (opcional)

Arranca el runner sin `SKYNET_CLAUDE_BIN` ni `FAKE_*` y lanza la plantilla tal cual, sin `preparar`, sobre un repositorio de prueba con su comando de verificación. Si Claude escribe `openspec/proposal.md`, `plan` recibe su resumen; si no, `spec` falla con el motivo. `implement` solo termina bien si pasan los tests y hay cambios.

## E2E

`scripts/e2e.sh spec.e2e.ts` ejecuta los pasos 3 y 4 en el navegador, con axe en la plantilla, la fase fallida y la pestaña Verificación.

## Qué mirar si algo falla

- `GET /api/workflow-runs/<id>`: cada fase lleva `checks`, `reads` y `verifications`.
- Los eventos `stage.verification.started` y `stage.verification.completed` del timeline.
- El prompt efectivo de `plan`, en los artefactos de su agente.
