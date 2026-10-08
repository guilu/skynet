#!/usr/bin/env bash
# Arranque del runner como servicio (systemd o launchd): carga ~/.config/skynet-runner/runner.env
# y ejecuta el runner instalado en ~/.local/share/skynet-runner.
set -euo pipefail

CONFIG=${SKYNET_RUNNER_CONFIG:-$HOME/.config/skynet-runner/runner.env}
INSTALL=${SKYNET_RUNNER_INSTALL:-$HOME/.local/share/skynet-runner}

if [[ -f $CONFIG ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$CONFIG"
  set +a
else
  echo "No existe $CONFIG (copia deploy/runner/runner.env.example)" >&2
  exit 1
fi

exec "$INSTALL/bin/skynet-runner" "$@"
