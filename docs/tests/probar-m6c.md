# Probar M6-C en local

M6-C hace que un agente cuyo proceso ya no existe acabe en «Fallida» en vez de quedarse en «Arrancando», elimina los worktrees (a mano o pasados 7 días) y limpia los logs antiguos del runner. Las E2E reinician el control plane y el runner a mitad de una ejecución.

## 1. Arrancar

En la rama de la PR, recompila y arranca todo con fake-claude lento, para tener tiempo de interrumpir. El control plane arranca con una retención corta para ver la limpieza automática sin esperar una semana:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> \
SKYNET_WORKTREE_RETENTION=5m SKYNET_WORKTREE_CHECK_INTERVAL=30s \
  ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_RUNNER_HOME=/tmp/m6c-runner \
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_DELAY_MS \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=1500 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Entra en `http://localhost:5173` con `admin` / `secreto` y usa un proyecto con un repositorio registrado (o créalos). Para lanzar el runner otra vez en los pasos siguientes, repite la última orden tal cual.

## 2. Reiniciar el control plane a mitad

1. Lanza un agente y espera a ver la primera herramienta (`Read`) en la línea de tiempo.
2. Para el control plane (Ctrl+C) y arráncalo otra vez con la misma orden.
3. Vuelve a entrar en la web y abre la ejecución: termina en «Completada», con todos sus eventos y sin huecos. El agente no pasa por «Fallida».

## 3. Reiniciar el runner a mitad

1. Lanza otro agente y espera a la primera herramienta.
2. Mata el runner de golpe: `pkill -9 -f dev.skynet.runner.RunnerMain`. El proceso de fake-claude sigue vivo (`pgrep -fa fakeclaude`).
3. Arranca el runner otra vez. El agente acaba en «Fallida» con «El runner se reinició durante la ejecución» y `pgrep -fa fakeclaude` ya no devuelve nada.

## 4. Un agente que el runner ya no tiene

Antes, un agente cuyo runner perdía la invocación se quedaba en «Arrancando» para siempre.

1. Lanza un agente y espera a la primera herramienta.
2. Mata el runner (`pkill -9 -f dev.skynet.runner.RunnerMain`), borra su journal (`rm /tmp/m6c-runner/journal.db*`) y mata el proceso huérfano (`pkill -f fakeclaude`).
3. Arranca el runner: se registra de nuevo y no sabe nada del agente.
4. Tras dos latidos (unos 30 s) el agente pasa a «Fallida» con «El proceso ya no existe en el runner».

## 5. Eliminar un worktree a mano

1. Lanza un agente nuevo y, cuando termine en «Completada» (antes de que pasen los 5 minutos de retención), ábrelo. En el inspector, «Eliminar worktree…» explica que borra el directorio, que la rama se conserva y que después no se podrá continuar, bifurcar ni verificar.
2. Confirma con «Eliminar». En unos segundos el agente muestra «Worktree eliminado el …» y la rama.
3. El directorio ya no existe (`ls /tmp/m6c-runner/workspaces/<ejecución>/`) y la rama sigue en el repositorio (`git -C <repositorio> branch --list 'skynet/*'`).
4. «Bifurcar…» y «Eliminar worktree…» ya no aparecen; en Conversación, la caja de mensaje dice «El worktree de esta sesión se eliminó: no se puede continuar.»; en Verificación, «Reejecutar verificación» está desactivado. «Reintentar…» sigue disponible.
5. En la línea de tiempo aparecen «Eliminación del worktree pedida (desde la web)» y «Worktree eliminado (…)».
6. Con un agente en curso, la API lo rechaza: `curl -s -u admin:secreto -X POST http://localhost:8080/api/agent-runs/<id>/workspace/cleanup` responde 409.

## 6. Retención automática

1. Deja algún agente terminado sin tocar (por ejemplo, el del paso 2).
2. A los 5 minutos de terminar, en menos de 30 s más, su worktree se elimina solo: «Eliminación del worktree pedida (retención)» y «Worktree eliminado».
3. Para volver a lo normal, arranca el control plane sin `SKYNET_WORKTREE_RETENTION` ni `SKYNET_WORKTREE_CHECK_INTERVAL` (7 días, cada hora).

## 7. Logs antiguos del runner

1. Envejece un log: `touch -d '8 days ago' /tmp/m6c-runner/logs/<algún-id>.ndjson`.
2. Reinicia el runner (Ctrl+C y la misma orden): al arrancar borra los logs de más de 7 días; en su salida aparece «Borrados 1 logs con más de PT168H». Los demás siguen ahí.
3. Los artefactos del agente siguen en la web (pestaña Artefactos): el control plane tiene su copia.

## 8. E2E

```bash
sudo -u postgres psql -c "DROP DATABASE IF EXISTS skynet_e2e" -c "CREATE DATABASE skynet_e2e OWNER skynet"
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh
```

Pasan las cinco, entre ellas «reiniciar el control plane a mitad no interrumpe la ejecución» y «reiniciar el runner a mitad deja el agente fallido y ningún proceso vivo».
