# Probar CH-C - El turno de Skyny en el runner

Segunda y última PR de CH-C (#337), tercera parte del hito CH «Chat con Skyny». La primera (#344) preparó el control plane; esta hace que **el runner conteste**. Lo explica [`docs/chat.md`](../chat.md), en «El turno de Skyny» → «En el runner».

- **El runner declara `skyny-chat`** si tiene Claude Code. Con `SKYNET_RUNNER_CHAT=false`, no.
- **Sin worktree:** cada conversación tiene su carpeta vacía y fija, `<SKYNET_RUNNER_HOME>/chat/<conversación>`, y el siguiente turno reanuda allí la sesión.
- **El MCP de Skynet** (`skynet`) es la única herramienta del agente, con el token del turno en un fichero `0600` que se borra al terminar.
- **Sin artefactos** ni evento de worktree.
- **Runners** muestra «Chat con Skyny» entre los proveedores del runner.
- fake-claude puede llamar a otro servidor MCP que el de memoria (`FAKE_MCP_SERVER`), para los tests.

La prueba real hace **dos preguntas a Haiku** (unos céntimos; cada turno tiene un tope de 0,50 $). No lanza workflows, no confirma ninguna acción, no toca ningún repositorio ni abre PRs.

## 1. Pruebas automáticas

```bash
./gradlew :runner:test --tests '*AgentChatTest' --tests '*RunnerMainTest' --tests '*AgentMemoryTest' \
  --tests '*ClaudeCodeProviderTest' --tests '*CodexProviderTest'
./gradlew :tools:fake-claude:test
cd web && npx vitest run src/format.test.ts
```

`AgentChatTest` (fake-claude y un control plane de mentira):
- un turno pide su token, lanza el agente en `chat/<conversación>`, y fake-claude consulta `estado` por el MCP de Skynet: el control plane recibe `GET /api/dashboard` con `Bearer skc_…`;
- no se crea ningún worktree, no hay evento `agent.workspace.ready` ni artefactos, y el fichero del token ya no existe; el `.mcp.json` tiene el servidor `skynet` pero no el token;
- el segundo turno es un `RESUME` de la sesión del primero y termina bien: fake-claude, como el CLI real, solo la encuentra desde la misma carpeta;
- un runner sin chat, o sin token, termina el turno con `failure: start` y un mensaje claro, sin llamar a nada.

`RunnerMainTest`: con Claude Code el runner declara `claude-code` y `skyny-chat`; con `SKYNET_RUNNER_CHAT=false` o sin Claude Code, no.

`AgentMemoryTest` y los de los proveedores siguen en verde: la memoria de las fases no cambia.

## 2. Con fake-claude (sin gastar nada)

Con Skynet en marcha y un runner que use fake-claude (`SKYNET_CLAUDE_BIN=…/fake-claude`), reinícialo con este código.

```bash
API=http://localhost:8080; AUTH="-u admin:<contraseña>"
```

1. Abre **Runners**.
   **Esperado:** en el runner sale «Chat con Skyny» junto a Claude Code, los dos con la etiqueta de simulado.
2. Escribe en una conversación:

   ```bash
   C=$(curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' \
     -d '{"message":"¿Qué hay en marcha?"}' | jq -r '.conversation.id'); echo $C
   ```

   **Esperado:** el estado es `ANSWERING` y, en unos segundos, `IDLE`:

   ```bash
   curl -s $AUTH "$API/api/conversations/$C" | jq '{state: .conversation.state, mensajes: [.messages[] | {author, body}]}'
   ```

   Hay un mensaje de `SKYNY` con el texto de la sesión grabada de fake-claude (no tiene que ver con la pregunta: es un simulador).
3. En la máquina del runner:

   ```bash
   ls ~/.skynet-runner/chat/        # una carpeta con el id de la conversación, vacía
   ls ~/.skynet-runner/logs/ | grep -c memory-token   # 0: los tokens se borran al terminar
   ```

## 3. Con Claude Code de verdad (dos preguntas a Haiku)

Con un runner con Claude Code real y sesión iniciada, reiniciado con este código:

1. En **Runners**, el runner muestra «Chat con Skyny» (sin etiqueta de simulado).
2. Pregunta algo que obligue a consultar Skynet:

   ```bash
   C=$(curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' \
     -d '{"message":"¿Cuántas ejecuciones hay en curso ahora mismo y de qué workflows?"}' | jq -r '.conversation.id')
   sleep 30; curl -s $AUTH "$API/api/conversations/$C" | jq '.conversation.state, .messages[-1].body'
   ```

   **Esperado:** `"IDLE"` y una respuesta de Skyny en español que coincide con lo que ves en «En curso».
3. Sigue la conversación:

   ```bash
   curl -s $AUTH -X POST "$API/api/conversations/$C/messages" -H 'Content-Type: application/json' \
     -d '{"body":"¿Y cuál fue la última que terminó?"}' > /dev/null
   sleep 30; curl -s $AUTH "$API/api/conversations/$C" | jq '.messages[-1].body'
   ```

   **Esperado:** contesta entendiendo «la última» por la conversación (reanudó la sesión).
4. Abre **Uso**.
   **Esperado:** «Chat con Skyny» con 2 invocaciones y un coste de céntimos.

No le pidas que lance, cancele o guarde nada: propondría la acción, y confirmarla ya no es parte de esta prueba.

## 4. Apagar el chat en un runner

1. Reinicia el runner con `SKYNET_RUNNER_CHAT=false`.
   **Esperado:** en **Runners** ya no sale «Chat con Skyny».
2. Si es el único runner del chat, escribe otra vez en la conversación.
   **Esperado:** la conversación queda en `OFFLINE` y no se crea ningún agente.

## 5. Lo que no cambia

- Los agentes de los workflows: su worktree, su memoria, sus artefactos.
- Los runners con Codex o Antigravity sin Claude Code: no declaran el chat.
- La web: la cortina llega con CH-D.
