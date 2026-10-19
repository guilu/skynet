#!/usr/bin/env bash
# Publica los workflows iniciales de workflows/*.yaml en un Skynet en marcha. Se salta los que ya
# existen, así que se puede repetir sin miedo.
#
#   SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh
#
# Antes de publicar, cada `model: ${VARIABLE}` toma su valor de workflows/modelos.env (o de una
# variable del entorno con el mismo nombre); si está vacío, la línea desaparece y el CLI usa su
# modelo por defecto. SeedWorkflowsTest comprueba que todos se pueden publicar.
#
# Variables: SKYNET_URL (por defecto http://localhost:8080), SKYNET_ADMIN_USER (admin) y
# SKYNET_ADMIN_PASSWORD (si no está, se lee de deploy/.env). Necesita curl y jq.
set -euo pipefail

cd "$(dirname "$0")/.."
command -v jq >/dev/null || { echo "Falta jq (pacman -S jq, apt install jq o brew install jq)." >&2; exit 1; }

env_file_value() { # fichero nombre: el valor de NOMBRE=valor, sin comentario
  [[ -f $1 ]] || return 0
  sed -n "s/^[[:space:]]*$2=//p" "$1" | tail -n1 | sed 's/[[:space:]]#.*//; s/^[[:space:]]*//; s/[[:space:]]*$//'
}

URL=${SKYNET_URL:-http://localhost:8080}
PASSWORD=${SKYNET_ADMIN_PASSWORD:-$(env_file_value deploy/.env SKYNET_ADMIN_PASSWORD)}
[[ -n $PASSWORD ]] || { echo "Define SKYNET_ADMIN_PASSWORD (o rellénala en deploy/.env)." >&2; exit 1; }
USER_PASS="${SKYNET_ADMIN_USER:-admin}:$PASSWORD"

api() { # método ruta [json]: escribe el cuerpo y devuelve el código HTTP en la última línea
  curl -sS -u "$USER_PASS" -X "$1" -H 'Content-Type: application/json' ${3:+-d "$3"} \
    -w '\n%{http_code}' "$URL$2"
}

model() { # el valor de una variable: primero el entorno, luego workflows/modelos.env
  local name=$1
  if [[ -n ${!name+x} ]]; then printf '%s' "${!name}"; else env_file_value workflows/modelos.env "$name"; fi
}

substitute() { # fichero: el YAML con los modelos puestos (o sin la línea si están vacíos)
  local line name value
  while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line =~ ^([[:space:]]*model:[[:space:]]*)\$\{([A-Z_]+)\}[[:space:]]*$ ]]; then
      name=${BASH_REMATCH[2]}
      value=$(model "$name")
      [[ -n $value ]] && printf '%s%s\n' "${BASH_REMATCH[1]}" "$value"
    else
      printf '%s\n' "$line"
    fi
  done < "$1"
}

curl -fsS -o /dev/null "$URL/actuator/health" \
  || { echo "No responde $URL: arranca antes el control plane." >&2; exit 1; }

created=0 skipped=0 failed=0
for file in workflows/*.yaml; do
  key=$(basename "$file" .yaml)
  response=$(api GET "/api/workflows/$key")
  if [[ ${response##*$'\n'} == 200 ]]; then
    echo "· $key: ya existe, no se toca"
    skipped=$((skipped + 1))
    continue
  fi

  body=$(substitute "$file" | jq -Rs '{sourceYaml: .}')
  response=$(api POST /api/workflows "$body")
  if [[ ${response##*$'\n'} != 201 ]]; then
    echo "✗ $key: no se pudo crear (HTTP ${response##*$'\n'}): ${response%$'\n'*}" >&2
    failed=$((failed + 1))
    continue
  fi
  detail=${response%$'\n'*}
  if [[ $(jq -r '.validation.publishable' <<< "$detail") != true ]]; then
    echo "✗ $key: borrador creado, pero no se puede publicar:" >&2
    jq -r '.validation.problems[] | "    línea \(.line // "?"): \(.message)"' <<< "$detail" >&2
    failed=$((failed + 1))
    continue
  fi
  id=$(jq -r '.id' <<< "$detail")
  revision=$(jq -r '.revision' <<< "$detail")
  response=$(api POST "/api/workflow-versions/$id/publish" "{\"revision\": $revision}")
  if [[ ${response##*$'\n'} != 200 ]]; then
    echo "✗ $key: no se pudo publicar (HTTP ${response##*$'\n'}): ${response%$'\n'*}" >&2
    failed=$((failed + 1))
    continue
  fi
  echo "✓ $key: publicado (v1)"
  created=$((created + 1))
done

echo "Workflows: $created publicados, $skipped ya estaban, $failed con error."
[[ $failed == 0 ]]
