# Probar RV-C: runner, secretos y entradas

RV-C ([#136](https://github.com/guilu/skynet/issues/136), parte de [#133](https://github.com/guilu/skynet/issues/133)) revisa los cinco hallazgos de prioridad media de la auditoría de Antigravity. Antes de corregir cada uno lo reproduje con un test que fallaba (RED). Cuatro eran reales y dos puntos ya estaban cubiertos o son así por diseño:

| # | Hallazgo | Cómo se reprodujo (RED) | Corrección |
|---|---|---|---|
| 4 | Permisos del home y de `journal.db` del runner, que guarda el token Bearer | El directorio quedaba en `rwxr-xr-x` y el journal legible por otros usuarios | `Journal.open` deja su directorio (`SKYNET_RUNNER_HOME`) en `0700` y `journal.db`, `-wal` y `-shm` en `0600`, también en un journal que ya existía |
| 5 | Contraseña de admin generada en el log y `DEFAULT_SECRET_ENV` | El log decía «contraseña generada para el usuario 'admin': `<contraseña>`»; `SKYNET_ADMIN_PASSWORD` y las claves de OpenAI/Gemini no estaban entre los secretos que se enmascaran | La contraseña generada va a `data/admin-password` (`SKYNET_ADMIN_PASSWORD_FILE`), creado con `0600`; el log solo dice dónde está. Se enmascaran también `SKYNET_ADMIN_PASSWORD`, `OPENAI_API_KEY`, `CODEX_API_KEY`, `GEMINI_API_KEY` y `GOOGLE_API_KEY` |
| 6 | DTOs del runner y de aprobaciones sin límites; token al reutilizar el nombre del runner | Un registro con un nombre de 10 000 caracteres, saltos de línea, capacidad 1 000 000 o 500 proveedores se aceptaba; también un latido con 20 000 invocaciones y un lote de 5 000 eventos; un cuerpo de 5 MB entraba entero | `RunnerLimits` responde 400 (nombre de 1 a 100 caracteres sin caracteres de control, capacidad de 1 a 100, versiones de hasta 200, hasta 20 proveedores, 1 000 invocaciones, 200 cuotas y 1 000 eventos por lote; el runner manda 100). Una petición JSON del runner de más de `SKYNET_RUNNER_MAX_REQUEST` (32 MB) da 413, con o sin `Content-Length`. **Ya estaba:** el comentario de una decisión de aprobación tiene su límite (`MAX_COMMENT`), y queda un test. **Por diseño:** volver a registrarse con el mismo nombre cambia el token, porque lo necesitan el «Revocar token» y un runner que pierde su journal; quien tiene el secreto de registro ya puede registrar cualquier runner. Ahora `runner.registered` lleva `tokenReplaced` y el log lo avisa |
| 7 | Prompt posicional de `ClaudeCodeProvider` que empieza por `-` | `claude -p "--version"` imprime la versión: el CLI lee el prompt como una opción | El prompt va al final, tras `--`; `fake-claude` también lo entiende |
| 8 | Sin límite de intentos en login, HTTP Basic y registro del runner | Tras 3 contraseñas malas, la buena entraba a la primera | Tras `SKYNET_MAX_FAILED_ATTEMPTS` (10) fallos seguidos desde un origen, ese origen recibe 429 con `Retry-After` durante `SKYNET_LOCKOUT` (5 min), aunque acierte. Un acierto borra los fallos. La web (login y Basic) y el registro del runner se cuentan aparte |

No hay migraciones ni dependencias nuevas, y no cambia la exposición de red. Detrás del nginx de Docker Compose todas las peticiones llegan desde la misma IP, así que el bloqueo es para todos los navegadores a la vez; con un solo usuario es lo esperado.

> **Para despliegues automáticos:** los pasos 1 a 3 no cambian nada fuera de carpetas temporales. El paso 4 bloquea el login durante 5 minutos: hazlo en una instancia de prueba.

## 1. Tests

```bash
./gradlew build --no-daemon
```

Los de RV-C:
- `JournalTest`: el directorio en `rwx------` y los ficheros en `rw-------`, también al reabrir uno con permisos abiertos;
- `SecurityConfigurationTest`: la contraseña generada no sale en el log, está en un fichero `rw-------` y con `SKYNET_ADMIN_PASSWORD` no se escribe ningún fichero;
- `PayloadRedactorTest`: los secretos nuevos se enmascaran por defecto;
- `ClaudeCodeProviderTest` y `FakeClaudeTest`: el prompt va tras `--`, aunque sea `--dangerously-skip-permissions`;
- `RunnerInputLimitsIT`: registro, latido y lote fuera de límites dan 400 sin tocar la base de datos, un cuerpo demasiado grande da 413, un comentario enorme da 400 y volver a registrarse deja `tokenReplaced`;
- `AuthThrottleIT` y `AuthThrottleTest`: 429 tras los fallos de login, Basic y registro, el acierto que borra los fallos y el fin del bloqueo.

## 2. Runner

1. Arranca un runner como siempre y comprueba: `stat -c '%A %n' ~/.skynet-runner ~/.skynet-runner/journal.db*`. Tiene que salir `drwx------` y `-rw-------`.
2. Un runner ya registrado sigue funcionando sin volver a registrarse.

## 3. Contraseña generada

1. Arranca el control plane **sin** `SKYNET_ADMIN_PASSWORD`: el log dice «la contraseña generada para el usuario 'admin' está en …/data/admin-password» y no la muestra.
2. `stat -c '%A' data/admin-password` da `-rw-------`, y con `cat data/admin-password` entras en la web.

## 4. Bloqueo de intentos

1. En la web, entra 10 veces con una contraseña mala: la siguiente, aunque sea la buena, dice que hay demasiados intentos (429).
2. A los 5 minutos entras con la buena.
3. `curl -u admin:mala …` cuenta igual que el login de la web.
