# Probar CU-C en local

CU-C (#112) trae la **página Uso** y cierra el hito CU:

- `/usage`, con «Uso» en la navegación: una tarjeta por CLI con un medidor por ventana (Skynet en azul, lo de fuera rayado, la marca del ritmo uniforme), la evolución de la ventana y lo que cuesta cada agente.
- El KPI «Cuota» en la cabecera de la ejecución y la sección «Cuota» en la pestaña Coste del agente.
- Los avisos de cuota (más del 90 %, o la semana 15 puntos por encima del ritmo) en el Dashboard y en Uso.

No hay migraciones ni cambios en la API.

## 1. Con los CLIs falsos

Arranca igual que en `probar-cu-b.md`, con Codex al 88 % y 3 puntos por sesión:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
rm -rf /tmp/cu-c-codex && mkdir /tmp/cu-c-codex
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_QUOTA_POLL=30s CODEX_HOME=/tmp/cu-c-codex \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CODEX_QUOTA,FAKE_CODEX_QUOTA_STEP \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CODEX_QUOTA=88,40 FAKE_CODEX_QUOTA_STEP=3,1 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

1. Entra en **Uso** (barra lateral, entre Runners y Actividad; en el móvil, en la barra de abajo).
   - Tarjeta de Claude Code con «Sin datos» y la nota de que solo informa al ejecutar un agente (aún no ha corrido ninguno).
   - Tarjetas de Codex y Antigravity. Codex: 88 % en la ventana de 5 h, «Justa», «Skynet 0 %», «Consultado hace…». Antigravity: «Solo lectura», sus grupos de modelos y la nota de que no se atribuye.
   - La raya vertical de cada medidor es el ritmo uniforme.
2. Lanza un agente suelto con **Codex** (Lanzar workflow → Proveedor «Codex»). Al terminar:
   - En Uso, Codex sube a 91 %, «Casi agotada», con «Skynet 3 %» y un trozo azul en el medidor. Arriba sale «Necesita tu atención (1)»: «Codex al 91 % de la ventana de 5 h».
   - El Dashboard trae el mismo aviso; al pulsarlo lleva a Uso.
   - En la ejecución, el KPI «Cuota» dice «3 % de 5 h» y debajo «Codex: 3 % de 5 h · 1 % semanal». En la pestaña **Coste** del agente, la sección «Cuota»: «Codex · Ventana de 5 h: 3 % (medido)».
3. Lanza un agente con **Claude Code**. La tarjeta de Claude Code pasa de «Sin datos» a sus medidores, con «Dato de hace…, de la última ejecución» (el fake da una cuota grabada con ventanas ya renovadas: sale a 0 % con «Se ha renovado; falta un dato nuevo»).
4. **Evolución de la ventana**: elige Codex y «5 h». Se ve la escalera (azul abajo, rayado encima) y la línea «ahora»; al pasar el ratón, el tooltip con Skynet, fuera y total. Con «Semana», la ventana semanal.
   - Con los fakes, `resetsAt` se mueve con cada muestra, así que todo sale junto cerca de «ahora». Con los CLIs de verdad la ventana es fija.
5. **Qué cuesta cada agente**: cambia entre 24 horas, 7 días y 30 días. Una fila por agente y CLI (`agent`, Agente suelto · Codex…) y, a la derecha, las ejecuciones que más han gastado, que enlazan a su página.
6. Pulsa **Actualizar**: sale «Pedido: el runner consulta sus cuotas…» y en unos segundos «Consultado hace un momento» en Codex.
7. Mira la página en el móvil (barra inferior) y en tema oscuro.

## 2. En la máquina de Skynet, con los CLIs de verdad

1. Abre Uso: las ventanas reales de Claude Code (tras el último agente), Codex y agy, con su plan y la hora de renovación.
2. Lanza un workflow con agentes de Claude Code y Codex. Al terminar, su parte aparece en azul en los medidores, en el KPI «Cuota» de la ejecución y en «Qué cuesta cada agente».
3. Usa `codex` a mano un rato y pulsa «Actualizar»: la subida aparece rayada (fuera de Skynet).

## 3. Comprobaciones automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test ./gradlew check
cd web && npx tsc -b && npx oxlint && npx vitest run
scripts/e2e.sh usage.e2e.ts
```

`usage.e2e.ts` fija la cuenta de Codex al 88 %, lanza un agente de Codex y otro de Claude Code, y comprueba la parte medida, los medidores, el aviso del 90 % en Uso y en el Dashboard, la cabecera y la pestaña Coste de la ejecución, y axe. Al final devuelve la cuenta a una cuota baja.
