# Probar RV-B: rutas, Git y salida estructurada

RV-B ([#135](https://github.com/guilu/skynet/issues/135), parte de [#133](https://github.com/guilu/skynet/issues/133)) corrige los tres hallazgos de prioridad alta de la auditoría de Antigravity. Antes de corregirlos, cada uno se reprodujo con un test que fallaba (RED): los tres eran reales.

| # | Hallazgo | Cómo se reprodujo (RED) | Corrección |
|---|---|---|---|
| 1 | Id de sesión sin validar: rutas y argumentos del CLI | Una reanudación con sesión `--dangerously-skip-permissions` llegaba a `claude --resume` (el CLI respondía «No conversation found with session ID: --dangerously-skip-permissions»). `ClaudeSessions.copy` aceptaba `../robado` y copiaba los enlaces simbólicos de la sesión siguiéndolos | `SafeIds.isSessionId` (letras, dígitos, `-` y `_`) en el runner antes de preparar el worktree y en `ClaudeSessions.copy`; la copia salta los enlaces simbólicos. El control plane no guarda un id de sesión que no lo sea (`SESSION_STARTED`) y no continúa una sesión con uno así |
| 2 | Ramas y hashes sin validar antes de `git` | `git worktree add … --detach` con la rama base `--detach`; `restore` con la instantánea `--hard`; `GitIndexer`/`WorktreeState` con el commit base `--output=<fichero>` | `SafeIds.isBranch` (reglas de `git check-ref-format --branch` y sin `-` inicial) al registrar el repositorio (400) y en el runner; `SafeIds.isCommit` (hash hexadecimal) para el commit base y la instantánea, en el runner y al guardarlos desde `WORKSPACE_READY`; `--` tras los commits en `diff`, `log`, `rev-list` y `reset` |
| 3 | `StructuredOutput.resolve` seguía enlaces simbólicos fuera del worktree | Un `schemas/spec.json` enlazado a un fichero de fuera se leía y se mandaba al CLI como schema | La ruta se resuelve con `toRealPath()` y tiene que seguir dentro del worktree real; un enlace dentro del worktree sigue valiendo |

No hay migraciones ni dependencias nuevas, y no cambia la exposición de red. Un repositorio ya registrado con una rama base inválida falla al lanzar («La rama base `…` no es un nombre de rama válido»), en vez de pasar la rama a git.

> **Para despliegues automáticos:** los pasos 1 y 2 no cambian nada fuera de carpetas temporales y de un proyecto de prueba que puedes archivar.

## 1. Tests

```bash
./gradlew test --no-daemon
```

Los de RV-B:
- `SafeIdsTest` (protocol): ids de sesión, ramas y hashes válidos e inválidos;
- `ClaudeSessionsTest`: ids con `../` o `-` rechazados, enlaces simbólicos (fichero y directorio) sin copiar, transcript enlazado ignorado;
- `WorkspaceManagerTest`: rama base `--detach`, `-q`, `--orphan=x`, `main..HEAD`, `a b` rechazadas sin crear nada; instantánea `--hard` o `HEAD~1` rechazada;
- `GitIndexerTest`: commit base `--output=<fichero>` rechazado en `GitIndexer` y `WorktreeState`, sin crear el fichero;
- `StructuredOutputTest`: enlace a un fichero de fuera o a través de un directorio de fuera rechazado; enlace dentro del worktree aceptado;
- `AgentExecutorTest`: reanudar con sesión `--dangerously-skip-permissions` o `../../x` termina con `failure: start` sin lanzar el CLI;
- `RunLifecycleIT`: registrar un repositorio con rama `--upload-pack=…`, `-main` o `main..HEAD` da 400; `release/1.2` vale;
- `RunnerProtocolIT`: un `WORKSPACE_READY` con commit `--output=…` e instantánea `HEAD~1`, y un `SESSION_STARTED` con sesión `--dangerously-skip-permissions`, no se guardan.

## 2. En la web

1. **Proyectos → un proyecto de prueba → Registrar repositorio**, con rama `--upload-pack=x`: sale «`--upload-pack=x` no es un nombre de rama válido para git (ni puede empezar por `-`)» y no se registra.
2. Con `release/1.2` (o vacía, que es `main`) se registra como siempre.
3. Lanza `adhoc` en un repositorio normal, continúa la sesión con un mensaje y bifúrcala: funciona igual que antes (los ids reales de los tres CLIs son UUIDs).
