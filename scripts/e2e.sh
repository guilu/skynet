#!/usr/bin/env bash
# E2E de la aplicación real: control plane + runner con fake-claude + web (vite preview) + Playwright.
#
# Necesita un PostgreSQL vacío o de pruebas en SKYNET_DB_URL (por defecto el de docker compose) y
# los navegadores de Playwright (`npx playwright install chromium`). Deja los logs en build/e2e.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
OUT=$ROOT/build/e2e
mkdir -p "$OUT"
export SKYNET_RUNNER_REGISTRATION_TOKEN=${SKYNET_RUNNER_REGISTRATION_TOKEN:-e2e-secret}
export E2E_REPO_PATH=${E2E_REPO_PATH:-$OUT/repo}
export SKYNET_ADMIN_USER=${SKYNET_ADMIN_USER:-admin}
export SKYNET_ADMIN_PASSWORD=${SKYNET_ADMIN_PASSWORD:-e2e-admin}

export E2E_OUT=$OUT
export E2E_RUNNER_NAME=${E2E_RUNNER_NAME:-e2e-$(date +%s)}

cleanup() {
  "$ROOT/scripts/e2e-service.sh" stop runner || true
  "$ROOT/scripts/e2e-service.sh" stop control-plane || true
}
trap cleanup EXIT

if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
  ./gradlew -q :control-plane:bootJar :runner:installDist :tools:fake-claude:installDist
fi

# Repositorio de juguete para el worktree del agente.
rm -rf "$E2E_REPO_PATH" "$OUT/runner-home" "$OUT/claude" "$OUT"/*.log
mkdir -p "$E2E_REPO_PATH"
git -C "$E2E_REPO_PATH" init -q -b main
printf 'def add(a, b):\n    return a - b\n' > "$E2E_REPO_PATH/calc.py"
# Comando de verificación del repositorio: un informe JUnit que pasa solo si add() suma.
cat > "$E2E_REPO_PATH/check.sh" <<'SH'
mkdir -p build/test-results/test
if grep -q 'a + b' calc.py; then
  r='<testcase classname="CalcTest" name="adds"/>'
else
  r='<testcase classname="CalcTest" name="adds"><failure message="add resta"/></testcase>'
fi
echo "<testsuite name='CalcTest'>$r</testsuite>" > build/test-results/test/TEST-CalcTest.xml
grep -q 'a + b' calc.py
SH
printf 'build/\n' > "$E2E_REPO_PATH/.gitignore"
git -C "$E2E_REPO_PATH" add calc.py check.sh .gitignore
git -C "$E2E_REPO_PATH" -c user.email=e2e@skynet -c user.name=e2e commit -qm "Inicial"

# Las E2E de reinicio paran y vuelven a arrancar los dos servicios con el mismo script.
scripts/e2e-service.sh start control-plane
scripts/e2e-service.sh start runner

cd web
if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
  npx vite build > /dev/null
fi
npx playwright test "$@"
