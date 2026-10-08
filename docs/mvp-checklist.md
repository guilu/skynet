# Checklist del MVP

Los 12 criterios de éxito del MVP (§23 de [la especificación](agentic-orchestration-system.md#23-criterios-de-éxito-del-mvp)), cómo se comprueba cada uno a mano con una sesión real de Claude Code y qué prueba automática lo cubre ya con fake-claude. El MVP se da por bueno cuando todas las casillas están marcadas en un repositorio real.

## Preparación

1. **Control plane y web** con Docker Compose (README, «Aplicación completa con Docker»), con `SKYNET_ADMIN_PASSWORD` y `SKYNET_RUNNER_REGISTRATION_TOKEN` en `deploy/.env`.
2. **Runner con Claude de verdad** en tu máquina, como servicio o en primer plano (README, «Runner local»). `SKYNET_CLAUDE_BIN` sin definir (usa `claude`) y Claude Code con sesión iniciada. En Runners aparece «En línea» con la versión de Claude Code.
3. **Un repositorio.** Vale uno tuyo con tests (por ejemplo, un proyecto Gradle: comando `./gradlew test`, informes por defecto). Si prefieres uno de juguete:

   ```bash
   mkdir -p ~/skynet-mvp && cd ~/skynet-mvp && git init -q -b main
   printf 'def add(a, b):\n    return a - b\n' > calc.py
   cat > check.sh <<'SH'
   # Test mínimo con informe JUnit: pasa si add(2, 3) == 5 y, si existe, subtract(5, 3) == 2.
   mkdir -p build/test-results
   if python3 -c 'import calc; assert calc.add(2, 3) == 5; assert not hasattr(calc, "subtract") or calc.subtract(5, 3) == 2'; then
     r='<testcase classname="CalcTest" name="calc"/>'; ok=0
   else
     r='<testcase classname="CalcTest" name="calc"><failure message="calc falla"/></testcase>'; ok=1
   fi
   echo "<testsuite name='CalcTest' tests='1'>$r</testsuite>" > build/test-results/TEST-CalcTest.xml
   exit $ok
   SH
   printf 'build/\n__pycache__/\n' > .gitignore
   git add . && git commit -qm "Inicial"
   ```

4. **Política del repositorio** (en el proyecto, «Política de agentes»): añade `Bash(git:*)` a las herramientas para que Claude pueda hacer commit (y `Bash(sh check.sh)` si quieres que ejecute los tests él también). El presupuesto por defecto (2 US$) sobra para estas pruebas.

## Criterios

Marca cada casilla (☐ → ☑) al comprobarlo con Claude. La columna «Automático» es la prueba que ya lo cubre con fake-claude en cada cambio.

| # | Criterio | Cómo comprobarlo | Automático |
|---|---|---|---|
| 1 | ☐ Registrar un repositorio | Proyectos → crea un proyecto → «Registrar repositorio» con la ruta local, el comando de verificación (`sh check.sh` o `./gradlew test`) y, si hace falta, los informes JUnit. Aparece en la lista con su verificación. | E2E `run.e2e.ts` (crear, lanzar…) |
| 2 | ☐ Crear un trabajo | En el proyecto, «Crear trabajo» (p. ej. «Arreglar la suma»). Aparece como `<CLAVE>-1`. | E2E `run.e2e.ts` |
| 3 | ☐ Lanzar una ejecución de Claude Code | En el trabajo, prompt «La función add de calc.py resta: arréglala, añade subtract y haz commit» → «Lanzar». La ejecución pasa de «En cola» a «En curso» en tu runner. | E2E `run.e2e.ts`; `compose-smoke.sh` en CI |
| 4 | ☐ Ver tokens, mensajes y herramientas en tiempo real | En la ejecución: «En vivo» en la cabecera, los tokens y el coste subiendo, y en el timeline cada `Read`, `Edit` y `Bash` según ocurren, con los mensajes de Claude. | E2E `run.e2e.ts` (incluido cortar la conexión y reconectar) |
| 5 | ☐ Ver el estado real del agente | La cabecera muestra el estado, el agente y la herramienta en curso y el runner; el Dashboard la lista como activa y Runners cuenta un agente activo. Al terminar: «Completada» con duración, turnos y coste. | E2E `run.e2e.ts`; `ReadProjectionsIT` |
| 6 | ☐ Conservar la sesión tras finalizar | Inspector → Resumen: id de sesión, rama `skynet/…` y worktree conservado. `git -C <repositorio> branch --list 'skynet/*'` muestra la rama. | E2E `run.e2e.ts` |
| 7 | ☐ Mostrar los archivos modificados y el diff | Inspector → Artefactos: `calc.py` en «Archivos modificados», su diff y el commit de Claude. | E2E `run.e2e.ts` |
| 8 | ☐ Mostrar el resultado de tests | Inspector → Verificación: «Verificado por Skynet» con «Pasa», «1 tests · 0 fallidos», el comando y su salida; la cabecera muestra «1/1 tests». «Reejecutar verificación» vuelve a pasarla. | E2E `run.e2e.ts`; `VerificationAndArtifactsIT` |
| 9 | ☐ Reanudar la sesión con un nuevo mensaje | Conversación → «Mensaje»: «Añade también multiply» → «Enviar». Se abre una ejecución nueva «Reanudación» con toda la conversación, en el mismo worktree; Claude recuerda lo anterior. | E2E `run.e2e.ts` |
| 10 | ☐ Cancelar una ejecución | Lanza otra con un prompt largo («Escribe tests exhaustivos para calc.py») y, en cuanto use herramientas, «Cancelar agente» → «Sí, cancelar». Acaba «Cancelada» y en tu máquina no queda ningún proceso de `claude` de esa ejecución (`pgrep -fa claude`). | E2E `run.e2e.ts` (cancelar a mitad) |
| 11 | ☐ Ver un timeline persistente | Recarga la página de una ejecución terminada: el timeline está entero. Reinicia el control plane (`docker compose … restart api`) y vuelve: sigue igual. Actividad muestra todos los eventos. | E2E `run.e2e.ts` (reinicios); `EventStoreIT` |
| 12 | ☐ Consultar los artefactos generados | Inspector → Artefactos: prompt, log NDJSON, resultado, cambios de git, diff e informe de tests, cada uno descargable o visible. | E2E `run.e2e.ts`; `VerificationAndArtifactsIT` |

## Comprobaciones de cierre

- [ ] **Sin secretos en la web.** Pide a Claude que lea un fichero con algo como `AKIA…` o `ghp_…`: en la web aparece como `[REDACTED]`. (E2E `review.e2e.ts`.)
- [ ] **Presupuesto.** Un lanzamiento con presupuesto 0,01 US$ acaba en «Fallida» con «Presupuesto agotado…».
- [ ] **Métricas.** El Dashboard cuenta las ejecuciones de la prueba y el coste coincide con la suma de sus agentes.
- [ ] **Reinicio del runner.** Con un agente en curso, `systemctl --user restart skynet-runner` (o `launchctl kickstart -k …`): el agente acaba «Fallida» («El runner se reinició durante la ejecución») y el runner vuelve «En línea».

## Registro

| Fecha | Versión (commit) | Repositorio | Resultado | Notas |
|---|---|---|---|---|
| | | | | |
