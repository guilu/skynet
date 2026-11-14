# Probar WF-C - Tarjetas de workflow con más detalle

WF-C (#315) es la tercera PR del hito WF («Workflows que se entienden»). Solo toca la web: la página **Fábrica › Workflows** usa el `overview` de WF-B para que cada tarjeta cuente qué hace el workflow sin tener que abrirlo.

Qué cambia:

- **La tarjeta entera es el enlace** al workflow. Las acciones van en «⋯»: un menú en escritorio y una hoja inferior en el móvil.
- **Tira de etapas**: Espe., Plan., Impl., Prob., Revi. y Entr., en azul las que tienen alguna fase. El lector de pantalla las dice en una frase («Etapas: Implementación, Revisión, Entrega.»).
- **Chips**: «N fases» (y «N a la vez» si hay fases en paralelo), aprobaciones, jueces, «Entrega por PR», «Solo lee» y «Pide N datos». Solo salen los que aplican.
- **Proveedores** con su monograma (CC, Cx, AG; R para «El del repositorio») y su nombre. Los colores de los monogramas son tokens nuevos (`--prov-claude`, `--prov-codex`, `--prov-agy` y su `-soft`) en claro y en oscuro.
- **Pie**: versión publicada y borrador, trabajos pendientes y fecha.
- **Filtros** «Con borrador», «Entregan por PR» y «Solo leen» con su número, y uno por proveedor si hay más de uno. Se guardan en la URL (`?f=pr,readonly&prov=codex`), igual que la búsqueda.
- **Móvil** (≤ 720 px): una columna, sin descripción, filtros desplazables en horizontal, botón flotante «Nuevo» sobre la barra de abajo y acciones en hoja inferior.

Un workflow cuyo `overview` llega `null` (su versión no se puede leer) se ve como antes: nombre, descripción y versiones.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/pages/WorkflowsPage.test.tsx
```

`WorkflowsPage.test.tsx` comprueba los chips de dos workflows, los nombres de los proveedores, la frase de etapas, los proveedores de una tarjeta y que los filtros «Solo leen» y de proveedor dejan solo las tarjetas que tocan.

## 2. Escritorio

Con el entorno local arrancado y los workflows sembrados (`scripts/seed-workflows.sh`), abre <http://localhost:5173/workflows>.

1. Cada tarjeta enseña la tira de etapas, los chips, los proveedores y el pie. Comprueba con los sembrados:
   - `especificar-planificar-implementar`: 9 fases, 2 aprobaciones, 1 juez, Entrega por PR; AG · CC · Cx.
   - `revisar-codigo`: «2 fases · 3 a la vez», «Solo lee», «Pide 1 dato»; solo Revisión y Entrega en azul.
   - `adhoc` (Agente suelto): «R · El del repositorio» y sin «⋯».
2. Pulsa en cualquier parte de una tarjeta (no solo en el título): abre su página.
3. Pulsa «⋯»: sale el menú de archivar sin abrir la tarjeta.
4. Pulsa «Solo leen»: quedan `revisar-codigo` e `investigar-mejoras`, y la URL lleva `?f=readonly`. Recarga: el filtro sigue.
5. Pulsa «Codex» con «Solo leen» activo: solo quedan los que cumplen los dos.
6. Navega con el teclado: Tab llega al enlace de cada tarjeta (se marca la tarjeta entera) y luego a su «⋯».
7. Cambia a tema oscuro (Ajustes › Apariencia): los monogramas y la tira se leen bien.

## 3. Móvil

En el navegador, con las herramientas de desarrollo en un móvil de 390 px de ancho (o en el teléfono):

1. Las tarjetas van en una columna y sin descripción.
2. Los filtros se desplazan en horizontal sin desbordar la página.
3. El botón «Nuevo» flota sobre la barra de abajo y abre «Nuevo workflow».
4. «⋯» abre una hoja inferior con «Ver qué hace» y «Archivar» (o «Restaurar» con «Archivados»). Escape o la X la cierran.
