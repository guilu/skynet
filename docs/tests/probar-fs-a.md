# Probar FS-A - Navegación en tres áreas

FS-A es la primera PR del hito FS (issue #226, milestone 85). Agrupa la navegación de la web en tres áreas:

- **Espacio de trabajo:** Inicio, Proyectos y Actividad.
- **Fábrica:** Ejecuciones, Workflows y Aprobaciones.
- **Administración:** Runners, Consumo y Ajustes.

Solo cambia la web. La API, la base de datos, los eventos y las rutas siguen igual.

- **Renombres:** Dashboard pasa a llamarse **Inicio** y Uso pasa a **Consumo**. «Ejecuciones» conserva su nombre hasta FS-F, que crea la vista «En curso».
- **Escritorio:** la barra lateral pone el nombre del área encima de sus secciones. Plegada, cada área se separa con una raya.
- **Migas:** empiezan por el área, por ejemplo «Administración › Runners» o «Espacio de trabajo › Proyectos › Analizador de texto».
- **⌘K:** la paleta agrupa los destinos en «Ir a · <área>».
- **Móvil:** la barra inferior lleva Inicio, Proyectos, **Fábrica** (las ejecuciones), **Aprobar** (con el número de pendientes) y **Más**. «Más» abre una hoja con todas las secciones en baldosas, agrupadas por área. En la cabecera, las migas se convierten en «‹ padre».

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/App.test.tsx src/pages/UsagePage.test.tsx src/SettingsPage.test.tsx
npm run typecheck && npm run lint && npm run format:check
npm test
```

`App.test.tsx` comprueba:

- que cada área agrupa sus secciones y que la sección actual está marcada;
- que el enlace de la marca dice «Skynet: ir al Inicio»;
- que las migas empiezan por el área y llevan «Volver a <padre>»;
- que la barra del móvil lleva Fábrica → `/runs` y Aprobar → `/approvals`, y que «Más» agrupa todas las secciones por área.

## 2. En la web (escritorio)

Con Skynet en marcha:

1. La barra lateral muestra tres grupos con su nombre encima:
   - «Espacio de trabajo»: Inicio, Proyectos y Actividad;
   - «Fábrica»: Ejecuciones, Workflows y Aprobaciones (con el contador si hay pendientes);
   - «Administración»: Runners, Consumo y Ajustes.
2. Abre **Runners**. Las migas dicen «Administración › Runners» y Runners está resaltado en la barra.
3. Abre **Consumo**. La URL sigue siendo `/usage` y la página es la misma de antes.
4. Abre un proyecto y después uno de sus trabajos. Las migas dicen «Espacio de trabajo › Proyectos › <proyecto> › <clave del trabajo>».
5. Abre una ejecución. Las migas empiezan por «Fábrica».
6. Pliega la barra (abajo, «Plegar»). Solo quedan los iconos, con una raya entre áreas. Al pasar el ratón por un icono sale su nombre.
7. Pulsa **⌘K** (o Ctrl+K). La lista muestra «Ir a · Espacio de trabajo», «Ir a · Fábrica» e «Ir a · Administración». Escribe «consumo» y pulsa Intro: abre `/usage`.
8. Cambia a tema oscuro y repite el paso 1: los nombres de área se leen en gris claro.

## 3. En el móvil (o con una ventana de unos 390 px)

1. La barra inferior muestra Inicio, Proyectos, Fábrica, Aprobar y Más. La pestaña actual resalta el icono con una píldora azul suave.
2. Si hay aprobaciones pendientes, Aprobar lleva el número sobre el icono.
3. Pulsa **Fábrica**: abre Ejecuciones. Pulsa **Aprobar**: abre Aprobaciones.
4. Abre un proyecto. Arriba a la izquierda sale «‹ Proyectos», que lleva a la lista de proyectos. Abre un trabajo: sale «‹ <nombre del proyecto>».
5. En Runners (sección sin padre) la cabecera dice solo «Runners».
6. Pulsa **Más**. Sube una hoja titulada «Todas las secciones», con tres grupos de baldosas: la sección actual en azul y, al pie, el estado de los runners y del control plane. Pulsa Consumo: se cierra la hoja y abre `/usage`.
7. Ninguna página se desplaza en horizontal.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
