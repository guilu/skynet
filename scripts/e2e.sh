#!/usr/bin/env bash
# E2E de la aplicación real: control plane + runner con fake-claude, fake-codex y fake-agy + web (vite preview) + Playwright.
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
export E2E_API_PORT=${E2E_API_PORT:-8080}
export SERVER_PORT=$E2E_API_PORT
export SKYNET_URL=http://127.0.0.1:$E2E_API_PORT

export E2E_OUT=$OUT
export E2E_RUNNER_NAME=${E2E_RUNNER_NAME:-e2e-$(date +%s)}

# GitHub App contra el GitHub falso de web/e2e/fake-github.ts (web/e2e/github.e2e.ts): una clave de
# usar y tirar, un secreto de webhooks de pruebas y las URL remotas de github.com.
export E2E_GITHUB_PORT=${E2E_GITHUB_PORT:-4200}
export SKYNET_GITHUB_APP_ID=1
export SKYNET_GITHUB_PRIVATE_KEY_FILE=$OUT/github-app.pem
export SKYNET_GITHUB_WEBHOOK_SECRET=${SKYNET_GITHUB_WEBHOOK_SECRET:-e2e-webhook-secret}
export SKYNET_GITHUB_API_URL=http://127.0.0.1:$E2E_GITHUB_PORT
export SKYNET_GITHUB_WEB_URL=https://github.com

cleanup() {
  "$ROOT/scripts/e2e-service.sh" stop runner || true
  "$ROOT/scripts/e2e-service.sh" stop control-plane || true
  [[ -n ${FAKE_GITHUB_PID:-} ]] && kill "$FAKE_GITHUB_PID" 2>/dev/null || true
}
trap cleanup EXIT

if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
  ./gradlew -q :control-plane:bootJar :runner:installDist :tools:fake-claude:installDist
fi

# Repositorio de juguete para el worktree del agente.
rm -rf "$E2E_REPO_PATH" "$OUT/runner-home" "$OUT/claude" "$OUT/codex" "$OUT/agy" "$OUT"/*.log
mkdir -p "$E2E_REPO_PATH"
git -C "$E2E_REPO_PATH" init -q -b main
# Secretos falsos que no deben llegar nunca a la web (web/e2e/review.e2e.ts): uno en calc.py, que
# sale como contexto en el diff, y otro que imprime check.sh, que sale en el log de la verificación.
export E2E_SECRET_GITHUB=ghp_e2eFakeTokenForRedaction0123456789ab
export E2E_SECRET_AWS=AKIAE2EFAKEKEY012345
printf '# token de pruebas: %s\ndef add(a, b):\n    return a - b\n' "$E2E_SECRET_GITHUB" > "$E2E_REPO_PATH/calc.py"
# Comando de verificación del repositorio: un informe JUnit que pasa solo si add() suma.
cat > "$E2E_REPO_PATH/check.sh" <<SH
echo "usando AWS_ACCESS_KEY_ID=$E2E_SECRET_AWS"
SH
cat >> "$E2E_REPO_PATH/check.sh" <<'SH'
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

openssl genrsa -out "$SKYNET_GITHUB_PRIVATE_KEY_FILE" 2048 2> /dev/null
node web/e2e/fake-github.ts "$E2E_GITHUB_PORT" >> "$OUT/fake-github.log" 2>&1 &
FAKE_GITHUB_PID=$!

# Las E2E de reinicio paran y vuelven a arrancar los dos servicios con el mismo script.
scripts/e2e-service.sh start control-plane
scripts/e2e-service.sh start runner

cd web
if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
  npx vite build > /dev/null
fi
npx playwright test "$@"
