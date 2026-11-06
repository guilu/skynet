-- DL-D: disparadores por despliegue. Una regla DEPLOYMENT lanza su workflow cuando lo desplegado en
-- un entorno queda sano (HEALTHY) o falla la salud (UNHEALTHY, que incluye TIMEOUT), siempre sobre
-- el mismo trabajo, como los programados.
ALTER TABLE trigger_rule DROP CONSTRAINT trigger_rule_kind_check;
ALTER TABLE trigger_rule ADD CONSTRAINT trigger_rule_kind_check
    CHECK (kind IN ('ISSUE', 'SCHEDULE', 'DEPLOYMENT'));
ALTER TABLE trigger_rule ADD COLUMN environment text;
ALTER TABLE trigger_rule ADD COLUMN deploy_health text CHECK (deploy_health IN ('HEALTHY', 'UNHEALTHY'));
ALTER TABLE trigger_rule DROP CONSTRAINT trigger_rule_shape;
ALTER TABLE trigger_rule ADD CONSTRAINT trigger_rule_shape CHECK (
    (kind = 'ISSUE' AND label IS NOT NULL)
    OR (kind = 'SCHEDULE' AND cron IS NOT NULL AND time_zone IS NOT NULL
        AND work_item_title IS NOT NULL)
    OR (kind = 'DEPLOYMENT' AND environment IS NOT NULL AND deploy_health IS NOT NULL
        AND work_item_title IS NOT NULL));

CREATE INDEX trigger_rule_deployment ON trigger_rule (repository_id)
    WHERE kind = 'DEPLOYMENT' AND enabled;

-- Cada despliegue dispara cada regla una sola vez (la clave primaria lo garantiza entre
-- instancias). Guarda qué pasó, para el historial y para el freno diario.
CREATE TABLE trigger_deployment_fire (
    trigger_id      uuid        NOT NULL REFERENCES trigger_rule (id) ON DELETE CASCADE,
    deployment_id   uuid        NOT NULL REFERENCES deployment (id) ON DELETE CASCADE,
    commit_sha      text        NOT NULL,
    outcome         text        NOT NULL CHECK (outcome IN ('LAUNCHED', 'SKIPPED', 'REJECTED')),
    reason          text,
    workflow_run_id uuid REFERENCES workflow_run (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL,
    PRIMARY KEY (trigger_id, deployment_id)
);

CREATE INDEX trigger_deployment_fire_launched ON trigger_deployment_fire (created_at)
    WHERE outcome = 'LAUNCHED';
