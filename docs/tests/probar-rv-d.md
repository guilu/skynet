# Probar RV-D: despliegue

RV-D ([#137](https://github.com/guilu/skynet/issues/137), parte de [#133](https://github.com/guilu/skynet/issues/133)) corrige los dos hallazgos de prioridad baja de la auditoría de Antigravity. Los dos se confirmaron antes de corregirlos:

| # | Hallazgo | Cómo se confirmó (RED) | Corrección |
|---|---|---|---|
| 9 | nginx como root y sin cabeceras de seguridad | `web/nginx.conf` servido con nginx en local: la respuesta no llevaba ni CSP, ni `X-Content-Type-Options`, ni `X-Frame-Options`, ni `Referrer-Policy`, y sí `Server: nginx/<versión>`. La imagen `nginx:1.29-alpine` arranca el proceso maestro como root y escucha en el 80 | Imagen `nginxinc/nginx-unprivileged:1.29-alpine` (usuario `nginx`, uid 101, puerto 8080; Compose publica `8081:8080`). Cabeceras con `always`: CSP solo del propio origen, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` y `server_tokens off` |
| 10 | Credenciales de PostgreSQL por defecto | El job de Compose de la CI levantaba el perfil `app` sin `SKYNET_DB_PASSWORD`, y la API y PostgreSQL quedaban con `skynet`/`skynet` | Con el perfil `app`, la API no arranca sin `SKYNET_DB_PASSWORD` (`SKYNET_REQUIRE_DB_PASSWORD=true`, como con la contraseña de admin) y `.env.example` la pide en el bloque obligatorio. Solo PostgreSQL (`docker compose up -d`), `e2e.sh` y el control plane fuera de Docker siguen con `skynet`/`skynet`, con PostgreSQL escuchando únicamente en `127.0.0.1` |

La CSP admite estilos en línea (`'unsafe-inline'` en `style-src`), porque la paleta personalizable y Monaco inyectan `<style>`. La comprobé con la web compilada detrás de nginx y un control plane real: el login y todas las páginas, también el editor YAML, cargan sin ninguna violación de CSP. Sin `'unsafe-inline'`, el navegador rechaza esos estilos.

No hay migraciones ni dependencias nuevas, y no cambia la exposición de red.

> **Antes de actualizar una instalación con Docker Compose:** añade `SKYNET_DB_PASSWORD` a `deploy/.env`. La contraseña se fijó al crear el volumen `postgres-data`. Si lo creaste sin ella, pon `SKYNET_DB_PASSWORD=skynet` o cámbiala antes con `docker compose -f deploy/docker-compose.yml exec postgres psql -U skynet -c "ALTER USER skynet PASSWORD '<nueva>'"`.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 y 3 levantan y paran el perfil `app` de Compose.

## 1. Tests

```bash
./gradlew test --no-daemon
```

`ControlPlaneApplicationTest`: con `SKYNET_REQUIRE_DB_PASSWORD=true` no arranca sin `SKYNET_DB_PASSWORD` (vacía o en blanco), y sin ese requisito sigue valiendo el valor de desarrollo.

El job **Docker Compose (app)** de la CI comprueba además que:
- el contenedor `web` no corre como root;
- la web manda las cuatro cabeceras y no dice la versión de nginx;
- la API no arranca sin `SKYNET_DB_PASSWORD`.

## 2. Docker Compose

```bash
# deploy/.env con SKYNET_ADMIN_PASSWORD, SKYNET_DB_PASSWORD y SKYNET_RUNNER_REGISTRATION_TOKEN
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
docker compose -f deploy/docker-compose.yml --profile app exec web id -u     # 101, no 0
curl -sI http://localhost:8081/ | grep -iE 'content-security|x-content|x-frame|referrer|^server'
```

1. En `http://localhost:8081` entra, abre un workflow y su editor YAML y una ejecución con su grafo: todo se ve como antes. En la consola del navegador no aparece ningún «Refused to … Content Security Policy».
2. `scripts/compose-smoke.sh` sigue pasando: el runner se registra a través de la web.

## 3. Sin contraseña de PostgreSQL

1. Quita `SKYNET_DB_PASSWORD` de `deploy/.env` y vuelve a levantar el perfil `app`: la API no arranca, y `docker compose … logs api` dice «Define SKYNET_DB_PASSWORD en deploy/.env».
2. `docker compose -f deploy/docker-compose.yml up -d`, sin perfil, sigue levantando solo PostgreSQL sin `.env`.
