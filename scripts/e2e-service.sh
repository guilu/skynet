#!/usr/bin/env bash
# Arranca o para un servicio de las E2E: `e2e-service.sh start|stop|kill control-plane|runner`.
#
# Lo usan scripts/e2e.sh para arrancarlos y las E2E de reinicio para pararlos a mitad de una
# ejecución. Cada servicio deja su pid en $E2E_OUT/<servicio>.pid y añade su salida a
# $E2E_OUT/<servicio>.log. `stop` envía TERM y espera; `kill` envía KILL, como un corte de luz.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${E2E_OUT:-$ROOT/build/e2e}
action=$1
service=$2
pidfile=$OUT/$service.pid

start() {
  case $service in
    control-plane)
      # El lanzador hace exec: el pid es el de la JVM.
      java -jar "$ROOT/control-plane/build/libs/control-plane.jar" >> "$OUT/control-plane.log" 2>&1 &
      echo $! > "$pidfile"
      for _ in $(seq 90); do
        curl -fsS "${SKYNET_URL:-http://127.0.0.1:8080}/actuator/health" 2>/dev/null \
          | grep -q '"UP"' && return 0
        sleep 1
      done
      echo "El control plane no arrancó: mira $OUT/control-plane.log" >&2
      return 1
      ;;
    runner)
      local FAKE=$ROOT/tools/fake-claude/build/install/fake-claude/bin
      # Los tres proveedores con sus fakes; cada revisor devuelve lo que dejó en .revision.json la
      # fase anterior del worktree (web/e2e/nodes.e2e.ts y providers.e2e.ts). Cada sesión de
      # fake-codex gasta cuota de la cuenta, como la de verdad (web/e2e/usage.e2e.ts). El agente
      # llama a la memoria con el guion que dejó en .memoria.json la fase anterior (memory.e2e.ts).
      # (El comentario va antes: entre líneas con `\` cortaría la orden y el runner usaría el
      # directorio y el nombre por defecto, los del runner normal del equipo.)
      SKYNET_RUNNER_HOME=$OUT/runner-home \
      SKYNET_RUNNER_NAME=${E2E_RUNNER_NAME:-e2e} \
      SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex \
      SKYNET_AGY_BIN=$FAKE/fake-agy \
      SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_DELAY_MS,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_STRUCTURED_OUTPUT,FAKE_CODEX_FIXTURE,FAKE_CODEX_DELAY_MS,FAKE_CODEX_STRUCTURED_OUTPUT,FAKE_AGY_FIXTURE,FAKE_AGY_DELAY_MS,FAKE_AGY_STRUCTURED_OUTPUT,FAKE_AGY_HOME,FAKE_CODEX_QUOTA_STEP,FAKE_MCP_CALLS \
      FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_DELAY_MS=${FAKE_CLAUDE_DELAY_MS:-500} FAKE_CLAUDE_APPLY=1 \
      FAKE_CLAUDE_STRUCTURED_OUTPUT=@.revision.json \
      FAKE_CODEX_DELAY_MS=200 FAKE_CODEX_STRUCTURED_OUTPUT=@.revision.json FAKE_CODEX_QUOTA_STEP=3,1 \
      FAKE_AGY_DELAY_MS=200 FAKE_AGY_STRUCTURED_OUTPUT=@.revision.json FAKE_AGY_HOME=$OUT/agy \
      FAKE_MCP_CALLS=@.memoria.json \
      CLAUDE_CONFIG_DIR=$OUT/claude CODEX_HOME=$OUT/codex \
        "$ROOT/runner/build/install/skynet-runner/bin/skynet-runner" >> "$OUT/runner.log" 2>&1 &
      echo $! > "$pidfile"
      ;;
    *)
      echo "Servicio desconocido: $service" >&2
      return 2
      ;;
  esac
}

stop() {
  local signal=$1
  [[ -f $pidfile ]] || return 0
  local pid
  pid=$(cat "$pidfile")
  kill "-$signal" "$pid" 2>/dev/null || true
  for _ in $(seq 60); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  rm -f "$pidfile"
}

case $action in
  start) start ;;
  stop) stop TERM ;;
  kill) stop KILL ;;
  *)
    echo "Uso: $0 start|stop|kill control-plane|runner" >&2
    exit 2
    ;;
esac
