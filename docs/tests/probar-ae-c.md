# Probar AE-C en local

AE-C lleva archivar y eliminar a la web (#51). Todo lo que en AE-A y AE-B se hacía con `curl` ahora tiene su botón. No hay migraciones ni dependencias nuevas.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

Entra en http://localhost:8080 y lanza un par de agentes que terminen, para tener ejecuciones con worktree.

## 2. Qué mirar

1. **Archivar una ejecución.**
   - En una ejecución terminada, abre el menú «⋯» de la cabecera y pulsa **Archivar**.
   - Sale un aviso «Archivado el …» con **Restaurar**.
   - En una ejecución en curso el menú no aparece.
2. **Eliminar con worktree.**
   - En el mismo menú, ahora con **Restaurar** y **Eliminar…**, pulsa **Eliminar…**.
   - El panel dice lo que se borraría y que el worktree lo impide; «Eliminar definitivamente» está desactivado.
   - Pulsa **Eliminar el worktree**. En unos segundos, cuando el runner lo borra, el botón se activa solo.
   - Elimina: vuelves al trabajo y la ejecución ya no está ni con el chip «Archivadas».
   - En **Actividad** aparece `workflow.purged`.
3. **Varias ejecuciones a la vez.**
   - En **Ejecuciones**, marca dos terminadas con las casillas y pulsa **Archivar** en la barra que aparece.
   - Una en curso falla y la barra dice por qué.
   - Con el chip **Archivadas** se ven; márcalas y prueba **Restaurar** y **Eliminar…**. Las que aún tengan worktree no se eliminan y se listan con su motivo.
4. **Proyectos y trabajos.**
   - Archiva un proyecto desde su menú «⋯» (en la lista o en su página).
   - Su página lo avisa y desaparecen «Nuevo repositorio», «Nuevo trabajo» y los botones de configurar.
   - En sus trabajos no se puede lanzar un agente, y el aviso dice que el proyecto está archivado.
   - En **Proyectos** ya no sale; con el chip **Archivados** sí. Restáuralo desde ahí.
   - Un trabajo se archiva igual, y en su página el chip «Archivadas» enseña sus ejecuciones archivadas.
5. **Repositorios y runners.**
   - En la pestaña Repositorios de un proyecto, cada tarjeta tiene su menú. Uno usado por algún agente no se elimina: el panel lo explica.
   - En **Runners**, el menú dice **Olvidar** y **Volver a enseñar**, y el chip es **Olvidados**.
6. **Teclado y tema oscuro.** El menú se abre con Intro y se recorre con las flechas; el panel de eliminar se cierra con Escape.

## 3. Comprobaciones automáticas

```bash
cd web && npm run lint && npx vitest run && npm run build
```

- `src/Archive.test.tsx` cubre un proyecto archivado (aviso, menú, panel bloqueado, limpieza y eliminación), el chip de proyectos, la acción en bloque de ejecuciones y el texto de las cuentas.
- `e2e/archive.e2e.ts` archiva una ejecución real, borra su worktree desde el panel, la elimina, y archiva y restaura un proyecto, pasando axe. Se lanza con `scripts/e2e.sh e2e/archive.e2e.ts`.
