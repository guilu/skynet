# Probar M6-E en local

M6-E cierra el MVP: Docker Compose revisado (fichero `.env`, logs con rotación, base de datos solo en local), el runner instalado como servicio (systemd o launchd), una prueba de humo de la instalación completa y la checklist de los 12 criterios del MVP para la prueba con Claude de verdad.

## 1. Docker Compose

```bash
cp deploy/.env.example deploy/.env
# Edita deploy/.env: SKYNET_ADMIN_PASSWORD=secreto y SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner>
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

1. Arranca sin pasar variables en la línea de órdenes: las lee de `deploy/.env`. `docker compose -f deploy/docker-compose.yml ps` muestra los tres servicios `healthy`.
2. Sin `SKYNET_ADMIN_PASSWORD` en `deploy/.env`, `up --wait` falla y `docker compose … logs api` explica que falta la contraseña.
3. PostgreSQL solo escucha en local: `docker compose -f deploy/docker-compose.yml port postgres 5432` da `127.0.0.1:5432`.
4. Solo PostgreSQL, para desarrollar, sigue funcionando sin `.env`: `docker compose -f deploy/docker-compose.yml up -d`.

## 2. Prueba de humo de la instalación

Con la aplicación del paso 1 y sin otros runners conectados:

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> scripts/compose-smoke.sh
```

Termina con «OK: el runner se registró por http://localhost:8081, la ejecución terminó «Completada» y el diff está subido.» En la web aparece el proyecto «Humo de Compose» con esa ejecución. CI hace lo mismo en el job «Docker Compose (app)».

## 3. Runner como servicio

Sigue el README, «Instalar el runner como servicio», con `SKYNET_URL=http://localhost:8081` y el secreto en `~/.config/skynet-runner/runner.env`.

1. `~/.local/share/skynet-runner/skynet-runner.sh` en primer plano se registra («Registrado como …»); para con Ctrl+C.
2. **Linux:** `systemctl --user enable --now skynet-runner`. `systemctl --user status skynet-runner` está `active (running)` y Runners lo muestra «En línea» con la versión de Claude Code.
   **macOS:** carga el plist con `launchctl bootstrap …`. `launchctl print gui/$(id -u)/dev.skynet.runner` dice `state = running` y `~/Library/Logs/skynet-runner.log` muestra la conexión.
3. Reinicia el equipo: el runner vuelve solo (en Linux sin entrar, gracias a `loginctl enable-linger`; en macOS al entrar con tu usuario).
4. Mata el proceso de golpe (`pkill -9 -f dev.skynet.runner.RunnerMain`): el servicio lo arranca otra vez a los 10 s. Si lo paras tú (`systemctl --user stop` o `launchctl bootout`), no.

## 4. Prueba real con Claude

Sigue [`docs/mvp-checklist.md`](../mvp-checklist.md) con el runner del paso 3 usando `claude` (sin `SKYNET_CLAUDE_BIN`). Marca los 12 criterios y las comprobaciones de cierre, y apunta el resultado en su tabla de registro. Con todo marcado se cierra la issue #9.
