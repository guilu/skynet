# Probar AR-A en local

AR-A hace visible si un runner usa un CLI de agentes **simulado** (fake-claude, fake-codex, fake-gemini, fake-agy) o el **real**. Los simuladores anuncian `(fake-…)` en `--version`; con eso el runner marca cada proveedor como simulado al registrarse y en cada latido, y cada invocación guarda si se ejecutó con un CLI simulado. Hay una migración nueva (`V26__agent_run_simulated.sql`, columna `agent_run.simulated`); no hay dependencias nuevas.

## 1. Arrancar con un CLI simulado

Control plane y web como siempre. El runner con fake-claude reproduciendo `02-tools`:

```bash
./gradlew :tools:fake-claude:installDist :runner:installDist
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE FAKE_CLAUDE_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

Al arrancar, el log del runner avisa: `Claude Code es un CLI simulado (…/fake-claude): sus agentes reproducen sesiones grabadas`.

## 2. Qué mirar con el CLI simulado

1. **Runners.** En la columna «Versiones», junto a `Claude Code 2.1.0 (fake-claude)`, sale la píldora ámbar **«Simulado»** (al pasar el ratón: «CLI simulado: reproduce una sesión grabada y no ejecuta el agente real»).
2. **Barra lateral.** El resumen de runners dice «1 runner en línea» y debajo «0 agentes · 1 con CLI simulado», con el punto en ámbar.
3. **Dashboard.** En «Necesita tu atención» aparece «Runner «<nombre>»» · «Usa Claude Code simulado: sus agentes reproducen sesiones grabadas» · «CLI simulado», que lleva a Runners. La tarjeta del runner, abajo, también lleva «Simulado».
4. **Ejecución.** Lanza `adhoc` («agente prompt») con cualquier prompt. En la cabecera, el chip «Claude Code» lleva «Simulado». En el detalle del agente, pestaña Resumen, la fila «Proveedor» dice «Claude Code · START» con «Simulado».

## 3. Con el CLI real

Para el runner y arráncalo **sin** `SKYNET_CLAUDE_BIN` ni las variables `FAKE_*` (con `claude` instalado y con sesión iniciada):

```bash
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> runner/build/install/skynet-runner/bin/skynet-runner
```

1. En Runners, `Claude Code 2.1.x (Claude Code)` sin «Simulado». La barra lateral ya no habla de CLI simulado y el aviso del Dashboard desaparece.
2. Lanza `adhoc` con «contesta con hola»: la respuesta es la del agente real y ni la cabecera ni el Resumen dicen «Simulado».
3. **Histórico.** Abre la ejecución del apartado 2: sigue marcada como «Simulado», aunque el runner ya use el CLI real.

Lo mismo vale para Codex (`SKYNET_CODEX_BIN` apuntando a fake-codex): sale «Codex … (fake-codex)» con «Simulado».

## 4. Comprobaciones automáticas

```bash
./gradlew build    # incluye RunnerEndToEndIT: su agente con fake-claude queda simulated=true
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

Los contratos (`fixtures/contracts/`) llevan el campo nuevo `simulated` en los proveedores de un runner y en cada agente.
