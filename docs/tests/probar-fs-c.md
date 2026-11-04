# Probar FS-C - Inicio como espacio de trabajo

FS-C es la tercera PR del hito FS (issue #228, milestone 87). Rehace el Dashboard como **Inicio**, la vista operativa del espacio de trabajo, y añade la sección **Trabajos**. Usa la API de FS-B y solo cambia la web.

- **Cabecera:**
  - una línea con los agentes que trabajan y en cuántos proyectos, lo que necesita tu intervención y lo gastado esta semana;
  - el botón principal pasa a «Nuevo trabajo» (pide el proyecto). «Lanzar agente» desaparece, porque se lanza desde un trabajo.
- **Necesita tu intervención:**
  - las aprobaciones llevan su proyecto, repositorio, intento, riesgo y antigüedad, y el botón «Revisar»;
  - nuevo aviso «trabajo atascado» (el último intento falló), con lo gastado sin resultado y el botón «Ver intentos»;
  - siguen los agentes sin respuesta, los runners sin latido, los proveedores sin runner, las cuotas y los CLIs simulados;
  - los fallos sueltos de 24 h ya no salen: si el trabajo sigue atascado, sale como atascado.
- **Tus proyectos:** una tarjeta por proyecto con:
  - sus repositorios como filas (rama, intentos en marcha, PRs abiertas y coste a 30 días);
  - una raya por trabajo abierto, del color de su fase;
  - un pie con los intentos en marcha, las PRs por revisar y el gasto a 30 días.
- **Trabajos pendientes:** los cinco primeros, empezando por lo que te necesita y lo atascado, con chips de fase para filtrar. «Ver los N» lleva a **Trabajos**.
- **Actividad reciente**, contada como resultados: PR fusionada («entregado»), PR abierta, intento fallido, terminado o empezado.
- **Trabajos** (`/work`, en Espacio de trabajo): todos los trabajos, por fase. Se filtran por fase, proyecto y repositorio, y los filtros quedan en la URL.
- **Consumo** (antes «Uso de los CLIs»): recibe las métricas globales del antiguo Dashboard, con el selector de periodo en la cabecera. Los runners siguen en Runners.
- **Móvil:**
  - los proyectos van en un carrusel;
  - los chips de fase se deslizan;
  - la tabla de repositorios pierde la columna de PRs;
  - la actividad se queda en tres elementos.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes reales. Las ejecuciones de la §2 usan fake-claude sobre demo-repo.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/App.test.tsx src/pages/UsagePage.test.tsx
npm run typecheck && npm run lint && npm run format:check
npm test
```

`App.test.tsx` comprueba:

- que Inicio cuenta y enlaza lo que necesita intervención, con el contexto y la acción del trabajo atascado;
- la tarjeta del proyecto, los pendientes ordenados por fase y su filtro, la actividad y la hoja «Nuevo trabajo»;
- que Inicio sin avisos ni proyectos se queda limpio;
- que Trabajos lee la fase y el proyecto de la URL;
- que Consumo lleva las métricas del periodo.

Las E2E `approvals.e2e.ts` y `usage.e2e.ts` buscan ahora «Necesita tu intervención» en Inicio y el título «Consumo».

## 2. En la web (escritorio)

Con Skynet en marcha y el proyecto de demo-repo:

1. Abre **Inicio**:
   - la línea bajo el saludo dice «N agentes trabajan en M proyectos · … · X US$ esta semana»;
   - arriba a la derecha está «Nuevo trabajo».
2. Lanza un workflow con aprobación (`feature-con-plan`) sobre un trabajo. Cuando pida aprobar el plan, en «Necesita tu intervención» sale «<clave> · <acción>» con «<proyecto> › demo-repo · intento N · Riesgo … · hace …» y el botón «Revisar», que abre la aprobación.
3. Haz que un trabajo falle (por ejemplo, un workflow con un comando de verificación que no pasa). Sale «<clave> · <título>: el último intento falló», con los intentos y lo gastado sin resultado, y «Ver intentos» abre el trabajo. Lánzalo otra vez: mientras corre, el aviso desaparece.
4. En **Tus proyectos**, la tarjeta del proyecto lista demo-repo con su rama:
   - las cifras de «En marcha», «PRs» y «30 días» cuadran con `GET /api/projects/<id>/overview`;
   - el pie dice «N intentos en marcha · N PRs por revisar · X US$ en 30 días».
5. En **Trabajos pendientes**:
   - lo que te necesita sale primero, cada fila con su fase, repositorio, workflow, intentos y coste;
   - pulsa un chip de fase: la lista se filtra;
   - pulsa «Ver los N»: abre Trabajos.
6. En **Trabajos**:
   - pulsa «Entregado»: salen los trabajos con la PR fusionada o cerrados;
   - elige un proyecto con varios repositorios: aparece «Repositorio»;
   - recarga: los filtros siguen en la URL.
7. **Actividad reciente** cuenta «<clave> entregado: PR #N fusionada en demo-repo» cuando se fusiona una PR, y «<clave> empezó con «<workflow>»» al lanzar.
8. Pulsa **Nuevo trabajo**, elige el proyecto y crea uno: se abre la página del trabajo.
9. Abre **Consumo**: el título es «Consumo» y, bajo los avisos de cuota, está «Resumen (últimos 7 días)» con las tarjetas y el gráfico. El selector de periodo de la cabecera cambia el resumen y «Qué cuesta cada agente».
10. Repite el paso 1 en tema oscuro.

## 3. En el móvil (o con una ventana de unos 390 px)

1. En Inicio, «Necesita tu intervención» va primero, con el botón de cada aviso bajo el texto.
2. Los proyectos se deslizan en horizontal, una tarjeta casi a lo ancho.
3. Los chips de fase de los pendientes se deslizan sin saltar de línea.
4. La actividad muestra tres elementos.
5. Ninguna página se desplaza en horizontal.

## 4. Al terminar

En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
