# Probar DL-D - Disparadores por despliegue

DL-D (#240) es la quinta y última PR del hito DL. Añade un tipo de disparador nuevo: **al quedar sano** o **al fallar la salud** lo desplegado en un entorno, lanzar un workflow. Lo cuenta [`docs/disparadores.md`](../disparadores.md) §13.

- **Regla `DEPLOYMENT`** por repositorio con `environment` y `health` (`HEALTHY` o `UNHEALTHY`, que incluye `TIMEOUT`). **Nace apagada.**
- **Una sola vez por despliegue**: tabla `trigger_deployment_fire` con clave primaria (regla, despliegue), escrita en la misma transacción que el lanzamiento.
- **Nunca por un SHA sustituido**: solo mira lo desplegado ahora (`SUCCEEDED`), y lo vuelve a comprobar justo antes de lanzar.
- **Solo lo nuevo**: comprobaciones terminadas después de crear o cambiar la regla, y como mucho del último día.
- Reutiliza el **interruptor**, el **freno diario** (ahora cuenta también estos lanzamientos) y la **cola** de DS-C. Si la anterior sigue en marcha, `SKIPPED`.
- **Migración `V47`**: tipo `DEPLOYMENT`, columnas `environment` y `deploy_health` en `trigger_rule`, y la tabla `trigger_deployment_fire`.
- **Eventos**: `trigger.deployment.launched`, `.skipped` y `.rejected`.
- **Web**: en la ficha del repositorio, «Nuevo disparador → Al desplegar» y la regla en la lista con su último disparo.

No cambia la política de aprobación y fusión de Hermes, ni su despliegue, ni el aviso por Telegram.

Las pruebas reales se hacen **solo sobre demo-repo**, con un workflow de una fase `command` (sin agentes ni regeneraciones). Nada de esta guía toca ficheros versionados de skynet; lo que se crea se borra al final.

## 1. Pruebas automáticas

```bash
SKYNET_TEST_DB_URL=jdbc:postgresql://localhost:5432/skynet_test \
  ./gradlew :control-plane:test --tests '*TriggerDeploymentIT' --tests '*TriggerContractIT' \
  --tests '*TriggerScheduleIT' --tests '*MigrationsIT' --tests '*ModularityTest'
cd web && npx vitest run src/Triggers.test.tsx src/contracts.test.ts
```

`TriggerDeploymentIT`:
- la regla nace apagada y, apagada, no hace nada;
- al encenderla, lo sano de antes no cuenta;
- con el interruptor general apagado, `REJECTED` una sola vez;
- encendido, un despliegue sano lanza el workflow en el trabajo `skynet:deployment:<id>`, y otra pasada no lanza nada;
- con la anterior en marcha, `SKIPPED`;
- dos despliegues sanos seguidos: el sustituido no dispara, solo el actual;
- una regla `UNHEALTHY` no dispara con un sano, sí con `TIMEOUT`, y el freno diario (1) rechaza el siguiente;
- validación: sin `environment`, entorno con espacios, con etiqueta, sin `health` o con `inputPolicy`, 400.

`web/e2e/deployments.e2e.ts` (E2E con el GitHub falso, entra en las rápidas):
- llegan los webhooks `deployment` y `deployment_status`;
- la salud se comprueba contra el propio `/actuator/health` y queda «Sano»;
- la pestaña Despliegues lo enseña;
- la regla «al quedar sano» lanza una vez, y una entrega repetida (200) u otra entrega del mismo estado no lanzan otra.

## 2. Prueba real sobre demo-repo

Con Skynet desplegado y el repositorio **demo-repo** registrado (su id en `REPO`). Se usa el entorno `prueba-dl-d`.

```bash
B=http://localhost:8080; AUTH=admin:<contraseña>
PSQL="docker compose -f deploy/docker-compose.yml exec -T postgres psql -U skynet -d skynet -tAc"
J='-H Content-Type:application/json'

# Un workflow sin agentes.
cat > /tmp/humo-dl-d.yaml <<'EOF'
id: humo-dl-d
name: Humo DL-D
stages:
  - id: probar
    type: command
    command: "true"
EOF
DRAFT=$(jq -Rs '{sourceYaml: .}' /tmp/humo-dl-d.yaml | curl -s -u $AUTH $J -X POST $B/api/workflows -d @-)
curl -s -u $AUTH $J -X POST $B/api/workflow-versions/$(jq -r .id <<< "$DRAFT")/publish \
  -d "{\"revision\": $(jq -r .revision <<< "$DRAFT")}" >/dev/null

# Comprobación de salud contra la propia API y la regla, apagada.
curl -s -u $AUTH $J -X PUT $B/api/repositories/$REPO/health-checks/prueba-dl-d \
  -d '{"url": "http://localhost:8080/actuator/health", "initialDelaySeconds": 0, "attempts": 2}' >/dev/null
RULE=$(curl -s -u $AUTH $J -X POST $B/api/repositories/$REPO/triggers \
  -d '{"kind": "DEPLOYMENT", "workflowKey": "humo-dl-d", "environment": "prueba-dl-d", "health": "HEALTHY"}')
jq '{enabled, deployment}' <<< "$RULE"     # enabled: false
ID=$(jq -r .id <<< "$RULE")
```

1. **Apagada no lanza.** Crea un despliegue sano y espera 20 s: `lastOutcome` sigue `null`.

   ```bash
   $PSQL "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, status, requested_at, finished_at, status_at, created_at, updated_at)
    VALUES ('00000000-0000-0000-0000-00000000d1d1', '$REPO', 'prueba-dl-d', 'dld-1', repeat('1', 40), 'SUCCEEDED', now(), now(), now(), now(), now())"
   curl -s -o /dev/null -u $AUTH -X POST $B/api/deployments/00000000-0000-0000-0000-00000000d1d1/health-check
   sleep 20; curl -s -u $AUTH $B/api/triggers/$ID | jq .deployment.lastOutcome   # null
   ```

2. **Encendida, lo de antes no cuenta; lo nuevo lanza una vez.** Enciéndela (con los disparadores generales encendidos en Ajustes → GitHub) y vuelve a comprobar la salud del mismo despliegue:

   ```bash
   curl -s -u $AUTH $J -X PUT $B/api/triggers/$ID -d '{"enabled": true}' >/dev/null
   curl -s -o /dev/null -u $AUTH -X POST $B/api/deployments/00000000-0000-0000-0000-00000000d1d1/health-check
   sleep 25; curl -s -u $AUTH $B/api/triggers/$ID | jq '.deployment | {lastOutcome, lastRunId}'   # LAUNCHED
   curl -s -o /dev/null -u $AUTH -X POST $B/api/deployments/00000000-0000-0000-0000-00000000d1d1/health-check
   sleep 25; $PSQL "SELECT count(*) FROM trigger_deployment_fire WHERE trigger_id = '$ID'"           # 1
   ```

3. **En la web**: Proyecto → Configuración → ficha de demo-repo → Disparadores. La regla dice «Al quedar sano lo desplegado en `prueba-dl-d` lanza humo-dl-d…», «Una vez por despliegue; nunca por un SHA ya sustituido» y «Última: …, lanzó (ver)». Crea otra con «Nuevo disparador → Al desplegar» y comprueba que nace «Desactivado» si no marcas «Activarlo ya». Bórrala.

4. **Un SHA sustituido no dispara.** Inserta otro despliegue más nuevo que termina bien (el anterior queda sustituido) y vuelve a comprobar el **viejo**: 409, y no hay disparo nuevo.

   ```bash
   $PSQL "UPDATE deployment SET status = 'SUPERSEDED' WHERE id = '00000000-0000-0000-0000-00000000d1d1'"
   curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -X POST $B/api/deployments/00000000-0000-0000-0000-00000000d1d1/health-check   # 409
   ```

## 3. Limpieza y checkout limpio

```bash
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -X DELETE $B/api/triggers/$ID                                    # 204
curl -s -o /dev/null -w '%{http_code}\n' -u $AUTH -X DELETE $B/api/repositories/$REPO/health-checks/prueba-dl-d    # 204
$PSQL "DELETE FROM deployment WHERE environment = 'prueba-dl-d'"
rm /tmp/humo-dl-d.yaml
git status --porcelain   # vacío
```

El trabajo «humo-dl-d (al desplegar en prueba-dl-d)» y su ejecución se pueden archivar desde la web.
