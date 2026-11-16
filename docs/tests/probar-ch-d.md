# Probar CH-D - La cortina del chat con Skyny

CH-D (#338) es la cuarta parte del hito CH «Chat con Skyny»: **la cortina en la web**. Solo cambia la web; el control plane y el runner son los de CH-A, CH-B y CH-C. Lo explica [`docs/chat.md`](../chat.md), en «La cortina».

- Botón «Pregúntale a Skyny» en la barra superior y atajo **⌘J / Ctrl+J**.
- La cortina baja sobre la página atenuada, con Skyny grande arriba, el prompt y tres sugerencias según la página.
- La conversación: mensajes, «Pensando…», parar el turno, aviso si no hay runner del chat.
- Las tarjetas de las acciones que propone Skyny, con Confirmar y Cancelar.
- La lista de conversaciones para retomar una, y «Nueva».

Las pruebas 1 y 2 no gastan nada. La 3 hace tres preguntas a Haiku (unos céntimos) y **no confirma** nada que lance o cambie algo: el lanzamiento se cancela desde la tarjeta.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/components/chat
```

`ChatCurtain.test.tsx`:
- sin conversación saluda, el prompt tiene el foco y las sugerencias son las de la página; la primera pregunta crea la conversación ligada al trabajo de la página, y sin runner del chat lo avisa;
- retoma la última conversación, enseña la tarjeta de lanzar con «Confirmar y lanzar»; al confirmar enlaza la ejecución, y el enlace cierra la cortina;
- mientras Skyny contesta no hay botón de enviar, sino «Parar», que para el turno;
- «Conversaciones» lista las recientes y retoma una; «Nueva» vuelve al saludo;
- una conversación guardada que ya no existe no da error: se empieza otra.

`chatContext.test.ts`: el trabajo o el proyecto de la página, las sugerencias, el estado y el nombre del modelo.

## 2. La cortina sin runner del chat

Con Skynet en marcha y ningún runner que declare el chat (o todos con `SKYNET_RUNNER_CHAT=false`):

1. En cualquier página, pulsa **Ctrl+J** (⌘J en el Mac).
   **Esperado:** la cortina baja desde el borde inferior de la barra superior (no aparece por encima de la barra en ningún momento), que se queda sin atenuar, y ocupa casi toda la web dejando un margen atenuado alrededor. Skyny sale grande y centrado, con «¿En qué te ayudo?», el prompt con el foco y tres sugerencias. En la barra, «Pregúntale a Skyny» queda marcado.
2. Pulsa **Escape**. Ábrela otra vez con el botón «Pregúntale a Skyny». Ciérrala con un clic en la parte atenuada.
3. Abre un trabajo de `demo-repo` y abre la cortina.
   **Esperado:** las sugerencias hablan de «este trabajo».
4. Pulsa «¿En qué punto está este trabajo?».
   **Esperado:** tu mensaje sale a la derecha, el título de la conversación arriba con la clave del trabajo, y el aviso «Ahora no hay ningún runner que atienda el chat…»; debajo de Skyny, «Ningún runner me atiende ahora».
5. Cierra la cortina, ve a otra página y vuelve a abrirla.
   **Esperado:** sigue en la misma conversación.
6. Pulsa «Conversaciones».
   **Esperado:** sale la conversación con la clave del trabajo y la fecha. «Nueva» vuelve al saludo.
7. Repite 1 y 4 con el tema oscuro y en el móvil (o con la ventana estrecha).
   **Esperado:** en el móvil la cortina ocupa casi toda la pantalla bajo la barra; Skyny es más pequeño (180 px, 150 con conversación) y sigue centrado arriba.

## 3. Con un runner del chat (tres preguntas a Haiku)

Con un runner con Claude Code real que declare «Chat con Skyny» en Runners:

1. Abre la cortina en Inicio y pulsa «¿Qué hay en marcha ahora mismo?».
   **Esperado:** debajo de Skyny, «Pensando…», y en lugar de la flecha de enviar sale el botón de parar. En unos segundos llega la respuesta de Skyny en español, que coincide con «En curso», sin recargar la página.
2. Escribe «Lanza revisar-codigo en demo-repo» (o un workflow que tengas sobre `demo-repo`).
   **Esperado:** Skyny contesta con una tarjeta «¿Lo lanzo?», su resumen y «Confirmar y lanzar» y «Cancelar»; debajo de Skyny, «Esperando tu confirmación».
3. Pulsa **Cancelar**.
   **Esperado:** la tarjeta dice «Cancelada · <hora>» y «No se ha hecho nada». En «En curso» no hay ninguna ejecución nueva.
4. Escribe otra pregunta y, mientras dice «Pensando…», pulsa el botón de parar.
   **Esperado:** en unos segundos llega el mensaje de que no pudo contestar porque lo paraste, y se puede volver a escribir.

## 4. Lo que no cambia

- La paleta ⌘K y el companion de la barra superior.
- El control plane y el runner: esta PR solo toca la web.
- Skyny no cambia de cara en la cortina todavía (CH-E).
