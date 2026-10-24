# Probar CO-B - Companion global y avisos

CO-B ([#161](https://github.com/guilu/skynet/issues/161), parte de [#140](https://github.com/guilu/skynet/issues/140)) trae el companion a toda la web.

Qué trae:
- **Servidor:** el stream `/api/events/stream` admite dos parámetros nuevos:
  - `after=latest`, que empieza desde ahora sin reenviar el histórico;
  - `types=`, una lista separada por comas de tipos exactos o prefijos con `.*` (`approval.*`).

  `GET /api/events` admite también `types=`. La reanudación con `Last-Event-ID` sigue igual, y un tipo mal formado o un `after` que no es número ni `latest` da 400.
- **Web:**
  - un único stream global (`CompanionProvider` en `AppShell`);
  - la mascota de 34 px en la barra superior, con tooltip y menú rápido;
  - una campana con los últimos avisos;
  - avisos flotantes con la cara de la mascota, agrupados y silenciables;
  - dos opciones nuevas en Ajustes › Companion.
- **Documentación:** [`docs/companion.md`](../companion.md), sección «Companion global (CO-B)».

No hay migraciones nuevas ni dependencias nuevas, y no cambia la exposición de red.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 a 6 usan la web y la API del control plane local. Las ejecuciones reales van sobre **demo-repo (proyecto DEMO), nunca sobre skynet**.

## 1. Tests

```bash
./gradlew build --no-daemon
cd web && npm run lint && npm run format:check && npm run typecheck && npx vitest run
./scripts/e2e.sh e2e/companion.e2e.ts
```

- **`EventStreamIT`:**
  - `after=latest` no reenvía lo anterior;
  - `types=` filtra por tipo exacto y por prefijo; `agent.rate_limit` no coincide con `agent.rateXlimit`, porque el `_` no hace de comodín;
  - con `Last-Event-ID` reanuda sin duplicados aunque lleve `after=latest`;
  - el histórico paginado también filtra por tipos;
  - los parámetros mal formados dan 400.
- **Web, `notices.test.ts`:**
  - qué eventos avisan y cuáles no (los cambios internos no avisan);
  - la agrupación en 10 s y el máximo de 3 tarjetas;
  - los textos.
- **Web, `CompanionProvider.test.tsx`:**
  - el stream único con `after=latest` y `types`;
  - la mascota que cuenta las ejecuciones;
  - el aviso de éxito con su clave;
  - dos fallos en una tarjeta;
  - ningún aviso de la ejecución que miras, salvo las aprobaciones;
  - el aviso de aprobación que se va al decidirla;
  - el silencio.
- **E2E `companion.e2e.ts`**, con fake-claude:
  - éxito con aviso y campana;
  - un fallo, con la mascota en Error hasta que abres la ejecución;
  - con el silencio, sin tarjetas pero con la campana contando;
  - axe con el aviso visible y con la campana abierta.

## 2. El stream

Con la sesión iniciada en el navegador, copia la cookie o usa Basic con el usuario administrador:

```bash
curl -N -u admin:… 'http://localhost:8080/api/events/stream?after=latest&types=workflow.*,approval.*'
```

- No sale nada del histórico, solo los latidos cada 15 s.
- Al lanzar algo en otra pestaña, llegan `workflow.started` y `workflow.status.changed`, pero no `agent.tool.*` ni `agent.message.*`.
- `curl -i '…/api/events/stream?types=approval.%25'` y `…?after=pronto` dan **400**.

## 3. La mascota y el menú

1. **Barra superior:** a la izquierda del tema hay una mascota en una pastilla y, a su lado, una campana.
   - Sin nada en marcha: En espera; a los 5 minutos se duerme («zZ»).
   - Al pasar el ratón: «Todo al día» o «2 ejecuciones activas · 1 aprobación pendiente».
2. Lanza un workflow sobre **demo-repo** (proyecto DEMO).
   - La mascota pasa a Pensando o Ejecutando, con un punto verde.
   - Al pulsarla, el menú dice «Ejecuciones activas 1».
3. Si el workflow tiene una puerta de aprobación, al llegar a ella la mascota pasa a **Te necesita** (saluda con la mano) y el menú dice «Te necesitan 1».
4. **Menú:**
   - «Ejecuciones activas» lleva a Ejecuciones filtradas por En curso.
   - «Te necesitan» lleva a Aprobaciones.
   - «Avisos recientes» abre la campana.
   - «Ajustes del companion» baja hasta la sección en Ajustes.

## 4. Avisos

Quédate en el Dashboard (no en la ejecución) mientras corre:

1. Al lanzar sale «Ejecutando workflow · DEMO-… · nombre», que se va solo a los 4 s.
2. Al terminar bien sale «Workflow completado · DEMO-… · 3 min · 0,12 $» en verde. Se va a los 6 s, y la mascota hace Éxito (confeti) durante 8 s.
3. **Puerta de aprobación:** sale «Te necesito · La fase «…» de DEMO-… espera tu decisión» en ámbar, con «Revisar».
   - No se va sola.
   - Al aprobar desde otra pestaña desaparece.
4. **Agrupar:** lanza dos o tres ejecuciones casi a la vez. Al terminar sale **una** tarjeta, «3 ejecuciones completadas · DEMO-1, DEMO-2 y DEMO-3», con «Ver todas».
5. **Fallo:** un workflow con una fase `command: exit 1` (por ejemplo, el de la E2E).
   - Sale «Ha ocurrido un error · DEMO-… · fase «…»» en rojo, y la mascota se pone roja.
   - Al abrir esa ejecución, o al abrir y cerrar la campana, vuelve a su cara normal.
6. **La ejecución que miras:** abre una ejecución y espera a que termine. No sale tarjeta, y en la campana entra ya leída. Una aprobación de esa misma ejecución sí avisa.
7. **Como mucho 3 tarjetas.** Si llegan más tipos distintos, sale la más antigua.

## 5. Campana, silencio y ajustes

1. **Campana:**
   - El número rojo cuenta los no leídos.
   - Al abrirla se ven los avisos con su cara, hora y enlace, con los nuevos resaltados.
   - Al cerrarla quedan leídos.
   - «Vaciar los avisos» la deja vacía, con la mascota y «Aún no hay avisos».
2. **Silenciar 1 h** (menú de la mascota): no salen tarjetas, pero la campana sigue contando. En el menú pone «Quitar el silencio».
3. **Ajustes › Companion:**
   - Sin «Mostrar la mascota en la barra superior», la mascota desaparece de la barra y la campana sigue.
   - Sin «Avisos flotantes», no salen tarjetas.
   - Las dos opciones se guardan en este navegador: otro navegador no las ve.

## 6. Sin conexión y móvil

1. **Sin conexión:** para el control plane (`scripts/e2e-service.sh stop control-plane`, o Ctrl+C si lo arrancaste a mano). A los 5 s la mascota pasa a gris, con la insignia sin señal, y el tooltip dice «Sin conexión en vivo; reconectando…». Al arrancarlo vuelve sola, y lo que pasó entre medias se relee.
2. **Móvil** (o DevTools a 390 px): la mascota y la campana siguen en la barra, y las tarjetas salen arriba, a lo ancho.
3. **Tema oscuro y acentos:** las tarjetas mantienen el contraste; el cuerpo de la mascota sigue al acento.
