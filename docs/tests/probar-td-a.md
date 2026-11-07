# Probar TD-A - Skyny 3D en el login

TD-A (#258) es la primera PR del hito TD (Skyny tridimensional). **Solo la pantalla de entrada** pasa a Skyny en 3D (three.js). El resto de la web sigue con el Skyny 2D.

- **Web**: `web/src/components/companion/three/` (modelo, pantalla de la cara y componente) y `LoginSkyny.tsx`, que elige entre el 3D y el 2D de siempre.
- **Dependencia nueva**: `three` 0.187. Va en un trozo aparte (`Skyny3D-*.js`, unos 139 KB gzip) que solo se descarga al abrir el login.
- **Sin migraciones, sin cambios de API ni de CI**.
- Si no hay WebGL 2, si el sistema pide reducir el movimiento, mientras se descarga el trozo o si el 3D falla, sale el Skyny 2D de antes (mismas poses).

Ninguna prueba modifica ficheros versionados de skynet ni de otros repositorios, ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/companion src/Auth.test.tsx
npm run build   # sale el trozo Skyny3D-*.js aparte
cd .. && ./scripts/e2e.sh companion.e2e.ts
```

- `three/body.test.ts`: el cuerpo es más ancho abajo y plano de perfil, y cada punto de las bandas cae sobre el cuerpo, en su dirección y 0,012 por fuera. El estudio calculaba esos puntos con rayos contra la malla (casi 1 s por banda); ahora se calculan al instante.
- `Auth.test.tsx` y los tests del companion siguen pasando: en jsdom no hay WebGL y sale el Skyny 2D.
- `companion.e2e.ts` entra por el login: con Chromium el login ya pinta en 3D y la entrada funciona igual.

## 2. En local

Arranca la web como siempre y cierra la sesión (o abre una ventana privada) para ver el login.

### 2.1 Skyny 3D

- A la izquierda sale Skyny en 3D, del color de acento, flotando sobre una sombra suave y **parpadeando** cada pocos segundos.
- Mueve el ratón por la pantalla: Skyny gira un poco hacia el cursor y los ojos lo siguen. Con Tab también mira al campo que tiene el foco.
- Pon el foco en **Contraseña**: se tapa los ojos con las dos manos.
- Pulsa el ojo de «Mostrar la clave» y vuelve al campo: baja la mano derecha y **mira de reojo**. Pulsa otra vez: se tapa los dos ojos.
- Sal del campo: las manos vuelven a su sitio.
- Entra con el usuario de siempre: el login funciona como antes.

### 2.2 Tema oscuro y paleta

- Cambia el sistema a tema oscuro (o `data-theme="dark"`): Skyny se ve bien sobre el fondo oscuro, y la sombra pasa a ser un resplandor azulado.
- Con una paleta personalizada (Ajustes › Apariencia), al volver al login el cuerpo toma el nuevo acento.

### 2.3 Respaldo 2D

- Activa «Reducir movimiento» en el sistema (o en DevTools › Rendering › `prefers-reduced-motion: reduce`) y recarga: sale el Skyny 2D de siempre, quieto, y se sigue tapando los ojos.
- En DevTools › Network, el trozo `Skyny3D-*.js` solo se descarga en el login, no en el resto de la web.

### 2.4 Móvil

Con una ventana de 390 px, Skyny 3D sale arriba, sin cortarse, y el formulario debajo.

## 3. En omarchy

Abre el login en el navegador de siempre: Skyny 3D debe ir fluido (60 fps) y sin errores en la consola. Si el navegador no tiene WebGL 2 o la GPU está desactivada, debe salir el Skyny 2D.
