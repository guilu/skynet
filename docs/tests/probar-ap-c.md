# Probar AP-C - Lecciones en la web y criterios aprendidos en el juez

AP-C (#286) es la tercera y última PR del hito AP (Aprendizaje continuo). Lo explica [`docs/workflows.md`](../workflows.md), en «Lecciones en la web y criterios aprendidos (AP-C)».

- **Fábrica → Lecciones** (`/lessons`), con las pestañas Propuestas, Aplicadas y Descartadas. Cada lección tiene:
  - su clase, su tipo y en cuántas ejecuciones salió;
  - sus hallazgos, con enlace a su ejecución;
  - el texto, editable mientras está propuesta;
  - los botones **Aplicar**, **Descartar** (pide motivo) y **Copiar para REVIEW.md**.
- **Ficha del repositorio:** la sección «Lecciones aplicadas», con **Desactivar** (pide motivo). Desactivar una `prevenir` archiva su restricción de la memoria.
- **Criterios aprendidos en el juez:**
  - las lecciones `no-senalar` aplicadas entran en el prompt del juez, en «## Lo aprendido en este repositorio», debajo de los criterios de `REVIEW.md`;
  - también en los escépticos y los jueces del delta;
  - el juez sigue con la memoria apagada.
- **La línea del worktree** dice «…, con N criterios aprendidos».
- **API:** `GET /api/factory/lessons?status=`, y `POST /api/lessons/{id}/dismiss` también desactiva una aplicada (evento `lesson.disabled`).
- **El aviso de Skyny** «He aprendido algo» lleva a Fábrica → Lecciones.
- **Sin migración.**

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs. Los agentes son fake-claude, así que no se gasta cuota ni hay regeneraciones caras.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*WorkflowEngineIT' --tests '*JudgePromptTest' \
  :runner:test --tests '*RepositoryReviewTest'
cd web && npx vitest run src/LessonsPage.test.tsx src/format.test.ts src/components/companion/notices.test.ts
```

- `WorkflowEngineIT.theSameClassInTwoRunsProposesALessonThatCanBeAppliedOrDismissed`, la parte de AP-C:
  - aplicada, la `no-senalar` llega al prompt del juez siguiente con «## Lo aprendido en este repositorio», su texto y la línea `<!-- skynet:learned 1 -->`;
  - `/api/factory/lessons?status=aplicada` da las dos, con el nombre del proyecto;
  - desactivar sin motivo da 400. Con motivo, la `prevenir` queda descartada, guarda el texto aplicado y su restricción de la memoria queda archivada. Repetirlo da 409;
  - desactivada la `no-senalar`, el juez siguiente ya no recibe la sección.
- `JudgePromptTest`: la sección va justo después del sitio de `REVIEW.md`, en el juez, el escéptico y el delta. Sin lecciones aplicadas, no sale.
- `RepositoryReviewTest`: el runner cuenta y quita la línea `<!-- skynet:learned N -->`.
- `LessonsPage.test.tsx`:
  - aplica con el texto editado;
  - descartar no deja confirmar sin motivo;
  - la pestaña Aplicadas dice quién la aplicó, y **Copiar para REVIEW.md** copia el texto;
  - la ficha del repositorio enseña solo las suyas y las desactiva con un motivo.
- `format.test.ts`: «Worktree listo en la rama …, con los criterios de REVIEW.md y 2 criterios aprendidos», «con 1 criterio aprendido» y «desactivó la lección».
- `notices.test.ts`: el aviso «He aprendido algo» lleva a `/lessons`.

## 2. Skynet desplegado, sin GitHub

El runner usa fake-claude. Añade a su entorno `FAKE_CLAUDE_JUDGE=/tmp/ap-c/suspender` y `FAKE_CLAUDE_REFUTE=/tmp/ap-c/refutar`, pon los dos nombres en `SKYNET_AGENT_ENV` y reinícialo. Haz `mkdir -p /tmp/ap-c` en esa máquina. `demo-repo` se usa sin URL remota y sin `REVIEW.md`.

Publica en **Workflows → Nuevo**. Usa como juez un agente que exista en el despliegue, p. ej. el juez de **Implementar una tarea**.

```yaml
id: ap-c-aprendido
name: Criterios aprendidos
stages:
  - id: implementar
    type: agent
    prompt: Haz el cambio
  - id: juzgar
    type: verification
    dependsOn: [implementar]
    judge:
      agents: [juez]
      refute: 1
    fix: implementar
    maxRounds: 2
```

### 2.1 Una lección «no señalar», en la web

1. `touch /tmp/ap-c/refutar`: mientras exista, los escépticos refutan todo.
2. Lanza `ap-c-aprendido` en dos trabajos nuevos de `demo-repo`. Antes de cada uno, escribe su testigo:

   ```bash
   echo '![estilo] src/calc.py:5 El nombre no se entiende' > /tmp/ap-c/suspender    # primer trabajo
   echo '![estilo] src/export.py:2 Nombre poco claro' > /tmp/ap-c/suspender         # segundo trabajo
   ```

   En los dos, el escéptico refuta el hallazgo y `juzgar` aprueba sin corregir.
3. Tras el segundo, Skyny avisa «He aprendido algo». Su enlace **Revisar** abre **Fábrica → Lecciones**.
4. En **Propuestas**, bajo «… › demo-repo», hay una tarjeta «No señalar · Estilo · en 2 ejecuciones · 2 hallazgos».
5. **Ver los hallazgos (2)** enseña los dos, «Refutado», cada uno con un enlace a su ejecución.
6. Cambia el texto por `No señales los nombres de variable cortos: aquí son la convención.`
7. **Copiar para REVIEW.md** cambia a «Copiado», y al pegar sale tu texto.
8. **Aplicar en los criterios del juez**. La tarjeta pasa a **Aplicadas** con «Aplicada por admin el …».

### 2.2 El juez la recibe

1. `rm /tmp/ap-c/refutar` y lanza un trabajo nuevo sin testigo.
2. En la conversación del juez:
   - el prompt tiene «## Lo aprendido en este repositorio», la explicación y tu texto, después de la rúbrica y antes de «## Los hallazgos»;
   - la línea del worktree dice «Worktree listo en la rama …, con 1 criterio aprendido».
3. Para el escéptico: `touch /tmp/ap-c/refutar`, escribe `echo '![estilo] src/x.py:1 Otro nombre' > /tmp/ap-c/suspender` y lanza otro trabajo. El prompt del escéptico también trae la sección, antes de «## Qué contestar».

### 2.3 Una lección «prevenir», en la memoria

1. `rm -f /tmp/ap-c/refutar`. Lanza dos trabajos nuevos, cada uno con su testigo:

   ```bash
   echo '[caso-limite] src/calc.py:3 No trata la lista vacía' > /tmp/ap-c/suspender    # primer trabajo
   echo '[caso-limite] src/export.py:8 No prueba la lista vacía' > /tmp/ap-c/suspender # segundo trabajo
   ```

2. En **Propuestas** sale «Prevenir · Caso límite · en 2 ejecuciones». **Aplicar en la memoria**.
3. En el proyecto, pestaña **Memoria**, con `demo-repo`, hay una restricción fijada «Lección: caso-limite».

### 2.4 Desactivarlas desde el repositorio

1. Abre el proyecto, pestaña **Configuración**. La ficha de `demo-repo` tiene «Lecciones aplicadas» con las dos.
2. En la de Estilo, **Desactivar**: el botón de confirmar no se activa sin motivo. Escribe `Ya no es la convención` y confirma. Desaparece de la ficha.
3. En **Fábrica → Lecciones → Descartadas** sale «Desactivada por admin el …: Ya no es la convención», con el texto que se aplicó. **Actividad** tiene «admin desactivó la lección `estilo` de demo-repo: Ya no es la convención».
4. Lanza un trabajo nuevo sin testigo. El prompt del juez ya no tiene «Lo aprendido», y la línea del worktree vuelve a «Worktree listo en la rama …».
5. Desactiva también la de Caso límite. En **Memoria**, la restricción «Lección: caso-limite» queda archivada.

## 3. Al terminar

- Archiva `ap-c-aprendido`.
- Quita `FAKE_CLAUDE_JUDGE` y `FAKE_CLAUDE_REFUTE` del runner, borra `/tmp/ap-c` y reinícialo.
- En el checkout de skynet, `git status --porcelain` está vacío y `git rev-parse HEAD` es el SHA de la PR.
