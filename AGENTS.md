# Guía para agentes (Codex, Claude y compañía)

Skynet es una plataforma web para orquestar, observar y auditar flujos agénticos de desarrollo. Este fichero es lo que lee primero cualquier agente que trabaje en el repo (Codex lo lee como `AGENTS.md`; Claude Code, a través de `CLAUDE.md`). El detalle está en el [README](README.md) y en `docs/`.

## Idioma y estilo

- Todo en **español**: textos de la interfaz, documentación, comentarios del código, mensajes de commit y PRs.
- La interfaz sigue [`docs/ui.md`](docs/ui.md): tokens de color de `web/src/index.css` (nada de hex sueltos), sin sombras en los paneles, pesos ≤ 600, sin mayúsculas en los textos.
- Las fuentes «Skynet Duo» (`/fonts` del proyecto) **no se suben al repo**; la web usa Barlow.

## Estructura

| Directorio | Qué hay |
|---|---|
| `control-plane/` | API Spring Boot (Java 21, PostgreSQL, Flyway) |
| `runner/` | Daemon que ejecuta los agentes en worktrees |
| `protocol/` | Contrato compartido control plane ↔ runner |
| `web/` | Frontend React + TypeScript + Vite |
| `docs/` | Documentación; `docs/tests/` tiene las guías de prueba de cada PR |

## Comprobar antes de subir

```bash
# Web (desde web/)
npm ci
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run

# Java (desde la raíz)
./gradlew build          # ./gradlew spotlessApply para formatear
```

## Ramas, commits y PRs

- Nunca directamente en `main`: una rama por cambio (`codex/<tema>` si la crea Codex, `claude/<tema>` si la crea Claude).
- El trabajo va por **hitos** con un prefijo de dos letras y una PR por parte: título de la PR «`XX-A - Nombre`» (p. ej. «SV-A - Estudio de Skyny v2 en el repo»).
- Cada PR lleva su guía de prueba en `docs/tests/probar-<xx-a>.md` (el título en minúsculas, con guion): qué cambia y cómo comprobarlo a mano.
- PRs listas para revisar (no en borrador). Se fusionan con *squash*.
- PRs pequeñas: sin mezclar cambios de base de datos, API, seguridad o CI con cambios de la web.

## Skyny 3D y su estudio

Skyny, la mascota, tiene una versión 3D hecha con three.js en `web/src/components/companion/three/`. El **estudio** donde se diseña está dentro del repo y usa ese mismo código: lo que cambias en el estudio es lo que sale en la web.

- Abre el estudio con `cd web && npm run dev` → <http://localhost:5173/skyny-studio.html>.
- **No hay HTML suelto que mantener.** Los cambios se hacen en los módulos de `three/` (cuerpo, modelo, cara, letreros, accesorios, gestos, transiciones), no en una copia del estudio.
- Para enseñarlo sin `npm run dev`: `npm run studio:export` genera `web/dist-studio/skyny-studio.html`, un único fichero que se abre con doble clic. Es una salida generada: no se edita ni se sube.
- Las reglas (colores por tokens, nada de CDN, sin cálculos pesados al arrancar, liberar memoria…) y qué hay en cada fichero: [`docs/ui/skyny-3d.md`](docs/ui/skyny-3d.md).
