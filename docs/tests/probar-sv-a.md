# Probar SV-A: estudio de Skyny v2 en el repo

SV-A trae al repo todo lo del estudio `skyny-v2.0.html` (hecho con Codex fuera del repo) sobre el módulo 3D compartido con la web, y retira el HTML suelto. Añade `AGENTS.md` y `CLAUDE.md` para que Codex y Claude trabajen igual, y `npm run studio:export` para tener el estudio en un solo fichero.

Solo toca la web (`web/src/components/companion/three/`, `web/src/studio/`, un script) y documentación. En la app, el único sitio con Skyny 3D sigue siendo el login, que cambia de aspecto (acabado del v2, sin cabeza, hebilla en la espalda).

## 1. Comprobaciones automáticas

```bash
cd web
npm ci
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run
npm run build
```

Todo en verde. En `npm run build`, el trozo `Skyny3D-*.js` ronda los 154 KB gzip (antes 139 KB) y solo se descarga en el login. `skyny-studio.html` no entra en `dist/`.

## 2. El estudio

```bash
cd web && npm run dev
# http://localhost:5173/skyny-studio.html
```

1. **Modelo**: sin nodo en la cabeza, manos más separadas, bisel metálico plano y acabado mate. En **3/4 trasera**, las tres bandas acaban en una hebilla triangular abierta, apoyada en la espalda.
2. **Letreros**: pulsa **Pensando**. Hace el gesto, escribe «PENSANDO...» letra a letra con cursor, lo deja un segundo y vuelve a los ojos, con el izquierdo guiñando. Prueba también Éxito (escribe «LISTO» y luego salta con confeti) y Error (letrero rojo).
3. **Te necesita / En pausa / Sin conexión**: la mano derecha sube y sostiene el «?», la pausa o la señal tachada.
4. **Reposo**: cada pocos segundos mira a un lado y parpadea. Pasa el ratón cerca de la cara y te sigue con los ojos; lejos, deja de seguirte.
5. **Cambios de estado**: entre dos estados, el color y la postura se funden (no saltan) y el accesorio anterior se desvanece.
6. **Simular tarea**: Pensando → Ejecutando con contador 1/5 … 5/5 → Revisando → Éxito. El botón pone **Detener demo** mientras corre y, si lo pulsas, para.
7. **Simular recuperación**: Ejecutando → Error → Te necesita; se para y aparece **Reintentar**. Al pulsarlo: Ejecutando con «REINTENTO 1/2» → Éxito.
8. **Cancelar tarea** (solo activo en Ejecutando durante una demo): las líneas de velocidad se recogen, escribe «CANCELADO» y vuelve a Reposo.
9. **Vista compacta**: Skyny a 180 px abajo a la derecha, con el encuadre de la web; en Pensando escribe «IDEA», y en Ejecutando el contador sale directamente.
10. **Tema**: en oscuro, los colores salen de los tokens de la web.
11. Con el sistema en «reducir movimiento», no hay gestos ni fundidos y los letreros salen enteros.

## 3. Exportar a un solo HTML

```bash
cd web && npm run studio:export
```

Crea `web/dist-studio/skyny-studio.html` (unos 950 KB). Ábrelo con doble clic, sin servidor: es el mismo estudio del punto 2. `git status` no lo muestra (está en `.gitignore`).

## 4. Login

Con la app en marcha, abre `/login`: Skyny 3D con el aspecto nuevo. Al poner el foco en la contraseña se tapa los ojos, y con «Mostrar la clave» mira de reojo, igual que antes.

## 5. Agentes

- `AGENTS.md` (raíz) explica idioma, comprobaciones, ramas, PRs y cómo se trabaja con Skyny 3D. `CLAUDE.md` lo importa.
- Abre Codex en el repo y pídele un cambio pequeño en Skyny (p. ej. el texto de un letrero en `led.ts`). Debe tocar `web/src/components/companion/three/` y no crear un HTML aparte.
