# Probar MV-F - Wiki y curador en la web

Tercera y última PR de MV-F (#311), que cierra el hito MV «Memoria viva». Solo cambia la web: la API ya estaba (la wiki es de MV-C y el curador, de MV-E).

- La pestaña **Memoria** del proyecto tiene tres vistas: **Entradas** (la de siempre), **Wiki** y **Curador**. La elegida va en la URL (`view=wiki`, `view=curations`).
- **Wiki:**
  - las páginas, primero las de todo el proyecto (el índice delante) y después las de cada repositorio, con quién las escribió, la versión, cuántas fuentes citan y la fecha;
  - cada página se abre en un panel: su markdown (sin HTML ni imágenes), sus fuentes (una entrada abre su panel; una PR, GitHub) y sus versiones;
  - un enlace a otra página (`[Lo hecho](hecho)`) la abre en el mismo panel;
  - **Regenerar las páginas de Skynet** llama a `POST /api/projects/{id}/wiki/refresh`.
- **Curador:** los informes, el más reciente primero, con el resumen, las páginas que escribió, sus propuestas (las entradas por su título), lo que no se aplicó y cuántas propuestas quedan pendientes. Una pendiente se **acepta** o se **rechaza**; las decididas dicen quién y cuándo.
- La pestaña Memoria de una invocación lista también las **páginas de la wiki** que recibió o leyó, con su versión y cómo (bloque, `mem_context` o `wiki_read`).

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. El curador es fake-claude.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && \
  npx vitest run src/MemoryWiki.test.tsx src/ProjectMemory.test.tsx src/components/run/MemoryTab.test.tsx
```

`MemoryWiki.test.tsx`:
- la wiki agrupa «Todo el proyecto» (Índice primero) y «tokenmeter»; una página del curador dice «del curador» y «v2 · 2 fuentes»;
- el índice se abre, un enlace `javascript:` no sale como enlace y se avisa de que no se edita a mano; su enlace «Arquitectura» abre esa página, con la PR enlazada a GitHub y sus versiones;
- la fuente «Fusionar con squash» abre la entrada en su panel;
- con la wiki vacía, **Regenerar** llama a `…/wiki/refresh` y enseña las páginas;
- el informe del curador tiene el resumen, «1 propuesta pendiente», «Módulos … sin cambios», lo rechazado y el enlace a la ejecución; las entradas de la fusión salen por su título; la rechazada dice «Rechazada por admin» sin botones; **Aceptar** llama a `…/proposals/pr-merge/accept` y vuelve a pedir los informes;
- sin informes, explica cómo lanzar «Curar la memoria».

`MemoryTab.test.tsx` comprueba la sección «Páginas de la wiki» de una invocación, con su enlace a la vista Wiki y «con wiki_read (2 veces)».

## 2. Skynet desplegado

Trabaja sobre el proyecto de `demo-repo`. Para tener un informe del curador, sigue antes [`probar-mv-e.md`](probar-mv-e.md) §2.1 y §2.2 (fake-claude con `FAKE_CLAUDE_STRUCTURED_OUTPUT`), sin aceptar ni rechazar nada todavía.

### 2.1 Wiki

1. Pestaña **Memoria → Wiki**.
   **Esperado:** en «Todo el proyecto», Índice primero, y Lo hecho, Errores conocidos y Decisiones y restricciones «de Skynet»; en `demo-repo`, Arquitectura y Convenciones «del curador». La URL lleva `view=wiki`.
2. Abre **Índice**.
   **Esperado:** el texto formateado, «La regenera Skynet: no se edita a mano» y, en «Otras páginas», Arquitectura y Convenciones.
3. Pulsa **Arquitectura** dentro del índice.
   **Esperado:** el panel pasa a Arquitectura, con «MV-E: demo-repo es una sola aplicación.», la fuente «Entrada · MV-E fusionar con squash» y la versión 1 en Versiones.
4. Pulsa la fuente.
   **Esperado:** se cierra la página y se abre la entrada en su panel.
5. Cierra el panel y pulsa **Regenerar las páginas de Skynet**.
   **Esperado:** la lista se recarga sin errores; las páginas del curador no cambian.

### 2.2 Curador

1. Pestaña **Memoria → Curador**.
   **Esperado:** el informe de la curación de MV-E: «MV-E: dos decisiones…», «2 propuestas pendientes», Arquitectura y Convenciones en «Páginas que escribió» y, desplegando «Lo que no se aplicó», `indice` con su motivo.
2. En la propuesta **Fusionar**, mira las entradas.
   **Esperado:** «Se queda: MV-E fusionar con squash» y «Se archiva: MV-E las PRs van con squash», cada una abre su panel.
3. Pulsa **Aceptar** en la fusión y **Rechazar** en el archivo.
   **Esperado:** «Aceptada por <tu usuario>» y «Rechazada por <tu usuario>», sin botones y sin «pendientes». En **Entradas**, «MV-E las PRs van con squash» está archivada y «MV-E usar Java 17» no.

### 2.3 Invocación

1. Abre la invocación del curador («Ver la invocación» en el informe) y su pestaña **Memoria**.
   **Esperado:** el texto del modo `curate` y, en «Páginas de la wiki», al menos el Índice «en el bloque del prompt». El enlace lleva a la vista Wiki del proyecto.

Al acabar, quita `FAKE_CLAUDE_STRUCTURED_OUTPUT` del runner como dice `probar-mv-e.md` y archiva las entradas MV-E si no las quieres.

## 3. Lo que no cambia

- La vista **Entradas**: filtros, ficha, confianza y «¿Quién lo vio?» (PR anterior).
- La API y la base de datos.
