# Probar AP-A - La clase de cada hallazgo

AP-A (#284) es la primera PR del hito AP (Aprendizaje continuo). Lo explica [`docs/workflows.md`](../workflows.md), en «La clase de cada hallazgo (AP-A)».

- **El juez dice la clase de cada hallazgo** (`category`), de una lista cerrada: `tests`, `caso-limite`, `errores`, `entradas`, `secretos`, `concurrencia`, `rendimiento`, `api`, `datos`, `alcance`, `estilo`, `documentacion` u `otro`. Su prompt explica cada una.
- **Si falta o no es de la lista**, el hallazgo queda en `otro`. El veredicto no se rechaza.
- **Migración V53:** `finding.category`. Lo de antes queda en `otro`.
- **Web:**
  - el registro de la fase enseña la clase delante de cada hallazgo, salvo `otro`;
  - **Fábrica → Jueces** tiene el filtro **Clase**.
- **API:** `GET /api/factory/judges?category=…`, con 400 si la clase no existe.
- **fake-claude:** el testigo de `FAKE_CLAUDE_JUDGE` admite la clase entre corchetes delante, p. ej. `[caso-limite] src/x.py:3 texto`. Sin ella, `tests`.

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*JudgeVerdictsTest' --tests '*JudgePromptTest' --tests '*WorkflowEngineIT' \
  :tools:fake-claude:test --tests '*FakeJudgeTest'
cd web && npx vitest run src/components/run/StageFindings.test.tsx src/JudgesPage.test.tsx
```

- `JudgeVerdictsTest.eachFindingHasACategoryFromTheListAndAnUnknownOneIsOther`:
  - el esquema exige `category` con la lista;
  - « Caso_Limite » vale como `caso-limite`;
  - una clase desconocida o que falta queda en `otro`.
- `JudgePromptTest`: el prompt del juez lista las clases con su explicación.
- `WorkflowEngineIT.findingsKeepTheirFingerprintAndOutcomeAcrossRounds`:
  - el hallazgo del README guarda `documentacion` y el que no dice clase queda en `otro`;
  - `?category=documentacion` cuenta solo ese;
  - `?category=magia` da 400.
- `FakeJudgeTest`: `[caso-limite]` delante del testigo pone la clase, sin ella sale `tests`, y sin `category` en el esquema no la pone.
- `StageFindings.test.tsx`: «Aparcado Caso límite src/Calc.java:14 …», y `otro` no sale.
- `JudgesPage.test.tsx`: el filtro **Clase** pide `?category=caso-limite` y lo deja elegido.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_JUDGE=/tmp/ap-a/suspender`, pon el nombre en `SKYNET_AGENT_ENV` y reinícialo. Haz `mkdir -p /tmp/ap-a` en esa máquina. `demo-repo` se usa sin URL remota.

Publica en **Workflows → Nuevo**. Usa como juez un agente que exista en el despliegue, p. ej. el juez de **Implementar una tarea**.

```yaml
id: ap-a-clase
name: Clase de los hallazgos
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [juez]
    fix: implementar
    maxRounds: 2
```

### 2.1 Un hallazgo con su clase

1. Escribe el testigo y lanza `ap-a-clase` en un trabajo nuevo de `demo-repo`:

   ```bash
   echo '[caso-limite] src/calc.py:3 No trata la lista vacía' > /tmp/ap-a/suspender
   ```

2. La ronda 1 de `juzgar` suspende y `implementar` corrige. La ronda 2 ya no tiene testigo, así que aprueba.
3. En la fase `juzgar`, el **Registro de hallazgos** dice «Corregido Caso límite src/calc.py:3 No trata la lista vacía».
4. En la conversación del juez, el prompt tiene «Y su clase (`category`), una de estas:» con las 13 clases.

### 2.2 Sin clase o con una que no existe

1. `echo '[magia] src/calc.py:3 Algo raro' > /tmp/ap-a/suspender` y lanza en un trabajo nuevo.
2. El registro dice «Corregido src/calc.py:3 Algo raro», sin clase: fake-claude manda `magia` y Skynet la deja en `otro`.

### 2.3 Fábrica → Jueces

1. Abre **Fábrica → Jueces**. La fila del juez cuenta los dos hallazgos.
2. En **Clase**, elige **Caso límite**. La URL lleva `?category=caso-limite` y la fila cuenta 1 hallazgo, corregido.
3. Vuelve a **Todas**: cuenta 2 otra vez.

## 3. Al terminar

- Archiva `ap-a-clase`.
- Quita `FAKE_CLAUDE_JUDGE` del runner, borra `/tmp/ap-a` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
