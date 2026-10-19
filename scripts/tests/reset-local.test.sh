#!/usr/bin/env bash
# Pruebas de scripts/reset-local.sh sin Docker real: `docker` y `systemctl` son simuladores que
# apuntan cada llamada, y los repositorios son de juguete en una carpeta temporal.
#
#   scripts/tests/reset-local.test.sh
#
# Comprueba las rutas históricas (inexistentes, que no son Git, con un .git roto o que no son la
# raíz de su repositorio), que --dry-run no cambia nada, la confirmación exacta «borrar» y que el
# borrado real solo toca lo suyo.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT=$ROOT/scripts/reset-local.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
failures=0 passed=0

fail() { echo "  ✗ $*" >&2; failures=$((failures + 1)); }
ok() { passed=$((passed + 1)); }
check() { # descripción comando...
  local what=$1; shift
  if "$@"; then ok; else fail "$what"; fi
}

# --- Simuladores --------------------------------------------------------------------------------

mkdir -p "$T/bin"
cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$RESET_TEST_DIR/docker.log"
case "$*" in
  *"ps --services"*) printf 'postgres\n' ;;
  *"exec -T postgres"*) cat "$RESET_TEST_DIR/repos.txt" ;;
  "volume inspect"*) exit 0 ;;
esac
EOF
cat > "$T/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$RESET_TEST_DIR/systemctl.log"
exit 3  # is-active: inactivo
EOF
chmod +x "$T/bin/docker" "$T/bin/systemctl"

git_quiet() { git -c user.email=t@skynet -c user.name=t -c init.defaultBranch=main "$@" >/dev/null 2>&1; }

# Un repositorio con lo que deja el runner (worktree, rama skynet/*, refs/skynet/*) y lo que no es
# suyo (main, otra rama y otra referencia), más un fichero sin confirmar.
make_repo() { # ruta home-del-runner
  mkdir -p "$1"
  git_quiet -C "$1" init
  echo hola > "$1/README.md"
  git_quiet -C "$1" add README.md
  git_quiet -C "$1" commit -m inicial
  git_quiet -C "$1" branch feature/mia
  git_quiet -C "$1" update-ref refs/notes/mias HEAD
  git_quiet -C "$1" update-ref refs/skynet/snapshots/abc HEAD
  git_quiet -C "$1" worktree add "$2/workspaces/$(basename "$1")-wt" -b skynet/TK-1/abc
  echo cambio > "$1/sin-confirmar.txt"
}

# Todo lo que se puede cambiar: ficheros (con su contenido) y referencias y worktrees de Git.
snapshot() {
  local dir
  (cd "$T" && find case home art -type f -print0 2>/dev/null | sort -z | xargs -0 -r sha1sum) || true
  for dir in "$T"/case/*/; do
    [[ -d $dir/.git ]] || continue
    git -C "$dir" for-each-ref --format='%(refname) %(objectname)' 2>/dev/null
    git -C "$dir" worktree list --porcelain 2>/dev/null
  done
}

# Prepara un caso: repositorios en $T/case, rutas en repos.txt y el home del runner.
setup() { # nombre-de-cada-tipo...
  rm -rf "$T/case" "$T/home" "$T/art" "$T/docker.log" "$T/systemctl.log"
  mkdir -p "$T/case" "$T/home/workspaces" "$T/home/logs" "$T/home/artifacts" "$T/art"
  : > "$T/repos.txt"
  echo journal > "$T/home/journal.db"
  echo mia > "$T/home/notas-mias.txt"
  echo log > "$T/home/logs/a.ndjson"
  echo artefacto > "$T/art/diff.patch"
  local kind
  for kind in "$@"; do
    case $kind in
      valido*) make_repo "$T/case/$kind" "$T/home" ;;
      inexistente*) ;;  # la ruta no existe
      sin-git*) mkdir -p "$T/case/$kind"; echo dato > "$T/case/$kind/fichero.txt" ;;
      git-roto*) mkdir -p "$T/case/$kind"; echo "gitdir: $T/no-existe" > "$T/case/$kind/.git" ;;
      subcarpeta*) make_repo "$T/case/$kind" "$T/home"; mkdir -p "$T/case/$kind/sub" ;;
    esac
    if [[ $kind == subcarpeta* ]]; then echo "$T/case/$kind/sub"; else echo "$T/case/$kind"; fi >> "$T/repos.txt"
  done
}

run_reset() { # [stdin] -- argumentos
  local input=$1; shift
  printf '%s' "$input" | env PATH="$T/bin:$PATH" RESET_TEST_DIR="$T" SKYNET_RUNNER_HOME="$T/home" \
    SKYNET_DB_USER=skynet "$SCRIPT" --artifacts "$T/art" --no-seed "$@" > "$T/out.txt" 2>&1
}

destructive_docker_calls() { grep -E '(^| )(down|up|rm)( |$)' "$T/docker.log" 2>/dev/null || true; }
branches() { git -C "$1" for-each-ref --format='%(refname)' refs/heads refs/skynet refs/notes | sort | tr '\n' ' '; }

# --- Casos --------------------------------------------------------------------------------------

dry_run_case() { # descripción tipos...
  local what=$1; shift
  echo "· --dry-run --runner con $what"
  setup "$@"
  snapshot > "$T/snap.before"
  local code=0
  run_reset '' --dry-run --runner || code=$?
  snapshot > "$T/snap.after"
  check "$what: termina con 0 (fue $code)" test "$code" = 0
  check "$what: no cambia nada" cmp -s "$T/snap.before" "$T/snap.after"
  check "$what: no para, borra ni levanta nada en Docker" test -z "$(destructive_docker_calls)"
  check "$what: no toca el servicio del runner" test ! -s "$T/systemctl.log"
  check "$what: dice que no ha borrado nada" grep -q -- '--dry-run: no se ha borrado nada' "$T/out.txt"
}

dry_run_case "una ruta inexistente" inexistente
check "inexistente: avisa" grep -q "se omite $T/case/inexistente: no existe en este equipo" "$T/out.txt"

dry_run_case "una carpeta que no es Git" sin-git
check "sin Git: avisa" grep -q "se omite $T/case/sin-git: no es un repositorio Git" "$T/out.txt"

dry_run_case "un .git roto (el código 128 de antes)" git-roto
check "git roto: avisa" grep -q "se omite $T/case/git-roto: no es un repositorio Git" "$T/out.txt"

dry_run_case "una subcarpeta de un repositorio" subcarpeta
check "subcarpeta: avisa" grep -q "se omite $T/case/subcarpeta/sub: no es la raíz de su repositorio" "$T/out.txt"

dry_run_case "un repositorio válido" valido
check "válido: cuenta sus ramas y referencias" \
  grep -q "en $T/case/valido: worktrees de Skynet, 1 ramas skynet/\* y 1 referencias refs/skynet/\*" "$T/out.txt"
check "válido: sin avisos" bash -c "! grep -q 'se omite' '$T/out.txt'"

dry_run_case "rutas mezcladas" inexistente-1 valido-1 sin-git-1 git-roto-1 valido-2
check "mezcladas: lista el primer válido" grep -q "en $T/case/valido-1: worktrees" "$T/out.txt"
check "mezcladas: lista el segundo válido" grep -q "en $T/case/valido-2: worktrees" "$T/out.txt"
check "mezcladas: avisa de las tres inválidas" test "$(grep -c 'Aviso: se omite' "$T/out.txt")" = 3
check "mezcladas: cuenta las omitidas" grep -q "se omiten 3 rutas" "$T/out.txt"

echo "· La confirmación tiene que ser exactamente «borrar»"
for answer in '' 'Borrar' 'borrar ' ' borrar' 'borrar.' 'si' 'BORRAR'; do
  setup valido inexistente
  snapshot > "$T/snap.before"
  code=0
  run_reset "$answer"$'\n' --runner || code=$?
  snapshot > "$T/snap.after"
  check "«$answer»: termina con 1 (fue $code)" test "$code" = 1
  check "«$answer»: no cambia nada" cmp -s "$T/snap.before" "$T/snap.after"
  check "«$answer»: no toca Docker" test -z "$(destructive_docker_calls)"
  check "«$answer»: dice que no ha borrado nada" grep -q 'No se ha borrado nada.' "$T/out.txt"
done

echo "· Borrado real con rutas mezcladas: solo toca lo suyo"
setup valido inexistente sin-git git-roto subcarpeta
code=0
run_reset $'borrar\n' --runner || code=$?
check "borrado: termina con 0 (fue $code)" test "$code" = 0
check "borrado: solo borra los dos volúmenes de Skynet" \
  grep -qx 'volume rm skynet_postgres-data skynet_artifacts-data' "$T/docker.log"
check "borrado: ningún otro docker rm" test "$(grep -c ' rm \|^volume rm' "$T/docker.log")" = 1
check "borrado: borra los artefactos indicados" test ! -e "$T/art"
check "borrado: borra el journal del runner" test ! -e "$T/home/journal.db"
check "borrado: borra los logs del runner" test ! -e "$T/home/logs"
check "borrado: borra los worktrees del runner" test ! -e "$T/home/workspaces"
check "borrado: conserva otros ficheros del home del runner" test -f "$T/home/notas-mias.txt"
check "borrado: quita las ramas skynet/* y refs/skynet/* y conserva el resto" \
  test "$(branches "$T/case/valido")" = "refs/heads/feature/mia refs/heads/main refs/notes/mias "
check "borrado: poda el worktree" test "$(git -C "$T/case/valido" worktree list | wc -l)" = 1
check "borrado: conserva lo sin confirmar" test -f "$T/case/valido/sin-confirmar.txt"
check "borrado: no toca la carpeta sin Git" test -f "$T/case/sin-git/fichero.txt"
check "borrado: no toca el .git roto" grep -q "gitdir: $T/no-existe" "$T/case/git-roto/.git"
check "borrado: no toca el repositorio de la subcarpeta" \
  test "$(branches "$T/case/subcarpeta")" = "refs/heads/feature/mia refs/heads/main refs/heads/skynet/TK-1/abc refs/notes/mias refs/skynet/snapshots/abc "
check "borrado: vuelve a levantar PostgreSQL" grep -q 'up -d --wait postgres' "$T/docker.log"

echo "· Borrado real solo con rutas inválidas"
setup inexistente sin-git
code=0
run_reset $'borrar\n' --runner || code=$?
check "solo inválidas: termina con 0 (fue $code)" test "$code" = 0
check "solo inválidas: avisa de las dos" test "$(grep -c 'Aviso: se omite' "$T/out.txt")" = 2

echo
echo "reset-local.sh: $passed comprobaciones bien, $failures mal."
((failures == 0))
