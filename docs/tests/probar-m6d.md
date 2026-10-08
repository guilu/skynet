# Probar M6-D en local

M6-D añade métricas al pie del dashboard (24 horas, 7 o 30 días) con cada cifra enlazada a la lista de ejecuciones filtrada, y dos E2E nuevas: axe en las páginas principales y que ningún secreto llega al DOM.

## 1. Arrancar

En la rama de la PR, recompila y arranca todo con fake-claude:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> \
  ./gradlew :control-plane:bootRun
cd web && npm install && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
SKYNET_RUNNER_HOME=/tmp/m6d-runner \
SKYNET_CLAUDE_BIN=$PWD/tools/fake-claude/build/install/fake-claude/bin/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE FAKE_CLAUDE_FIXTURE=02-tools \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Entra en `http://localhost:5173` con `admin` / `secreto`. Lanza tres o cuatro agentes en un repositorio registrado y cancela uno a mitad, para tener ejecuciones completadas y canceladas.

## 2. Métricas del dashboard

1. Abre el Dashboard. Al pie, «Métricas» muestra el periodo «7 días» marcado y las cifras: ejecuciones, completadas, fallidas, canceladas, activas, duración mediana, tokens (entrada / salida) y coste.
2. Debajo, «Ejecuciones por día»: siete barras (las de días sin ejecuciones, vacías). Al pasar el ratón por encima de la de hoy, un recuadro dice el día, cuántas ejecuciones, cuántas completadas, fallidas y canceladas, y el coste. El recuadro no se sale del gráfico, tampoco en la última barra.
3. «Ver como tabla» abre la misma información en una tabla.
4. Pulsa «24 horas»: la URL lleva `?period=24h`, el gráfico pasa a «Ejecuciones por hora» con 24 o 25 barras etiquetadas por hora local, y las ejecuciones de hace un rato caen en la hora actual. «30 días» da 30 o 31 barras. Recargar la página conserva el periodo.
5. Pulsa la cifra de «Canceladas»: lleva a Ejecuciones filtrada por «Cancelada» y con «Creadas desde …» (la fecha de inicio del periodo) y un botón «Quitar». La lista tiene tantas filas como decía la cifra. «Quitar» deja solo el filtro de estado.
6. Lo mismo con «Ejecuciones» (todas las del periodo) y «Completadas».
7. Con el tema oscuro (arriba a la derecha), el gráfico y las cifras se leen igual.

Por API:

```bash
curl -s -u admin:secreto 'http://localhost:8080/api/dashboard/metrics?period=24h&tz=Europe/Madrid' | jq '{total, succeeded, cancelled, medianDurationSeconds, costUsd, buckets: (.buckets | length)}'
curl -s -o /dev/null -w '%{http_code}\n' -u admin:secreto 'http://localhost:8080/api/dashboard/metrics?period=1y'   # 400
```

## 3. Contraste

El verde de «Completada» es algo más oscuro que antes (`#1a7a43`): las insignias, la salud de los runners y «Pasa» en Verificación siguen leyéndose verdes y ahora llegan a 4,5:1 sobre blanco.

## 4. E2E

```bash
sudo -u postgres psql -c "DROP DATABASE IF EXISTS skynet_e2e" -c "CREATE DATABASE skynet_e2e OWNER skynet"
(cd web && npm ci)
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh
```

Pasan las siete, entre ellas las dos nuevas de `web/e2e/review.e2e.ts`:

- «ningún secreto llega al DOM»: el repositorio de prueba lleva un token de GitHub falso en `calc.py` y su `check.sh` imprime una clave de AWS falsa (`E2E_SECRET_GITHUB`, `E2E_SECRET_AWS` en `scripts/e2e.sh`). Ninguna pestaña de la ejecución ni la API de eventos los contiene; el diff y la salida de la verificación muestran `[REDACTED]`.
- «las páginas principales pasan axe»: WCAG 2.1 A y AA sin ningún problema en el login, las pestañas de una ejecución, el dashboard (con las métricas), Ejecuciones, Proyectos, Runners, Actividad, Workflows, un proyecto y un trabajo.

Solo esas dos: `SKYNET_DB_URL=… scripts/e2e.sh review.e2e.ts`.
