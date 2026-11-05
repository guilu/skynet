# Probar DL-A - Modelo de despliegues y persistencia

DL-A (#237) es la primera PR del hito DL (Deployment Lifecycle). Guarda los despliegues de cada repositorio en cada entorno y los enseña por la API. Todavía **no recibe** eventos: eso llega con DL-B (GitHub Deployments). Lo cuenta [`docs/despliegues.md`](../despliegues.md).

- **Modelo**: dos ejes separados.
  - Estado: `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED` o `SUPERSEDED`.
  - Salud: `UNKNOWN`, `CHECKING`, `HEALTHY`, `UNHEALTHY` o `TIMEOUT`; siempre `UNKNOWN` hasta DL-C.
- **Reglas**:
  - idempotente por `externalId` (despliegue y estado);
  - un estado más viejo que llega tarde se guarda sin aplicarse;
  - un despliegue que termina bien deja superados a los anteriores del mismo entorno;
  - los cambios de un entorno se serializan.
- **API** (solo lectura):
  - `GET /api/repositories/{id}/deployments`;
  - `GET /api/repositories/{id}/environments`;
  - `GET /api/deployments/{id}`.
- **Eventos**: `deployment.created`, `deployment.status` y `deployment.superseded`.
- **Migración `V45`**: tablas `deployment` y `deployment_status`. Se aplica sola al arrancar.

No cambia nada de lo que ya había: ni la política de aprobación y fusión, ni el despliegue de Hermes, ni el aviso por Telegram.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes. La sección 3 crea filas de prueba en el entorno `prueba-dl-a` y las borra al final.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*DeploymentsIT' --tests '*MigrationsIT' --tests '*ModularityTest'
```

`DeploymentsIT`:
- un despliegue pasa por sus estados una vez, y repetir el despliegue o el estado no crea nada;
- un estado viejo que llega tarde queda con `applied: false`, y a igual hora gana el final;
- el error se guarda con los secretos ocultos;
- un despliegue nuevo que termina bien deja superados a los anteriores, también cuando el más viejo termina después. Uno que falla no cambia lo desplegado, y un despliegue superado ya no cambia;
- los entornos son independientes;
- cuatro despliegues que terminan a la vez en el mismo entorno dejan exactamente uno desplegado, el más nuevo;
- la API filtra por SHA (prefijo) y da 400 con un SHA inválido y 404 con un id inexistente;
- se rechazan el SHA corto, un entorno con espacios, una URL con usuario y el estado `SUPERSEDED` desde fuera.

`MigrationsIT` aplica `V45` sobre una base vacía.

## 2. La API en el entorno desplegado

Con Skynet desplegado y un repositorio cualquiera dado de alta (su id en `REPO`):

```bash
B=http://localhost:8080; AUTH=admin:<contraseña>
curl -s -u $AUTH $B/api/repositories/$REPO/deployments      # []
curl -s -u $AUTH $B/api/repositories/$REPO/environments     # []
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH "$B/api/repositories/$REPO/deployments?sha=xyz"   # 400
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH $B/api/deployments/00000000-0000-0000-0000-000000000000   # 404
curl -s -o /dev/null -w '%{http_code}\n' $B/api/repositories/$REPO/deployments   # 401 sin sesión
```

## 3. Lectura de datos reales del modelo

Crea dos despliegues en el entorno `prueba-dl-a`: uno viejo superado por uno nuevo. Luego léelos por la API.

```bash
PSQL="docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc"
$PSQL "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, ref, status, requested_at, created_at, updated_at, finished_at)
 VALUES ('00000000-0000-0000-0000-00000000d1a1', '$REPO', 'prueba-dl-a', 'dla-1', repeat('1', 40), 'main', 'SUCCEEDED', now() - interval '1 hour', now(), now(), now() - interval '50 minutes'),
        ('00000000-0000-0000-0000-00000000d1a2', '$REPO', 'prueba-dl-a', 'dla-2', repeat('2', 40), 'main', 'SUCCEEDED', now() - interval '10 minutes', now(), now(), now() - interval '5 minutes');
 UPDATE deployment SET status = 'SUPERSEDED', superseded_by = '00000000-0000-0000-0000-00000000d1a2' WHERE id = '00000000-0000-0000-0000-00000000d1a1';"
curl -s -u $AUTH $B/api/repositories/$REPO/environments
curl -s -u $AUTH "$B/api/repositories/$REPO/deployments?environment=prueba-dl-a&sha=1111111"
curl -s -u $AUTH $B/api/deployments/00000000-0000-0000-0000-00000000d1a1
```

Esperado:
- `environments`: `prueba-dl-a` con `deployed.externalId = "dla-2"`, `latest.externalId = "dla-2"` y `health: "UNKNOWN"`;
- el filtro por SHA: solo `dla-1`, con `status: "SUPERSEDED"` y `supersededBy` el id de `dla-2`;
- el detalle: `statuses: []`.

Casos negativos: la base rechaza un SHA corto y un estado inventado.

```bash
$PSQL "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, requested_at, created_at, updated_at) VALUES (gen_random_uuid(), '$REPO', 'prueba-dl-a', 'dla-x', 'abc', now(), now(), now())"   # viola el CHECK de commit_sha
$PSQL "UPDATE deployment SET status = 'DEPLOYED' WHERE external_id = 'dla-1'"   # viola el CHECK de status
```

Limpieza:

```bash
$PSQL "DELETE FROM deployment WHERE environment = 'prueba-dl-a'"
```

## 4. Checkout limpio

```bash
git status --porcelain   # vacío
```
