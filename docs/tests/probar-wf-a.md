# Probar WF-A - Workflows sembrados más claros

WF-A (#313) es la primera PR del hito WF («Workflows que se entienden»). Solo cambia los workflows sembrados de `workflows/*.yaml`, sus tests y la documentación. No toca el motor ni la web, y no tiene migración.

- **Cada fase tiene `name` y `phase`.** Son lo que enseñarán las tarjetas, el resumen y el grafo (WF-C a WF-F). Hoy ya salen en el grafo y en la ejecución.
- **Fuera los prompts de agente que nunca se leían.** El prompt de la fase manda sobre el del agente. Afecta a `cartografo`, `arquitecto`, `redactor`, `investigador` y `desarrollador` (en `corregir-bug` e `implementar-tarea`). Cada agente lleva ahora una `description` de una línea.
- **Descripciones cortas**, que dicen para qué sirve cada workflow.
- **`corregir-bug`:** la puerta `sin-reproducir` («Decides si seguir sin reproducirlo»). Solo se para si `reproducir` devuelve `reproduced: false`; si se reprodujo, se omite y `corregir` sigue sin esperar.
- **`conocer-repositorio`:**
  - el mapa se confirma con un commit (`checks: [commit]`);
  - se entrega con las cuatro fases de siempre (`pr`, `ci`, `aprobar-fusion` con `risk: low`, `fusionar`), todas con `condition: repository.github`;
  - la puerta no se aprueba sola: no hay juez.
- **`revisar-codigo`:**
  - `informe` devuelve `outputSchema` (`summary` y `findings` con `title`, `file`, `severity`, `recommendation` y `source`);
  - el agente `tests` pasa a llamarse `revisor-tests`, y el hijo sigue con id `tests`, así que `{{stages.revisar.tests.output…}}` no cambia.
- **`adhoc` no cambia:** su YAML está en la migración V12 y cambiarlo pediría una migración nueva, que no va en esta PR.

Ninguna prueba de esta guía modifica ficheros versionados de skynet. Las ejecuciones usan fake-claude y fake-codex, así que no gastan cuota.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*SeedWorkflowsTest'
```

`SeedWorkflowsTest`, con los seis workflows:
- se publican sin problemas, con los modelos de `modelos.env` y con todos rellenos;
- `everyStageHasANameAndAPhase`: ninguna fase sin `name` ni `phase`;
- `noAgentPromptIsDead`: ningún agente tiene un prompt que no use ninguna fase. Los jueces sí cuentan, porque lo reciben como «Indicaciones».

## 2. Publicarlos en un Skynet local

Con el control plane en marcha (ver `docs/arranque-local.md`):

```bash
scripts/seed-workflows.sh --actualizar
```

- Espera `✓ … actualizado (vN)` en los seis, o `· … ya está al día` si ya estaban.
- Si alguno tiene un borrador abierto, el script no lo toca: descártalo antes desde la web.
- En **Fábrica → Workflows**, abre cada uno en la pestaña **Grafo**. Cada nodo debe enseñar su nombre («Especifica la propuesta», «Espera la CI»…) y salir en su carril; `aprobar-plan` sale en Planificar.

## 3. La puerta de `corregir-bug`, sobre demo-repo

1. Lanza `corregir-bug` sobre un trabajo de demo-repo con el runner de fake-codex, de modo que `reproducir` devuelva `reproduced: true`:
   - la fase «Decides si seguir sin reproducirlo» sale **omitida** (`stage.skipped`, `condition-false`);
   - `corregir` arranca sin esperar y continúa el worktree de `reproducir`.
2. Si tu fake devuelve `reproduced: false`:
   - la ejecución se para en **Aprobaciones**, con la acción «No se ha podido reproducir …: ¿sigo con la corrección?»;
   - al aprobarla, `corregir` sigue en el mismo worktree.

## 4. Terminar

```bash
git status --short   # vacío: el checkout sigue limpio
```
