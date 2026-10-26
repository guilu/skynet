#!/usr/bin/env bash
# Galería visual de Skynet (VS-A): siembra datos de demostración en un entorno aislado, captura
# las pantallas de web/visual/navigation-map.json y deja la galería en docs/screenshots.
#
#   cd web && npm run screenshots        # o scripts/screenshots.sh [argumentos de Playwright]
#
# Nunca usa la base de datos normal: levanta un PostgreSQL desechable (Docker o, si no hay, un
# clúster temporal con `initdb`, que no corre como root) que se borra al terminar, o usa
# SCREENSHOTS_DB_URL si se le da una base vacía (la Action, con su propio servicio). Servicios,
# runner y repositorio de juguete viven en $SCREENSHOTS_TMP (/tmp/skynet-visual), con puertos
# propios, y también se borran al salir.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
VIS=${SCREENSHOTS_TMP:-/tmp/skynet-visual}
DB_PORT=${SCREENSHOTS_DB_PORT:-55432}
DB_CONTAINER=skynet-screenshots-db

# Lo que lean los servicios y Playwright. Las credenciales son de usar y tirar.
# UTF-8 para todo: sin él, el runner pasa las tildes de los comandos como «?».
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export E2E_OUT=$VIS
export E2E_REPO_PATH=$VIS/analizador-texto
export E2E_RUNNER_NAME=runner-demo
export E2E_API_PORT=${SCREENSHOTS_API_PORT:-18090}
export SERVER_PORT=$E2E_API_PORT
export SKYNET_URL=http://127.0.0.1:$E2E_API_PORT
export SKYNET_ADMIN_USER=admin
SKYNET_ADMIN_PASSWORD=$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')
SKYNET_RUNNER_REGISTRATION_TOKEN=$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')
export SKYNET_ADMIN_PASSWORD SKYNET_RUNNER_REGISTRATION_TOKEN

db_mode=
cleanup() {
  "$ROOT/scripts/e2e-service.sh" stop runner || true
  "$ROOT/scripts/e2e-service.sh" stop control-plane || true
  case $db_mode in
    docker) docker rm -f "$DB_CONTAINER" > /dev/null 2>&1 || true ;;
    initdb) "$PG_BIN/pg_ctl" -D "$VIS/pg" -m immediate stop > /dev/null 2>&1 || true ;;
  esac
  if [[ "${SCREENSHOTS_KEEP:-}" != 1 ]]; then
    rm -rf "$VIS"
  fi
}
trap cleanup EXIT

rm -rf "$VIS"
mkdir -p "$VIS"

# --- Base de datos desechable ---------------------------------------------------------------
if [[ -n "${SCREENSHOTS_DB_URL:-}" ]]; then
  # Defensa: la base normal se llama `skynet`. Esta tiene que ser otra.
  if [[ $SCREENSHOTS_DB_URL =~ /skynet(\?|$) ]]; then
    echo "SCREENSHOTS_DB_URL apunta a una base llamada 'skynet': usa una base solo para capturas" >&2
    exit 1
  fi
  db_mode=external
  export SKYNET_DB_URL=$SCREENSHOTS_DB_URL
elif docker info > /dev/null 2>&1; then
  db_mode=docker
  docker rm -f "$DB_CONTAINER" > /dev/null 2>&1 || true
  docker run -d --rm --name "$DB_CONTAINER" --tmpfs /var/lib/postgresql/data \
    -e POSTGRES_DB=skynet_screenshots -e POSTGRES_USER=skynet -e POSTGRES_PASSWORD=skynet \
    -p "127.0.0.1:$DB_PORT:5432" postgres:16-alpine > /dev/null
  for _ in $(seq 60); do
    docker exec "$DB_CONTAINER" pg_isready -U skynet -d skynet_screenshots > /dev/null 2>&1 && break
    sleep 1
  done
  export SKYNET_DB_URL=jdbc:postgresql://127.0.0.1:$DB_PORT/skynet_screenshots
else
  PG_BIN=$(dirname "$(command -v initdb || ls -d /usr/lib/postgresql/*/bin/initdb 2> /dev/null | sort -V | tail -1)")
  if [[ ! -x $PG_BIN/initdb ]]; then
    echo "Hace falta Docker o PostgreSQL (initdb) para la base desechable, o SCREENSHOTS_DB_URL" >&2
    exit 1
  fi
  db_mode=initdb
  "$PG_BIN/initdb" -D "$VIS/pg" -U skynet --auth=trust > /dev/null
  "$PG_BIN/pg_ctl" -D "$VIS/pg" -l "$VIS/postgres.log" -w \
    -o "-p $DB_PORT -k $VIS -c listen_addresses=127.0.0.1" start > /dev/null
  "$PG_BIN/createdb" -h 127.0.0.1 -p "$DB_PORT" -U skynet skynet_screenshots
  export SKYNET_DB_URL=jdbc:postgresql://127.0.0.1:$DB_PORT/skynet_screenshots
fi
export SKYNET_DB_USER=${SKYNET_DB_USER:-skynet} SKYNET_DB_PASSWORD=${SKYNET_DB_PASSWORD:-skynet}
echo "Base de datos desechable ($db_mode): $SKYNET_DB_URL"

# --- Binarios -------------------------------------------------------------------------------
if [[ "${SCREENSHOTS_SKIP_BUILD:-}" != 1 ]]; then
  ./gradlew -q :control-plane:bootJar :runner:installDist :tools:fake-claude:installDist
fi

# --- Repositorio de juguete, sin secretos ---------------------------------------------------
# El agente simulado (fixtures/claude/02-tools) corrige `add()` en calc.py; check.sh es la
# verificación del repositorio y solo pasa con la suma corregida.
mkdir -p "$E2E_REPO_PATH"
git -C "$E2E_REPO_PATH" init -q -b main
cat > "$E2E_REPO_PATH/README.md" << 'MD'
# analizador-texto

Cuenta palabras y frases de un texto y suma los totales de varios ficheros.
MD
printf 'def add(a, b):\n    return a - b\n' > "$E2E_REPO_PATH/calc.py"
cat > "$E2E_REPO_PATH/check.sh" << 'SH'
mkdir -p build/test-results/test
if grep -q 'a + b' calc.py; then
  r='<testcase classname="TotalesTest" name="suma"/>'
else
  r='<testcase classname="TotalesTest" name="suma"><failure message="add resta"/></testcase>'
fi
echo "<testsuite name='TotalesTest'>$r<testcase classname='PalabrasTest' name='cuenta'/></testsuite>" > build/test-results/test/TEST-Totales.xml
grep -q 'a + b' calc.py
SH
printf 'build/\n' > "$E2E_REPO_PATH/.gitignore"
git -C "$E2E_REPO_PATH" add -A
git -C "$E2E_REPO_PATH" -c user.email=demo@example.invalid -c user.name=demo commit -qm "Inicial"

# --- Servicios y capturas -------------------------------------------------------------------
scripts/e2e-service.sh start control-plane
scripts/e2e-service.sh start runner

cd web
if [[ "${SCREENSHOTS_SKIP_BUILD:-}" != 1 ]]; then
  npx vite build > /dev/null
fi
npx playwright test -c playwright.screenshots.config.ts "$@"
node visual/build-map.ts
# Privacidad, índice y ZIP: si algo falla aquí, no se empaqueta.
node visual/finish.ts
