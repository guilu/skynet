#!/usr/bin/env bash
# Publica los workflows iniciales de workflows/*.yaml en un Skynet en marcha. Se salta los que ya
# existen, así que se puede repetir sin miedo.
#
#   SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh
#   SKYNET_ADMIN_PASSWORD=<contraseña> scripts/seed-workflows.sh --actualizar
#
# Con --actualizar, un workflow que ya existe y cuyo YAML publicado no es el del repositorio se
# publica como versión nueva. Si tiene un borrador abierto no se toca, para no pisarlo.
#
# Antes de publicar, cada `model: ${VARIABLE}` toma su valor de workflows/modelos.env (o de una
# variable del entorno con el mismo nombre); si está vacío, la línea desaparece y el CLI usa su
# modelo por defecto. SeedWorkflowsTest comprueba que todos se pueden publicar.
#
# Variables: SKYNET_URL (por defecto http://localhost:8080), SKYNET_ADMIN_USER (admin) y
# SKYNET_ADMIN_PASSWORD (si no está, se lee de deploy/.env). Necesita curl y jq.
set -euo pipefail

UPDATE=false
case ${1:-} in
  --actualizar) UPDATE=true ;;
  '') ;;
  *) echo "Uso: $0 [--actualizar]" >&2; exit 2 ;;
esac

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

publish() { # clave id revisión: publica el borrador; escribe la versión o falla
  local response
  response=$(api POST "/api/workflow-versions/$2/publish" "{\"revision\": $3}")
  if [[ ${response##*$'\n'} != 200 ]]; then
    echo "✗ $1: no se pudo publicar (HTTP ${response##*$'\n'}): ${response%$'\n'*}" >&2
    return 1
  fi
  jq -r '.version' <<< "${response%$'\n'*}"
}

update() { # clave fichero vista: publica el YAML del repositorio como versión nueva si ha cambiado
  local key=$1 file=$2 view=$3 source response detail version
  if [[ $(jq -r '.workflow.draft // empty | .id' <<< "$view") ]]; then
    echo "· $key: tiene un borrador abierto, no se toca"
    skipped=$((skipped + 1))
    return
  fi
  source=$(substitute "$file")
  response=$(api GET "/api/workflow-versions/$(jq -r '.workflow.published.id' <<< "$view")")
  if [[ $(jq -r '.sourceYaml' <<< "${response%$'\n'*}") == "$source" ]]; then
    echo "· $key: ya está al día"
    skipped=$((skipped + 1))
    return
  fi
  response=$(api POST "/api/workflows/$key/draft")
  detail=${response%$'\n'*}
  if [[ ${response##*$'\n'} != 200 ]]; then
    echo "✗ $key: no se pudo abrir el borrador (HTTP ${response##*$'\n'}): $detail" >&2
    failed=$((failed + 1))
    return
  fi
  response=$(api PUT "/api/workflow-versions/$(jq -r '.id' <<< "$detail")" \
    "$(jq -n --arg yaml "$source" --argjson revision "$(jq '.revision' <<< "$detail")" \
      '{sourceYaml: $yaml, revision: $revision}')")
  detail=${response%$'\n'*}
  if [[ ${response##*$'\n'} != 200 || $(jq -r '.validation.publishable' <<< "$detail") != true ]]; then
    echo "✗ $key: el borrador nuevo no se puede publicar (HTTP ${response##*$'\n'}): $detail" >&2
    failed=$((failed + 1))
    return
  fi
  if version=$(publish "$key" "$(jq -r '.id' <<< "$detail")" "$(jq '.revision' <<< "$detail")"); then
    echo "✓ $key: actualizado (v$version)"
    updated=$((updated + 1))
  else
    failed=$((failed + 1))
  fi
}

created=0 updated=0 skipped=0 failed=0
for file in workflows/*.yaml; do
  key=$(basename "$file" .yaml)
  response=$(api GET "/api/workflows/$key")
  if [[ ${response##*$'\n'} == 200 ]]; then
    if $UPDATE; then
      update "$key" "$file" "${response%$'\n'*}"
    else
      echo "· $key: ya existe, no se toca"
      skipped=$((skipped + 1))
    fi
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
  if ! publish "$key" "$(jq -r '.id' <<< "$detail")" "$(jq -r '.revision' <<< "$detail")" > /dev/null; then
    failed=$((failed + 1))
    continue
  fi
  echo "✓ $key: publicado (v1)"
  created=$((created + 1))
done

echo "Workflows: $created publicados, $updated actualizados, $skipped sin cambios, $failed con error."
[[ $failed == 0 ]]
