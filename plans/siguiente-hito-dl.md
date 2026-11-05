# Siguiente hito DL — Deployment Lifecycle

Estado: propuesta aprobada para creación de issues (2026-11-05). Implementación pendiente.

## Contexto
Hermes configuró listeners GitHub en Omarchy; Omarchy recibe webhooks y despliega mediante Git y Docker Compose sobre un entorno compartido. Hermes avisa por Telegram. Skynet debe observar y verificar los despliegues sin sustituir listeners ni alterar las reglas actuales de revisión/fusión. No asumir que un webhook recibido o un compose exitoso equivalen a aplicación saludable.

## Alcance y orden
- **DL-A**: modelo de despliegue, persistencia, API de consulta y estados.
- **DL-B**: contrato autenticado e idempotente de eventos de despliegue; adaptación de los listeners existentes en Omarchy, sin crear un segundo receptor GitHub.
- **DL-C**: health checks, timeouts, detección de despliegues atascados, reconciliación y evidencia.
- **DL-D**: disparadores de workflows por eventos de despliegue, con límites de concurrencia y deduplicación; aprovechar el mecanismo DS existente.
- **DL-E**: interfaz por proyecto/repositorio/PR con commit, rama, estado, hora, URL, comprobaciones y enlaces.

## Reglas de arquitectura
1. Separar aprobación de PR, resultado de despliegue y salud posterior. No cambiar la política actual de Hermes ni autorizar merges por el simple estado del despliegue.
2. Entorno único compartido: serializar despliegues por destino; mostrar qué SHA está realmente desplegado y evitar atribuir pruebas al SHA equivocado. No prometer staging aislado ni rollback automático.
3. Contrato mínimo: eventId, repository, environment, deploymentId, pullRequest (opcional), branch, commitSha, eventType (started/succeeded/failed), timestamp, url (opcional), error (opcional). Autenticación, idempotencia, tolerancia a eventos fuera de orden y no registrar secretos.
4. El listener debe responder rápido y publicar los eventos de manera fiable; reintentos con backoff y reconciliación de pérdidas.
5. Skynet verifica salud después del despliegue; una verificación fallida no implica revertir automáticamente. Registrar evidencia y notificar sin confundir resultado de compose con disponibilidad real.
6. No duplicar los disparadores GitHub existentes de DS. Reutilizar configuración de repositorios y límites.

## Dependencias
DL-A → DL-B → DL-C → DL-D → DL-E (DL-E puede avanzar después de DL-A si el contrato se estabiliza).

## Entrega y aceptación
Una PR por issue. Cada PR debe incluir `docs/tests/probar-dl-a.md` ... `probar-dl-e.md`, respectivamente, con pruebas automáticas, pasos manuales, resultados esperados, casos negativos y verificación de checkout limpio. Hermes podrá ejecutar estas guías siguiendo el procedimiento actual. Probar con simuladores y repositorio demo antes de afectar despliegues reales.

## Fuera de alcance
Staging dedicado por PR, Wake-on-LAN, despliegue en el PC Windows, rollback automático y sustitución del bridge de issues `skynet-commands`.
