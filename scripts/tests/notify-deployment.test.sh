#!/usr/bin/env bash
# Pruebas de scripts/notify-deployment.sh contra un GitHub falso: lo que manda, los reintentos con
# un 5xx, el fallo con un 4xx, la validación y que el token no sale nunca.
#
#   scripts/tests/notify-deployment.test.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT=$ROOT/scripts/notify-deployment.sh
WORK=$(mktemp -d)
failures=0 passed=0
SHA=4f1c2a9e0b7d3c5e8f6a1b2c3d4e5f60718293a4
TOKEN=ghp_tokenDePruebaQueNoDebeSalir0123456789

# El GitHub falso apunta cada petición en requests.log. Si existe fail-once, la primera petición
# responde 502; si existe reject, todas responden 422.
cat > "$WORK/fake.py" <<'PY'
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
work = sys.argv[1]
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode()
        with open(os.path.join(work, 'requests.log'), 'a') as f:
            f.write(json.dumps({'path': self.path, 'auth': self.headers.get('Authorization'), 'body': json.loads(body)}) + '\n')
        if os.path.exists(os.path.join(work, 'fail-once')):
            os.remove(os.path.join(work, 'fail-once'))
            self.send_response(502); self.end_headers(); return
        if os.path.exists(os.path.join(work, 'reject')):
            self.send_response(422); self.end_headers(); self.wfile.write(b'{"message":"Validation Failed"}'); return
        self.send_response(201); self.send_header('Content-Type', 'application/json'); self.end_headers()
        self.wfile.write(json.dumps({'id': 900001 if self.path.endswith('/deployments') else 950001}).encode())
HTTPServer(('127.0.0.1', int(sys.argv[2])), H).serve_forever()
PY
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 "$WORK/fake.py" "$WORK" "$PORT" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$WORK"' EXIT
for _ in $(seq 50); do (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break; sleep 0.1; done

export GITHUB_TOKEN=$TOKEN GITHUB_API=http://127.0.0.1:$PORT

ok() { passed=$((passed + 1)); }
ko() { echo "  ✗ $1" >&2; failures=$((failures + 1)); }
last() { tail -1 "$WORK/requests.log" | jq -r "$1"; }

# start: imprime el id y manda el SHA exacto, el entorno y la PR.
out=$("$SCRIPT" start guilu/demo-repo "$SHA" skynet/demo-7 12 2>"$WORK/err")
[ "$out" = 900001 ] && ok || ko "start imprime el id (salió '$out')"
[ "$(last .path)" = /repos/guilu/demo-repo/deployments ] && ok || ko "start va a /deployments"
[ "$(last .body.ref)" = "$SHA" ] && ok || ko "start despliega el SHA exacto"
[ "$(last .body.environment)" = omarchy ] && ok || ko "el entorno por defecto es omarchy"
[ "$(last .body.payload.pull_request)" = 12 ] && ok || ko "la PR va en payload.pull_request"
[ "$(last '.body.required_contexts | length')" = 0 ] && ok || ko "sin required_contexts"
[ "$(last .body.auto_merge)" = false ] && ok || ko "sin auto_merge"
[ "$(last .auth)" = "Bearer $TOKEN" ] && ok || ko "manda el token"

# status con URL, y un 502 que se reintenta.
touch "$WORK/fail-once"
DEPLOY_ENVIRONMENT=prod "$SCRIPT" status guilu/demo-repo 900001 success "compose OK" http://omarchy:8081 2>"$WORK/err" \
  && ok || ko "status con un 502 se reintenta y termina bien"
[ "$(grep -c statuses "$WORK/requests.log")" = 2 ] && ok || ko "status se reintentó una vez"
[ "$(last .body.state)" = success ] && ok || ko "status manda el estado"
[ "$(last .body.environment_url)" = http://omarchy:8081 ] && ok || ko "status manda la URL"

# Sin PR ni URL: no los manda.
"$SCRIPT" start guilu/demo-repo "$SHA" >/dev/null 2>&1
[ "$(last '.body.payload | has("pull_request")')" = false ] && ok || ko "sin PR no hay pull_request"

# Un 4xx no se reintenta y falla.
touch "$WORK/reject"
before=$(wc -l < "$WORK/requests.log")
if "$SCRIPT" status guilu/demo-repo 900001 failure x 2>"$WORK/err"; then ko "un 422 falla"; else ok; fi
[ $(($(wc -l < "$WORK/requests.log") - before)) = 1 ] && ok || ko "un 422 no se reintenta"
grep -q "422" "$WORK/err" && ok || ko "el error dice el código"
rm "$WORK/reject"

# Validación local: no llega a GitHub.
before=$(wc -l < "$WORK/requests.log")
"$SCRIPT" start guilu/demo-repo abc 2>/dev/null && ko "un SHA corto falla" || ok
"$SCRIPT" status guilu/demo-repo 900001 deployed 2>/dev/null && ko "un estado inventado falla" || ok
"$SCRIPT" start guilu/demo-repo "$SHA" main doce 2>/dev/null && ko "una PR no numérica falla" || ok
[ "$(wc -l < "$WORK/requests.log")" = "$before" ] && ok || ko "la validación no llama a GitHub"
GITHUB_TOKEN= "$SCRIPT" start guilu/demo-repo "$SHA" 2>/dev/null && ko "sin token falla" || ok

# El token no sale nunca por la salida ni por los errores.
"$SCRIPT" start guilu/demo-repo "$SHA" main 7 >"$WORK/all" 2>&1 || true
touch "$WORK/reject"; "$SCRIPT" start guilu/demo-repo "$SHA" >>"$WORK/all" 2>&1 || true
grep -q "$TOKEN" "$WORK/all" "$WORK/err" && ko "el token aparece en la salida" || ok

echo "notify-deployment: $passed bien, $failures mal"
[ "$failures" -eq 0 ]
