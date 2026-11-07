# Probar TD-B - Estudio de Skyny 3D dentro del repo

TD-B (#260) mete el estudio de Skyny 3D en el repo. Ahora usa el mismo módulo que la app (`web/src/components/companion/three/`), así que lo que se diseña en el estudio sale tal cual en la web. Cómo se edita: [`docs/ui/skyny-3d.md`](../ui/skyny-3d.md).

- **Web**: el módulo 3D ya tiene los 12 estados del companion (ojos, accesorios, gestos de entrada, confeti), repartidos en `model.ts`, `face.ts`, `accessories.ts`, `states.ts`, `motion.ts` y `scene.ts`. Los colores salen de los mismos tokens que el Skyny 2D.
- **Estudio**: `web/skyny-studio.html` + `web/src/studio/`, solo con `npm run dev`. **No va al build.**
- **El login no cambia**: sigue en Reposo, como en TD-A.
- Sin migraciones, sin cambios de API, de CI ni de dependencias.

Ninguna prueba modifica ficheros versionados de skynet ni de otros repositorios, ni lanza agentes.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/companion src/Auth.test.tsx
npm run build && ls dist   # no hay skyny-studio.html en dist
```

- `three/motion.test.ts`: los gestos empiezan y acaban en reposo, y al acabar todo queda en su sitio y visible. Cada estado del companion tiene su expresión 3D, y Reposo y En espera no hacen gesto.
- `three/body.test.ts` (de TD-A): las bandas siguen apoyadas sobre el cuerpo.

## 2. En local

```bash
cd web && npm run dev
```

### 2.1 Estudio

Abre `http://localhost:5173/skyny-studio.html`.

- Sale Skyny en Reposo con la lista de los 12 estados a la derecha, cada uno con su color, cuándo sale y sus ojos y accesorio.
- Pulsa cada estado: hace su gesto de entrada como en el estudio original. Pensando saca burbujas, Ejecutando hace una carrerilla con líneas de velocidad, Revisando acerca la lupa, Éxito salta con confeti, Error se sacude con los ojos «> <», Te necesita levanta la mano con el «?», En pausa se para, Inactivo cierra los ojos con «zZZ» y Sin conexión pone la señal tachada. Cancelado sale gris con los ojos en raya.
- **Repetir gesto** lo vuelve a lanzar. La URL guarda el estado (`?state=running`), y al recargar abre en ese.
- Las **vistas** (Frontal, 3/4, Lateral, Trasera, 3/4 trasera, Superior) mueven la cámara con suavidad, y con el ratón se orbita, se acerca y se desplaza.
- **Idle** para y reanuda el flotar, **Malla** enseña la malla y **Parpadear** parpadea. **Captura PNG** descarga `skyny-3d-<estado>-<vista>.png` con fondo transparente.
- **Tapar** y **De reojo** hacen las poses del login. Con **Mirada** activado, Skyny sigue al cursor por el lienzo.
- **Tema** cambia entre claro y oscuro: Skyny y la lista cambian de color, y la sombra pasa a ser un resplandor.

### 2.2 El login sigue igual

Cierra la sesión y abre el login: Skyny 3D en Reposo, que se tapa los ojos con la contraseña y mira de reojo con «Mostrar la clave», como en [`probar-td-a.md`](probar-td-a.md).

## 3. En omarchy

Nada que probar: el estudio solo existe con `npm run dev`. Tras desplegar, el login debe verse igual que con TD-A.
