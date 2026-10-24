#!/usr/bin/env bash
# Decide qué E2E lanza el workflow E2E (.github/workflows/e2e.yml). Lee por la entrada estándar los
# ficheros que cambia la PR (uno por línea) y escribe en la salida estándar `rapidas=`, `lentas=` y
# `matriz=` (JSON para `strategy.matrix`), listas para añadir a $GITHUB_OUTPUT.
#
#   git diff --name-only BASE...HEAD | EVENTO=pull_request ETIQUETAS=e2e-completo scripts/ci/e2e-alcance.sh
#
# - Fuera de una PR (push a main, workflow_dispatch) corren todas.
# - En una PR con la etiqueta `e2e-completo` corren todas, toque lo que toque.
# - En una PR sin ella corren solo las rápidas, y solo si toca algo que afecta a las E2E.
set -euo pipefail

EVENTO=${EVENTO:-}
ETIQUETAS=${ETIQUETAS:-}

# Rutas que pueden cambiar el resultado de las E2E. Los .md no cuentan en ningún sitio.
RELEVANTES='^(control-plane|runner|protocol|tools|web|fixtures|build-logic|gradle)/'
RELEVANTES+='|^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$'
RELEVANTES+='|^scripts/e2e[^/]*\.sh$|^scripts/ci/e2e-alcance\.sh$|^\.github/workflows/e2e\.yml$'

toca_e2e() {
  grep -v '\.md$' | grep -Eq "$RELEVANTES"
}

if [[ $EVENTO != pull_request ]]; then
  rapidas=true lentas=true
elif grep -qx 'e2e-completo' <<< "${ETIQUETAS//,/$'\n'}"; then
  rapidas=true lentas=true
elif toca_e2e; then
  rapidas=true lentas=false
else
  rapidas=false lentas=false
fi

# Las rápidas en 6 shards y las @lento (una de 4 a 10 minutos de límite cada una) en 4, para que
# ningún shard cargue con todas las pesadas. Cada job gasta ~2 min en preparar el entorno; con 6
# shards de rápidas ninguno pasa de ~7 min.
entradas=()
if [[ $rapidas == true ]]; then
  for s in 1 2 3 4 5 6; do entradas+=("{\"nombre\":\"rápidas $s/6\",\"args\":\"--grep-invert @lento --shard=$s/6\"}"); done
fi
if [[ $lentas == true ]]; then
  for s in 1 2 3 4; do
    entradas+=("{\"nombre\":\"lentas $s/4\",\"args\":\"--grep @lento --pass-with-no-tests --shard=$s/4\"}")
  done
fi
matriz=$(IFS=,; echo "{\"include\":[${entradas[*]}]}")

echo "rapidas=$rapidas"
echo "lentas=$lentas"
echo "matriz=$matriz"
