# Probar AG-B: archivos generados en la web

AG-B ([#147](https://github.com/guilu/skynet/issues/147), cierra [#145](https://github.com/guilu/skynet/issues/145)) enseña en la web los ficheros que una fase declara en `artifacts`, que AG-A ya guarda como `DECLARED_FILE`.

Qué cambia:
- **Pestaña Artefactos:** nueva sección «Archivos generados», entre los cambios y los logs. Lista solo los `DECLARED_FILE` (el último de cada ruta), por ruta, con su tamaño y tipo.
  - **Descargar** baja el fichero entero por `GET /api/artifacts/{id}/download`, con su nombre base. Un artefacto recortado no ofrece la descarga.
  - **Abrir** lo enseña dentro de Skynet cuando hay una vista segura y pesa como mucho 8 MB:
    - Markdown formateado con `react-markdown` y GFM (tablas, listas de tareas). Su HTML no se interpreta. Los enlaces solo pueden ser `http`, `https` o `mailto`, y se abren en otra pestaña con `noopener noreferrer`. Las imágenes no se cargan: salen como «[imagen: texto]». «Ver como texto» enseña el original.
    - JSON y texto, en el visor de código.
    - PNG, JPEG, GIF y WebP, como imagen pedida a la propia descarga. No hace falta tocar la CSP.
  - Los PDF dicen que se abren con el visor del navegador al descargarlos. Los SVG (pueden llevar scripts) y los demás binarios se ofrecen solo para descargar.
- **Pestaña Verificación:** el check `artifacts` lista debajo los ficheros que no se guardaron, con su motivo («`docs/plano.pdf` · no se guardó: Supera el máximo de 10 MB por fichero»).
- **API:** `CheckResult` lleva `omitted` (`[{path, reason}]`, vacío si no hay). Contratos regenerados.
- **Dependencias web:** `react-markdown` y `remark-gfm`.

No hay migraciones.

## 1. Tests

```bash
./gradlew build --no-daemon
cd web && npm ci && npx vitest run && npx tsc -b --noEmit && npm run lint && npx prettier --check src e2e
```

Los de AG-B:
- `GeneratedFiles.test.tsx`:
  - solo salen los ficheros declarados, por ruta, con su enlace de descarga y su nombre; lo recortado no se descarga;
  - el Markdown se abre sin `script`, `img` ni `iframe`, sin el enlace `javascript:`, con los `https` en otra pestaña y «Ver como texto»;
  - las imágenes se piden a la descarga; los PDF y los binarios no se abren;
  - la verificación dice qué ficheros no se guardaron;
  - reglas: el último de cada ruta, los tipos con vista previa (el SVG no) y los enlaces permitidos.
- `contracts.test.ts` y `ContractIT`: `omitted` en `CheckResult` y un `DECLARED_FILE` en `artifacts.json`.
- E2E `web/e2e/files.e2e.ts` (con `scripts/e2e.sh`):
  - una fase `command` escribe un Markdown declarado y una nota sin declarar;
  - en Artefactos sale solo el Markdown, se abre formateado sin ejecutar su `<script>` y pasa axe;
  - se descarga entero y sigue descargándose igual después de limpiar el worktree.

## 2. Ejecución real

> **No uses el repositorio `skynet`** para esta prueba. Se hace solo con `demo-repo`, del proyecto DEMO.

1. En la web, abre el proyecto **DEMO** y crea un trabajo, o reutiliza uno que ya exista.
2. Lanza el workflow `conocer-repositorio` desde ese trabajo. Como repositorio, selecciona **exclusivamente `demo-repo`**, el registrado en `/home/diegobarrioh/code/demo-repo`. No selecciones ningún otro.
3. Espera a que termine la fase que declara `artifacts: [docs/skynet/mapa.md]`. Comprueba en la cabecera de la ejecución que el repositorio es `demo-repo`.
4. Abre esa fase y su pestaña **Artefactos**:
   - «Archivos generados» lista `docs/skynet/mapa.md` con su tamaño y `text/markdown`, y nada más que lo declarado. Los demás cambios siguen en «Cambios».
   - **Abrir** lo enseña formateado: títulos, listas y tablas. **Ver como texto** enseña el Markdown original y **Ver formateado** vuelve.
   - Si el mapa tiene enlaces `https`, se abren en otra pestaña.
5. Pulsa **Descargar**. El navegador guarda `mapa.md` y su contenido coincide con el del worktree (`diff ~/Descargas/mapa.md <worktree>/docs/skynet/mapa.md`; la ruta del worktree está en el evento `agent.workspace.ready`).
6. En la pestaña **Verificación**, el check `artifacts` pasa y no lista ningún fichero sin guardar.
7. Limpia el worktree (menú «Más acciones» de la ejecución → archivar, y después eliminar el worktree, o con `POST /api/workflow-runs/<id>/workspaces/cleanup`). Recarga la página: el fichero sigue en «Archivos generados», se abre y se descarga igual.
8. Con las herramientas del navegador abiertas, comprueba que no hay errores de CSP en la consola en ningún paso.

## 3. Markdown hostil e imágenes (opcional)

1. Con un workflow propio en DEMO/`demo-repo`, una fase `command` que escriba y confirme `docs/skynet/hostil.md` con `<script>alert(1)</script>`, `<img src=x onerror=alert(1)>`, `[x](javascript:alert(1))` y `![logo](https://example.com/logo.png)`, y lo declare en `artifacts`.
   - Al abrirlo no salta ningún `alert`, `x` no es un enlace, la imagen sale como «[imagen: logo]» y en la pestaña Red no hay ninguna petición a `example.com`.
2. Si la fase declara además un PNG pequeño, **Abrir** lo enseña como imagen. Un PDF solo ofrece **Descargar**, con la nota del visor del navegador.
3. Con el runner arrancado con `SKYNET_ARTIFACT_FILE_MAX_MB=1` y un fichero declarado de 2 MB, la pestaña Verificación lo lista bajo `artifacts` con «no se guardó: Supera el máximo de 1 MB por fichero», y no sale en «Archivos generados».
