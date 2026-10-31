# Probar GH-A - App y tokens de instalación

GH-A es la primera PR del hito GH (GitHub conectado, issue #193). Conecta Skynet con una GitHub App propia sin cambiar todavía ningún workflow. Hace cuatro cosas:

- **Configuración de la App:** `SKYNET_GITHUB_APP_ID`, `SKYNET_GITHUB_PRIVATE_KEY_FILE` (la ruta al `.pem`), `SKYNET_GITHUB_WEBHOOK_SECRET`, `SKYNET_GITHUB_API_URL` (por defecto `https://api.github.com`) y `SKYNET_GITHUB_WEB_URL` (el host de las URL remotas: por defecto `https://github.com`, o el de la API con GitHub Enterprise). Docker Compose las pasa a la API y monta el `.pem` de solo lectura. Si falta alguna de las tres primeras, Skynet arranca igual y dice cuáles faltan.
- **JWT y tokens de instalación:** el JWT es RS256 y dura 9 minutos. Se firma con la clave PKCS#1 que da GitHub, sin dependencias nuevas, y también acepta PKCS#8. Los tokens de instalación se guardan en memoria, limitados a un repositorio y a unos permisos, y se renuevan cuando les quedan menos de 5 minutos. Nunca se escriben en la base de datos, en los logs ni en los eventos.
- **Migración V30:** crea `github_installation`, `github_installation_repository` y `github_webhook_delivery`. Esta última no se usa hasta GH-B.
- **API:**
  - `GET /api/github` devuelve el estado de la App (comprobado con `GET /app` como mucho una vez por minuto), sus instalaciones y el enlace de cada repositorio de Skynet con GitHub. El enlace se calcula por su URL remota, sin distinguir mayúsculas, y puede ser `CONNECTED`, `NOT_INSTALLED` o `NOT_GITHUB`.
  - `POST /api/github/sync` vuelve a pedir las instalaciones y sus repositorios. Responde 409 si la App no está configurada y 502 si GitHub falla, y en los dos casos no cambia nada. Si la App está configurada, también se sincroniza al arrancar.

La web no cambia, porque la pestaña Ajustes → GitHub llega en GH-C. Ninguna prueba de esta guía modifica ficheros versionados de skynet ni llama al GitHub real.

## 1. Pruebas automáticas

```bash
./gradlew :control-plane:test --tests '*AppJwtTest' --tests '*RemoteNameTest' --tests '*GitHubIT' --tests '*GitHubUnconfiguredIT' --tests '*MigrationsIT' --tests '*ModularityTest'
./gradlew :control-plane:spotlessCheck
```

Los IT necesitan PostgreSQL, ya sea con Docker o con `SKYNET_TEST_DB_URL`. Comprueban:

- **`AppJwtTest`:**
  - lee la clave en PKCS#1 y en PKCS#8, y rechaza lo que no es una clave;
  - el JWT tiene `iss` = id de la App, `iat` 60 s antes y `exp` 9 min después, y su firma se verifica con la clave pública.
- **`RemoteNameTest`:** saca el host y `owner/nombre` de las URL https, ssh y `git@host:owner/nombre.git`. Ignora rutas locales, `file://` y URL que no son `owner/nombre`.
- **`GitHubIT`**, contra un GitHub falso que verifica la firma de cada JWT y solo acepta los tokens que ha dado él:
  - **Sincronizar:** guarda la instalación y sus repositorios. Enlaza `https://github.com/Guilu/Demo-Repo.git` y `git@github.com:guilu/demo-repo.git` como `CONNECTED`, un repositorio que la App no cubre como `NOT_INSTALLED`, y uno de GitLab o sin remoto como `NOT_GITHUB`. Pide un solo token, de solo lectura de metadatos, y ningún token acaba en las tablas.
  - **Resincronizar:** si GitHub ya no acepta el token guardado (401), se pide otro y se repite. Una instalación desinstalada desaparece. Una suspendida se queda con sus repositorios de antes, pero su enlace pasa a `NOT_INSTALLED`.
  - **Tokens:** el de un repositorio se pide con `repository_ids` y los permisos indicados, y se reutiliza mientras dura. Si cambian los permisos, se pide otro. Con la instalación suspendida falla con el 403 de GitHub, y el mensaje no contiene ningún token.
  - **Credenciales malas:** con un 401 de GitHub, `sync` responde 502 con «GitHub respondió 401». El estado muestra el error y las instalaciones que había no se borran.
- **`GitHubUnconfiguredIT`:** sin variables, `GET /api/github` dice `configured: false` con las tres variables que faltan, y `POST /api/github/sync` responde 409.
- **`MigrationsIT`** y **`ModularityTest`:** V30 se aplica, y el módulo `github` respeta los límites de Spring Modulith.

## 2. Skynet desplegado, sin App

Con Skynet en marcha y sin las variables `SKYNET_GITHUB_*`, que es lo normal tras desplegar esta rama:

```bash
curl -s -u admin:<contraseña> http://localhost:8080/api/github | jq '{configured, missing, apiUrl, app, installations}'
```

1. Sale `configured: false`, `missing` con `SKYNET_GITHUB_APP_ID`, `SKYNET_GITHUB_PRIVATE_KEY_FILE` y `SKYNET_GITHUB_WEBHOOK_SECRET`, `apiUrl: "https://api.github.com"`, `app: null` e `installations: []`.
2. `repositories` lista los repositorios sin archivar. `demo-repo` sale con `access: "NOT_GITHUB"` si no tiene URL remota, o `"NOT_INSTALLED"` si apunta a github.com.
3. Sincronizar devuelve 409 con el motivo:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -u admin:<contraseña> -X POST http://localhost:8080/api/github/sync
   ```
   Escribe `409`.
4. En el log del control plane, al arrancar, aparece `GitHub App sin configurar: falta SKYNET_GITHUB_APP_ID, …`.
5. Las tablas existen y están vacías:
   ```bash
   docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc "SELECT count(*) FROM github_installation; SELECT count(*) FROM github_webhook_delivery;"
   ```
   Escribe `0` dos veces. Si tu base de datos no va en Docker, usa el `psql` equivalente.

## 3. Con una App real (opcional, manual)

Esta parte no la ejecuta Hermes. La App se crea a mano, igual que se explicará en Ajustes → GitHub en GH-C:

1. En GitHub, ve a **Settings → Developer settings → GitHub Apps → New GitHub App**:
   - nombre: `skynet-<algo>`;
   - homepage: cualquier URL;
   - webhook desactivado por ahora, hasta GH-B;
   - permisos: Metadata (lectura), Contents (lectura y escritura), Pull requests (lectura y escritura), Checks (lectura), Commit statuses (lectura) e Issues (lectura y escritura);
   - instalable solo en tu cuenta.
2. Genera una clave privada (`.pem`) e instala la App **solo** en el repositorio de GitHub de `demo-repo`.
3. Arranca el control plane con:
   - `SKYNET_GITHUB_APP_ID=<id>`;
   - `SKYNET_GITHUB_PRIVATE_KEY_FILE=/ruta/al.pem`;
   - `SKYNET_GITHUB_WEBHOOK_SECRET=<cualquier texto>`.
4. Al arrancar, el log dice `GitHub sincronizado: 1 instalaciones`. `GET /api/github` muestra `app.slug`, tu instalación con ese único repositorio, y `demo-repo` (si su `remoteUrl` apunta a él) con `access: "CONNECTED"`.
5. `grep -c ghs_ <log del control plane>` escribe `0`.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
