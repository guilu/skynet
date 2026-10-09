# Probar UI-G en local

UI-G añade la página **Ajustes**, donde se elige la paleta de colores de la web. Lo que se guarda vale para todos los navegadores y para los dos temas (#47).

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

En desarrollo, arranca el control plane y el runner como siempre y luego `cd web && npm ci && npm run dev`. Hay una migración nueva, `V9__settings.sql` (tabla `app_setting`), que Flyway aplica sola al arrancar. No hay dependencias nuevas.

## 2. Qué mirar

1. **Ajustes.** En la barra lateral hay una entrada nueva, «Ajustes». En el móvil está en el cajón «Más» y también en ⌘K. La página enseña:
   - las paletas predefinidas;
   - un color para el acento y para cada estado;
   - una vista previa en claro y en oscuro.
2. **Paletas.** Elige «Mandarina».
   - La vista previa cambia y el texto de los botones naranjas pasa a oscuro para seguir siendo legible.
   - El resto de la web no cambia todavía y aparece «Cambios sin guardar.».
   - Pulsa **Guardar**: sale «Guardado.» y toda la web cambia al momento, la barra lateral y los botones incluidos.
3. **Colores sueltos.**
   - Cambia «Fallo» o «En curso» con el selector de color y mira la vista previa en los dos temas.
   - Elige un color muy claro, por ejemplo un amarillo, para «Correcto». Las píldoras y el botón «Aprobar» siguen siendo legibles, porque su texto y su fondo se ajustan solos.
   - «Por defecto», al lado de cada color, vuelve a poner el de Skynet.
4. **Persistencia.**
   - Recarga la página y comprueba que la paleta se mantiene.
   - Ábrela en otro navegador o en una ventana privada: el login ya sale con los colores elegidos.
5. **Tema oscuro.** Cambia el tema con el botón de la barra superior: los colores oscuros se calculan a partir de los mismos que elegiste.
6. **Diff.** Abre una ejecución con diff (Artefactos): Monaco usa los colores nuevos sin recargar.
7. **Volver.** Pulsa «Volver a los colores de Skynet» y después **Guardar**: queda todo como antes.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
./gradlew :control-plane:test
```

- `palette.test.ts` comprueba tres cosas:
  - que con los colores de Skynet se reproducen exactamente los tokens de `index.css`;
  - que todas las paletas predefinidas llegan a AA;
  - que con colores difíciles la tinta y los botones se corrigen.
- `AppearanceIT` prueba el endpoint (guardar, sustituir, validar `#rrggbb`). `SecurityIT` comprueba que la paleta se lee sin sesión pero no se cambia.
- La E2E pasa axe en Ajustes, en claro y en oscuro. Además guarda una paleta propia, recarga, comprueba que se aplica y pasa axe en el dashboard con ella.

Los IT de Java también se pueden pasar en local, sin Docker, contra un PostgreSQL vacío:

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_it ./gradlew :control-plane:test
```
