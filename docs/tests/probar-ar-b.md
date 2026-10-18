# Probar AR-B en local

AR-B retira Gemini CLI y añade **Antigravity CLI** (`agy`) como tercer proveedor de agentes, a partir de las sesiones de agy 1.4.2 grabadas en la máquina de Diego. No hay migraciones ni dependencias nuevas. Desaparecen `SKYNET_GEMINI_BIN`, `fake-gemini` y sus variables; el runner busca agy en `SKYNET_AGY_BIN` (por defecto `agy`).

## 1. Runner con agy real

Con `agy` instalado y con la sesión de Google iniciada para el usuario del runner (`agy --version` responde `1.4.2`):

```bash
./gradlew :runner:installDist
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

Al conectarse escribe `proveedores: claude-code …, antigravity 1.4.2` (más los que tenga). En **Runners** sale «Antigravity CLI 1.4.2» **sin** «Simulado».

## 2. Un agente suelto con Antigravity

En un trabajo, **Lanzar workflow** con `adhoc`:

1. El selector **Proveedor** ofrece «Claude Code», «Codex» y «Antigravity CLI» (ya no hay Gemini CLI). Elige Antigravity y deja el modelo vacío. Prompt: «contesta con hola».
2. Termina «Completada». En la cabecera, el chip «Antigravity CLI»; en la conversación, «hola». El Resumen enseña los tokens, pero sin coste: agy no dice qué modelo usa por defecto, y el fin del proceso lo avisa como «agy (modelo predeterminado)» sin precio.
3. **Continuar.** Escribe «¿y en inglés?» en la caja de mensajes: es la misma conversación de agy (`--conversation`), con la respuesta en el mismo worktree.
4. **Bifurcar** sale desactivado con «Antigravity CLI no permite bifurcar una sesión.».
5. Lánzalo otra vez con un modelo de `agy models` (p. ej. un `gemini-3-flash` si sale en la lista): la cabecera enseña el modelo y el Resumen ya lleva coste estimado.

## 3. Herramientas y permisos

En un repositorio con un `calc.py` con `def add(a, b): return a - b`, lanza `adhoc` con Antigravity y «lee calc.py y arregla add»:

- La conversación enseña `view_file` y `replace_file_content` con sus rutas, y la pestaña Cambios el diff de `calc.py`.
- Con la política por defecto (`dontAsk`), agy aprueba las ediciones (`--mode accept-edits`) pero **no los comandos**: un prompt como «ejecuta ls -la» acaba con «Permiso denegado: RunCommand». Con `permissionMode: bypassPermissions` en la política, el runner pasa `--dangerously-skip-permissions` y el comando se ejecuta. Con `plan`, `--mode plan`.

## 4. Salida estructurada y plantilla

1. **Nuevo workflow** → plantilla **«Revisión con Codex y Antigravity»** (antes «Revisión con Codex y Gemini»): el revisor de seguridad lleva `provider: antigravity`.
2. Publícala y lánzala. El revisor de Antigravity devuelve su `output` (`hasBlockingFindings`, `summary`) validado por el propio agy (`--json-schema`), y la condición de la fase `fix` decide con él.
3. En **Lanzar workflow**, la política de cada fase tacha en el revisor de seguridad «no lo admite Antigravity CLI» para la lista de herramientas y los turnos máximos.

## 5. Gemini CLI retirado

1. Un borrador con `provider: gemini` marca el error «Gemini CLI se ha retirado: usa `antigravity` (Antigravity CLI), que lo sustituye» y no se puede publicar.
2. Una versión publicada antes con Gemini sigue viéndose, pero lanzarla responde «El agente `…` de … usa Gemini CLI, que se ha retirado: publica una versión con `provider: antigravity`». Reintentar o continuar uno de sus agentes responde 409 («Gemini CLI se ha retirado: este agente ya no se puede relanzar»).
3. Las ejecuciones antiguas con Gemini se ven con el proveedor «Gemini CLI (retirado)».
4. **Proveedores** (`GET /api/providers`) ya no lista `gemini`; **Uso** tampoco, y la tarjeta de Antigravity ya no dice «Solo lectura».

## 6. Sin cuenta: fake-agy

```bash
./gradlew :tools:fake-claude:installDist :runner:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_AGY_BIN=$FAKE/fake-agy SKYNET_AGENT_ENV=FAKE_AGY_FIXTURE FAKE_AGY_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

Sale «Antigravity CLI 1.4.2 (fake-agy)» con «Simulado» y los agentes reproducen las sesiones grabadas (`01-simple-text`, `02-tools`, `03-resume` al continuar, `04-schema`, `05-denied`, `06-cancelled`). El resto de variables, en [`docs/antigravity-stream-json.md`](../antigravity-stream-json.md).

## 7. Comprobaciones automáticas

```bash
./gradlew build    # AntigravityEventParserTest/CostMeterTest/ProviderTest, FakeAgyTest, AgentExecutorTest con fake-agy, WorkflowEngineIT
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
scripts/e2e.sh     # providers.e2e.ts: la plantilla con Codex y Antigravity y un agente suelto con Antigravity
```

Si agy real rechaza alguna opción de la línea de comandos (el prompt va como `--print=<prompt>`), el log del agente lo dice en su stderr: pásamelo y lo ajusto.
