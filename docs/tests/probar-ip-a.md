# Probar IP-A - Catálogo de repositorios del runner

IP-A (#252) es la primera PR del hito IP (importar un proyecto desde un repositorio clonado). El runner busca los repositorios git de sus carpetas raíz (`SKYNET_REPO_ROOTS`), deduce de cada uno ruta, remoto sin credenciales, rama, nombre, descripción, herramienta, comando de validación y lo demás, y manda el catálogo al control plane. Lo explica [`docs/importar-proyecto.md`](../importar-proyecto.md).

- **Runner**: `SKYNET_REPO_ROOTS` (separadas por `:`) y `SKYNET_REPO_SCAN` (`10m`; `off`). Busca al arrancar, en su intervalo y cuando se pide «Volver a buscar».
- **Protocolo**: `RepositoryCatalog`, `DiscoveredRepository` y `HeartbeatReply.refreshRepositories`.
- **API**:
  - `POST /api/runner/repository-catalog` (runner);
  - `GET /api/repository-catalog`;
  - `POST /api/repository-catalog/refresh`.
- **Migración `V48`**: tabla `runner_repository_catalog`, una fila por runner. Se aplica sola al arrancar.
- **Web**: todavía nada. El asistente llega en IP-C, así que esta guía usa `curl`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni de otros repositorios. El runner solo **lee**: git de solo lectura, sin bloqueos, y una lista fija de ficheros, nunca `.env`. No se lanza ningún agente, así que no se gasta cuota.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :runner:test --tests 'dev.skynet.runner.catalog.*' --tests '*RunnerDaemonTest' \
  :control-plane:test --tests '*RepositoryCatalogIT' --tests '*RunnerProtocolIT' --tests '*MigrationsIT' --tests '*ModularityTest'
```

- `RepositoryScannerTest`, con repositorios git temporales:
  - encuentra los de hasta dos niveles;
  - no entra en ocultos, `node_modules`, repositorios dentro de otros, worktrees ni el directorio del runner;
  - para en el máximo y lo dice;
  - lee remoto (sin credenciales), rama de `origin/HEAD` o la actual, último commit, cambios sin commitear y `CLAUDE.md`;
  - sin remoto ni manifiesto, usa la carpeta.
- `ManifestsTest`:
  - npm con pnpm, el `test` de relleno de `npm init`, Maven sin el nombre del `<parent>`, Python, Cargo y Go;
  - Gradle tiene preferencia sobre un `package.json`;
  - nunca lee `.env`;
  - el primer párrafo del README y el corte a 300 caracteres.
- `RemoteUrlsTest`: quita usuario y token de `https://` y la contraseña de `ssh://`, y deja `git@host:…` y rutas locales.
- `RepositoryCatalogIT`:
  - guarda la última foto de cada runner y marca las rutas ya registradas, aunque se registraran con barra final;
  - sanea el texto para `jsonb`;
  - «Volver a buscar» llega una vez a cada runner;
  - exige el token del runner y rechaza rutas relativas y catálogos de más de 500 repositorios.

## 2. En local, con una carpeta de prueba

Prepara una carpeta raíz con clones de `demo-repo` y un repositorio sin remoto:

```bash
rm -rf /tmp/ip-a && mkdir -p /tmp/ip-a/code/clientes
git clone -q https://github.com/guilu/demo-repo.git /tmp/ip-a/code/demo-repo
git clone -q https://github.com/guilu/demo-repo.git /tmp/ip-a/code/clientes/demo-2
git -C /tmp/ip-a/code/demo-repo remote set-url origin https://usuario:ghp_falso@github.com/guilu/demo-repo.git
mkdir /tmp/ip-a/code/notas && git -C /tmp/ip-a/code/notas init -q -b main
echo cambio > /tmp/ip-a/code/clientes/demo-2/nuevo.txt
mkdir -p /tmp/ip-a/code/a/b && git -C /tmp/ip-a/code/a/b init -q   # tercer nivel: no sale
```

Arranca el control plane y un runner con esa carpeta:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/ip-a/runner SKYNET_REPO_ROOTS=/tmp/ip-a/code SKYNET_REPO_SCAN=off \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
```

### 2.1 El catálogo

```bash
api http://localhost:8080/api/repository-catalog | jq '.[0] | {runnerName, roots, truncated, repos: [.repositories[].repository | {path, remoteUrl, defaultBranch, defaultBranchSource, projectName, tool, dirty}]}'
```

- Sale un runner con `roots: ["/tmp/ip-a/code"]` y `truncated: false`.
- Salen **tres** repositorios, ordenados por ruta: `clientes/demo-2`, `demo-repo` y `notas`. El de `a/b` no sale porque está en el tercer nivel.
- `demo-repo` tiene `remoteUrl: "https://github.com/guilu/demo-repo.git"`, **sin** `usuario:ghp_falso@`.
- `demo-2` tiene `dirty: true`. `demo-repo` tiene `dirty: false` y `defaultBranchSource: "origin/HEAD"`.
- `notas` tiene `remoteUrl: null`, `projectNameSource: "directory"`, `tool: null` y `defaultBranch: "main"`.
- Comprueba que el token no se ha guardado en ningún sitio: `psql … -c "SELECT count(*) FROM runner_repository_catalog WHERE repositories::text LIKE '%ghp_falso%'"` da 0.

### 2.2 Ya registrado

Crea un proyecto y registra `demo-repo` a mano, con barra final:

```bash
P=$(api -X POST http://localhost:8080/api/projects -d '{"key":"DEM","name":"Demo"}' | jq -r .id)
api -X POST http://localhost:8080/api/projects/$P/repositories -d '{"name":"demo-repo","localPath":"/tmp/ip-a/code/demo-repo/"}' >/dev/null
api http://localhost:8080/api/repository-catalog | jq '.[0].repositories[] | {path: .repository.path, registered}'
```

`demo-repo` lleva `registered` con `projectKey: "DEM"`. Los otros dos llevan `null`.

### 2.3 Volver a buscar

```bash
git clone -q https://github.com/guilu/demo-repo.git /tmp/ip-a/code/demo-3
api -X POST -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/repository-catalog/refresh   # 204
```

Antes de 20 segundos (lo que tarda un latido), el catálogo tiene cuatro repositorios con `demo-3`, y su `scannedAt` es nuevo. Sin la petición, con `SKYNET_REPO_SCAN=off`, el runner no habría vuelto a buscar.

### 2.4 Sin carpetas

Para el runner y arráncalo sin `SKYNET_REPO_ROOTS`. Su catálogo anterior sigue en `GET /api/repository-catalog`, pero `refresh` ya no lo cambia. Ese runner no busca nada.

## 3. En omarchy

Añade a `~/.config/skynet-runner/runner.env`:

```bash
SKYNET_REPO_ROOTS=$HOME/code
```

Reinicia el runner (`systemctl --user restart skynet-runner`). En menos de un minuto:

```bash
api http://omarchy:8080/api/repository-catalog | jq '.[] | {runnerName, scannedAt, repos: [.repositories[] | {name: .repository.name, projectName: .repository.projectName, remote: .repository.remoteUrl, tool: .repository.tool, validation: .repository.validationCommand, registered: .registered.projectKey}]}'
```

- Salen `forma`, `akademia` y `tokenmeter`, con su remoto de GitHub, su herramienta y un comando de validación que tenga sentido para cada uno.
- `skynet` y `demo-repo`, si están en `~/code`, llevan su proyecto en `registered`.
- Ningún `remoteUrl` lleva credenciales.
- `git -C ~/code/forma status` está como antes: el runner no ha tocado nada.
