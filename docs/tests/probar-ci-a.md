# Probar CI-A - E2E más rápidas y bajo demanda

CI-A saca las E2E de `ci.yml` a su propio workflow, `.github/workflows/e2e.yml`. En una PR corren
solo las rápidas (3 shards) y solo si la PR toca algo que afecta a las E2E; las cuatro `@lento`
(4 shards, una por shard) corren en cada push a `main`, a mano o con la etiqueta `e2e-completo`.
El check `E2E` resume el resultado.

Antes, el shard 1/3 tardaba ~17,5 min porque le tocaban todas las pesadas. Ahora ningún shard
debería pasar de ~7 min.

## 1. Pruebas del script que decide

```bash
scripts/tests/e2e-alcance.test.sh        # «e2e-alcance: 32 bien, 0 mal»
```

Cubren: push y `workflow_dispatch` lanzan todas; una PR de `docs/`, `.md`, `deploy/`,
`workflows/*.yaml`, `scripts/reset-local.sh` o `ci.yml` no lanza ninguna; una PR que toca
`control-plane/`, `runner/`, `protocol/`, `tools/`, `web/`, `fixtures/`, Gradle o los scripts de E2E
lanza solo las rápidas; con `e2e-completo` (y solo con esa etiqueta exacta) corren todas.

## 2. Reparto de las pruebas

```bash
cd web
npx playwright test --list --grep @lento                 # approvals, nodes, providers (revisión), spec
for s in 1 2 3; do npx playwright test --list --grep-invert @lento --shard=$s/3 | tail -1; done
cd ..
```

Las rápidas son 23 pruebas en 3 shards (8, 9 y 6) y las lentas 4, una por shard.

## 3. En la propia PR de CI-A

1. La PR toca `web/` y `e2e.yml`, así que en **Checks** aparecen «Qué E2E corren», **E2E rápidas
   1/3, 2/3, 3/3** y **E2E** (resumen). No aparece ningún shard de lentas. Los jobs Java, Web y
   Compose siguen en el workflow CI.
2. Pon la etiqueta **`e2e-completo`** en la PR. Se relanza solo el workflow E2E (CI no) con las 3
   rápidas y **E2E lentas 1/4…4/4**. Ningún shard debería pasar de ~7 min.
3. Pon otra etiqueta cualquiera (p. ej. `documentation`): no se cancela nada en curso y la ejecución
   nueva sale con todos los jobs omitidos.
4. Actions → **E2E** → **Run workflow** → rama `claude/project-thread-y7sbg8`: corren las 7 partes.

## 4. Una PR que no toca las E2E

Crea una rama que solo cambie un `.md` de `docs/` y abre una PR (luego ciérrala sin fusionar). En
el workflow E2E, «Qué E2E corren» dice `rapidas=false`, no hay shards y el check **E2E** sale en
verde con «La PR no toca nada de las E2E».

## 5. Tras fusionar

El push a `main` lanza las 7 partes. En las PRs siguientes, si quieres las lentas antes de fusionar
(p. ej. al tocar el motor, aprobaciones o plantillas), pon `e2e-completo`.

## 6. En local

```bash
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh --grep-invert @lento  # rápidas
SKYNET_DB_URL=jdbc:postgresql://localhost:5432/skynet_e2e scripts/e2e.sh --grep @lento         # lentas
```

La protección de `main` (ruleset MainProtect) no exige checks, así que no hay que tocarla. Si algún
día se exigen, basta con exigir **E2E**.
