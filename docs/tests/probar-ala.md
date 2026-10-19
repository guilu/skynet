# Probar AL-A en local

AL-A deja Skynet listo para probarlo con tus proyectos:
- **`scripts/reset-local.sh`** borra la base de datos, los artefactos y, con `--runner`, lo que dejó el runner en tus repositorios.
- **`scripts/seed-workflows.sh`** publica cinco workflows iniciales que reparten el trabajo entre Claude Code, Codex y Antigravity CLI.
- Los modelos de cada proveedor están en **`workflows/modelos.env`**.

Por qué cada proveedor y cada modelo: [`docs/arranque-local.md`](../arranque-local.md), «Qué proveedor para qué».

No hay migraciones ni cambios en el control plane, el runner o la web.

> **Para despliegues automáticos:** el paso 2 borra datos y **no** se ejecuta solo (pide escribir «borrar»). Haz solo los pasos 1 y 3: `seed-workflows.sh` no borra nada y se salta los workflows que ya existen.

## 1. Ver qué borraría

Con Skynet levantado con Docker Compose:

```bash
scripts/reset-local.sh --dry-run --runner
```

Lista los volúmenes `skynet_postgres-data` y `skynet_artifacts-data`, la carpeta del runner (`~/.skynet-runner`) y, por cada repositorio registrado, cuántas ramas `skynet/*` y referencias `refs/skynet/*` tiene. Termina con «(--dry-run: no se ha borrado nada)».

## 2. Borrar de verdad (a mano)

1. Si alguna rama `skynet/*` tiene trabajo que quieras conservar, fusiónala o renómbrala antes.
2. Lanza el borrado:

   ```bash
   scripts/reset-local.sh --runner
   ```

3. Escribe `borrar`. El script:
   - para el servicio `skynet-runner`, si está en marcha;
   - para Skynet y borra los volúmenes;
   - limpia el runner y tus repositorios;
   - vuelve a levantar Skynet y arranca el runner;
   - publica los workflows: «Workflows: 5 publicados, 0 ya estaban, 0 con error.».
4. En la web:
   - **Proyectos**, **Ejecuciones** y **Actividad** están vacíos;
   - en **Runners**, tu runner vuelve a estar en línea, registrado de nuevo;
   - en tus repositorios, `git worktree list` solo enseña el principal y `git branch --list 'skynet/*'` no da nada.

Si escribes otra cosa, dice «No se ha borrado nada.» y no toca nada.

## 3. Los workflows iniciales

1. Sin borrar nada, vuelve a ejecutarlo:

   ```bash
   SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh
   ```

   Dice «ya existe, no se toca» para los cinco.
2. En **Workflows** están `adhoc` y los cinco nuevos, todos con su v1 publicada:
   - `conocer-repositorio`
   - `corregir-bug`
   - `especificar-planificar-implementar`
   - `implementar-tarea`
   - `revisar-codigo`
3. Abre **Implementar una tarea** → pestaña **Grafo**:
   - `explorar` → `implementar` → `revisar` (con `calidad` y `seguridad`) → `corregir`;
   - en **Qué define**, cada agente con su proveedor: Antigravity, Claude Code (`sonnet`) y Codex.

## 4. Elegir los modelos de Codex y Antigravity

1. Mira los ids que admite cada CLI con el usuario del runner:
   - Codex: `/model` dentro de `codex`.
   - Antigravity: `agy models`.
2. Rellena `CODEX_FUERTE`, `CODEX_RAPIDO`, `AGY_FUERTE` (Gemini 4 Argon si tu plan lo tiene) y `AGY_RAPIDO` en `workflows/modelos.env`.
3. Comprueba que siguen siendo publicables:

   ```bash
   ./gradlew :control-plane:test --tests '*SeedWorkflowsTest'
   ```

4. Para aplicarlos, vuelve al paso 2 (siembra de cero) o edita en la web los workflows ya publicados (**Editar** abre su v2).

## 5. Con un proyecto tuyo

1. **Proyectos** → crea uno y registra un repositorio con su ruta local. Déjale sin comando de verificación.
2. Crea un trabajo «Conocer el repositorio» y lanza **Conocer un repositorio**:
   - `comandos` (Codex) devuelve `testCommand`;
   - `mapa` (Antigravity) deja `docs/skynet/mapa.md` en el worktree, y sale entre los cambios del agente.
3. Pon ese `testCommand` como **comando de verificación** del repositorio.
4. Crea un trabajo con una tarea pequeña (título y descripción claros) y lanza **Implementar una tarea**:
   - `implementar` termina «Verificando» → completada solo con los tests en verde y cambios;
   - `revisar` lanza Codex y Antigravity a la vez;
   - `corregir` se omite si ninguno encuentra algo que bloquee.
5. En **Uso**, el gasto queda repartido entre las tres cuentas.

## 6. Tests automáticos

```bash
./gradlew :control-plane:test --tests '*SeedWorkflowsTest'
```

Cada `workflows/*.yaml` se puede publicar sin ningún aviso, con los modelos de `modelos.env` y con todos rellenos, y su nombre de fichero coincide con su `id`.
