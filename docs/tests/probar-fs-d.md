# Probar FS-D - Proyecto como centro de operaciones

FS-D es la cuarta PR del hito FS (issue #229, milestone 88). La página del proyecto pasa a ser su centro de operaciones. Usa la API de FS-B (`/overview` y `/changes`) y solo cambia la web.

- **Cifras:** bajo la cabecera, cinco cifras:
  - trabajos abiertos;
  - ejecuciones en curso;
  - PRs abiertas y fusionadas;
  - aprobaciones pendientes;
  - coste acumulado en 30 días.

  Las tres primeras enlazan a su pestaña.
- **Pestañas:** Resumen (nueva y por defecto), Trabajos, Ejecuciones (nueva), Cambios (nueva), Workflows (nueva), Memoria y Configuración (lo que era Repositorios). El enlace antiguo `?tab=repos` abre Configuración.
- **Resumen**, a la izquierda:
  - los repositorios como tarjetas: rama, trabajos, en marcha, PRs abiertas y coste a 30 días;
  - el tablero de trabajos por fase, en cuatro columnas: Por empezar, En marcha (con PR en revisión), Te necesita (con Atascado) y Entregado.
- **Resumen**, a la derecha:
  - lo que necesita intervención en el proyecto (sus aprobaciones y sus trabajos atascados);
  - las tres últimas PRs, con enlace a Cambios;
  - el coste por trabajo, con una barra relativa al más caro.
- **Ejecuciones:** las 50 más recientes del proyecto, con enlace a la lista completa filtrada.
- **Cambios:** las PRs del proyecto con su trabajo, repositorio, estado e intento.
- **Workflows:** los usados en el proyecto, con veces, tasa de éxito, coste y último uso (sobre las 50 ejecuciones más recientes).
- **Móvil:**
  - las cifras se deslizan en horizontal;
  - las pestañas quedan fijas arriba al hacer scroll;
  - el tablero se convierte en secciones plegables, con «En marcha» y «Te necesita» abiertas.
- La página 404 dice «Volver al Inicio».

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. Las ejecuciones de la §2 usan fake-claude sobre demo-repo.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/ProjectPage.test.tsx src/AgentPolicy.test.tsx src/ProjectMemory.test.tsx
npm run typecheck && npm run lint && npm run format:check
npm test
```

`ProjectPage.test.tsx` comprueba:

- que el proyecto abre por el Resumen, con las cifras, los repositorios, el tablero, los cambios y el coste;
- que `?tab=repos` abre Configuración.

La E2E `review.e2e.ts` entra ahora en Configuración para ver los repositorios.

## 2. En la web (escritorio)

Con Skynet en marcha y el proyecto de demo-repo:

1. Abre el proyecto:
   - la pestaña elegida es Resumen;
   - las cinco cifras cuadran con `GET /api/projects/<id>/overview` (`openWorkItems`, `activeRuns`, `openPullRequests`/`mergedPullRequests`, `pendingApprovals` y `costUsd`).
2. Pulsa la cifra de ejecuciones en curso: abre la pestaña Ejecuciones con las del proyecto.
3. En el Resumen:
   - la tarjeta de demo-repo enseña su rama y sus cifras;
   - en el tablero, cada trabajo está en la columna de su fase, con su repositorio, workflow, PR, intentos y coste.
4. Lanza un workflow con aprobación. Mientras espera:
   - el trabajo pasa a «Te necesita»;
   - «Necesita intervención» enseña la aprobación con su repositorio, riesgo y antigüedad;
   - el enlace abre la aprobación.
5. Cuando haya una PR, sale en «Cambios» (las tres últimas). «Historial» abre la pestaña Cambios con todas. Cada fila enlaza a la PR en GitHub, al trabajo y a la ejecución.
6. Abre la pestaña Workflows: cada workflow usado sale con sus veces y su tasa de éxito, y enlaza a su página.
7. Abre `?tab=repos`: se selecciona Configuración, con los repositorios, la verificación, la política y los disparadores de siempre.
8. Repite el paso 1 en tema oscuro.

## 3. En el móvil (o con una ventana de unos 390 px)

1. Las cifras van en una fila que se desliza.
2. Las pestañas muestran Resumen, Trabajos y «Más». Al bajar, se quedan fijas arriba.
3. El tablero son secciones plegables: «En marcha» y «Te necesita» abiertas, «Por empezar» y «Entregado» plegadas.
4. Ninguna página se desplaza en horizontal.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
