# Probar VA-A - Rutas protegidas y tests intactos

VA-A (#218) es la primera PR del hito VA (validación independiente). Hasta ahora, un agente podía ponerse en verde borrando el test que falla o poniéndole `@Disabled`, y podía cambiar la guía de pruebas o la CI. Con VA-A, esa fase ya no pasa su verificación.

- **Check `tests-intact`**:
  - mira el diff de la fase, sin ejecutar nada;
  - falla si se borra un fichero de tests, se saca de donde están los tests, tiene menos casos (`@Test`, `it(`, `def test_`, `func Test…`) o se añade una forma de saltarlos (`@Disabled`, `@Ignore`, `.skip`, `.only`, `xit`, `@pytest.mark.skip`, `t.Skip()`…);
  - si solo hay menos aserciones, avisa sin fallar.
- **`tests` incluye `tests-intact`** sin tocar el YAML. Una fase con `allowTestRemoval: true` lo quita.
- **Rutas protegidas**:
  - se declaran con `protected:` en la raíz del workflow o en una fase, y en la ficha del repositorio (**Configurar verificación → Rutas protegidas**). Las tres se suman;
  - toda fase `agent` o `parallel` lleva entonces el check `protected`, que falla si cambia, borra o renombra algo de esas rutas;
  - el agente recibe una línea antes de su prompt («Rutas protegidas: no cambies…»), y Claude Code además `--disallowedTools Edit(<glob>)`.
- **Avisos al publicar**:
  - `protected` en una fase que no es `agent` ni `parallel`;
  - una ruta que también está en `scope`;
  - `allowTestRemoval` sin `tests`.

  `allowTestRemoval: true` junto con `tests-intact` es un error.
- **Web**:
  - el formulario de verificación del repositorio tiene «Rutas protegidas», con las sugerencias `.github/workflows/**`, `docs/tests/**` y `**/acceptance/**`;
  - la ficha del repositorio las enseña;
  - en la pestaña **Verificación** del agente, los avisos de un check salen con ⚠ debajo.
- **API**:
  - `PUT /api/projects/{id}/repositories/{rid}/verification` admite `protectedPaths`. Sin ese campo, se quedan como estaban; una ruta que sale del repositorio da 400;
  - la vista del repositorio trae `protectedPaths`;
  - cada check de una verificación trae `warnings`.
- **Migración `V40`**: `repository.protected_paths` (`text[]`, vacío por defecto). Se aplica sola al arrancar.
- **fake-claude**: con `FAKE_CLAUDE_CHEAT=<fichero>`, si el fichero existe, la invocación hace trampa al terminar bien y borra el testigo:
  - si el fichero contiene `<ruta>`, borra ese fichero de tests;
  - si contiene `disable <ruta>`, le pone `@Disabled` a su primer `@Test`.

  En los dos casos, lo confirma con un commit.

Ninguna prueba de esta guía modifica ficheros versionados de skynet: lo que se crea va a `/tmp/va-a`. Nada gasta cuota real. La sección 4 usa `guilu/demo-repo` y nunca skynet.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*DefinitionParserTest' --tests '*WorkflowEngineIT' \
  --tests '*ContractIT' --tests '*ProjectControllerIT' \
  :runner:test --tests '*TestIntegrityTest' --tests '*StageChecksTest' --tests '*ClaudeCodeProviderTest' \
  :tools:fake-claude:test
cd web && npx vitest run src/contracts.test.ts src/AgentPolicy.test.tsx src/components/run
```

- `TestIntegrityTest` detecta:
  - un test borrado (con su nombre, «`CalculadoraTest.divideEntreCero`») y un fichero borrado, también sin confirmar;
  - un `@Disabled` añadido y un fichero de tests movido fuera de los tests;
  - `.skip` y `.only` en TypeScript, `@pytest.mark.skip` y `t.Skip()`.

  No cuenta como problema:
  - renombrar un fichero dentro de los tests y añadir casos;
  - quitar una aserción, que solo avisa.
- `StageChecksTest`: `protected` falla al mover un fichero protegido (cuenta la ruta vieja) y pasa si solo cambian otros; `tests-intact` devuelve sus avisos.
- `ClaudeCodeProviderTest`: las rutas protegidas llegan como `--disallowedTools Edit(docs/tests/**),Edit(.github/workflows/**)`.
- `DefinitionParserTest`:
  - `tests` añade `tests-intact` detrás;
  - `allowTestRemoval` lo quita;
  - `protected` de la raíz y de la fase se suman y van al final de las fases `agent` y `parallel`;
  - los avisos y errores nuevos.
- `fake-claude:test`: `FAKE_CLAUDE_CHEAT` desactiva y borra un test, cada vez con su commit, y solo una vez por testigo.
- `ContractIT` y `contracts.test.ts`: los checks de las verificaciones llevan `warnings`.

## 2. En local, con los CLIs falsos

Prepara un repositorio de prueba con un test de Java y arranca todo con el modo «tramposo» de fake-claude:

```bash
rm -rf /tmp/va-a && mkdir -p /tmp/va-a/repo/src/test/java/demo /tmp/va-a/repo/docs/tests
cd /tmp/va-a/repo && git init -q -b main
cat > src/test/java/demo/CalculadoraTest.java <<'JAVA'
package demo;

import org.junit.jupiter.api.Test;

class CalculadoraTest {
  @Test
  void suma() {}

  @Test
  void divideEntreCero() {}
}
JAVA
echo '# Guía' > docs/tests/probar.md
echo 'print(1)' > calc.py
git add -A && git -c user.email=t@t -c user.name=t commit -qm base
cd -

SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_RUNNER_HOME=/tmp/va-a/runner \
SKYNET_CLAUDE_BIN=$FAKE/fake-claude SKYNET_CODEX_BIN=$FAKE/fake-codex SKYNET_AGY_BIN=$FAKE/fake-agy \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_CHEAT \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 FAKE_CLAUDE_CHEAT=/tmp/va-a/trampa \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner

cd web && npm run dev
```

En la web, registra `/tmp/va-a/repo` en un proyecto y, en **Configurar verificación**, pon el comando `true` (así `tests` pasa siempre: lo que se prueba es `tests-intact`). Mientras no exista `/tmp/va-a/trampa`, fake-claude funciona como siempre.

### 2.1 Borrar el test que falla

1. Publica en **Workflows → Nuevo**:

   ```yaml
   id: va-a-tramposo
   name: Tramposo de VA-A
   stages:
     - id: implement
       type: agent
       prompt: Arregla calc.py y deja los tests en verde
       checks: [tests, diff]
   ```

   El resumen del workflow enseña, en `implement`, los checks `tests`, `tests-intact` y `diff`.
2. `echo src/test/java/demo/CalculadoraTest.java > /tmp/va-a/trampa` y lánzalo sobre un trabajo del repositorio.
3. La fase `implement` acaba **Fallida**:
   - en el progreso dice «No pasó tests-intact: Se ha borrado el fichero de tests `src/test/java/demo/CalculadoraTest.java` (2 tests)»;
   - en la pestaña **Verificación** del agente: ✓ `tests`, ✗ `tests-intact` con ese motivo, y ✓ `diff`.
4. `/tmp/va-a/trampa` ya no existe.

### 2.2 Desactivar un test

El agente trabajó en su worktree: `git -C /tmp/va-a/repo status` sigue limpio y el test sigue en el checkout.

1. `echo 'disable src/test/java/demo/CalculadoraTest.java' > /tmp/va-a/trampa` y lanza otra vez el workflow.
2. Falla con «No pasó tests-intact: Se ha añadido `@Disabled` en `src/test/java/demo/CalculadoraTest.java`».

### 2.3 `allowTestRemoval`

1. Publica una versión nueva con `allowTestRemoval: true` en `implement`. Al publicar no hay avisos y el resumen ya no enseña `tests-intact`.
2. `echo src/test/java/demo/CalculadoraTest.java > /tmp/va-a/trampa` y lánzala: la fase termina **bien**.
3. Cambia `checks: [tests, diff]` por `checks: [diff]` y deja `allowTestRemoval: true`: el editor avisa «`allowTestRemoval` no cambia nada sin el check `tests`». Pon `checks: [tests-intact, diff]` con `allowTestRemoval: true`: es un error («contradice el check `tests-intact`»).

### 2.4 Rutas protegidas

1. En la ficha del repositorio, **Configurar verificación**:
   - el formulario tiene «Rutas protegidas» con tres sugerencias;
   - pulsa **+ docs/tests/\*\***, **Guardar verificación** y cierra;
   - la ficha dice «Rutas protegidas: `docs/tests/**`».
2. Publica:

   ```yaml
   id: va-a-guia
   name: Toca la guía
   stages:
     - id: implement
       type: agent
       prompt: Arregla calc.py
       checks: [diff]
   ```

3. Sin trampa (`rm -f /tmp/va-a/trampa`), y con `FAKE_CLAUDE_APPLY=1`, fake-claude solo cambia `calc.py`: lanza el workflow y la fase termina **bien**. En **Verificación**, ✓ `diff` y ✓ `protected` `docs/tests/**`.
4. En el **Registro** del agente (o en el evento `agent.spawned` de Actividad), el prompt empieza por «Rutas protegidas: no cambies, borres ni muevas nada en `docs/tests/**`…», y en `agent.spawned` está `protectedPaths: ["docs/tests/**"]`.
5. `echo docs/tests/probar.md > /tmp/va-a/trampa` (la «trampa» borra la guía) y lanza otra vez: la fase falla con «No pasó protected: Ha cambiado una ruta protegida: `docs/tests/probar.md`».
6. **Al publicar**, en un workflow nuevo:
   - pon `scope: ["docs/**"]`, `checks: [scope]` y `protected: [docs/tests/**]` en una fase `agent`: sale el aviso «`docs/tests/**` está en `scope` y en `protected`: gana `protected`…»;
   - pon `protected` en una fase `command`: sale el aviso «`protected` solo vale en fases `agent` o `parallel`…».

## 3. La API

```bash
api() { curl -s -u admin:secreto -H 'Content-Type: application/json' "$@"; }
P=<id-del-proyecto>; R=<id-del-repositorio>
api -X PUT -d '{"validationCommand": "true", "protectedPaths": ["docs/tests/**", ".github/workflows/**"]}' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification | jq .protectedPaths
# ["docs/tests/**", ".github/workflows/**"]
api -X PUT -d '{"validationCommand": "true"}' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification | jq .protectedPaths
# sin el campo, se quedan: ["docs/tests/**", ".github/workflows/**"]
api -X PUT -d '{"validationCommand": "true", "protectedPaths": ["../fuera"]}' -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification
# 400
api -X PUT -d '{"validationCommand": "true", "protectedPaths": []}' \
  http://localhost:8080/api/projects/$P/repositories/$R/verification | jq .protectedPaths
# []
```

## 4. En la máquina de Skynet, sobre `guilu/demo-repo` (Hermes)

El runner omarchy usa fake-claude, así que no se gasta cuota. Añade al entorno del runner `FAKE_CLAUDE_CHEAT=/tmp/va-a/trampa` (y `FAKE_CLAUDE_CHEAT` en `SKYNET_AGENT_ENV`) y reinícialo.

1. Con el despliegue en la versión de la PR, en la máquina del runner, `mkdir -p /tmp/va-a` y elige un fichero de tests de `demo-repo`: `git -C <checkout de demo-repo> ls-files | grep -iE 'test'`. Escribe su ruta en `/tmp/va-a/trampa`.
2. Lanza **Implementar una tarea** sobre `demo-repo`, desde la web o desde una issue con la etiqueta `skynet:<clave>` si los disparadores de DS están configurados.
3. Comprueba:
   - la fase `implementar` falla con «No pasó tests-intact: Se ha borrado …» y el nombre del fichero o del test;
   - el workflow no llega a `revisar` ni a `pr`, así que no se abre ninguna PR.
4. Sin trampa, la misma ejecución termina como antes:
   - en la verificación de `implementar` sale ✓ `tests-intact`;
   - con rutas protegidas en el repositorio, también ✓ `protected`.
5. **Deja todo como estaba**:
   - cierra la issue si la abriste;
   - quita `FAKE_CLAUDE_CHEAT` del runner y reinícialo;
   - vacía las rutas protegidas de `demo-repo` si las pusiste.

## 5. Al terminar

- `rm -rf /tmp/va-a`.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
