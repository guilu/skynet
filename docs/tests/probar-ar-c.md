# Probar AR-C: los tres agentes reales

AR-C cierra el hito AR: Skynet ejecuta Claude Code, Codex y Antigravity CLI reales en el runner (omarchy) y lo deja documentado. No hay código nuevo, migraciones ni dependencias: cambian el README («Runner con CLIs reales») y `deploy/runner/runner.env.example`. La E2E con los tres simuladores (`web/e2e/providers.e2e.ts`) llegó en AR-B.

## 1. Preparar omarchy

Con el usuario del runner, cada CLI tiene que responder y tener sesión:

```bash
claude --version   # 2.1.x; sesión con /login dentro de claude
codex --version    # codex-cli 0.1xx; sesión con codex login
agy --version      # 1.4.x; sesión de Google al abrir agy
```

Quita del entorno (o de `~/.config/skynet-runner/runner.env`) los `SKYNET_CLAUDE_BIN`, `SKYNET_CODEX_BIN` y `SKYNET_AGY_BIN` que apunten a los fakes y todas las `FAKE_*`. Comprueba que el `PATH` del servicio incluye los directorios de los tres (`dirname $(command -v agy)`…). Reinicia el runner:

```bash
systemctl --user restart skynet-runner && journalctl --user -u skynet-runner -n 20
```

El log dice `proveedores: claude-code 2.1.x, codex 0.1xx, antigravity 1.4.x` y ningún aviso de «CLI simulado».

## 2. Qué mirar en la web

1. **Runners:** omarchy enseña las tres versiones sin la píldora «Simulado». La barra lateral y «Necesita tu atención» del Dashboard ya no hablan de CLIs simulados.
2. **Proveedores al lanzar:** en **Lanzar workflow** con `adhoc`, el selector tiene Claude Code, Codex y Antigravity CLI, los tres activos.

## 3. Un agente con cada CLI

Para cada proveedor, lanza `adhoc` en un repositorio de prueba (p. ej. `tkm`) con el prompt «contesta con hola»:

| Proveedor | Qué esperar |
|---|---|
| Claude Code | «hola», con coste en el Resumen |
| Codex | «hola»; coste solo si pones un modelo (`gpt-5.5`) |
| Antigravity CLI | «hola»; coste solo si pones un modelo de `agy models` |

En los tres: ni la cabecera ni el Resumen dicen «Simulado», y la conversación es la respuesta real, no la sesión grabada de `calc.py`.

**Continuar.** En cada uno, escribe «¿y en inglés?» en la caja de mensajes: sigue la misma sesión. **Bifurcar** funciona con Claude Code y Codex; con Antigravity sale desactivado («Antigravity CLI no permite bifurcar una sesión.»).

## 4. Salida estructurada con los tres

1. **Nuevo workflow** → plantilla «Revisión con Codex y Antigravity» (Claude implementa y corrige, Codex revisa la calidad, Antigravity la seguridad). Cambia los dos `command: ./gradlew test` por el comando de tests del repositorio de prueba y publícala.
2. Lánzala sobre un trabajo pequeño. Las fases terminan, cada revisor devuelve `hasBlockingFindings` y `summary` (en el Progreso, «Salida: …» bajo su caja, y en la pestaña Verificación de su agente), y `fix` se ejecuta u omite según lo que digan.

## 5. Cuota

En **Uso**, tras las ejecuciones del apartado 3, las tarjetas de Claude Code, Codex y Antigravity CLI tienen datos, y la parte «Skynet» de cada medidor sube con sus agentes (la de agy se consulta cada `SKYNET_QUOTA_POLL`, 10 min por defecto; «Actualizar» la pide ya).

## 6. Volver a los simuladores

Apunta `SKYNET_CLAUDE_BIN`, `SKYNET_CODEX_BIN` y `SKYNET_AGY_BIN` a `tools/fake-claude/build/install/fake-claude/bin/fake-*` y reinicia: Runners vuelve a enseñar «Simulado» en los tres, y las ejecuciones del apartado 3 siguen sin él (el histórico no cambia).

Si algún CLI real falla al lanzar (una opción que no reconoce, un permiso), los logs del agente (pestaña Artefactos) traen su stderr: pásamelo.
