# Probar IT-A en local

IT-A hace configurable el puerto del control plane usado por las pruebas E2E. El control plane, el runner y el proxy de Vite deben compartir `E2E_API_PORT`, incluso si el proceso hereda valores distintos para `SERVER_PORT` o `SKYNET_URL`.

## 1. Prueba unitaria y validaciones estáticas

```bash
cd web
npm test -- --run src/vite-config.test.ts
npm run lint
npm run format:check
npm run build
cd ..
bash -n scripts/e2e.sh scripts/e2e-service.sh
git diff --check origin/main...HEAD
```

La prueba unitaria comprueba el puerto alternativo `18084` y conserva `8080` como valor predeterminado.

## 2. E2E real con el puerto 8080 ocupado

Usa un PostgreSQL desechable y un puerto alternativo para el API:

```bash
docker rm -f skynet-it-a-postgres 2>/dev/null || true
docker run -d --name skynet-it-a-postgres \
  -e POSTGRES_DB=skynet \
  -e POSTGRES_USER=skynet \
  -e POSTGRES_PASSWORD=skynet \
  -p 127.0.0.1:55432:5432 \
  postgres:16-alpine

until docker exec skynet-it-a-postgres pg_isready -U skynet -d skynet; do sleep 1; done

E2E_API_PORT=18084 \
SERVER_PORT=19999 \
SKYNET_URL=http://127.0.0.1:19999 \
SKYNET_DB_URL=jdbc:postgresql://127.0.0.1:55432/skynet \
SKYNET_DB_USER=skynet \
SKYNET_DB_PASSWORD=skynet \
./scripts/e2e.sh e2e/providers.e2e.ts
```

Las dos pruebas deben pasar. Los valores heredados `19999` comprueban que el script establece una configuración coherente a partir de `E2E_API_PORT=18084`.

## 3. Limpieza

```bash
docker rm -f skynet-it-a-postgres
```
