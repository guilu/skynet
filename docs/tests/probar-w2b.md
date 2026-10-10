# Probar W2-B en local

W2-B lleva el **motor de workflows a la web** (#60): un trabajo lanza cualquier workflow publicado con sus datos, la web enseña lo que se le permitirá a cada fase, y la ejecución lista todas sus fases desde el principio con a qué fase espera cada una. Cierra el hito W2 con la E2E de tres fases y reinicio del control plane.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude. El retardo entre líneas deja tiempo para ver las fases en marcha:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=1500 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

No hay migraciones ni dependencias nuevas. Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado y un trabajo.

## 2. Publicar el workflow de ejemplo

En **Workflows → Nuevo workflow**, pega el YAML de «Un workflow de ejemplo» del README (`revisar-y-corregir`), **Crear borrador** y **Publicar…**.

## 3. Lanzar desde la web

1. En el trabajo, el botón es ahora **Lanzar workflow**. El panel abre con `Workflow` = **Agente suelto · v1** (`adhoc`) y un campo **Prompt**, con su descripción debajo.
2. Elige **Revisar y corregir · v1**. El campo **Prompt** desaparece y sale **Objetivo** (obligatorio) con «Qué hay que revisar.».
3. Bajo la política del repositorio sale **Los agentes del workflow solo pueden recortarla:** con una línea por fase. `revision` y `tests` llevan la del repositorio y «límites del lanzamiento»; `correccion · agente corrector` lleva `herramientas Read, Edit` (solo las que el repositorio permite) y «20 turnos» (o el máximo del repositorio, si es menor).
4. Cambia de repositorio, si tienes otro con otra política: las líneas cambian.
5. Escribe un objetivo y **Lanzar**. Abre la ejecución.

## 4. La vista de la ejecución

1. La cabecera tiene **Workflow: Revisar y corregir** (`revisar-y-corregir v1`), que lleva a su página.
2. El árbol enseña las tres fases desde el principio, en el orden del YAML. `revision` y `tests` trabajan a la vez (las dos con el borde resaltado); `correccion` sale **Pendiente**, con borde discontinuo, «· agente corrector» y **Espera a revision y tests**.
3. Cuando termina una de las dos, el texto cambia a **Espera a** la que queda. Cuando terminan las dos, `correccion` pasa por **Lista** y arranca; su texto pasa a **Después de revision y tests**.
4. La ejecución termina **Completada**, con un agente por fase.

## 5. Cancelar una ejecución de varias fases

1. Lanza otra vez **Revisar y corregir** y, mientras `revision` y `tests` trabajan, pulsa **Cancelar ejecución** en la cabecera. Pide confirmación con «¿Cancelar la ejecución? Se detienen sus agentes y no arranca ninguna fase más…».
2. Confirma: los dos agentes se detienen y `correccion` acaba **Cancelada** con **No arrancó: se canceló la ejecución**. La ejecución termina **Cancelada**.
3. Una ejecución de `adhoc` (una fase) sigue mostrando **Cancelar agente**, como antes.

## 6. Cascada al cancelar un solo agente

1. Lanza **Revisar y corregir**. Con varias fases la cabecera cancela la ejecución entera, así que cancela solo el agente de `tests` por la API (su id sale en la URL al elegirlo en el árbol, `?agent=…`):

   ```bash
   curl -s -u admin:secreto -X POST http://localhost:8080/api/agent-runs/<id-del-agente>/cancel
   ```

2. `tests` acaba **Cancelada**, el motor cancela `revision` y `correccion` dice **No arrancó: se canceló la ejecución**. Si una fase falla en vez de cancelarse, el texto es **No arrancó porque falló …**.

## 7. Reiniciar el control plane a mitad

1. Lanza **Revisar y corregir** con `FAKE_CLAUDE_DELAY_MS=1500` en el runner.
2. Mientras `revision` y `tests` trabajan, para el control plane (Ctrl+C) y vuelve a arrancarlo.
3. Vuelve a entrar en la web: la ejecución sigue, `correccion` arranca cuando terminan las otras dos y todo termina **Completada**, con un solo agente por fase.

## 8. Lo automático

- `./gradlew :control-plane:test --tests '*WorkflowEngineIT' --tests '*ContractIT'`: política efectiva por fase antes de lanzar y `workflow`, `agent` y `dependsOn` en la vista.
- `cd web && npm test`: el formulario con `adhoc` y con otro workflow (datos por tipo y política por fase), las notas de cada fase (`stageNotes.test.ts`) y la vista con fases pendientes y omitidas.
- `SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh engine.e2e.ts`: tres fases, dos en paralelo y una que las une, lanzadas desde la web; se reinicia el control plane a mitad y termina **Completada** con un agente por fase.
