# Probar W5-C en local

W5-C (#94) lleva las puertas de aprobación a la web:
- el menú **Aprobaciones**, con su contador;
- la página de cada petición, desde la que se decide;
- la puerta en el progreso de la ejecución;
- las pendientes en el dashboard.

Además, la plantilla «Especificar, planificar e implementar» gana la puerta `aprobar-plan`. Con esto termina el hito W5. No hay migraciones nuevas.

## 1. Arrancar

Igual que en W5-B: control plane, web y runner con fake-claude, y el repositorio de prueba `/tmp/w4b` (con `calc.py`).

```bash
SKYNET_ADMIN_PASSWORD=secreto SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> ./gradlew :control-plane:bootRun
cd web && npm run dev

./gradlew :runner:installDist :tools:fake-claude:installDist
FAKE=$PWD/tools/fake-claude/build/install/fake-claude/bin
SKYNET_CLAUDE_BIN=$FAKE/fake-claude \
SKYNET_AGENT_ENV=FAKE_CLAUDE_FIXTURE,FAKE_CLAUDE_APPLY,FAKE_CLAUDE_STRUCTURED_OUTPUT \
FAKE_CLAUDE_FIXTURE=02-tools FAKE_CLAUDE_APPLY=1 \
FAKE_CLAUDE_STRUCTURED_OUTPUT='{"summary":"Que add() sume"}' \
SKYNET_RUNNER_REGISTRATION_TOKEN=<secreto-runner> runner/build/install/skynet-runner/bin/skynet-runner
```

Usa el workflow `con-puerta` de W5-B. Si no lo tienes, publícalo con el YAML de `probar-w5b.md` y quita la línea `checks` de `plan`.

## 2. La plantilla

1. **Workflows** → **Nuevo workflow** → plantilla «Especificar, planificar e implementar».
2. En «Qué define» hay 4 fases. `aprobar-plan` dice «Pide aprobar "Aprobar el plan de {{workItem.key}} (openspec/tasks.md): …" · Riesgo medio · Corrige plan».
3. No la publiques: `spec` necesita escribir `openspec/proposal.md`, y fake-claude no lo hace.

## 3. La puerta en la web

1. Lanza `con-puerta` en un trabajo del repositorio de prueba. Espera a que termine `plan`.
2. **Progreso:** `aprobar-plan` está en ámbar con «Esperando aprobación». Al pulsarla se ven la acción, «Riesgo alto» y un botón **Revisar**.
3. **Menú:** **Aprobaciones** lleva un contador con 1. En el móvil (o con la ventana estrecha), el contador está en **Más**.
4. **Dashboard:** «Necesita tu atención» empieza por la acción, con el trabajo, el riesgo y «Esperando aprobación».
5. **Aprobaciones:** la petición sale arriba, en negrita, con «Riesgo alto» y «Pendiente».
   - Los filtros de estado, riesgo y proyecto cambian la URL.
   - «Quitar los filtros» vuelve a la lista entera.

## 4. Decidir desde la página

1. Pulsa **Revisar**. La página enseña:
   - la acción y su riesgo;
   - de dónde viene: proyecto, trabajo, ejecución, repositorio y coste;
   - en «Lo que dejaron sus dependencias», el agente de `plan` con su salida (`summary`), la rama y los ficheros con +/−;
   - «Lo que se permitirá al aprobar» (`implement` con sus herramientas);
   - «Qué pasa con cada decisión»;
   - el historial, vacío.
2. **Comentar** sin texto dice «Escribe el comentario.». Escribe algo y comenta: aparece en el historial y la petición sigue pendiente.
3. **Rechazar** sin texto dice «Para rechazar, escribe el motivo.». No rechaces todavía.
4. Escribe «Cubre también los negativos» y pulsa **Pedir cambios**.
   - Aparece «Corrigiendo», con el enlace al agente que corrige.
   - En el progreso, la puerta dice «Corrigiendo».
   - En `plan` hay un segundo agente que reanuda la sesión.
5. Cuando termina la corrección, la página dice «Corregido» y enlaza a «Ver la ronda 2 (pendiente)».
   - La ronda 2 enseña «Cambios pedidos en la ronda 1» con tu comentario.
   - En el progreso, la puerta dice «Espera una decisión · ronda 2».
6. Pulsa **Aprobar**. Como el riesgo es alto, se abre «Aprobar con riesgo alto» con «Pueden arrancar: implement.». Pulsa **Sí, aprobar**.
   - La página pasa a «Aprobada».
   - El contador del menú desaparece.
   - La ejecución sigue con `implement` y `tests` y termina «Completada».
7. En el timeline de la ejecución salen, en castellano:
   - «aprobar-plan pide aprobar …»;
   - «admin pidió cambios a plan: …»;
   - la ronda 2;
   - «admin aprobó aprobar-plan».

## 5. Rechazar

1. Lanza `con-puerta` otra vez y abre su aprobación.
2. Escribe un motivo y pulsa **Rechazar**.
3. La petición pasa a «Rechazada». La ejecución termina «Fallida», con `implement` y `tests` «Cancelada».

## 6. Publicar con puerta

En `con-puerta`, **Nueva versión** y **Publicar…**: el aviso dice «Se para en esta puerta hasta que alguien decide en Aprobaciones» con `aprobar-plan`, su acción y «Riesgo alto».

## Qué mirar si algo falla

- `GET /api/approvals?status=PENDING`: lo que cuenta el menú.
- `GET /api/approvals/<id>`: lo que enseña la página.
- En la ejecución, `stages[].approval` de la puerta: la última petición, con `correctionAgentRunId` mientras corrige.
