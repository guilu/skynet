# Probar CH-F - Resumen a la memoria

CH-F (#340) es la última parte del hito CH «Chat con Skyny». Cuando una conversación con proyecto se archiva, o lleva un rato sin mensajes, Skyny resume lo decidido y lo deja en la memoria del proyecto como observación **sin confirmar**. Solo cambia el control plane, con la migración V66. Lo explica [`docs/chat.md`](../chat.md), en «Resumen a la memoria».

La prueba 1 no gasta nada. La 2 hace dos preguntas y dos resúmenes con Haiku, unos céntimos en total.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests 'dev.skynet.controlplane.chat.*'
```

`ChatSummaryTest`:
- lee el título, el qué y el porqué, aunque vengan con Markdown o en varias líneas;
- con «NADA» o sin el formato, no guarda nada;
- recorta los títulos largos;
- el prompt lleva el proyecto y lo más reciente de la conversación.

`ChatTurnsIT`:
- al archivar una conversación con proyecto, sale un turno nuevo que no continúa la sesión. Su resumen se guarda con el tipo `SESSION_SUMMARY`, el tema `chat/<conversación>`, la etiqueta `chat`, el origen `AGENT` y sin confirmar. No deja mensaje y la conversación vuelve a reposo;
- una conversación quieta se resume pasado el tiempo y solo una vez. Con «NADA» no guarda nada. Una conversación sin proyecto no se resume nunca. Y el mensaje siguiente continúa la sesión de la respuesta, no la del resumen.

## 2. Con un runner del chat (Haiku)

Con un runner con Claude Code real que declare «Chat con Skyny» en Runners, y con el proyecto de `demo-repo`:

1. Abre el proyecto de `demo-repo` y abre la cortina (**Ctrl+J**). Escribe «Para el lector de CSV, decidimos aceptar también el punto y coma como separador porque lo usa Excel en español».
   **Esperado:** Skyny contesta. La conversación queda ligada al proyecto: su clave sale en el título de la cortina.
2. Archiva la conversación:
   ```bash
   curl -u admin:$PASS -X POST http://localhost:8080/api/conversations/<id>/archive
   ```
   El id está en `localStorage['skynet.chat.conversation']` o en «Conversaciones».
   **Esperado:** en Uso sale otro turno de «Chat con Skyny». Al terminar, en la memoria del proyecto hay una observación de tipo resumen de sesión, sin confirmar, con la etiqueta `chat`, un título del estilo «Separador punto y coma en el lector de CSV», y en «Dónde» la conversación.
3. Confírmala en la memoria del proyecto.
   **Esperado:** pasa a confirmada, como cualquier otra.
4. Abre otra conversación desde el mismo proyecto, pregunta «¿Qué hay en marcha?», y espera 30 minutos (o arranca Skynet con `SKYNET_CHAT_SUMMARY_IDLE=1m` y `SKYNET_CHAT_SUMMARY_INTERVAL=30s`).
   **Esperado:** sale un turno de resumen. Como no se decidió nada, contesta «NADA» y no aparece ninguna observación nueva. El evento `chat.conversation.summarized` de la conversación tiene `outcome: nothing`.

## Qué no cambia

- La web, el runner y el protocolo son los de CH-A a CH-E.
- Las conversaciones sin proyecto no se resumen.
