# Probar DL-E - Interfaz de despliegues

DL-E (#241) es la cuarta PR del hito DL. Enseña en la web lo que DL-A a DL-C ya guardan y comprueban. Lo cuenta [`docs/despliegues.md`](../despliegues.md) §5.

- **Proyecto → Despliegues**, una tarjeta por repositorio:
  - cada entorno con lo desplegado ahora (SHA, rama, PR, URL y horas);
  - su estado y su salud en **dos píldoras** distintas;
  - el último pedido aparte y sin salud si no es lo desplegado;
  - el historial de los últimos 20;
  - el botón «Reconciliar con GitHub».
- **Detalle de un despliegue** (al pulsar su SHA): los datos, la salud de ese SHA con cada intento como evidencia, «Volver a comprobar» y los estados recibidos.
- **Proyecto → Configuración**, en la ficha del repositorio: la sección **Salud de los despliegues** para crear, editar y quitar la comprobación de cada entorno.

Solo cambia la web, sus tests y un test de contrato del backend (`DeploymentContractIT`, que genera los JSON de `fixtures/contracts`). No hay migración, ni API nueva, ni cambios de seguridad.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes. Lo que se crea está en el entorno `prueba-dl-e` y se borra al final.

## 1. Pruebas automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npx vitest run src/Deployments.test.tsx src/contracts.test.ts
cd .. && SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*DeploymentContractIT'
```

`Deployments.test.tsx`:
- el entorno enseña lo desplegado (`99179be`, enlace a `github.com/guilu/skynet/pull/245`) con «Desplegado» y «Sano» por separado, y el último pedido que falló (`abcdef0`) con «Falló» y **sin** píldora de salud: «Sano» sale una sola vez;
- en el historial, el sustituido no enseña salud;
- el detalle enseña los dos intentos («Intento 1: Código malo», «Intento 2: Bien»), el SHA completo y el estado recibido, y «Volver a comprobar» hace el `POST`;
- sin despliegues explica de dónde llegan, y «Reconciliar con GitHub» dice «3 despliegues y 5 estados revisados, 1 cambio aplicado»;
- la ficha del repositorio lista la comprobación y crea otra con el `PUT` y el cuerpo esperados;
- `gitHubRepo` reconoce las URL remotas https y ssh de GitHub.

`contracts.test.ts` comprueba que los tipos de `api.ts` tienen las mismas claves que las vistas reales (`deployments`, `deployment-environments`, `deployment-detail`, `health-checks`, `deployment-reconciled`).

## 2. Recorrido en el entorno desplegado

Con Skynet desplegado y un repositorio cualquiera de un proyecto (su id en `REPO` y el del proyecto en `PROJECT`). Se crean dos despliegues a mano en el entorno `prueba-dl-e`: uno desplegado y, después, uno que falla.

```bash
B=http://localhost:8080; AUTH=admin:<contraseña>
PSQL="docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc"
$PSQL "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, ref, status, requested_at, finished_at, status_at, created_at, updated_at)
 VALUES ('00000000-0000-0000-0000-00000000d1e1', '$REPO', 'prueba-dl-e', 'dle-1', repeat('1', 40), 'main', 'SUCCEEDED', now() - interval '1 hour', now() - interval '1 hour', now() - interval '1 hour', now(), now()),
        ('00000000-0000-0000-0000-00000000d1e2', '$REPO', 'prueba-dl-e', 'dle-2', repeat('2', 40), 'roto', 'FAILED', now(), now(), now(), now(), now())"
$PSQL "UPDATE deployment SET error = 'docker compose pull falló' WHERE id = '00000000-0000-0000-0000-00000000d1e2'"
```

Abre `http://localhost:8080/projects/$PROJECT?tab=deployments` (o el proyecto → pestaña **Despliegues**):

1. La tarjeta del repositorio tiene el entorno **prueba-dl-e** con `1111111` y la píldora «Desplegado». La salud dice «Salud sin comprobar»: el entorno aún no tiene comprobación.
2. Debajo, «Último pedido: `2222222`» con «Falló» y **sin** píldora de salud.
3. En el historial salen los dos. Pulsa `2222222`: el detalle enseña «Error: docker compose pull falló», «Solo se comprueba lo desplegado con éxito» y no tiene «Volver a comprobar».

Comprobación de salud desde la web:

4. Ve a **Configuración**. En la ficha del repositorio, **Salud de los despliegues → Nueva comprobación**: entorno `prueba-dl-e`, URL `http://localhost:8080/actuator/health`, 2 intentos. Guarda: sale en la lista con «2 intentos cada 10 s, tras 15 s».
5. Vuelve a **Despliegues**, pulsa `1111111` y **Volver a comprobar**. En unos segundos la salud pasa de «Comprobando» a «Sano», con «Intento 1: Bien» y HTTP 200 como evidencia. La tarjeta del entorno enseña «Sano» junto a `1111111`, nunca junto a `2222222`.
6. Edita la comprobación con la URL `http://localhost:1/nada` y vuelve a comprobar: tras los 2 intentos, «No sano» con los intentos en «Error»; el despliegue sigue «Desplegado» (no se revierte nada).
7. Si el repositorio es de GitHub y la App tiene **Deployments: lectura**, **Reconciliar con GitHub** dice cuántos despliegues y estados revisó. Sin la App, sale el error 409 en la tarjeta.

Accesibilidad y móvil:

8. Repite 1-3 con el navegador a 390 px de ancho, en tema claro y en oscuro: nada se sale de la pantalla, el historial se desplaza dentro de su tabla y el detalle sube desde abajo.
9. Con el teclado: Tab llega a cada SHA y a los botones; Intro abre el detalle y Escape lo cierra y devuelve el foco.
10. Si hay un proyecto archivado, abre su pestaña Despliegues: se ve, pero sin «Reconciliar», «Volver a comprobar» ni botones en la ficha. No archives uno real para probarlo.

## 3. Limpieza y checkout limpio

```bash
$PSQL "DELETE FROM deployment WHERE environment = 'prueba-dl-e'"
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -X DELETE $B/api/repositories/$REPO/health-checks/prueba-dl-e   # 204
git status --porcelain   # vacío
```
