# Entrega por PR

Con la GitHub App conectada ([`github.md`](github.md)), un workflow entrega su trabajo como una PR de GitHub y la lleva hasta la fusión (hito EN). Son cuatro fases, que las plantillas `especificar-planificar-implementar`, `implementar-tarea` y `corregir-bug` ya traen al final:

```yaml
  - id: pr                     # EN-A: sube la rama y abre la PR
    type: github-pr
    dependsOn: [revisar, "corregir?"]
    workspaceFrom: revisar
    condition: repository.github
  - id: ci                     # EN-B: espera la CI y, si falla, la corrige
    type: github-check
    dependsOn: [pr]
    condition: repository.github
    fix: implementar
  - id: aprobar-fusion         # una persona decide, viendo la PR y su CI
    type: human-approval
    dependsOn: [ci]
    condition: repository.github
    action: "Fusionar la PR de {{workItem.key}}: {{workItem.title}}"
    risk: high
  - id: fusionar               # EN-C: fusiona el commit aprobado
    type: github-merge
    dependsOn: [aprobar-fusion]
    condition: repository.github
```

Con `condition: repository.github`, en un repositorio sin GitHub (o sin acceso de la App) las cuatro se omiten y la ejecución termina como siempre.

## El recorrido

1. **`pr`.** El runner sube el worktree a la rama `skynet/<clave del trabajo>` con un token de un solo uso, y el control plane abre la PR (o actualiza la que ya hay) con lo que hizo cada fase, sus comprobaciones y el coste.
2. **`ci`.** Espera a que pase la CI de ese commit. Los webhooks de checks y statuses la despiertan; si se pierden, pregunta cada 2 minutos. Si la CI falla, el agente de `fix` sigue en su sesión con lo que falló (checks, anotaciones y el final del log) y el runner sube la corrección. Como mucho, `maxRounds` rondas (2).
3. **`aprobar-fusion`.** La puerta enseña la PR: número y enlace, rama, el commit que se aprueba y la CI de ese commit, check a check. Con riesgo alto, aprobar pide confirmarlo.
4. **`fusionar`.** Antes de fusionar comprueba en GitHub que la PR sigue abierta **en el commit aprobado** y con la CI en verde, y pasa ese `sha` a GitHub, que no fusiona si alguien ha subido algo después. Fusiona con `method` (`squash` por defecto), borra la rama (`deleteBranch`), cierra el trabajo («Cerrado») y pide eliminar los worktrees que se subieron.

En la ejecución, la tarjeta **Pull request** enseña en todo momento la PR, la rama, el commit, la CI con sus rondas y la fusión. En **Actividad**, cada paso tiene su frase («pr abrió la PR #12…», «implementar corrige la CI (ronda 1 de 2)…», «fusionar fusionó la PR #12 y borró la rama…»).

## Lo que puede salir mal

| Qué pasa | Qué hace Skynet |
|---|---|
| Alguien sube a la rama después de aprobar | `fusionar` falla: «La PR cambió después de la aprobación…». La PR se queda como está |
| La CI se pone roja después de aprobar | `fusionar` falla con los checks que fallan; si aún está en marcha, espera |
| GitHub no deja fusionar (conflictos, reglas de la rama) | `fusionar` falla con el mensaje de GitHub |
| Alguien fusiona la PR a mano en GitHub | La fase `fusionar` cuenta como hecha (`external: true`) y el trabajo se cierra |
| Alguien cierra la PR sin fusionarla | La ejecución se cancela con el motivo «su PR se cerró sin fusionarla» |
| Se agota el tiempo de `fusionar` (30 min por defecto) | La fase falla y ya no se fusiona |

Una fase que falla se reintenta como cualquier otra (**Reintentar fase**): vuelve a comprobar el commit y la CI.

## Lo que necesita el repositorio

- La App instalada en él, con los permisos de [`github.md`](github.md) §1: Contents y Pull requests de escritura, Checks y Commit statuses de lectura y, para corregir con los logs, Actions de lectura.
- Una CI que se lance en las PR, en `.github/workflows/`. Skynet nunca la escribe (la App no tiene el permiso Workflows y el runner no sube cambios en esa carpeta). Sin CI, `ci` pasa a los 5 minutos con un aviso (o falla con `ifNoChecks: fail`).
- Si la rama por defecto exige revisiones o checks concretos, GitHub no dejará fusionar hasta cumplirlos y `fusionar` fallará con su mensaje.

Para probarlo de punta a punta, `guilu/demo-repo` necesita un `.github/workflows/ci.yml` como este (lo añade una persona, a mano):

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Tests
        run: |
          if compgen -G "test_*.py" > /dev/null || compgen -G "tests/*.py" > /dev/null; then
            pip install --quiet pytest
            pytest -q
          else
            python -c "from calc import add; assert add(2, 3) == 5, 'add(2, 3) debería dar 5'"
          fi
```

## API

- `GET /api/workflow-runs/{id}/pull-requests`: la PR de la ejecución.
- `GET /api/workflow-runs/{id}/ci-checks`: cada espera de la CI, con su ronda y sus checks.
- `GET /api/workflow-runs/{id}/merges`: cada fusión, con el método, el commit esperado, el de la fusión, si se borró la rama y el error.
- La evidencia de una aprobación (`GET /api/approvals/{id}`) lleva `pullRequest` cuando desbloquea una `github-merge`.

## Pruebas

- `MergeIT` y `CiCheckIT` (control plane, con un GitHub falso) cubren cada caso de la tabla.
- `web/e2e/delivery.e2e.ts` recorre las cuatro fases de punta a punta contra el GitHub falso de `web/e2e/fake-github.ts` y un remoto local (`SKYNET_GITHUB_GIT_URL=file://…`), sin salir a GitHub.
