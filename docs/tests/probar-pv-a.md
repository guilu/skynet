# Probar PV-A en local

PV-A prepara el runner para tener **varios proveedores de agentes** (#77): Claude Code pasa a ser uno de ellos, cada runner dice qué CLIs tiene instalados y el control plane solo le da agentes que puede ejecutar. Todavía no hay Codex ni Gemini (llegan en PV-B y PV-C), así que con Claude Code todo debe funcionar exactamente igual que antes; lo nuevo se ve en **Runners**, en el **Dashboard** y cuando un runner no tiene Claude Code.

## 1. Arrancar

En la rama de la PR, recompila y arranca el control plane, la web y el runner con fake-claude:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=1000 \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Hay una migración nueva (`V16`, tabla `runner_provider`) que se aplica sola al arrancar; los runners que ya tenías quedan con Claude Code. No hay dependencias nuevas.

- El runner escribe al conectarse `… (capacidad 2; proveedores: claude-code 2.1.0 (fake-claude))`.
- Entra en `http://localhost:5173` con `admin` / `secreto`. En **Runners**, la columna de versiones dice `runner <versión> · Claude Code 2.1.0 (fake-claude)`.
- En el **Dashboard**, la tarjeta del runner dice «En línea» y `Claude Code 2.1.0 (fake-claude) · 0 agentes activos`.

## 2. Nada cambia con Claude Code

1. En un proyecto con un repositorio registrado, **Lanzar agente** con cualquier prompt. Termina «Completada», con sus herramientas y su worktree, como siempre.
2. En el agente terminado, **Enviar mensaje** y **Bifurcar** funcionan igual que antes.
3. Si tienes publicado el workflow `con-comando` de W3-A (o la plantilla «Revisión en paralelo» de W3-D), lánzalo: termina «Completada» con su comando y sus agentes.

## 3. Un runner sin Claude Code

1. Para el runner (Ctrl+C) y arráncalo con un ejecutable que no existe:

   ```bash
   SKYNET_CLAUDE_BIN=/no/existe runner/build/install/skynet-runner/bin/skynet-runner
   ```

   El log avisa «El runner no tiene ningún proveedor de agentes disponible: solo recibirá comandos» y termina con `proveedores: ninguno`. No hace falta el secreto de registro: reutiliza su token, y la lista nueva llega con el primer latido.
2. En **Runners**, la columna de versiones dice `runner <versión> · sin proveedores de agentes`.
3. **Lanzar agente**: el agente se queda «En cola» (el runner no lo recoge).
4. En el **Dashboard**, «Necesita tu atención» muestra **Claude Code** con «1 agente espera: ningún runner en línea tiene Claude Code» y el estado «Sin runner»; el enlace lleva a **Runners**.
5. Para el runner y arráncalo otra vez con fake-claude (el comando del paso 1). En unos segundos el agente arranca y termina «Completada», y el aviso desaparece del Dashboard.

## 4. `SKYNET_PROVIDERS`

1. Para el runner y arráncalo con `SKYNET_PROVIDERS=codex` (y fake-claude como en el paso 1). El log dice `proveedores: ninguno`: el runner solo declara los que le pides, y Codex aún no lo conoce («SKYNET_PROVIDERS: proveedor desconocido codex»).
2. Arráncalo de nuevo sin `SKYNET_PROVIDERS` para dejarlo como estaba.

## 5. Con Claude Code de verdad (opcional)

Arranca el runner sin `SKYNET_CLAUDE_BIN` (usa `claude`) con tu sesión de Claude Code iniciada. En **Runners** aparece `Claude Code <versión real>` y un agente lanzado con un prompt corto termina «Completada» como siempre.

## Qué mirar si algo falla

- Logs del runner: la línea `proveedores:` al conectarse dice qué ha declarado.
- `GET /api/runners` devuelve `providers` de cada runner; `GET /api/dashboard`, `providerShortages`.
- Un agente que llega a un runner sin su proveedor falla con «Este runner no tiene … disponible» sin crear worktree (solo pasaría con un runner antiguo o una carrera al cambiar de CLIs).
