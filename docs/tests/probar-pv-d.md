# Probar PV-D en local

PV-D lleva los proveedores a la web (#80). **Lanzar workflow** deja elegir el proveedor y el modelo del agente. La ejecución enseña el proveedor de cada agente y desactiva, con el motivo, lo que su proveedor no admite. La política de cada fase tacha lo que el proveedor ignora. Hay una plantilla nueva, «Revisión con Codex y Gemini». Es el criterio de aceptación del hito PV: esa plantilla termina «Completada» con revisores de los tres proveedores.

La prueba usa fake-claude, fake-codex y fake-gemini; al final hay un paso opcional con los CLIs de verdad.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con los tres fakes. Hay una migración nueva (`V17`), que se aplica sola.

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_GEMINI_BIN=$FAKE/fake-gemini \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_STRUCTURED_OUTPUT,FAKE_CODEX_STRUCTURED_OUTPUT,FAKE_GEMINI_STRUCTURED_OUTPUT \
FAKE_CLAUDE_FIXTURE=02-tools \
FAKE_CLAUDE_STRUCTURED_OUTPUT=@.revision.json FAKE_CODEX_STRUCTURED_OUTPUT=@.revision.json \
FAKE_GEMINI_STRUCTURED_OUTPUT=@.revision.json \
CODEX_HOME=/tmp/fake-codex-home GEMINI_CLI_HOME=/tmp/fake-gemini-home \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Los tres revisores devuelven lo que haya en `.revision.json` del worktree; lo escribe la fase de tests del paso 3.

- En **Runners**, el runner lista `Claude Code 2.1.0 (fake-claude) · Codex codex-cli 0.50.0 (fake-codex) · Gemini CLI 0.65.0`.

## 2. Lanzar un agente con otro proveedor

1. En un trabajo, **Lanzar workflow** con `adhoc`. Debajo del repositorio sale **Agente** con **Proveedor** (Claude Code) y **Modelo** vacío, con «el de Skynet» de ejemplo.
2. Elige **Gemini CLI**: el ejemplo del modelo pasa a «el predeterminado de Gemini CLI». Escribe `gemini-2.5-pro`, un prompt y **Lanzar**.
3. En la ejecución, la cabecera tiene los chips **Gemini CLI** y **gemini-2.5-pro**, y termina «Completada». En **Resumen**, Proveedor dice `Gemini CLI · START`.
4. La caja de **Conversación** deja escribir: **Enviar** «Sigue» continúa con Gemini y `gemini-2.5-pro`.
5. **Bifurcar…** sale desactivado y debajo pone «Gemini CLI no permite bifurcar una sesión.».
6. Para el runner y vuelve a abrir **Lanzar workflow**: Codex y Gemini CLI salen desactivados con «(ningún runner en línea)». Arranca el runner otra vez como en el paso 1.

## 3. La plantilla «Revisión con Codex y Gemini»

1. En **Workflows**, **Nuevo workflow**, elige la plantilla «Revisión con Codex y Gemini». En **Qué define**, el agente `calidad` dice `Codex` y `seguridad` `Gemini CLI`. No hay avisos: los revisores no llevan `tools`.
2. Cambia el `id` a `multi`, y los dos `command: ./gradlew test` por comandos que valgan en tu repositorio de prueba:
   - el de `tests`: `printf '{"hasBlockingFindings":true,"summary":"ok"}' > .revision.json`;
   - el de `tests-finales`: `test -f .revision.json`.
3. **Crear borrador** y **Publicar**.
4. En un trabajo, **Lanzar workflow** con `multi`. No sale el selector de proveedor (todas las fases fijan su agente). En «Política de cada fase»:
   - `review.calidad` empieza por `Codex` y tiene tachado `herramientas …` con «(no lo admite Codex)»;
   - `review.seguridad`, lo mismo con «no lo admite Gemini CLI».
5. **Lanzar**. Termina «Completada» con las cinco fases; `fix` se ejecuta porque los dos revisores dijeron `hasBlockingFindings: sí`.
6. En la fase `review`, abre `seguridad`: la cabecera dice **Gemini CLI** y **Bifurcar…** está desactivado. Abre `calidad`: **Codex**, y **Bifurcar…** activo.
7. Publica otra versión con `"hasBlockingFindings":false` en el comando de `tests` y lánzala: `fix` queda «Omitida» y la ejecución termina «Completada».

## 4. Errores de la API

Con la sesión de la web, desde la consola del navegador o con `curl`:

- `POST /api/work-items/<id>/runs` con `"provider": "opencode"` responde 400 «El proveedor `opencode` no existe…».
- Con `"definitionId"` de `multi` y `"provider": "codex"` responde 400 «En multi cada fase fija su agente…».
- `GET /api/providers` devuelve los tres con sus capacidades y `onlineRunners`.

## 5. Con los CLIs de verdad (opcional)

Con Claude Code, Codex y Gemini CLI instalados y con sesión (ver [`docs/providers.md`](../providers.md)), arranca el runner sin `SKYNET_*_BIN` ni `FAKE_*`. Lanza la plantilla «Revisión con Codex y Gemini» con tus comandos de tests reales sobre un repositorio de prueba. Comprueba que los dos revisores devuelven su salida estructurada y que la condición decide `fix`.

## Qué mirar si algo falla

- Logs del runner: la línea `proveedores:` al conectarse.
- `GET /api/providers`: si un proveedor tiene `onlineRunners: 0`, el runner no lo declaró.
- El log NDJSON de cada agente, en `~/.skynet-runner/logs/<agente>.ndjson`.
