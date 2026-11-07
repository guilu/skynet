# Importar un proyecto desde un repositorio clonado

Hito IP. En vez de crear a mano el proyecto y su repositorio (nombre, descripción, ruta, remoto, rama, comando de validación…), Skynet los deduce de un repositorio git que ya está clonado en el disco del runner, como `~/code/forma`.

El control plane no ve el disco del runner, así que es el runner quien busca y lee los repositorios y manda lo que encuentra: el **catálogo de repositorios**.

| PR | Qué añade |
|---|---|
| IP-A (#252) | El catálogo: el runner busca, deduce y manda; el control plane lo guarda y lo sirve. |
| IP-B (#253) | Crear proyecto y repositorio a partir de una entrada del catálogo en un solo paso, y lo que dice GitHub de cada remoto. |
| IP-C (#254) | El asistente «Importar proyecto» en la web. |

## Configuración del runner

En `~/.config/skynet-runner/runner.env` (ver [`deploy/runner/runner.env.example`](../deploy/runner/runner.env.example)):

```bash
# Carpetas raíz, separadas por «:». ~ es la carpeta personal.
SKYNET_REPO_ROOTS=$HOME/code
# Cada cuánto vuelve a buscar: 10m por defecto; off busca solo al arrancar y cuando se pide.
#SKYNET_REPO_SCAN=10m
```

Sin `SKYNET_REPO_ROOTS` el runner no busca nada y no manda catálogo; el alta manual sigue igual.

## Dónde busca

- En cada carpeta raíz y hasta **dos niveles** por debajo: `~/code/forma` y `~/code/clientes/akademia` sí; `~/code/a/b/c` no.
- Un repositorio es una carpeta con un **directorio** `.git`. Los worktrees y los submódulos tienen un fichero `.git`, así que no cuentan (tampoco los worktrees del propio runner).
- No entra en carpetas ocultas, `node_modules`, `build`, `target`, `dist`, `out`, `vendor`, `venv` ni `__pycache__`, ni dentro de un repositorio, ni en el directorio de datos del runner (`SKYNET_RUNNER_HOME`), ni sigue enlaces simbólicos.
- Para en **200 repositorios** (el catálogo lo dice con `truncated`).
- Busca al arrancar, cada `SKYNET_REPO_SCAN` y cuando alguien pide «Volver a buscar» (llega en la respuesta al siguiente latido, como «Actualizar» en Uso).

## Qué deduce de cada repositorio

Solo ejecuta git de lectura (`--no-optional-locks`, sin pedir credenciales) y lee una lista fija de ficheros de como mucho 256 KB. **Nunca** lee `.env` ni ningún otro fichero.

| Campo | De dónde |
|---|---|
| `path` | Ruta real (sin enlaces) del repositorio. |
| `name` | Nombre de la carpeta. |
| `remoteUrl` | `git remote get-url origin`, o del primer remoto. **Sin credenciales**: de `https://usuario:token@host/…` queda `https://host/…`; de `ssh://usuario:secreto@host/…`, `ssh://usuario@host/…`. |
| `defaultBranch` | `refs/remotes/origin/HEAD` (`origin/HEAD`); si no, la rama actual (`current`); si no, `main` (`default`). |
| `projectName` | `rootProject.name` de `settings.gradle(.kts)`, `<name>` o `<artifactId>` de `pom.xml` (sin los del `<parent>`), `name` de `package.json` (sin el ámbito `@…/`), `[project]` o `[tool.poetry]` de `pyproject.toml`, `[package]` de `Cargo.toml`, el módulo de `go.mod`; si no, la carpeta (`directory`). |
| `description` | La del manifiesto; si no, el primer párrafo del README sin título, insignias, HTML, bloques de código ni marcas de Markdown. Como mucho 300 caracteres. |
| `tool` | `gradle`, `maven`, `npm`/`pnpm`/`yarn` (por el lockfile), `python`, `cargo` o `go`, en ese orden de preferencia. |
| `validationCommand` | `./gradlew check` (o `gradle check`), `./mvnw -q verify` (o `mvn`), `<gestor> test` si `package.json` tiene un `test` de verdad, `pytest`, `cargo test`, `go test ./...`. |
| `testReportPaths` | Los informes JUnit de Gradle o de Maven; vacío para el resto. |
| `protectedPaths` | `.github/workflows/**` si existe esa carpeta. |
| `lastCommit`, `lastCommitSubject`, `lastCommitAt` | `git log -1`. |
| `dirty` | Si `git status --porcelain` dice algo. |
| `agentInstructions` | Si hay `CLAUDE.md` o `AGENTS.md`. |

## API

Del runner (con su token):

- `POST /api/runner/repository-catalog` con un `RepositoryCatalog` (`scannedAt`, `roots`, `repositories`, `truncated`). Es una foto completa: sustituye a la anterior del runner (tabla `runner_repository_catalog`, migración `V48`). Como mucho 500 repositorios, 20 carpetas y textos de 2.000 caracteres; las rutas tienen que ser absolutas.
- La respuesta al latido lleva `refreshRepositories`.

De la web:

- `GET /api/repository-catalog`: un elemento por runner con catálogo (`runnerId`, `runnerName`, `lastHeartbeatAt`, `scannedAt`, `receivedAt`, `roots`, `truncated`) y sus `repositories`, cada una con `repository` (lo de la tabla de arriba) y `registered` (`projectId`, `projectKey`, `projectName`, `repositoryId`) si ya hay un repositorio registrado en esa ruta, o `null`.
- `POST /api/repository-catalog/refresh` (204): todos los runners vuelven a buscar en su siguiente latido. La petición caduca a los 2 minutos.

## Importar (IP-B)

`POST /api/projects/import` crea el proyecto y su repositorio **en una sola transacción**: si algo falla, no se crea nada.

```json
{
  "key": "FORMA",
  "name": "Forma",
  "description": "Gestor de formularios dinámicos",
  "repository": {
    "name": "forma",
    "localPath": "/home/diego/code/forma",
    "remoteUrl": "git@github.com:guilu/forma.git",
    "defaultBranch": "main",
    "validationCommand": "./gradlew check",
    "testReportPaths": ["**/build/test-results/**/*.xml"],
    "protectedPaths": [".github/workflows/**"]
  }
}
```

- Responde 201 con `project` y `repository` (las mismas vistas que `GET /api/projects/{id}` y la lista de repositorios).
- 409 si la clave ya existe o si ya hay un repositorio registrado en esa ruta, aunque se registrara con una barra final. El mensaje dice en qué proyecto está.
- 400 con las mismas reglas que el alta manual: clave `^[A-Z][A-Z0-9]{1,9}$`, ruta absoluta y rutas protegidas dentro del repositorio.
- La política de los agentes es la global, como en el alta manual.
- En Actividad, el `project.created` lleva `importedFrom` y se lee «Proyecto FORMA importado desde /home/diego/code/forma». Le sigue el `repository.registered` de siempre.

`GET /api/projects/key-suggestion?name=forma` da `{"key": "FORMA"}`: el nombre en mayúsculas, sin acentos ni símbolos, si cabe en 6 letras; si no, sus 3 primeras (`akademia` → `AKA`, `tokenmeter` → `TOK`). Si ya existe, le añade un número (`FORMA2`). Si empieza por un número, le antepone una `P`.

### Lo que dice GitHub

Si la GitHub App está configurada y el remoto de una entrada del catálogo es de ese GitHub, la entrada lleva `github`:

- `fullName` y `htmlUrl`;
- `covered`: si la App está instalada en ese repositorio;
- `description`: la descripción del repositorio en GitHub, solo si está cubierto y tiene una. Se pide con un token de `metadata: read` limitado a ese repositorio y se recuerda 10 minutos.

Las entradas ya registradas, sin remoto o con un remoto de otro sitio llevan `github: null`. Si GitHub no responde, la entrada sale igual, sin descripción.
