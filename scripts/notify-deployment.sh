#!/usr/bin/env bash
# Avisa a GitHub de un despliegue para que Skynet lo vea (hito DL). Lo usan los listeners que
# despliegan con Git y Docker Compose: no despliega nada, solo crea el despliegue y sus estados en
# GitHub Deployments. GitHub manda los webhooks `deployment` y `deployment_status` a la App de
# Skynet y, si Skynet no estaba (por ejemplo, porque se está desplegando a sí mismo), los vuelve a
# entregar más tarde. Ver docs/despliegues.md §3.
#
#   id=$(scripts/notify-deployment.sh start guilu/skynet <sha> [rama] [pr])
#   scripts/notify-deployment.sh status guilu/skynet "$id" in_progress
#   scripts/notify-deployment.sh status guilu/skynet "$id" success "docker compose up -d: OK" http://omarchy:8081
#   scripts/notify-deployment.sh status guilu/skynet "$id" failure "docker compose pull falló"
#
# `start` imprime el id del despliegue. Los estados válidos son queued, pending, in_progress,
# success, failure y error.
#
# Variables:
#   GITHUB_TOKEN        token con permiso Deployments: lectura y escritura en el repositorio
#                       (un token fino del usuario o de otra App; nunca se imprime)
#   DEPLOY_ENVIRONMENT  el entorno (por defecto omarchy)
#   GITHUB_API          la API (por defecto https://api.github.com)
#
# Reintenta los fallos de red y los 5xx con espera creciente (2, 4, 8 y 16 s). Necesita curl y jq.
set -euo pipefail

API=${GITHUB_API:-https://api.github.com}
ENVIRONMENT=${DEPLOY_ENVIRONMENT:-omarchy}

usage() {
  sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[ -n "${GITHUB_TOKEN:-}" ] || { echo "Falta GITHUB_TOKEN" >&2; exit 2; }
command -v jq >/dev/null || { echo "Falta jq" >&2; exit 2; }

# post <ruta> <json>: imprime la respuesta; reintenta red y 5xx, falla con 4xx.
post() {
  local path=$1 body=$2 attempt=1 delay=2 out code
  out=$(mktemp)
  trap 'rm -f "$out"' RETURN
  while :; do
    code=$(curl -sS -o "$out" -w '%{http_code}' -X POST \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H 'Accept: application/vnd.github+json' \
      -H 'X-GitHub-Api-Version: 2022-11-28' \
      --data "$body" "${API}${path}") || code=000
    case $code in
      2??) cat "$out"; return 0 ;;
      000 | 5??) ;;
      *)
        echo "GitHub respondió $code a POST $path: $(jq -r '.message // empty' "$out" 2>/dev/null)" >&2
        return 1
        ;;
    esac
    if [ "$attempt" -ge 5 ]; then
      echo "GitHub no respondió a POST $path tras $attempt intentos (último código $code)" >&2
      return 1
    fi
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2))
  done
}

command=${1:-}
case $command in
  start)
    [ $# -ge 3 ] || usage
    repo=$2 sha=$3 ref=${4:-$3} pr=${5:-}
    [[ $sha =~ ^[0-9a-f]{40}$ ]] || { echo "El SHA tiene que ser completo (40 caracteres)" >&2; exit 2; }
    [ -z "$pr" ] || [[ $pr =~ ^[1-9][0-9]*$ ]] || { echo "La PR tiene que ser un número" >&2; exit 2; }
    # ref = el SHA exacto: GitHub despliega ese commit aunque la rama avance. Sin
    # required_contexts, GitHub no exige que la CI esté en verde (eso ya lo decide Hermes) y
    # sin auto_merge no toca la rama.
    body=$(jq -n --arg ref "$sha" --arg env "$ENVIRONMENT" --arg branch "$ref" --arg pr "$pr" '{
      ref: $ref, environment: $env, auto_merge: false, required_contexts: [],
      description: ("Despliegue de " + $branch),
      payload: ({branch: $branch} + (if $pr == "" then {} else {pull_request: ($pr | tonumber)} end))
    }')
    post "/repos/${repo}/deployments" "$body" | jq -er '.id'
    ;;
  status)
    [ $# -ge 4 ] || usage
    repo=$2 id=$3 state=$4 description=${5:-} url=${6:-}
    [[ $id =~ ^[0-9]+$ ]] || { echo "El id del despliegue tiene que ser un número" >&2; exit 2; }
    case $state in
      queued | pending | in_progress | success | failure | error) ;;
      *) echo "Estado no válido: $state" >&2; exit 2 ;;
    esac
    body=$(jq -n --arg state "$state" --arg d "${description:0:140}" --arg url "$url" '{state: $state}
      + (if $d == "" then {} else {description: $d} end)
      + (if $url == "" then {} else {environment_url: $url} end)')
    post "/repos/${repo}/deployments/${id}/statuses" "$body" | jq -er '.id' >/dev/null
    ;;
  *) usage ;;
esac
