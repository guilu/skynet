# Probar UI-I en local

UI-I rehace la página de una ejecución como la maqueta: cabecera con chips, cuatro tarjetas de cifras, el grafo de progreso de las fases y las acciones del agente bajo el título. Solo cambia la web; no hay migraciones ni dependencias nuevas.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

En desarrollo, `cd web && npm ci && npm run dev` con el control plane levantado como siempre. Lanza un workflow de varias fases con una que se omita (por ejemplo «Revisar y arreglar», donde `fix` se omite si la revisión no pide cambios) y abre la ejecución.

## 2. Qué mirar

1. **Cabecera.**
   - A la izquierda, la insignia grande del estado: verde con ✓ al completarse, morada mientras corre, roja si falla.
   - Arriba, la clave del trabajo (enlaza al trabajo), el estado y «En vivo». El menú «⋯» de archivar sigue a la derecha.
   - Bajo el título, los chips: workflow con su versión (enlaza al workflow), runner, modelo y rama. Si el agente elegido es de una fase `command`, el modelo y la rama salen de otro agente de la ejecución.
   - Ya no están los recuadros «Workflow», «Agente» ni «Runner».
2. **Acciones.** «Reintentar…», «Bifurcar…» y «Eliminar worktree…» están bajo los chips, no en el inspector. Actúan sobre el agente elegido en el progreso. Cada una abre su panel de confirmación como antes. Con la ejecución en curso aparece ahí «Cancelar agente» o «Cancelar ejecución».
3. **Cifras.**
   - Duración, Tokens, Coste y Tests, cada una con su icono de color.
   - Tokens enseña la suma de entrada y salida, y debajo el desglose con la caché.
   - En curso, Coste pone «Llega al terminar».
   - Tests enseña «aprobados/total» y el estado de la verificación. Sin verificación, el cuarto hueco queda vacío. En el móvil, ese hueco desaparece.
4. **Progreso.**
   - Las fases van en fila, unidas por una línea. Cada estado tiene su icono: ✓ verde completada, morado latiendo en curso, rojo fallida, línea discontinua y ⏭ omitida, número gris pendiente.
   - Bajo cada fase se ve la duración (o la herramienta en curso) y su relación con las demás («Después de review», «Omitida porque su condición es falsa»).
   - A la derecha del título: «Fase X» mientras corre, «N de M fases completadas» al terminar.
   - Pulsa una fase. Se rodea de un anillo azul y debajo aparecen sus agentes. Elegir uno lo abre en la conversación y el inspector, y cambia el objetivo de las acciones. Una fase omitida enseña su nota sin cambiar el agente.
5. **Paneles.** Debajo quedan dos paneles redimensionables, Actividad e Inspector. Las herramientas se eligen desde la cascada o la conversación.
6. **Móvil y oscuro.**
   - En el móvil las pestañas son Actividad y Detalle. El progreso cabe en el ancho y las notas de cada fase salen al abrirla.
   - En oscuro, los colores se mantienen legibles.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

Las E2E (`scripts/e2e.sh`) usan ahora el progreso y la cascada en lugar del árbol.
