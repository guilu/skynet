# Probar WF-B - Resumen de la definición en la API

WF-B (#314) es la segunda PR del hito WF («Workflows que se entienden»). Solo toca la API: cada workflow de `GET /api/workflows` y de `GET /api/workflows/{key}` trae `overview`, que las tarjetas de WF-C usarán sin tener que pedir cada versión. No hay migración y la web no cambia.

`overview` se calcula de la misma versión de la que salen `name` y `description`: la publicada o, si no hay ninguna, el borrador. Si esa versión no se puede leer, vale `null`. Campos:

- `stages`, `parallelChildren`, `gates` y `judges`;
- `providers` (con `repository` si una fase de agente no dice `agent`) y `fallbackProviders` (las reservas que no trabajan ya);
- `lanes`: las macrofases en el orden de los carriles del grafo;
- `inputs`: `[{name, required}]`;
- `delivers` y `readOnly`.

Lo explica [`docs/workflows.md`](../workflows.md), en «El resumen de cada workflow (WF-B)».

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_it \
  ./gradlew :control-plane:test --tests '*WorkflowOverviewTest' --tests '*ContractIT' --tests '*WorkflowDefinitionsIT'
```

- `WorkflowOverviewTest`, con los workflows sembrados de `workflows/`:
  - `especificar-planificar-implementar`: 9 fases, 2 aprobaciones, 1 juez, Antigravity, Claude Code y Codex, cinco etapas, entrega por PR y no es de solo lectura;
  - `implementar-tarea`: 2 jueces;
  - `corregir-bug`: 8 fases con la puerta condicional y las etapas en el orden del grafo;
  - `revisar-codigo`: 3 a la vez, el dato `foco` opcional y de solo lectura;
  - `investigar-mejoras`: Codex sale como reserva, no como proveedor;
  - `conocer-repositorio`: no es de solo lectura porque confirma el mapa;
  - un agente suelto sin `agent` sale como `repository`.
- `ContractIT`: `fixtures/contracts/workflows.json` y `workflow.json` llevan `overview`.
- `WorkflowDefinitionsIT`: la lista y el detalle siguen igual, también con borradores y archivados.

## 2. En un Skynet local

Con el control plane en marcha y los workflows sembrados (`scripts/seed-workflows.sh --actualizar`, ver `docs/arranque-local.md`):

```bash
curl -s -u admin:$SKYNET_ADMIN_PASSWORD localhost:8080/api/workflows \
  | jq '.[] | {key, overview}'
```

Comprueba:
- **`adhoc`:** `providers: ["repository"]`, `inputs: [{name: "prompt", required: true}]` y `lanes: []`. Su fase no tiene `phase`, porque `adhoc` no cambió en WF-A.
- **`revisar-codigo`:** `parallelChildren: 3` y `readOnly: true`.
- **`especificar-planificar-implementar`:** `gates: 2`, `delivers: true` y `lanes: ["spec","plan","implement","review","deliver"]`.
- **Con un borrador:** abre uno de un workflow que ya tiene versión publicada (botón «Editar») y añade una fase. `overview` no cambia, porque sigue saliendo de la versión publicada.
- **Workflow nuevo, sin publicar:** créalo desde «Nuevo workflow». `overview` sale del borrador y cambia al guardar.

## 3. Terminar

```bash
git status --short   # vacío: el checkout sigue limpio
```
