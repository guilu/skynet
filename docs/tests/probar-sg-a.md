# Probar SG-A - Vulnerabilidades de dependencias npm

SG-A ([#172](https://github.com/guilu/skynet/issues/172)) deja `npm audit` de la web a cero.

Qué trae (solo dependencias de `web/`):
- **`dompurify` 3.4.15 → 3.4.17** (crítica). Llega a través de `monaco-editor`; se fuerza con `overrides` en `package.json` para no bajar Monaco a 0.56 como propone `npm audit fix --force`.
- **`vite` 8.3.2 → 8.3.5** (alta, servidor de desarrollo) y **`undici` 7.30.0 → 7.30.1** (alta, solo jsdom en los tests), con `npm audit fix`.
- De rebote suben de parche `rolldown`, `postcss`, `nanoid` y `@oxc-project/types`.

No hay cambios de código, ni en el servidor, ni migraciones.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 y 3 usan la web del control plane local. No hace falta lanzar ejecuciones.

## 1. Auditoría y tests

```bash
cd web && npm ci && npm audit && npm run lint && npm run format:check && npm run typecheck && npx vitest run && npm run build
```

- `npm audit` termina con «found 0 vulnerabilities».
- `npm ls dompurify` muestra `dompurify@3.4.17 overridden` bajo `monaco-editor`.
- El resto, en verde como siempre.

## 2. Monaco sigue funcionando

dompurify lo usa el editor Monaco, así que hay que comprobar las vistas que lo cargan:
1. **Workflows › un workflow › Editar:** el editor YAML se abre, colorea, autocompleta y valida.
2. **Una ejecución › Diff:** el diff se ve con colores y se puede desplazar.
3. **Una ejecución › Logs:** el visor de logs se abre.

En la consola del navegador no aparecen errores nuevos.

## 3. Modo desarrollo

`cd web && npm run dev` arranca con Vite 8.3.5 y la web carga igual que antes.
