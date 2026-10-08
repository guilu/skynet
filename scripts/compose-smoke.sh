#!/usr/bin/env bash
# Prueba de humo de la aplicación de Docker Compose con un runner en el host, como se instala de
# verdad: el runner (con fake-claude) se registra a través de la web (nginx), ejecuta un agente en
# un repositorio de juguete, sube sus artefactos y la ejecución termina «Completada».
#
# Necesita la aplicación levantada con el mismo secreto de registro:
#   SKYNET_ADMIN_PASSWORD=<contraseña> SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> \
#     docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
#   SKYNET_ADMIN_PASSWORD=<contraseña> SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto> scripts/compose-smoke.sh
#
# Pensada para una instalación recién levantada (como en CI): si hay otros runners conectados, el
# agente puede ir a uno de ellos.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
URL=${SKYNET_URL:-http://localhost:${SKYNET_WEB_PORT:-8081}}
USER_PASS="${SKYNET_ADMIN_USER:-admin}:${SKYNET_ADMIN_PASSWORD:?define SKYNET_ADMIN_PASSWORD}"
: "${SKYNET_RUNNER_REGISTRATION_TOKEN:?define SKYNET_RUNNER_REGISTRATION_TOKEN}"
OUT=$ROOT/build/compose-smoke
REPO=$OUT/repo

api() { # método ruta [json]
  curl -fsS -u "$USER_PASS" -X "$1" -H 'Content-Type: application/json' ${3:+-d "$3"} "$URL$2"
}

./gradlew -q :runner:installDist :tools:fake-claude:installDist
rm -rf "$OUT" && mkdir -p "$REPO"
git -C "$REPO" init -q -b main
printf 'def add(a, b):\n    return a - b\n' > "$REPO/calc.py"
git -C "$REPO" add calc.py
git -C "$REPO" -c user.email=smoke@skynet -c user.name=smoke commit -qm "Inicial"

RUNNER_NAME=smoke-$(date +%s)
SKYNET_URL=$URL SKYNET_RUNNER_NAME=$RUNNER_NAME SKYNET_RUNNER_HOME=$OUT/runner-home \
  SKYNET_CLAUDE_BIN=$ROOT/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
  SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
  runner/build/install/skynet-runner/bin/skynet-runner > "$OUT/runner.log" 2>&1 &
RUNNER_PID=$!
trap 'kill $RUNNER_PID 2>/dev/null || true; wait $RUNNER_PID 2>/dev/null || true' EXIT

echo "Esperando a que el runner $RUNNER_NAME se registre…"
for _ in $(seq 60); do
  api GET /api/runners | jq -e --arg n "$RUNNER_NAME" '.[] | select(.name == $n and .status == "ONLINE")' > /dev/null && break
  sleep 1
done
api GET /api/runners | jq -e --arg n "$RUNNER_NAME" '.[] | select(.name == $n and .status == "ONLINE")' > /dev/null \
  || { echo "El runner no se registró"; cat "$OUT/runner.log"; exit 1; }

KEY=S$(date +%s | tail -c 6)
PROJECT=$(api POST /api/projects "{\"key\":\"$KEY\",\"name\":\"Humo de Compose\"}" | jq -r .id)
REPO_ID=$(api POST "/api/projects/$PROJECT/repositories" "{\"name\":\"demo\",\"localPath\":\"$REPO\"}" | jq -r .id)
ITEM=$(api POST "/api/projects/$PROJECT/work-items" '{"title":"Arreglar la suma","type":"BUG"}' | jq -r .id)
RUN=$(api POST "/api/work-items/$ITEM/runs" "{\"repositoryId\":\"$REPO_ID\",\"prompt\":\"Arregla add()\"}" | jq -r .id)

echo "Ejecución $RUN lanzada; esperando a que termine…"
STATUS=
for _ in $(seq 120); do
  STATUS=$(api GET "/api/workflow-runs/$RUN" | jq -r .status)
  [[ $STATUS == SUCCEEDED || $STATUS == FAILED || $STATUS == CANCELLED ]] && break
  sleep 1
done
[[ $STATUS == SUCCEEDED ]] || { echo "La ejecución acabó en $STATUS"; cat "$OUT/runner.log"; exit 1; }

AGENT=$(api GET "/api/workflow-runs/$RUN" | jq -r '.stages[0].agents[0].id')
echo "Esperando a los artefactos del agente…"
for _ in $(seq 30); do
  api GET "/api/agent-runs/$AGENT/artifacts" | jq -e 'map(.type) | index("DIFF")' > /dev/null && break
  sleep 1
done
api GET "/api/agent-runs/$AGENT/artifacts" | jq -e 'map(.type) | index("DIFF")' > /dev/null \
  || { echo "No llegó el diff"; api GET "/api/agent-runs/$AGENT/artifacts"; cat "$OUT/runner.log"; exit 1; }

echo "OK: el runner se registró por $URL, la ejecución terminó «Completada» y el diff está subido."
