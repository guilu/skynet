# Probar: las PRs «UI:» de solo web no lanzan las E2E

## Qué cambia

`scripts/ci/e2e-alcance.sh` recibe el título de la PR (`TITULO`). Si empieza por `UI:` y todo lo que toca
la PR (sin contar los `.md`) está en `web/`, fuera de `web/e2e/` y de `web/playwright.config.ts`, no se
lanzan las E2E y el check `E2E` sale verde con «La PR no toca nada de las E2E». Siguen corriendo:

- si la PR toca las propias E2E o algo fuera de `web/` (backend, Gradle, scripts de E2E…);
- con la etiqueta `e2e-completo`;
- en cada push a `main`, es decir, al fusionar la PR.

## Cómo comprobarlo

1. `scripts/tests/e2e-alcance.test.sh` → «41 bien, 0 mal».
2. En una PR «UI: …» que solo toque `web/src/`, el job «Qué E2E corren» escribe `rapidas=false` y no
   aparecen los «E2E rápidas N/6».
3. Ponle la etiqueta `e2e-completo`: se lanzan todas.
4. Una PR sin «UI:» que toque `web/src/` sigue lanzando las rápidas.
