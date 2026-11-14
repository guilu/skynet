# Probar WF-E - Detalle «Resumen»

WF-E (#317) es la quinta PR del hito WF («Workflows que se entienden»). Solo toca la web: la página de un workflow explica qué hace sin leer el YAML.

Qué cambia:

- **Pestañas Resumen, Grafo y YAML.** Una versión publicada abre en «Resumen»; un borrador, en el YAML. Se recuerdan en la URL (`?view=summary`, `?view=graph`, `?view=yaml`), y los enlaces de siempre con `?view=graph` siguen funcionando.
- **Versión en un desplegable** («Versión»), en lugar de la fila de píldoras. Si ves la publicada y hay un borrador abierto, un aviso lo dice con «Ver borrador».
- **Resumen**:
  - cifras: fases (y en cuántas etapas), agentes, cuántas te piden aprobar, jueces y si entrega por PR;
  - **paso a paso** por etapa (Especificación, Planificación…), numerado en el orden del YAML. Sustituye a «Qué define». Cada paso lleva su título, el tipo en palabras (Agente, Tu aprobación, Juez, GitHub…), quién lo hace (proveedor, agente, modelo y reserva), una frase sacada de la definición y chips: después de qué, qué recibe (`{{stages.X.output.Y}}` del prompt), qué deja, qué comprueba, qué devuelve, si lo reintenta, el riesgo, criterios y escépticos del juez, condición y tiempo máximo. «Ver prompt» lo despliega.
- **«Listo para lanzar»** sustituye a la tarjeta «Problemas»: si tiene errores, si el repositorio necesita su comando de verificación, de qué proveedores necesita cuota y si tiene fases de GitHub. Debajo, los problemas de siempre; pulsarlos lleva a su línea en el YAML.
- **«Al lanzar te pide»** (los datos de entrada, o que solo pide el trabajo) y **«Agentes»** con proveedor, modelo, los permisos en palabras («Puede editar ficheros», «Solo lectura»…), la reserva y en qué fases trabajan.
- **Móvil**: los pasos son filas que abren una hoja inferior con todo lo del paso.

Fuera de esta PR: «Lanzar» desde la cabecera (hoy se lanza desde el trabajo) y la pestaña «Versiones» (el desplegable hace su papel). El índice de fases del YAML tampoco entra.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/components/workflow/steps.test.ts src/Workflows.test.tsx
```

- `steps.test.ts`: grupos por etapa, numeración, frases de agente, aprobación y juez, chips (deja, recibe, tests, reintentos, riesgo), cifras, permisos y «Listo para lanzar».
- `Workflows.test.tsx`: el paso a paso de un borrador, «Listo para lanzar», el desplegable de versiones, el aviso de borrador y que la versión publicada abre en «Resumen».

Las E2E de workflows, editor, spec, nodos y proveedores leen ahora el paso a paso y la tarjeta «Listo para lanzar».

## 2. Escritorio

Con el entorno local y los workflows sembrados:

1. Abre <http://localhost:5173/workflows/especificar-planificar-implementar>. Sale «Resumen»: 9 fases en 5 etapas, 2 te piden aprobar, 1 juez, PR.
2. El paso «Juzga contra la propuesta» dice cuántos criterios puntúa, cuántos escépticos refutan y quién corrige; sus chips llevan criterios y escépticos.
3. «Apruebas el plan» lleva «Riesgo medio» en ámbar y dice quién hace los cambios si los pides.
4. «Ver prompt» de «Planifica las tareas» despliega el prompt; su chip «Recibe de «Especifica la propuesta»» nombra lo que lee.
5. A la derecha, «Listo para lanzar» pide el comando de verificación (lo usan los tests de «Implementa el plan»), cuota de Antigravity, Claude Code y Codex, y avisa de las fases de GitHub.
6. «Agentes»: `desarrollador` sale como «Puede editar ficheros» con reserva Codex; `revisor`, sin `permissionMode`, como «Lo que permita el repositorio».
7. Pulsa «Editar»: se abre el borrador v2 en el YAML. Vuelve a la v1 con el desplegable «Versión»: sale el aviso «Hay un borrador v2…» con «Ver borrador».
8. Escribe un error en el borrador (`colr: azul`): «Listo para lanzar» dice «Tiene 1 error…» y el problema lleva a su línea.
9. `?view=graph` abre el grafo como antes.

## 3. Móvil

En un móvil de 390 px:

1. La cabecera, el desplegable y el aviso caben sin desbordar.
2. Las cifras van en dos columnas y los pasos son filas con su proveedor.
3. Pulsa un paso: una hoja inferior con quién, la frase, los chips y el prompt. Escape o la X la cierran.
