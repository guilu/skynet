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

pids=()
cleanup() {
  # El lanzador del runner es un script: hay que parar también la JVM que arranca.
  for pid in "${pids[@]}"; do
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
  ./gradlew -q :control-plane:bootJar :runner:installDist :tools:fake-claude:installDist
fi

# Repositorio de juguete para el worktree del agente.
rm -rf "$E2E_REPO_PATH" "$OUT/runner-home" "$OUT/claude"
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

java -jar control-plane/build/libs/control-plane.jar > "$OUT/control-plane.log" 2>&1 &
pids+=($!)
for _ in $(seq 60); do
  curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"' && break
  sleep 2
done
curl -fsS http://localhost:8080/actuator/health > /dev/null

SKYNET_RUNNER_HOME=$OUT/runner-home \
SKYNET_RUNNER_NAME=e2e-$(date +%s) \
SKYNET_CLAUDE_BIN=$ROOT/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_DELAY_MS,FAKE_CLAUDE_APPLY \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=${FAKE_CLAUDE_DELAY_MS:-500} FAKE_CLAUDE_APPLY=1 \
CLAUDE_CONFIG_DIR=$OUT/claude \
  runner/build/install/skynet-runner/bin/skynet-runner > "$OUT/runner.log" 2>&1 &
pids+=($!)

cd web
if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
  npx vite build > /dev/null
fi
npx playwright test "$@"
