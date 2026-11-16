# Probar MV-F - ¿Quién lo vio?

Primera PR de MV-F (#311), el último paso del hito MV «Memoria viva»: la API que necesita la web para decir, desde una entrada de la memoria, qué agentes la vieron. Esta PR no cambia la web.

- **`GET /api/memory/{id}/readers`** devuelve las invocaciones que recibieron, buscaron, leyeron o guardaron la entrada, de la más reciente a la más antigua, hasta 200. Cada una lleva la ejecución, la fase, la clave del trabajo, el proveedor, la versión que vio, cómo (`PROMPT`, `CONTEXT`, `SEARCH`, `GET` o `SAVE`) y cuántas veces. Una entrada que no existe da 404.
- **Migración V61:** un índice en `memory_access (observation_id)`, para no recorrer la tabla entera.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*MemoryAgentIT' --tests '*MigrationsIT'
```

`MemoryAgentIT` crea una entrada con un agente y la lee con otro.
- **Esperado:** `readers` devuelve `SAVE` para el primero y `SEARCH`, `GET` y `CONTEXT` para el segundo, todo en la versión 1, con la clave del trabajo (`ALPHA-1`), la fase y la ejecución.
- Una entrada que no existe da 404.

## 2. Skynet desplegado

Trabaja sobre `demo-repo`. Elige una entrada que haya llegado a algún agente, por ejemplo la bitácora de una ejecución anterior, que va en el bloque de la siguiente.

```bash
curl -s $AUTH "$API/api/projects/<proyecto>/memory?kind=RUN_LOG&size=1" | jq -r '.items[0].id'
curl -s $AUTH "$API/api/memory/<id>/readers" | jq '.[] | {workItemKey, stageKey, provider, via, version, times}'
```

**Esperado:**
- una fila con `PROMPT` por cada agente que la recibió en su bloque, con su trabajo y su fase;
- con `mem_search` o `mem_get` (guion de [`probar-mv-a.md`](probar-mv-a.md#2-skynet-desplegado-sin-github)), también filas `SEARCH` y `GET`;
- `curl -s -o /dev/null -w '%{http_code}' $AUTH "$API/api/memory/$(uuidgen)/readers"` da `404`.

## 3. Lo que no cambia

- `GET /api/agent-runs/{id}/memory` y la pestaña Memoria van igual.
