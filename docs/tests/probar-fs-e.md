# Probar FS-E - Trabajo con intentos, coste y resultado

FS-E es la quinta PR del hito FS (issue #230, milestone 89). Separa en la web las tres palabras: **trabajo** (lo que quieres conseguir), **workflow** (cómo) y **ejecución** (cada intento). Usa la API de FS-B y solo cambia la web.

- **Página del trabajo:**
  - **Cabecera:** la clave y el título, la fase (Te necesita, En marcha…), el repositorio, el workflow con su versión y el origen.
  - **Acciones:** «Nuevo intento» (antes «Lanzar workflow»). Si hay una aprobación pendiente, también «Revisar la aprobación».
  - Tres tarjetas: **Qué** (la descripción), **Cómo** (el workflow y su versión, con enlace) e **Intentos** (cuántos lleva y cómo va el actual).
  - **Intento actual:** sus fases en orden, cada una con el color de su estado.
  - **Todos los intentos**, numerados («Intento 3 · actual»). Cada uno lleva su estado en palabras («Esperando aprobación», «Fallido en Verificación», «Cancelado»), su workflow y versión, su duración y su coste. La tabla de ejecuciones desaparece.
  - **Resultado:** la PR (abierta o fusionada) o qué falta para tenerla.
  - **Lo que ha costado:** el total de todos los intentos, lo gastado en intentos sin resultado (fallidos o cancelados), los tokens y el tiempo.
- **Hoja de aprobación:** «Revisar la aprobación» abre una hoja sin salir del trabajo. Lleva:
  - el riesgo y lo que pasa al aprobar;
  - lo gastado (este intento y el trabajo entero);
  - las mismas decisiones que la página de la aprobación (el panel se ha extraído a `components/approval/DecisionPanel.tsx`);
  - el enlace a toda la evidencia.
- **Ejecución:** la cabecera dice «Intento N de DEMO-4». Las migas pasan a «Espacio de trabajo › Proyectos › <proyecto> › <clave> › Intento N», y en el móvil «‹ <clave>».
- **Móvil:**
  - la acción principal queda fija abajo, sobre la barra inferior: «Revisar la aprobación», con el icono de nuevo intento al lado, o «Lanzar otro intento»;
  - las fases del intento actual van en vertical;
  - el enlace «‹ padre» de la cabecera se corta con puntos suspensivos si no cabe.

No incluye el recuento de trabajos por workflow en la página Workflows: queda para FS-F.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. Las ejecuciones de la §2 usan fake-claude sobre demo-repo.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/WorkItemPage.test.tsx src/App.test.tsx
npm run typecheck && npm run lint && npm run format:check
npm test
```

`WorkItemPage.test.tsx` comprueba:

- las tarjetas Qué, Cómo e Intentos;
- los intentos numerados con su estado, el resultado y el coste;
- la hoja de aprobación con lo gastado;
- que una ejecución se presenta como «Intento 2 de TKM-1», con sus migas.

Las E2E que lanzaban con «Lanzar workflow» usan ahora «Nuevo intento».

## 2. En la web (escritorio)

Con Skynet en marcha y el proyecto de demo-repo:

1. Crea un trabajo y ábrelo:
   - la fase es «Por empezar»;
   - Cómo dice «Se elige al lanzar el primer intento»;
   - Todos los intentos invita a lanzar el primero.
2. Pulsa **Nuevo intento** y lanza `feature-con-plan`:
   - la cabecera enseña el workflow y su versión;
   - Intento actual enseña sus fases (Plan en marcha, el resto sin empezar).
3. Cuando el plan pida aprobación:
   - la fase pasa a «Te necesita»;
   - el intento dice «Esperando aprobación»;
   - aparece «Revisar la aprobación».
4. Pulsa «Revisar la aprobación». En la hoja salen el riesgo, lo que pasa al aprobar y lo gastado («Este intento» y «El trabajo, con todos sus intentos»). Rechaza con un motivo: la hoja se cierra y el intento termina como fallido.
5. Lanza otro intento: sale como «Intento 2 · actual» y el primero como «Intento 1» con su estado. «Lo que ha costado» suma los dos y dice cuánto se fue en intentos sin resultado.
6. Abre «Intento 2»:
   - la cabecera de la ejecución dice «Intento 2 de <clave>»;
   - las migas terminan en «<clave> › Intento 2», y la clave lleva al trabajo.
7. Cuando el intento abra la PR, Resultado la enlaza («abierta, espera revisión») y, al fusionarla, «fusionada: trabajo entregado».
8. Repite el paso 1 en tema oscuro.

## 3. En el móvil (o con una ventana de unos 390 px)

1. Con una aprobación pendiente, abajo (sobre la barra inferior) está «Revisar la aprobación» con el icono de nuevo intento al lado. Sin aprobación, «Lanzar otro intento».
2. Los botones de la cabecera no se repiten: solo queda el menú «…».
3. Las fases del intento actual van una debajo de otra.
4. «Revisar la aprobación» abre la hoja desde abajo, con Aprobar, Rechazar, Pedir cambios y Comentar.
5. Ninguna página se desplaza en horizontal.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
