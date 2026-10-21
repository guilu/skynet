# Probar AG-A: captura y descarga de archivos declarados

AG-A ([#146](https://github.com/guilu/skynet/issues/146), parte de [#145](https://github.com/guilu/skynet/issues/145)) guarda como artefactos independientes los ficheros que una fase declara en `artifacts`, y añade su descarga por id. La web todavía no los enseña: eso llega en AG-B. Aquí se prueba con la API.

Qué cambia:
- **Runner:** en la verificación de una fase con `artifacts`, cada fichero que casa con un glob declarado (y nada más) se sube como `DECLARED_FILE`. Su nombre es la ruta relativa, y los metadatos llevan `path`, `glob`, `mediaType` y `size`. Va ligado a la verificación que lo produjo.
- **Seguridad:** solo cuentan los ficheros regulares y los enlaces simbólicos cuyo destino real es un fichero regular dentro del worktree y fuera de `.git`. Un enlace que sale del worktree, una FIFO, un socket o un dispositivo no se suben y ya no cuentan como «existe». Antes, un enlace a `/etc/passwd` hacía pasar el check.
- **Límites:** `SKYNET_ARTIFACT_FILE_MAX_MB` (10), `SKYNET_ARTIFACT_STAGE_MAX_MB` (50) y `SKYNET_ARTIFACT_STAGE_MAX_FILES` (50). Lo que no cabe se omite entero y aparece en `omitted` del resultado del check, con su motivo.
- **MIME:** sale de la extensión, con una lista cerrada. El HTML se trata como texto. El texto se redacta y los binarios no se tocan.
- **Control plane:** `GET /api/artifacts/{id}/download` devuelve el artefacto entero con `Content-Disposition: attachment` (nombre base saneado, `filename` ASCII y `filename*` UTF-8) y `X-Content-Type-Options: nosniff`. Necesita sesión. Las imágenes y los PDF conservan su tipo, el texto sale como `text/plain` o JSON, y lo demás como `application/octet-stream`. No acepta rutas.

No hay migraciones ni dependencias nuevas. El tipo de artefacto es texto libre en la base de datos.

## 1. Tests

```bash
./gradlew build --no-daemon
```

Los de AG-A:
- `DeclaredFilesTest`:
  - solo se seleccionan los ficheros declarados, y varios por patrón, cada uno con su ruta;
  - las rutas fuera del worktree no casan nunca y `.git` queda excluido;
  - se omiten los enlaces externos, los enlaces a `.git` y las FIFOs, mientras que los enlaces internos leen su destino;
  - se aplican los límites por fichero, por número y por fase, y los tipos MIME;
- `AgentExecutorTest.aStageVerificationRunsItsChecksWithoutTheRepositoryCommand`: la verificación sube `calc.py` como `DECLARED_FILE`, con su verificación y su contenido exacto;
- `StageChecksTest`: los checks de siempre siguen igual;
- `ArtifactControllerTest`:
  - el nombre de descarga saneado: comillas, `;`, saltos de línea, `..\` y nombres vacíos;
  - el tipo con el que se descarga cada MIME;
- `VerificationAndArtifactsIT.declaredFilesAreDownloadedWholeAsAttachments`:
  - un Markdown se descarga redactado y como adjunto, y un PNG byte a byte;
  - lo que dice ser `text/html` se sirve como texto plano;
  - un id que no existe da 404.

## 2. Ejecución real

1. Lanza `conocer-repositorio` sobre un repositorio cualquiera y espera a que termine la fase que declara `artifacts: [docs/skynet/mapa.md]`.
2. Busca el id del agente de esa fase (en la URL del detalle de la ejecución) y lista sus artefactos:
   ```bash
   curl -s -u admin:$SKYNET_ADMIN_PASSWORD http://localhost:8080/api/agent-runs/<agente>/artifacts | jq '.[] | {id, type, name, mediaType, size, metadata}'
   ```
   Sale un `DECLARED_FILE` con `name: "docs/skynet/mapa.md"`, `mediaType: "text/markdown"` y `metadata.glob: "docs/skynet/mapa.md"`. No sale ningún otro fichero cambiado del worktree: esos siguen en `changes.diff`.
3. Descárgalo:
   ```bash
   curl -s -D - -o mapa.md -u admin:$SKYNET_ADMIN_PASSWORD http://localhost:8080/api/artifacts/<id>/download
   ```
   Las cabeceras llevan `Content-Disposition: attachment; filename="mapa.md"; filename*=UTF-8''mapa.md` y `X-Content-Type-Options: nosniff`, y `mapa.md` coincide con el del worktree.
4. Borra el worktree del runner (o archiva la ejecución y limpia los worktrees) y repite el paso 3: la descarga sigue funcionando.
5. Sin credenciales, `curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/artifacts/<id>/download` da 401.

## 3. Límites y enlaces (opcional)

1. Arranca el runner con `SKYNET_ARTIFACT_FILE_MAX_MB=1` y una fase cuyo prompt cree un fichero declarado de 2 MB. El check `artifacts` pasa, el fichero no aparece como `DECLARED_FILE` y el evento `verification.completed` lleva en ese check `omitted: [{path, reason: "Supera el máximo de 1 MB por fichero"}]`.
2. Si el agente crea `docs/skynet/mapa.md` como enlace a `/etc/hostname`, el check falla con «`docs/skynet/mapa.md` no existe» y no se sube nada.
