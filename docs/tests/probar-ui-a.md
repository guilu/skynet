# Probar UI-A en local

UI-A es la primera PR del rediseño de la interfaz. Cambia el aspecto de toda la web al lenguaje visual de la maqueta (`docs/ui/maqueta.html`): formas redondeadas, bordes de 2 px, sombras cortas, botones con volumen, estados con color, icono y texto, y tipografía redondeada. **No cambia la estructura de las páginas ni lo que hacen**: todo sigue en el mismo sitio.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

O en desarrollo: control plane y runner como siempre, y `cd web && npm ci && npm run dev`. Después de `npm ci` hay dependencias nuevas (Tailwind, lucide y las fuentes).

## 2. Qué mirar

1. **Login.** Tarjeta redondeada con borde y sombra corta; el botón «Entrar» es azul, con volumen, y se hunde al pulsarlo.
2. **Barra lateral.** Cada sección lleva su icono y el texto en mayúsculas; la activa va en un recuadro azul suave con borde. Arriba, la marca de Skynet con su icono.
3. **Estados.** En Dashboard, Ejecuciones y una ejecución, cada estado es una píldora con color, icono y texto: «En curso», «Pensando» y «Ejecutando» en violeta y latiendo; «Completada» en verde con ✓; «Fallida» en rojo con ✕; «Sin actividad» y «Esperando…» en ámbar; «En cola» y «Cancelada» en gris. El indicador «En vivo» de la cabecera de una ejecución también es una píldora.
4. **Botones.** Lanzar, Reintentar, Bifurcar y Enviar son azules con volumen. «Cancelar agente», «Eliminar worktree…» y «Revocar token…» son blancos con borde y texto rojo; al confirmar, «Sí, cancelar», «Eliminar» y «Sí, revocar» son rojos. Los «No» y «Cancelar» de los paneles son blancos con borde. Los botones desactivados son grises y no se hunden.
5. **Tarjetas, tablas y pestañas.** Tarjetas con esquinas muy redondeadas y borde visible. Las tablas tienen filas en bloques redondeados. Las pestañas del inspector y el periodo de las métricas son un selector segmentado: la opción elegida es una «tecla» blanca.
6. **Formularios.** Campos con fondo gris azulado y borde de 2 px; al enfocarlos, el borde se vuelve azul con un halo suave.
7. **Modo oscuro.** Cambia el tema arriba a la derecha («Oscuro»). Todo se lee bien: fondos azul noche, estados más luminosos y botones de éxito y peligro con texto oscuro.
8. **Móvil.** Con la ventana estrecha (unos 400 px), la barra lateral pasa a una fila horizontal con scroll y nada se sale de la pantalla.
9. **Sin cambios de comportamiento.** Lanza una ejecución, envía un mensaje, cancela un agente, bifurca y reejecuta la verificación: todo funciona como antes.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

La E2E (`scripts/e2e.sh`) incluye axe (WCAG 2.1 AA en todas las páginas, claro) y la prueba de «sin secretos en el DOM».
