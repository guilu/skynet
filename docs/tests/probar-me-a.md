# Probar ME-A: modelo, búsqueda y API de memoria

ME-A ([#150](https://github.com/guilu/skynet/issues/150), parte de [#141](https://github.com/guilu/skynet/issues/141)) es la base de la memoria de proyecto, al estilo [engram](https://github.com/Gentleman-Programming/engram).

Qué trae:
- **Observaciones** de un proyecto o de uno de sus repositorios: decisiones, bugs resueltos, descubrimientos, patrones, configuración, trampas y restricciones.
- **Contenido** con la estructura de engram: título, `topicKey` y los campos `what`, `why`, `where` y `learned`, más etiquetas.
- **Temas:** guardar otra vez el mismo `topicKey` en el mismo ámbito crea una versión nueva, y la anterior queda en el historial.
- **Búsqueda** de texto completo de PostgreSQL, con filtros, y revisión por personas: editar, archivar, restaurar y fijar.

Todavía no hay agentes que la usen (ME-B y ME-C) ni pestaña en la web (ME-D): esta PR se prueba por la API.

Hay una migración nueva, la **V27** (`memory_observation`, `memory_observation_revision` y `memory_access`). No hay dependencias nuevas ni cambia la exposición de red. Borrar un proyecto o un repositorio archivado borra también su memoria y los eventos de esa memoria.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. El paso 2 crea un proyecto `MEMO` y lo borra al final.

## 1. Tests

```bash
./gradlew build --no-daemon
```

`MemoryIT` comprueba:
- **Versiones:** el mismo tema crea la versión 2 con su historial; guardar lo mismo no crea versión; el mismo tema en otro ámbito es otra observación.
- **Aislamiento:** cada proyecto y repositorio ve solo lo suyo, y no se puede guardar en el repositorio de otro proyecto.
- **Búsqueda:** por palabras y por trozos del título o del tema, con filtros por tipo y fijadas, y paginación. Sin texto, salen primero las fijadas.
- **Archivado:** una archivada no sale en las búsquedas y deja libre su tema; no se restaura si otra ocupa su tema y no se puede fijar.
- **Edición:** cuando una persona edita lo que guardó un agente, se crea una versión de origen `HUMAN`, y no se puede quitar el tema a otra observación.
- **Secretos:** se guardan enmascarados en la observación, en su historial y en los eventos.
- **Límites:** título, campos, `topicKey`, etiquetas, caracteres de control, búsqueda larga, `RUN_LOG` reservado y proyecto archivado.
- **Borrado:** borrar el proyecto se lleva su memoria y sus eventos.

## 2. API

Con el control plane en marcha, en `http://localhost:8080` y con la contraseña de admin en `$SKYNET_ADMIN_PASSWORD`:

```bash
api() { curl -s -u "admin:$SKYNET_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
P=$(api -X POST localhost:8080/api/projects -d '{"key":"MEMO","name":"Memoria"}' | jq -r .id)
R=$(api -X POST localhost:8080/api/projects/$P/repositories \
      -d '{"name":"demo","localPath":"/tmp/memo-demo"}' | jq -r .id)

# 1. Una decisión del repositorio: 201, versión 1.
api -X POST localhost:8080/api/projects/$P/memory -d "{\"repositoryId\":\"$R\",\"kind\":\"DECISION\",
  \"title\":\"Fusionar con squash\",\"topicKey\":\"git/merge\",\"what\":\"Las PRs van con squash\"}" | jq '{id,version}'

# 2. El mismo tema otra vez: 200, la misma id y la versión 2.
api -X POST localhost:8080/api/projects/$P/memory -d "{\"repositoryId\":\"$R\",\"kind\":\"DECISION\",
  \"title\":\"Fusionar con squash\",\"topicKey\":\"git/merge\",\"what\":\"Squash y borrar la rama\"}" | jq '{id,version}'

# 3. Buscar «squash» y ver el historial (versiones 2 y 1).
api "localhost:8080/api/projects/$P/memory?q=squash" | jq '.total, .items[].title'
ID=$(api "localhost:8080/api/projects/$P/memory?q=squash" | jq -r '.items[0].id')
api localhost:8080/api/memory/$ID/history | jq '.[].version'

# 4. Un secreto se guarda enmascarado («redacted»: true).
api -X POST localhost:8080/api/projects/$P/memory -d '{"kind":"CONFIG","title":"Token",
  "what":"usa ghp_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"}' | jq '{what,redacted}'

# 5. Archivar: deja de salir en la búsqueda; con archived=true, sí.
api -X POST localhost:8080/api/memory/$ID/archive | jq .archivedAt
api "localhost:8080/api/projects/$P/memory?q=squash" | jq .total                 # 0
api "localhost:8080/api/projects/$P/memory?q=squash&archived=true" | jq .total   # 1

# Limpieza
api -X POST localhost:8080/api/projects/$P/archive > /dev/null
api -X DELETE localhost:8080/api/projects/$P | jq .
```

Comprueba también que `GET /api/projects/$P/memory?repository=project` solo devuelve lo que no tiene repositorio, y que `?repository=$R&withProject=true` devuelve lo del repositorio y lo de todo el proyecto.
