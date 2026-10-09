# Probar UI-E en local

UI-E es la quinta PR del rediseño de la interfaz: rehace las páginas de proyecto y de trabajo, lleva los formularios a un panel lateral y pule artefactos, verificación y diff. Estados vacíos, animaciones y la guía de estilo llegan en UI-F.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

O en desarrollo: control plane y runner como siempre, y `cd web && npm ci && npm run dev`. No hay dependencias nuevas y el backend no cambia.

## 2. Qué mirar

1. **Proyecto.** Abre un proyecto. Arriba, su nombre y dos botones: «Nuevo repositorio» y «Nuevo trabajo». Debajo, dos pestañas con su número: Trabajos (una tabla con clave, título, tipo, estado y fecha) y Repositorios. La pestaña elegida queda en la URL (`?tab=repos`).
2. **Repositorios.** Cada repositorio es una tarjeta con su ruta, su rama, el comando de verificación y un resumen de la política de agentes (propia o global, modo, herramientas, entorno y máximos).
3. **Panel lateral.** «Nuevo trabajo», «Nuevo repositorio», «Configurar verificación» y «Editar política» abren un panel por la derecha. Se cierra con la X, con Escape o pulsando fuera, y el foco vuelve al botón. Al crear un trabajo o registrar un repositorio, el panel se cierra solo y salta a su pestaña. En la política, guardar y «Volver a la política global» funcionan como antes y dicen «Guardado.».
4. **Trabajo.** La página del trabajo enseña su tipo y fecha, la descripción y sus ejecuciones en tabla (estado, agente, duración, tokens y coste). «Lanzar agente» abre el formulario en el panel lateral, con la política del repositorio y los límites. Al lanzar, se abre la ejecución.
5. **Reintentar, bifurcar y eliminar worktree.** En el resumen de un agente terminado, cada botón abre el panel lateral con lo que hará (en un aviso amarillo) y la confirmación. Bifurcar pide el mensaje.
6. **Artefactos.** Arriba, la rama, los commits base y final, cuántos archivos, líneas y commits, y un aviso si quedan archivos sin confirmar. Cada archivo lleva su letra de color (A verde, M azul, D rojo) y sus `+`/`−`. En el inspector estrecho, el diff va debajo de la lista. Los commits salen como filas con su SHA.
7. **Diff con el tema de la web.** El diff (Monaco) usa los colores de la web: fondo, números de línea, verde para lo añadido y rojo para lo quitado. Cambia el tema claro/oscuro con el diff abierto: se adapta al momento.
8. **Verificación.** «Declarado por el agente» y «Verificado por Skynet» van en tarjetas, y los tests llevan una barra con pasados (verde), fallidos (rojo) y omitidos (gris). «Reejecutar verificación» sigue igual.
9. **Móvil.** Con la ventana estrecha, el panel lateral sube desde abajo y los botones de la cabecera ocupan el ancho.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

La E2E (`scripts/e2e.sh`) registra el repositorio, crea el trabajo y lanza el agente desde los paneles laterales, y pasa axe en la pestaña de repositorios y con los paneles de política, nuevo trabajo y lanzar abiertos.
