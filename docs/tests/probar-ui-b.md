# Probar UI-B en local

UI-B es la segunda PR del rediseño de la interfaz: cambia la estructura común de la web (barra lateral, barra superior, búsqueda, menús, login y navegación en el móvil). El contenido de cada página no cambia; eso llega en UI-C, UI-D y UI-E.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

O en desarrollo: control plane y runner como siempre, y `cd web && npm ci && npm run dev`. Después de `npm ci` hay dependencias nuevas (cmdk y tres primitivos de Radix: diálogo, menú y pista).

## 2. Qué mirar

1. **Login.** Una tarjeta en dos mitades: a la izquierda la marca de Skynet con «Tus agentes, a la vista.» y tres estados de ejemplo; a la derecha el formulario. En el móvil, la parte de los estados se oculta.
2. **Barra lateral.** La marca de Skynet está arriba de la barra y no en la superior. Al pie aparece el resumen de runners («1 runner en línea · 1 agente»; el punto se vuelve ámbar si alguno no envía latidos, y al pulsarlo lleva a Runners), el estado del control plane y el botón «Plegar».
3. **Plegar.** Con «Plegar», la barra se queda solo con los iconos. Al pasar el ratón o enfocar con el teclado un icono, sale su nombre en una pista. Recarga la página: sigue plegada. Pulsa el mismo botón para desplegarla.
4. **Migas.** La barra superior dice dónde estás: «Ejecuciones › TKM-1» en una ejecución, «Proyectos › TokenMeter» en un proyecto y «Proyectos › TokenMeter › TKM-1» en un trabajo. Cada miga anterior a la última es un enlace. Las migas que había dentro de las páginas desaparecen.
5. **Búsqueda ⌘K.** Pulsa ⌘K (Ctrl+K en Linux o Windows) o el botón «Buscar ejecución, trabajo o runner…». Escribe una clave (`TKM-1`) o parte de un nombre: salen secciones, proyectos, trabajos, las 30 ejecuciones más recientes con su estado y los runners. Con las flechas y Enter abres el elegido; Esc cierra. También cambia el tema («tema oscuro»).
6. **Tema.** El botón redondo con el icono de pantalla, sol o luna abre un menú con Sistema, Claro y Oscuro; la opción elegida lleva ✓.
7. **Cuenta.** El botón con la inicial del usuario abre un menú con el nombre y «Salir».
8. **Móvil.** Con la ventana estrecha (unos 400 px), la barra lateral desaparece y abajo hay una barra fija con Dashboard, Proyectos, Ejecuciones, Runners y «Más». «Más» abre un cajón desde abajo con todas las secciones, el resumen de runners y el estado del control plane. Arriba quedan la última miga, la lupa, el tema y la cuenta.
9. **Teclado.** Con Tab llegas a «Saltar al contenido», a la barra lateral, a las migas, a la búsqueda, al tema y a la cuenta. Los menús se recorren con las flechas, y Esc los cierra y devuelve el foco al botón.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

La E2E (`scripts/e2e.sh`) pasa axe también con la paleta ⌘K y el menú de tema abiertos, y su prueba de login sale con el menú de cuenta.
