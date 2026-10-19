# Empezar de cero en local

Cómo dejar Skynet como recién instalado y empezar con los **workflows iniciales**. Son cinco workflows que reparten el trabajo entre Claude Code, Codex y Antigravity CLI según lo que mejor hace cada uno.

## 1. Borrar todo

```bash
scripts/reset-local.sh --dry-run --runner   # primero, ver qué borraría
scripts/reset-local.sh --runner             # pide escribir «borrar»
```

El script borra:
- la base de datos y los artefactos de Docker Compose (los volúmenes `skynet_postgres-data` y `skynet_artifacts-data`);
- `data/artifacts` si arrancas el control plane con `bootRun`.

Después vuelve a levantar lo que estaba en marcha (el perfil `app` o solo PostgreSQL). Las migraciones crean el esquema vacío, con `adhoc` como único workflow, y al final publica los workflows iniciales.

**Con `--runner`** también limpia lo que el runner dejó en este equipo:
- su journal, sus logs, sus artefactos pendientes y sus worktrees (en `SKYNET_RUNNER_HOME`, por defecto `~/.skynet-runner`);
- en cada repositorio registrado, los worktrees, las ramas `skynet/*` y las referencias `refs/skynet/*`.

**Rutas que ya no son repositorios.** La base de datos guarda la ruta de cada repositorio que registraste. Si una ya no existe, no es un repositorio Git (o su `.git` está roto) o es una subcarpeta de otro repositorio, el script la avisa con «Aviso: se omite … : motivo», no la toca y sigue con las demás. Con solo rutas así, `--dry-run` también termina bien (código 0).

Si el runner es el servicio de systemd `skynet-runner`, el script lo para antes y lo arranca al final. Se vuelve a registrar solo con su `SKYNET_RUNNER_REGISTRATION_TOKEN`. Con launchd (macOS), o si lo arrancas a mano, páralo tú antes.

**Confirmación.** Hay que escribir exactamente `borrar`. Cualquier otra respuesta (`Borrar`, `borrar ` con un espacio, `sí`, nada) no borra nada y termina con código 1. `--yes` se la salta, solo para scripts.

**Qué borra y qué no.** Nunca toca:
- otros volúmenes de Docker;
- otros ficheros del home del runner;
- las demás ramas y referencias de tus repositorios, ni lo que tengas sin confirmar;
- las rutas omitidas.

`--dry-run` no cambia nada: no para ni levanta Docker, no toca el servicio del runner ni ningún fichero.

`scripts/tests/reset-local.test.sh` lo comprueba en la CI, con Docker y systemd simulados.

Ojo:
- Las ramas `skynet/*` son el trabajo de los agentes. Si quieres conservar alguna, fusiónala o renómbrala antes.
- Con `bootRun`, para el control plane antes y arráncalo después. El script publica los workflows si lo encuentra en marcha; si no, dice cómo hacerlo.

Lo que **no** se borra:
- el usuario, que sale de `SKYNET_ADMIN_USER` y `SKYNET_ADMIN_PASSWORD`;
- `deploy/.env`;
- las sesiones de los CLIs.

Los ajustes de apariencia vuelven a los de fábrica.

## 2. Publicar los workflows iniciales

`reset-local.sh` ya lo hace. Para publicarlos en una instalación que no quieres borrar:

```bash
SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh
```

Publica cada `workflows/*.yaml` como su v1 y se salta los que ya existen. Necesita `curl` y `jq`.

`SKYNET_URL` va por defecto a `http://localhost:8080`, y la contraseña se lee de `deploy/.env` si no la pasas.

**Los modelos** salen de [`workflows/modelos.env`](../workflows/modelos.env), con una variable por proveedor y papel: `CLAUDE_FUERTE`, `CODEX_RAPIDO`, `AGY_FUERTE`… Vacía, la línea `model:` desaparece y el CLI usa su modelo por defecto. Una variable del entorno con el mismo nombre manda sobre el fichero:

```bash
CODEX_FUERTE=<id de codex> AGY_FUERTE=<id de agy models> scripts/seed-workflows.sh
```

Los ids de Codex y de Antigravity dependen de la versión del CLI y de tu plan, así que vienen vacíos:
- Codex: los nombres salen en `/model` dentro de `codex`.
- Antigravity: `agy models` los da sin espacios, p. ej. `gemini-3.1-pro-high`. El validador no admite nombres con espacios como «Gemini 3.1 Pro (High)».

Para cambiar un modelo de un workflow ya publicado, edítalo en la web: **Editar** abre su v2. El script no toca los que ya existen.

## 3. Dar de alta tus proyectos

Los proyectos y los repositorios no se siembran.

1. En **Proyectos**, crea un proyecto y registra cada repositorio con su ruta en la máquina del runner.
2. En el repositorio, pon su **comando de verificación**: los tests que hay que pasar, p. ej. `./gradlew test`, `npm test` o `pytest`. Las fases con `checks: [tests]` no pasan sin él.
3. Si no sabes cuál es, lanza primero **Conocer un repositorio**: Codex lo averigua y lo deja en la salida de su fase.
4. Crea un trabajo con un título y una descripción claros. Los workflows los usan como enunciado (`{{workItem.title}}` y `{{workItem.description}}`).

## 4. Los workflows

| Workflow | Para qué | Fases (proveedor) |
|---|---|---|
| `adhoc` | Una tarea suelta con tu prompt | agente (el que elijas al lanzar) |
| `conocer-repositorio` | El primer contacto con un proyecto | comandos de compilar y de tests (Codex) → mapa en `docs/skynet/mapa.md` (Antigravity) |
| `implementar-tarea` | Una tarea bien definida | explorar (Antigravity, solo lectura) → implementar con los tests en verde (Claude Code) → revisión de calidad (Codex) y de seguridad (Antigravity) a la vez → corregir si algo bloquea (Claude Code) |
| `corregir-bug` | Un fallo que se puede reproducir | test que falla (Codex) → corrección con los tests en verde (Claude Code) → buscar regresiones (Antigravity) → ajustar si hay alguna (Claude Code) |
| `revisar-codigo` | Una auditoría sin cambios, con un `foco` opcional | arquitectura (Claude Code), tests (Codex) y seguridad (Antigravity) a la vez → un informe con los hallazgos por prioridad (Claude Code) |
| `especificar-planificar-implementar` | Una funcionalidad que conviene pensar antes | especificación (Antigravity) → plan (Claude Code) → **tu aprobación** → implementar (Claude Code) → revisión (Codex) → corregir si algo bloquea (Claude Code) |

Las plantillas de la web siguen ahí para crear workflows nuevos: Dos fases, Revisión en paralelo, Revisión con Codex y Antigravity, Especificar-planificar-implementar y Desde cero.

## 5. Qué proveedor para qué

Investigado el 2026-10-19 en las páginas de los fabricantes y en comparativas recientes (fuentes al final). Las cifras de los benchmarks varían entre fuentes, y los propios fabricantes avisan de que son una guía aproximada. Lo que manda es probarlo en tus proyectos, y la página **Uso** enseña cuánta cuota gasta cada uno.

### Claude Code: implementar y razonar sobre el diseño

Es la referencia para trabajar dentro de un repositorio real: cambios en varios ficheros, refactorizaciones y seguir las convenciones del proyecto. Por eso implementa y corrige en todos los workflows.

| Papel | Modelo | Por qué |
|---|---|---|
| `CLAUDE_GENERAL` | `sonnet` (Sonnet 5.5) | El modelo por defecto para programar. En Terminal-Bench 4.0 queda a la par o por encima de Opus según la fuente, y es más rápido y más barato. Anthropic lo da como el más fuerte en tareas acotadas del día a día, como corregir bugs. |
| `CLAUDE_FUERTE` | `opus` (Opus 5.5) | Lo más difícil y lo más largo: planificar, revisar la arquitectura y juntar un informe. En revisiones de código encuentra más problemas que Sonnet. |
| `CLAUDE_RAPIDO` | `haiku` (Haiku 5.5) | Pasos acotados y baratos: resúmenes, clasificar, subagentes. No lo usa ningún workflow inicial, pero queda definido. |

Los alias (`opus`, `sonnet`, `haiku`) los resuelve Claude Code al último modelo de cada familia, así que no hay que tocarlos cuando sale uno nuevo.

### Codex: comandos, tests y una segunda opinión

Brilla en el trabajo de terminal (Terminal-Bench) y en tareas autónomas que se pueden delegar, como ejecutar, reproducir o comprobar.
- Reproduce los bugs (escribe el test que falla y lo ejecuta).
- Averigua cómo se compila y se prueba un repositorio.
- Revisa la calidad. Al ser otra familia de modelos, su revisión es independiente de la de quien implementó.

| Papel | Modelo | Por qué |
|---|---|---|
| `CODEX_FUERTE` | el principal (GPT-6.1 Sol según la documentación de octubre) | Casi iguala al tope de gama (Astra) en código agéntico con mucho menos coste por tarea. Las comparativas lo dan como el modelo recomendado para trabajo complejo en Codex y como el predeterminado desde Codex CLI 0.161. Vacío, se usa el de `~/.codex/config.toml`. |
| `CODEX_RAPIDO` | el rápido (familia Luna) | Tareas cortas y concretas, como averiguar los comandos. Vacío, el predeterminado. |

### Antigravity CLI: el repositorio entero y la seguridad

Gemini destaca por el contexto enorme, que le permite leer un repositorio entero. Gemini 4 Argon, además, está entrenado para tareas largas, y Google lo presenta como su modelo de frontera para ingeniería de software:
- en DeepSWE v1.1 tiene la mejor nota de su tabla (77,9 %);
- en el despliegue interno de Google encontró fallos de seguridad críticos que otros modelos no vieron.

Por eso explora, escribe la especificación y el mapa del repositorio, y revisa la seguridad.

Ojo: con `acceptEdits`, Antigravity **no puede ejecutar comandos** (se deniegan; ver [`providers.md`](providers.md)). Por eso nunca le toca pasar tests ni hacer commits: lee, escribe ficheros o revisa en solo lectura.

| Papel | Modelo | Por qué |
|---|---|---|
| `AGY_FUERTE` | Gemini 4 Argon, si tu plan lo tiene | Especificar, hacer el mapa del repositorio y la revisión de seguridad. Tiene una cuota semanal aparte de la del resto de modelos Gemini, así que no se come la de los Flash. |
| `AGY_RAPIDO` | un Gemini Flash (`gemini-3.x-flash-medium` o el que dé `agy models`) | Explorar antes de implementar y buscar regresiones: leer mucho y rápido, sin gastar la cuota de Argon. |

Antigravity también ofrece modelos de Claude y GPT, con una cuota compartida entre ellos. Los workflows no los usan: para esos ya están Claude Code y Codex, con sus propias cuentas.

### Reglas que siguen los workflows

1. **Quien implementa no se revisa a sí mismo.** Claude Code implementa, y lo revisan Codex y Antigravity.
2. **Lo que se puede comprobar, lo comprueba Skynet.** Las fases que cambian código llevan `checks: [tests, diff]`, y la de reproducir un bug devuelve si lo consiguió.
3. **Los revisores van en solo lectura** (`permissionMode: plan`) y devuelven `hasBlockingFindings`. Solo se corrige si algo bloquea.
4. **Cada proveedor gasta su propia cuota.** Repartir el trabajo entre los tres hace que una sola ventana de 5 horas pare menos la tarea.

### Fuentes

- Anthropic, [Introducing Claude Opus 5.5](https://www.anthropic.com/claude-opus-5-5).
- The Decoder, [Sonnet 5.5 casi iguala a Opus 5.5 con hasta un 30 % menos de coste por tarea](https://the-decoder.com/anthropics-claude-sonnet-5-5-nearly-matches-opus-5-5-on-benchmarks-while-costing-up-to-30-percent-less-per-task/).
- CodeRabbit, [Sonnet 5.5 vs Opus 5.5 en revisión de código](https://www.coderabbit.ai/blog/sonnet-5-5-model-review).
- Developers Digest, [Opus 5.5 vs Sonnet 5.5 vs Haiku 5.5](https://www.developersdigest.tech/blog/claude-opus-5-5-vs-sonnet-5-5-vs-haiku-5-5).
- OpenAI, [Introducing GPT-6.1 Sol](https://openai.com/index/introducing-gpt-6-1-sol/).
- Vellum, [GPT-6.1 Sol benchmarks](https://www.vellum.ai/blog/gpt-6-1-sol-benchmarks-explained).
- AIDEN, [Best Codex model in 2026](https://aidenapp.org/best-codex-model).
- Google, [Gemini 4 Argon in Google Antigravity](https://antigravity.google/blog/gemini-4-argon-in-google-antigravity/).
- Google DeepMind, [Gemini 4 Argon, model card](https://deepmind.google/models/model-cards/gemini-4-argon/).
- 9to5Google, [Gemini 4 Argon llega a AI Pro en Antigravity](https://9to5google.com/2026/10/15/gemini-4-argon-is-now-rolling-out-to-googles-antigravity-for-ai-pro-subscribers/).
- Codelabs, [salida de `agy models`](https://github.com/googlecodelabs/feedback/issues/2298).
- DeployHQ, [Claude Code vs Codex CLI vs Gemini CLI](https://www.deployhq.com/blog/comparing-claude-code-openai-codex-and-google-gemini-cli-which-ai-coding-assistant-is-right-for-your-deployment-workflow).
- TeamoRouter, [Codex vs Claude Code vs Gemini CLI](https://teamorouter.com/blogs/codex-vs-claude-code-vs-gemini-cli).
