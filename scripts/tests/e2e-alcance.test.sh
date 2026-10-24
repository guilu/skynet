#!/usr/bin/env bash
# Pruebas de scripts/ci/e2e-alcance.sh: qué E2E lanza cada evento según los ficheros y etiquetas.
#
#   scripts/tests/e2e-alcance.test.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT=$ROOT/scripts/ci/e2e-alcance.sh
failures=0 passed=0

# espera descripción rápidas lentas evento etiquetas ficheros...
espera() {
  local what=$1 rapidas=$2 lentas=$3 evento=$4 etiquetas=$5; shift 5
  local out
  out=$(printf '%s\n' "$@" | EVENTO=$evento ETIQUETAS=$etiquetas "$SCRIPT")
  if grep -qx "rapidas=$rapidas" <<< "$out" && grep -qx "lentas=$lentas" <<< "$out"; then
    passed=$((passed + 1))
  else
    echo "  ✗ $what: esperaba rapidas=$rapidas lentas=$lentas y salió:" >&2
    sed 's/^/      /' <<< "$out" >&2
    failures=$((failures + 1))
  fi
}

# Fuera de una PR corren todas.
espera 'push a main' true true push '' docs/a.md
espera 'workflow_dispatch' true true workflow_dispatch ''

# PR que no toca nada de las E2E.
espera 'solo docs' false false pull_request '' docs/tests/probar-x.md README.md
espera 'un .md dentro de web/' false false pull_request '' web/README.md
espera 'deploy y workflows iniciales' false false pull_request '' deploy/docker-compose.yml workflows/revision.yaml
espera 'scripts que no son de E2E' false false pull_request '' scripts/reset-local.sh scripts/seed-workflows.sh
espera 'ci.yml no lanza las E2E' false false pull_request '' .github/workflows/ci.yml
espera 'PR sin ficheros' false false pull_request ''

# PR que toca código: solo las rápidas.
for f in control-plane/src/A.java runner/src/B.java protocol/x.json tools/fake-claude/C.java \
  web/src/App.tsx web/e2e/run.e2e.ts fixtures/claude/a.jsonl build-logic/x.kts gradle/libs.versions.toml \
  build.gradle.kts settings.gradle.kts gradle.properties scripts/e2e.sh scripts/e2e-service.sh \
  scripts/ci/e2e-alcance.sh .github/workflows/e2e.yml; do
  espera "toca $f" true false pull_request '' docs/a.md "$f"
done
espera 'otra etiqueta no cuenta' true false pull_request 'bug,documentation' web/src/App.tsx
espera 'etiqueta con nombre parecido' false false pull_request 'e2e-completo-no' docs/a.md

# La etiqueta e2e-completo lanza todas, toque lo que toque.
espera 'e2e-completo con código' true true pull_request 'bug,e2e-completo' web/src/App.tsx
espera 'e2e-completo solo con docs' true true pull_request 'e2e-completo' docs/a.md

# La matriz: 6 shards de rápidas y 4 de lentas, JSON válido.
matriz() { printf '%s\n' "$@" | EVENTO=${EVENTO:-pull_request} "$SCRIPT" | sed -n 's/^matriz=//p'; }
check_matriz() { # descripción entradas ficheros...
  local what=$1 n=$2; shift 2
  local got
  got=$(matriz "$@" | jq '.include | length') || got=invalido
  if [[ $got == "$n" ]]; then passed=$((passed + 1)); else
    echo "  ✗ $what: esperaba $n entradas y salieron $got" >&2
    failures=$((failures + 1))
  fi
}
check_matriz 'matriz vacía' 0 docs/a.md
check_matriz 'matriz de rápidas' 6 web/src/App.tsx
EVENTO=push check_matriz 'matriz completa' 10
if EVENTO=push matriz | jq -e '[.include[].args] | map(select(test("@lento"))) | length == 10' > /dev/null \
  && EVENTO=push matriz | jq -e '[.include[] | select(.nombre | startswith("lentas"))][0].args | test("--grep @lento")' > /dev/null; then
  passed=$((passed + 1))
else
  echo "  ✗ las rápidas excluyen @lento y las lentas lo eligen" >&2
  failures=$((failures + 1))
fi

echo "e2e-alcance: $passed bien, $failures mal"
[[ $failures == 0 ]]
