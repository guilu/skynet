#!/usr/bin/env bash
# Deja Skynet como recién instalado: borra la base de datos y los artefactos, vuelve a levantarlo y
# publica los workflows iniciales (scripts/seed-workflows.sh). No se puede deshacer.
#
#   scripts/reset-local.sh                       # base de datos y artefactos
#   scripts/reset-local.sh --runner              # además, lo del runner en este equipo (abajo)
#   scripts/reset-local.sh --dry-run [--runner]  # solo dice lo que borraría
#
# Opciones:
#   --runner [dir]  También el runner: su journal, logs, artefactos y worktrees (en dir, por defecto
#                   SKYNET_RUNNER_HOME o ~/.skynet-runner), y en cada repositorio registrado los
#                   worktrees, las ramas skynet/* y las referencias refs/skynet/* que dejó. Para el
#                   servicio skynet-runner si está en marcha y lo vuelve a arrancar al final; se
#                   registra solo con su SKYNET_RUNNER_REGISTRATION_TOKEN.
#   --artifacts dir Carpeta de artefactos del control plane si lo arrancas con bootRun (por defecto
#                   data/artifacts). Con Docker Compose los artefactos van en su volumen.
#   --no-seed       No publica los workflows iniciales.
#   --yes           No pide confirmación (para scripts: borra sin preguntar).
#   --dry-run       No borra nada.
#
# Funciona con Docker Compose (deploy/docker-compose.yml, perfil app o solo PostgreSQL) y con el
# control plane arrancado con bootRun: en ese caso páralo antes y vuelve a arrancarlo al final.
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f deploy/docker-compose.yml)
PROJECT=skynet

runner=false runner_home=${SKYNET_RUNNER_HOME:-$HOME/.skynet-runner}
artifacts_dir=data/artifacts seed=true yes=false dry_run=false
while (($#)); do
  case $1 in
    --runner)
      runner=true
      if [[ ${2:-} && $2 != --* ]]; then runner_home=$2; shift; fi
      ;;
    --artifacts) artifacts_dir=${2:?--artifacts necesita una carpeta}; shift ;;
    --no-seed) seed=false ;;
    --yes) yes=true ;;
    --dry-run) dry_run=true ;;
    -h | --help) sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Opción desconocida: $1 (mira --help)" >&2; exit 2 ;;
  esac
  shift
done

command -v docker >/dev/null || { echo "Falta docker." >&2; exit 1; }

env_file_value() { # fichero nombre
  [[ -f $1 ]] || return 0
  sed -n "s/^[[:space:]]*$2=//p" "$1" | tail -n1 | sed 's/[[:space:]]#.*//; s/^[[:space:]]*//; s/[[:space:]]*$//'
}
DB_USER=${SKYNET_DB_USER:-$(env_file_value deploy/.env SKYNET_DB_USER)}
DB_USER=${DB_USER:-skynet}

running_services() { "${COMPOSE[@]}" --profile app ps --services --status running 2>/dev/null || true; }
services=$(running_services)
app_was_up=false
grep -qx api <<< "$services" && app_was_up=true

# Repositorios registrados, leídos antes de borrar (solo hacen falta para limpiar el runner).
repos=()
if $runner; then
  if grep -qx postgres <<< "$services"; then
    while IFS= read -r repo; do
      [[ -n $repo ]] && repos+=("$repo")
    done < <("${COMPOSE[@]}" exec -T postgres \
      psql -U "$DB_USER" -d skynet -Atc "SELECT DISTINCT local_path FROM repository ORDER BY 1" 2>/dev/null || true)
  else
    echo "Aviso: PostgreSQL no está en marcha, así que no sé qué repositorios limpiar." >&2
    echo "       Arráncalo (${COMPOSE[*]} up -d postgres) y vuelve a ejecutar el script." >&2
    exit 1
  fi
fi

volumes=()
for volume in postgres-data artifacts-data; do
  docker volume inspect "${PROJECT}_$volume" >/dev/null 2>&1 && volumes+=("${PROJECT}_$volume")
done

echo "Se va a borrar:"
echo "  - los volúmenes de Docker: ${volumes[*]:-(ninguno existe)}"
[[ -d $artifacts_dir ]] && echo "  - los artefactos de bootRun: $artifacts_dir"
if $runner; then
  echo "  - lo del runner en $runner_home (journal, logs, artefactos y worktrees)"
  for repo in "${repos[@]}"; do
    if [[ -d $repo/.git || -f $repo/.git ]]; then
      branches=$(git -C "$repo" for-each-ref --format='%(refname:short)' 'refs/heads/skynet/' | wc -l)
      refs=$(git -C "$repo" for-each-ref --format='%(refname)' 'refs/skynet/' | wc -l)
      echo "  - en $repo: worktrees de Skynet, $branches ramas skynet/* y $refs referencias refs/skynet/*"
    else
      echo "  - $repo: no existe en este equipo, se deja como está"
    fi
  done
fi
$seed && echo "Y después se publican los workflows iniciales de workflows/."

if $dry_run; then
  echo "(--dry-run: no se ha borrado nada)"
  exit 0
fi
if ! $yes; then
  read -r -p "Esto no se puede deshacer. Escribe «borrar» para seguir: " answer
  [[ $answer == borrar ]] || { echo "No se ha borrado nada."; exit 1; }
fi

runner_service=false
if $runner && command -v systemctl >/dev/null && systemctl --user is-active --quiet skynet-runner 2>/dev/null; then
  echo "→ Parando el servicio skynet-runner"
  systemctl --user stop skynet-runner
  runner_service=true
fi

echo "→ Parando Skynet"
"${COMPOSE[@]}" --profile app down
((${#volumes[@]})) && docker volume rm "${volumes[@]}" >/dev/null
[[ -d $artifacts_dir ]] && rm -rf "$artifacts_dir"

if $runner; then
  echo "→ Limpiando el runner"
  rm -rf "$runner_home/journal.db" "$runner_home/logs" "$runner_home/artifacts" "$runner_home/workspaces"
  for repo in "${repos[@]}"; do
    [[ -d $repo/.git || -f $repo/.git ]] || continue
    git -C "$repo" worktree prune
    git -C "$repo" for-each-ref --format='%(refname)' 'refs/heads/skynet/' 'refs/skynet/' \
      | while read -r ref; do git -C "$repo" update-ref -d "$ref"; done
    echo "  $repo limpio"
  done
fi

if $app_was_up; then
  echo "→ Levantando Skynet (perfil app)"
  "${COMPOSE[@]}" --profile app up -d --wait
else
  echo "→ Levantando PostgreSQL"
  "${COMPOSE[@]}" up -d --wait postgres
fi
$runner_service && { echo "→ Arrancando el servicio skynet-runner"; systemctl --user start skynet-runner; }

if $seed; then
  url=${SKYNET_URL:-http://localhost:8080}
  if curl -fsS -o /dev/null "$url/actuator/health" 2>/dev/null; then
    echo "→ Publicando los workflows iniciales"
    scripts/seed-workflows.sh
  else
    echo "Arranca el control plane (./gradlew :control-plane:bootRun) y publica los workflows con:"
    echo "  scripts/seed-workflows.sh"
  fi
fi
echo "Listo: Skynet está como recién instalado."
