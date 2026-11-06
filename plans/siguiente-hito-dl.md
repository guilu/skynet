# Siguiente hito DL — Deployment Lifecycle

Estado: plan revisado y **aprobado por Diego el 2026-11-05**. Milestones 91 (DL-A) a 95 (DL-E); issues #237 a #241. Implementación en curso: DL-A, DL-B, DL-C y DL-E fusionadas; DL-D en revisión.

## Contexto
Hermes configuró listeners de GitHub en Omarchy. Omarchy recibe webhooks y despliega con Git y Docker Compose sobre un entorno compartido, y Hermes avisa por Telegram.
- Skynet debe observar y verificar los despliegues sin sustituir los listeners ni alterar las reglas actuales de revisión y fusión.
- No hay que asumir que un webhook recibido o un compose exitoso equivalen a una aplicación sana.
- Hermes despliega también el propio Skynet, que se reinicia durante su propio despliegue.

## Decisión: eventos con GitHub Deployments
Los listeners de Hermes crean un *deployment* y sus *deployment statuses* con la API de GitHub. Skynet los recibe por el webhook de la App que ya existe (eventos `deployment` y `deployment_status`, permiso **Deployments: lectura**).
- Reutiliza la firma, la cola, la idempotencia por entrega y la re-entrega de GH.
- No hay segundo receptor ni secreto nuevo.
- Cuando el que se despliega es Skynet, GitHub guarda el evento hasta que vuelve.
- La reconciliación (DL-C) lista los despliegues de GitHub para recuperar lo perdido.

## Alcance y orden: A → B → C → E → D
- **DL-A**: modelo, persistencia (V45), eventos `deployment.*` y API de lectura. Dos ejes: estado (`PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `SUPERSEDED`) y salud (`UNKNOWN`, `CHECKING`, `HEALTHY`, `UNHEALTHY`, `TIMEOUT`).
- **DL-B**: eventos `deployment` y `deployment_status` por el webhook de la App. Incluye un script de referencia y documentación para los listeners. Aplicarlo en Omarchy es un paso manual de la guía.
- **DL-C**: health checks con URL configurada por el admin (nunca la del evento), timeouts, reintentos acotados, despliegues atascados, reconciliación y evidencia.
- **DL-E**: interfaz por proyecto y repositorio: versión desplegada por entorno, estado y salud por separado, e historial.
- **DL-D**: un nuevo tipo de regla en los disparadores de DS («al quedar sano» o «al fallar la salud» → workflow), apagado por defecto. Reutiliza la cola y el freno diario de DS-C.

## Reglas de arquitectura
1. Separar la aprobación de la PR, el resultado del despliegue y la salud posterior. No cambiar la política actual de Hermes ni autorizar fusiones por el estado del despliegue.
2. Entorno único compartido:
   - serializar por destino;
   - un despliegue que termina bien deja `SUPERSEDED` a los anteriores, para no atribuir pruebas al SHA equivocado;
   - no se promete staging aislado ni rollback automático.
3. Idempotencia por id de despliegue y de estado. Tolerancia al desorden: un estado más viejo que llega tarde se guarda sin aplicarse. No registrar secretos: el error pasa por el redactor.
4. Una verificación de salud fallida no revierte nada: se registra la evidencia y se avisa.
5. No duplicar los disparadores de DS; reutilizar la configuración de repositorios y los límites.

## Entrega y aceptación
- Una PR por issue, cada una con su guía `docs/tests/probar-dl-a.md` … `probar-dl-e.md`.
- Las pruebas usan GitHub falso o curl firmado.
- La prueba real natural es el despliegue de la propia PR por Hermes, y es solo observación. DL-D se prueba solo sobre demo-repo.
- DL-A a DL-D son medianas (BD, API o seguridad). DL-E puede ser pequeña si es solo web.

## Fuera de alcance
Staging dedicado por PR, Wake-on-LAN, despliegue en el PC Windows, rollback automático y sustitución del puente `skynet-commands`.
