# Probar FS-F - Fábrica y Administración

FS-F es la última PR del hito FS (issue #231, milestone 90). Completa las áreas Fábrica y Administración. Solo cambia la web y usa la API de FS-B.

- **Fábrica › En curso** (`/runs`):
  - las ejecuciones activas y en cola, agrupadas por proyecto › repositorio, con lo que llevan gastado;
  - cada una lleva una barra con una raya por fase (verde terminada, morada en marcha, ámbar esperando, roja fallida), la fase actual, la duración y el coste;
  - debajo van «Resultados de hoy» y «Terminadas sin resultado» (24 h).
- **Historial** (`/runs?view=history`): la lista de ejecuciones de antes, con sus filtros. Un enlace viejo con filtros (`/runs?status=FAILED`) abre esta pestaña.
- **Resultados** (`/results`): las PRs de todos los proyectos, con su trabajo, su proyecto › repositorio, su estado y su intento, y un filtro por estado.
- **Workflows**: nueva columna «Trabajos», con los pendientes que usan cada workflow.
- **Administración** (`/admin`):
  - una portada con una tarjeta por sección y las ventanas de cuota más gastadas;
  - se llega desde «Administración» en las migas y desde «Resumen» en la hoja «Más» del móvil.
- **Proveedores** (`/providers`):
  - cada CLI con los runners que lo tienen, su versión, si es simulado y lo que admite;
  - debajo, «Continuar tras la cuota», la misma sección que en Ajustes › Ejecución.
- **Mantenimiento** (`/maintenance`):
  - lo archivado (proyectos, workflows y runners olvidados), cada cosa con su menú para restaurarla o eliminarla;
  - las últimas entregas del webhook de GitHub.
- **Galería visual:** `navigation-map.json` sigue la nueva estructura:
  - pantallas nuevas: Trabajos, Historial, Resultados, Administración, Proveedores y Mantenimiento;
  - el grupo «Configuración» pasa a «Administración»;
  - el preparador de «Nuevo intento» usa el botón nuevo.
- **Móvil:**
  - en En curso, cada ejecución ocupa dos líneas: arriba el trabajo, con su tiempo y su coste; debajo, la barra de fases a todo lo ancho;
  - la portada de Administración es una lista.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. Las ejecuciones de la §2 usan fake-claude sobre demo-repo.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/FactoryPage.test.tsx src/App.test.tsx visual
npm run typecheck && npm run lint && npm run format:check
npm test
```

`FactoryPage.test.tsx` comprueba:

- que En curso agrupa por proyecto › repositorio, con la barra de fases, los resultados de hoy y las terminadas sin resultado;
- que un filtro en la URL abre Historial;
- que Resultados lista las PRs;
- que Proveedores cruza los CLIs con los runners;
- que Administración enlaza sus cinco secciones;
- que Mantenimiento junta lo archivado y las entregas del webhook.

`App.test.tsx` comprueba los grupos de la barra lateral con sus nuevas secciones. `review.e2e.ts` pasa axe por las páginas nuevas en los dos temas.

## 2. En la web (escritorio)

Con Skynet en marcha y el proyecto de demo-repo:

1. Lanza dos intentos (uno con `feature-con-plan`) y abre **En curso**:
   - sale el grupo «<proyecto> › demo-repo» con las dos ejecuciones;
   - la de `feature-con-plan`, cuando pida aprobar el plan, tiene una raya ámbar y la píldora de la fase;
   - la suma «… US$ en curso» cuadra con el coste de cada fila.
2. Haz que un intento falle: sale en «Terminadas sin resultado» con «<clave> falló en <fase>».
3. Pulsa **Historial**:
   - la URL pasa a `?view=history`;
   - los filtros de estado, fecha y búsqueda funcionan como antes;
   - abre `/runs?status=FAILED` en otra pestaña: se abre Historial con el filtro puesto.
4. Abre **Resultados**:
   - las PRs de los agentes salen con su trabajo y su intento;
   - pulsa «Fusionadas»: solo quedan esas, y el filtro queda en la URL.
5. En **Workflows**, la columna «Trabajos» cuenta los pendientes de cada uno.
6. Pulsa «Administración» en las migas de Runners: abre la portada.
   - «Runners» dice cuántos están en línea;
   - «Consumo» lleva lo gastado en 30 días y las barras de las ventanas de cuota.
7. Abre **Proveedores**:
   - fake-claude sale como «Simulado» en el runner omarchy;
   - un proveedor sin runner en línea dice «Sin runner»;
   - cambia «Esperas por fase» y guarda: el cambio también se ve en Ajustes › Ejecución.
8. Archiva un proyecto de prueba y abre **Mantenimiento**:
   - sale en «Proyectos archivados»;
   - restáuralo desde su menú «⋯»: desaparece de la lista.
   - Con la GitHub App configurada, abajo salen las últimas entregas.
9. Repite los pasos 1 y 6 en tema oscuro.

## 3. En el móvil (o con una ventana de unos 390 px)

1. En **En curso**, cada ejecución lleva el trabajo con el tiempo y el coste a la derecha, y debajo la barra de fases a todo lo ancho con la píldora.
2. En **Más**, junto a «Administración» está «Resumen», que abre la portada. La portada es una lista de cinco filas.
3. Resultados, Proveedores y Mantenimiento se leen en una columna.
4. Ninguna página se desplaza en horizontal.

## 4. Galería visual

Lanza el workflow **Galería visual** (o pon la etiqueta `galeria-visual` en la PR) y comprueba que el artifact trae las 46 pantallas, en los dos temas y los dos tamaños, sin problemas de privacidad.

## 5. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
