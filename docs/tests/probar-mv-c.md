# Probar MV-C - Páginas de la wiki

MV-C (#308) es la tercera PR del hito MV «Memoria viva». Lo explica [`docs/memoria.md`](../memoria.md), en «La wiki».

- **Cada proyecto tiene una wiki**: páginas de markdown con sus fuentes y sus versiones (migración V58: `wiki_page` y `wiki_page_revision`).
- **Skynet genera cuatro sin modelo**, de todo el proyecto:
  - **Índice** (`indice`): qué páginas hay, con una línea de cada una.
  - **Lo hecho** (`lo-hecho`): los trabajos con una PR fusionada, con la PR, la fecha, el repositorio y los ficheros.
  - **Errores conocidos** (`errores-conocidos`): hallazgos corregidos, lecciones de `prevenir` aplicadas, `PITFALL` y `BUGFIX` confirmados y escapes del juez.
  - **Decisiones y restricciones** (`decisiones`): las `DECISION` y `CONSTRAINT` confirmadas.
- **Cuándo:** al terminar cada ejecución, al fusionarse una PR (después de confirmar su memoria) y a mano con `POST /api/projects/{id}/wiki/refresh`.
- **Lo que no cambia no crea versión.**
- **API de solo lectura:** `GET /api/projects/{id}/wiki`, `…/wiki/{slug}`, `…/wiki/{slug}/revisions` y `…/revisions/{version}`.
- **Todavía no la ven los agentes** (MV-D) ni la web (MV-F).

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WikiPagesIT' --tests '*MigrationsIT' --tests '*ModularityTest'
```

`WikiPagesIT`:
- con un trabajo entregado (AKA-1, PR #40, con `src/Pdf.java` y `web/Notas.tsx` en su `GIT_CHANGES`), un hallazgo corregido en dos ejecuciones, una lección aplicada, un escape y memoria de una persona y de un agente:
  - al terminar una ejecución salen las cuatro páginas, con origen `GENERATED`;
  - «Lo hecho» lleva «- **AKA-1** «Exportar las notas a PDF»: [PR #40](…), 3/11/2026, en akademia. Ficheros: `src/Pdf.java`, `web/Notas.tsx`.», y no el trabajo sin entregar;
  - «Errores conocidos» lleva el hallazgo «(en 2 ejecuciones)», la lección, la trampa confirmada y el escape; no lleva el hallazgo abierto, la lección propuesta ni la trampa sin confirmar del agente;
  - «Decisiones y restricciones» lleva la restricción y la decisión;
  - el índice dice «1 trabajo entregado; el último, AKA-1 el 3/11/2026», «1 fallo corregido, 1 lección, 1 trampa o bug, 1 escape» y «1 restricción, 1 decisión».
- regenerar sin cambios deja la versión 1; con una decisión nueva, `decisiones` pasa a la versión 2, el historial tiene dos versiones y la 1 no lleva la nueva. «Lo hecho» sigue en la 1;
- una página que no existe da 404;
- un proyecto vacío dice «Todavía no se ha entregado nada».

## 2. Skynet desplegado, sin GitHub

Trabaja sobre `demo-repo`. «Entregado» quiere decir PR fusionada, y sin GitHub no hay ninguna: márcala a mano como en [`probar-mv-b.md` §2](probar-mv-b.md#2-skynet-desplegado-sin-github), en la base de datos **de pruebas**, nunca en la de producción.

### 2.1 Las páginas de un proyecto con historia

1. Regenera la wiki del proyecto de `demo-repo`:

   ```bash
   curl -s $AUTH -X POST "$API/api/projects/<proyecto>/wiki/refresh" | jq '.[] | {slug, version}'
   ```

   **Esperado:** `decisiones`, `errores-conocidos`, `indice` y `lo-hecho`, todas en la versión 1.
2. Lee el índice y «Lo hecho»:

   ```bash
   curl -s $AUTH "$API/api/projects/<proyecto>/wiki/indice" | jq -r .body
   curl -s $AUTH "$API/api/projects/<proyecto>/wiki/lo-hecho" | jq -r .body
   ```

   **Esperado:**
   - el índice empieza por «La wiki de …» y enlaza las tres páginas con su resumen;
   - en «Lo hecho» sale el trabajo de la PR marcada como fusionada, con «PR #40», la fecha, `demo-repo` y, si su ejecución subió cambios, los ficheros.
3. Mira sus fuentes: `curl -s $AUTH "$API/api/projects/<proyecto>/wiki/lo-hecho" | jq .sources`.
   **Esperado:** una fuente `pull_request` por trabajo, con «DEMO-… · PR #40» y su URL.

### 2.2 Solo entra lo confirmado

1. Guarda desde la web, en la pestaña Memoria del proyecto, una **Decisión** «Probar la wiki» y una **Trampa** «Cuidado con la wiki».
2. Regenera (2.1, paso 1).
   **Esperado:** `decisiones` y `errores-conocidos` suben a la versión 2, y las dos entradas salen en su página. `lo-hecho` y el `indice` cambian solo si cambió su resumen.
3. Lanza en el proyecto un workflow con fake-claude que guarde un `PITFALL` con `mem_save` (guion de la guía de MV-A, `FAKE_MCP_CALLS=@.memoria.json`), y espera a que termine **sin** PR fusionada ni aprobación.
   **Esperado:** la trampa del agente no sale en «Errores conocidos», porque está sin confirmar.
4. Confírmala (`POST /api/memory/<id>/confirm`) y regenera.
   **Esperado:** ahora sí sale.

### 2.3 Se regenera sola y guarda el historial

1. Lanza cualquier trabajo con fake-claude y espera a que termine.
   **Esperado:** sin regenerar a mano, `GET …/wiki` enseña `updatedAt` reciente en las páginas que cambiaron.
2. Pide el historial: `curl -s $AUTH "$API/api/projects/<proyecto>/wiki/decisiones/revisions" | jq '.[] | {version, createdBy}'`.
   **Esperado:** una versión por cada cambio, de la más reciente a la más antigua, todas de `skynet`.
3. Pide una versión vieja con `…/revisions/1`. **Esperado:** el contenido de entonces.

## 3. Lo que no cambia

- Lanzar, la bitácora (`RUN_LOG`), la confirmación de la memoria y «¿ya está hecho?» van igual.
- Los agentes reciben el mismo bloque `<memoria-del-proyecto>` de antes.
- La pestaña Memoria de la web no cambia.
