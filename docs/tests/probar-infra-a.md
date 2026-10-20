# Probar INFRA-A en local

INFRA-A permite que `scripts/reset-local.sh` respete la configuración estándar de Docker Compose cuando una instalación usa varios ficheros Compose o un fichero de entorno protegido.

No cambia la base de datos, el control plane, el runner ni la web.

> **Para despliegues automáticos:** todos los pasos son no destructivos. No ejecutes el reset real.

## 1. Prueba regresiva aislada

```bash
scripts/tests/reset-local.test.sh
```

Debe terminar con:

```text
reset-local.sh: 87 comprobaciones bien, 0 mal.
```

La primera prueba comprueba que, al definir `COMPOSE_FILE` y `COMPOSE_ENV_FILES`, el script conserva ambas variables y no añade por su cuenta `-f deploy/docker-compose.yml`.

## 2. Sintaxis y formato

```bash
bash -n scripts/reset-local.sh scripts/tests/reset-local.test.sh
git diff --check
```

Ambos comandos deben terminar con código 0 y sin errores.

## 3. Instalación con override, sin borrar datos

Define los ficheros apropiados para la instalación y ejecuta únicamente el modo de simulación:

```bash
COMPOSE_FILE=deploy/docker-compose.yml:/ruta/al/override.yml \
COMPOSE_ENV_FILES=/ruta/al/entorno-protegido.env \
scripts/reset-local.sh --dry-run --runner
```

Debe listar lo que borraría, terminar con `(--dry-run: no se ha borrado nada)` y devolver código 0. Las llamadas a Docker Compose deben usar la combinación indicada por `COMPOSE_FILE`; el script no debe intentar sustituirla por el fichero base.

## 4. Compatibilidad sin variables

```bash
env -u COMPOSE_FILE -u COMPOSE_ENV_FILES scripts/reset-local.sh --dry-run --runner
```

Debe conservar el comportamiento anterior: usar `deploy/docker-compose.yml`, no modificar datos y terminar con código 0.
