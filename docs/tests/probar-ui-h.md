# Probar UI-H en local

UI-H aplica a la web el logotipo del estudio de imagen corporativa (variante «Principal»): barra lateral, login, favicon e iconos (#55). Solo cambia la web; no hay migraciones ni dependencias nuevas.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

En desarrollo, `cd web && npm ci && npm run dev` con el control plane levantado como siempre. Si el navegador guarda el favicon antiguo (el rayo de Vite), recarga sin caché.

## 2. Qué mirar

1. **Login.** Cierra sesión.
   - A la izquierda está la baldosa azul grande con el isotipo en blanco.
   - Bajo «Skynet» aparece el lema «AGENTES · FLUJOS · EJECUCIONES».
2. **Barra lateral.** Entra y fíjate en el logo de arriba.
   - Al pasar el ratón se ilumina y al pulsarlo se hunde. Te lleva al Dashboard desde cualquier página.
   - Con el tabulador tiene un contorno de foco visible.
   - Pliega la barra: solo queda la baldosa, centrada.
3. **Tema oscuro.** Cambia el tema: la baldosa pasa a un azul más profundo y el isotipo sigue en blanco.
4. **Favicon.** La pestaña enseña el isotipo sobre la baldosa azul. En el móvil, «Añadir a pantalla de inicio» usa el icono nuevo.
5. **Colores personalizables.**
   - En **Ajustes**, elige «Mandarina» y guarda. La baldosa de la barra lateral y la del login se vuelven naranjas con el isotipo oscuro, como el texto de los botones. El favicon de la pestaña también cambia a naranja.
   - Vuelve a los colores de Skynet y guarda: el favicon vuelve al azul.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```
