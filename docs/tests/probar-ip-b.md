# Probar IP-B - Importación de proyecto en un paso

IP-B (#253) es la segunda PR del hito IP. Con el catálogo de IP-A, el proyecto y su repositorio se crean **a la vez** con una sola llamada, y el catálogo dice lo que GitHub sabe de cada remoto. Lo cuenta [`docs/importar-proyecto.md`](../importar-proyecto.md) en «Importar (IP-B)».

- **API**:
  - `POST /api/projects/import`, atómico: 409 si la clave existe o la ruta ya está registrada;
  - `GET /api/projects/key-suggestion?name=`;
  - `github` (`fullName`, `covered`, `description`, `htmlUrl`) en cada entrada de `GET /api/repository-catalog`.
- **Actividad**: «Proyecto FORMA importado desde /home/diego/code/forma».
- **Sin migraciones**. La web del asistente llega en IP-C, así que esta guía usa `curl`.

Ninguna prueba modifica ficheros versionados de skynet ni de otros repositorios, y no se lanza ningún agente.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*ProjectImportIT' --tests '*ProjectKeysTest' \
  --tests '*CatalogGitHubIT' --tests '*RepositoryCatalogIT' --tests '*ModularityTest'
cd web && npx vitest run src/format.test.ts
```

- `ProjectImportIT`:
  - crea el proyecto y el repositorio con todos sus campos, y los eventos con `importedFrom`;
  - con la clave ocupada no crea nada, ni el repositorio;
  - con la ruta ya registrada (con barra final) no crea nada y dice en qué proyecto está;
  - rechaza rutas protegidas fuera del repositorio, rutas relativas y claves en minúsculas;
  - sugiere FORMA, AKA, TOK y FORMA2 cuando FORMA está ocupada.
- `ProjectKeysTest`: acentos, números al principio, nombres de una letra o sin letras.
- `CatalogGitHubIT`, con el GitHub falso:
  - un remoto cubierto trae su descripción, o `null` si no la tiene;
  - uno sin instalar dice `covered: false`;
  - GitLab y sin remoto llevan `github: null`.

## 2. En local

Arranca como en la sección 2 de [`probar-ip-a.md`](probar-ip-a.md), con la misma carpeta `/tmp/ip-a/code`. Si no la tienes, prepárala como allí.

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
api http://localhost:8080/api/projects/key-suggestion?name=demo-repo        # {"key":"DEM"}
```

### 2.1 Importar desde el catálogo

Coge la entrada de `demo-repo` del catálogo e impórtala tal cual:

```bash
E=$(api http://localhost:8080/api/repository-catalog | jq '.[0].repositories[] | select(.repository.name=="demo-repo") | .repository')
api -X POST http://localhost:8080/api/projects/import -d "$(jq -n --argjson r "$E" '{key:"DEM", name:"Demo", description:$r.description, repository:{name:$r.name, localPath:$r.path, remoteUrl:$r.remoteUrl, defaultBranch:$r.defaultBranch, validationCommand:$r.validationCommand, testReportPaths:$r.testReportPaths, protectedPaths:$r.protectedPaths}}')" | jq '{key: .project.key, repo: .repository | {name, localPath, remoteUrl, defaultBranch, validationCommand}}'
```

- Responde con el proyecto `DEM` y su repositorio, con la ruta y el remoto del catálogo.
- En la web, **Proyectos** muestra Demo con su repositorio, y **Actividad** dice «Proyecto DEM importado desde /tmp/ip-a/code/demo-repo».
- En el catálogo, `demo-repo` lleva ahora `registered.projectKey: "DEM"`.

### 2.2 Lo que no se puede

```bash
# La misma ruta otra vez, con otra clave: 409 «… ya está registrado en el proyecto DEM».
api -X POST http://localhost:8080/api/projects/import -d '{"key":"OTRO","name":"Otro","repository":{"name":"x","localPath":"/tmp/ip-a/code/demo-repo/"}}'
# Una clave ocupada: 409, y no aparece ningún repositorio nuevo.
api -X POST http://localhost:8080/api/projects/import -d '{"key":"DEM","name":"Otro","repository":{"name":"notas","localPath":"/tmp/ip-a/code/notas"}}'
api http://localhost:8080/api/projects/key-suggestion?name=demo        # {"key":"DEMO"}
api http://localhost:8080/api/projects/key-suggestion?name=demo-repo   # {"key":"DEM2"}
```

## 3. En omarchy, con GitHub

Con la App configurada y el catálogo de `~/code` de IP-A:

```bash
api http://omarchy:8080/api/repository-catalog | jq '.[].repositories[] | {name: .repository.name, github}'
```

- `forma`, `akademia` y `tokenmeter` llevan `github.covered: true` si la App los cubre (está instalada con «All repositories»), y su descripción de GitHub si la tienen.
- Si los importas aquí, hazlo solo con los que quieras tener de verdad en Skynet. El asistente de IP-C hará lo mismo desde la web.
