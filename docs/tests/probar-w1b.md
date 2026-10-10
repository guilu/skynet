# Probar W1-B en local

W1-B lleva las **definiciones de workflow** a la web (#58): un menú **Workflows** con la lista, el alta y la página de cada workflow, donde el YAML se edita en Monaco con autocompletado, se valida mientras se escribe y se publica. Nada se ejecuta todavía con estas definiciones: eso llega en W2.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

No hay migraciones nuevas. La web añade `monaco-yaml` (autocompletado de YAML a partir del JSON Schema). El backend añade `GET /api/workflows/schema`, `key` y `version` opcionales en `POST /api/workflows/validate`, reserva `new`, `schema` y `validate` como id de workflow, y saca los tipos de fase con su nombre del YAML (`agent`).

Abre <http://localhost:8080> y entra con tu usuario.

## 2. Qué mirar

1. **La lista.** En el menú, **Workflows** enseña `adhoc` («Agente suelto») con «v1 · Publicada» y sin borrador. No tiene menú de archivar.
2. **El alta con la plantilla.** Pulsa **Nuevo workflow**:
   - El editor trae una plantilla de dos fases; **Problemas** dice «Sin problemas» y **Qué define** lista `review` y `fix` («Depende de review · Worktree: continúa el de su dependencia»), el dato `foco` y el agente `reviewer`.
   - Escribe `stag` en una línea nueva al principio: sale una lista de sugerencias (`stages`, `agents`…) y, al pasar el ratón por `permissionMode`, su descripción.
3. **Errores mientras se escribe.**
   - Cambia `dependsOn: [review]` por `dependsOn: [reviw]`. En menos de un segundo aparece «1 error»: «No hay ninguna fase `reviw`; ¿quisiste decir `review`?», subrayado en su línea.
   - Pulsa el problema en la lista: el cursor salta a esa línea y columna.
   - Cambia `type: agent` de `fix` por `type: human-approval`: el problema es «Aún no» (llega en W5), en ámbar.
   - Deja `fix` como estaba.
4. **Crear y publicar.**
   - Cambia el `id` a `revisar` y pulsa **Crear borrador**. Lleva a `/workflows/revisar`, con el chip «v1 · Borrador validado» y «aún sin publicar».
   - Cambia algo y pulsa **Ctrl+S** (o **Guardar**): pasa de «Cambios sin guardar» a «Guardado el…».
   - **Publicar…** abre un panel que explica que la versión ya no cambia. Al publicar, el chip pasa a «v1 · Publicada» y el editor queda de solo lectura, sin botones.
5. **Una versión nueva.**
   - **Editar** abre el borrador de la v2 con el mismo YAML. Los chips enseñan v2 y v1; la v1 se sigue pudiendo abrir y leer.
   - Mete un error y guarda: el chip dice «v2 · Borrador con errores» y **Publicar…** está desactivado con «Corrige los errores para publicar».
   - **Descartar borrador…** lo borra: queda solo la v1 y vuelve **Editar**.
6. **Importar.** En un borrador, **Importar archivo…** carga un `.yaml` del disco en el editor (sin guardar todavía). **Ctrl+Z** lo deshace.
7. **Archivar.** En el menú `⋯` de `revisar`, **Archivar**: sale el aviso de archivado, desaparece de la lista y vuelve con el chip **Archivados**. Restáuralo desde el mismo menú.
8. **Actividad.** Con **Actividad** abierta en otra pestaña mientras haces lo anterior, salen «Borrador de revisar v1 creado», «revisar v1 publicado», «Borrador de revisar v2 descartado» y «Workflow revisar archivado».
9. **Ids reservados.** En el alta, `id: new` marca el error «`new` está reservado: elige otro id» y **Crear borrador** responde «No se puede crear el workflow sin un `id` válido».
10. **Modo oscuro y móvil.** El editor sigue el tema; en una ventana estrecha, los problemas y el resumen pasan debajo del editor.

## 3. Pruebas automáticas

```bash
./gradlew build                       # incluye WorkflowDefinitionsIT y ContractIT
cd web && npm run lint && npm run typecheck && npm test
scripts/e2e.sh workflows.e2e.ts       # control plane, runner y Playwright con axe
```
