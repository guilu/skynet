# Probar CH-C - El turno de Skyny en el control plane

CH-C (#337) es la tercera parte del hito CH «Chat con Skyny» y va en dos PRs. Esta es la primera: el **control plane**. El runner que ejecuta los turnos llega en la segunda. Lo explica [`docs/chat.md`](../chat.md), en «El turno de Skyny».

- **Migración V65:** `agent_run.stage_run_id` y `repository_id` pasan a ser opcionales, solo para `role = 'chat'`, y la tabla `chat_turn` liga cada turno a su conversación y su mensaje.
- **Cada mensaje es un turno:** si hay un runner del chat en línea, se encola y la conversación queda en `ANSWERING`. El primero empieza una sesión y los siguientes la continúan en el mismo runner.
- **La respuesta** de Skyny llega como mensaje, con las acciones que propuso como tarjetas.
- **Uso:** «Chat con Skyny».
- **Nuevo:** `POST /api/conversations/{id}/stop` y `POST /api/runner/agent-runs/{turno}/chat-token`.
- **Protocolo:** `StartAgent.chat` (`ChatTurn`) y el proveedor `skyny-chat` que declara un runner que sabe ejecutar turnos.

Ningún runner declara todavía `skyny-chat`, así que **en un Skynet desplegado nada cambia**: los mensajes siguen quedando en `OFFLINE` hasta la segunda PR. Esta guía lo comprueba, y lo demás se prueba con los tests, que hacen de runner.

Ninguna prueba de esta guía lanza agentes, gasta cuota, modifica ficheros versionados de skynet ni abre PRs.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*ChatTurnsIT' --tests '*ConversationsIT' --tests '*ChatActionsIT' \
  --tests '*WorkflowEngineIT' --tests '*RunnerProtocolIT' --tests '*MigrationsIT' --tests '*ModularityTest'
./gradlew :protocol:test
```

`ChatTurnsIT`:
- sin un runner que declare `skyny-chat`, el mensaje queda en `OFFLINE` y no se crea ningún agente, aunque haya un runner con Claude Code;
- con uno, el primer mensaje es un `START` sin ejecución ni repositorio, con Haiku, solo `mcp__skynet` y un prompt que empieza por «Eres Skyny», con el proyecto de la conversación y el mensaje al final; en `agent_run` queda con `role = 'chat'` y sin fase;
- el runner pide el token de la conversación y lee con él; mientras contesta, otro mensaje da 409;
- al terminar, el texto del resultado es el mensaje de Skyny, la conversación vuelve a `IDLE` y el token deja de valer;
- el segundo mensaje es un `RESUME` de la misma sesión, solo con el mensaje nuevo, hijo del primer turno;
- una acción que Skyny propone durante el turno sale como tarjeta en su mensaje; un turno que falla deja «No he podido contestar: …»;
- parar un turno en cola lo cancela en el acto y lo dice; uno que nadie recoge en 2 minutos se retira al escribir otra vez; uno en marcha recibe `CANCEL` y se cierra con el fin del proceso;
- en Uso sale «Chat con Skyny» con el turno, y no hay ejecuciones;
- solo el runner del turno recibe su token, y solo mientras no ha terminado.

`WorkflowEngineIT` y `RunnerProtocolIT` siguen en verde: los agentes de los workflows no cambian.

## 2. Skynet desplegado

Con Skynet en marcha y los runners de siempre:

```bash
API=http://localhost:8080; AUTH="-u admin:<contraseña>"
```

1. Aplica la migración: arranca el control plane y mira el log.
   **Esperado:** «Successfully applied 1 migration … v65».
2. Escribe en una conversación:

   ```bash
   curl -s $AUTH -X POST "$API/api/conversations" -H 'Content-Type: application/json' -d '{"message":"¿Qué hay en marcha?"}' | jq '.conversation.state'
   ```

   **Esperado:** `"OFFLINE"`, porque ningún runner sabe todavía ejecutar turnos del chat. En «Runners» no aparece ninguna invocación nueva.
3. Lanza un workflow cualquiera sobre `demo-repo` como siempre (o mira uno reciente).
   **Esperado:** se ejecuta igual que antes, y en «Uso» no aparece «Chat con Skyny».

## 3. Lo que no cambia

- Los agentes de los workflows, su cola, su cuota y su cancelación.
- Los runners actuales: no reciben turnos del chat.
- La web: la cortina llega con CH-D.
