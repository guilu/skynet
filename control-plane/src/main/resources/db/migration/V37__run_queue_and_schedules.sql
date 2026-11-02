-- DS-C: cola por repositorio y disparadores programados.

-- Ejecuciones a la vez en el repositorio; NULL: las de skynet.runs.max-concurrent-per-repository.
-- Las que pasan del límite esperan en PENDING hasta que se libera un hueco.
ALTER TABLE repository ADD COLUMN max_concurrent_runs integer
    CHECK (max_concurrent_runs BETWEEN 1 AND 50);

-- Un disparador SCHEDULE lanza su workflow con una expresión cron de Spring en su zona horaria,
-- siempre sobre el mismo trabajo, que se crea la primera vez con work_item_title.
ALTER TABLE trigger_rule DROP CONSTRAINT trigger_rule_kind_check;
ALTER TABLE trigger_rule ADD CONSTRAINT trigger_rule_kind_check CHECK (kind IN ('ISSUE', 'SCHEDULE'));
ALTER TABLE trigger_rule ALTER COLUMN label DROP NOT NULL;
ALTER TABLE trigger_rule ADD COLUMN cron text;
ALTER TABLE trigger_rule ADD COLUMN time_zone text;
ALTER TABLE trigger_rule ADD COLUMN work_item_title text;
-- Al purgar el trabajo, el siguiente disparo crea otro.
ALTER TABLE trigger_rule ADD COLUMN work_item_id uuid REFERENCES work_item (id) ON DELETE SET NULL;
ALTER TABLE trigger_rule ADD COLUMN next_fire_at timestamptz;
ALTER TABLE trigger_rule ADD COLUMN last_fired_at timestamptz;
-- Qué pasó en el último disparo: LAUNCHED, SKIPPED (la anterior seguía en marcha) o REJECTED.
ALTER TABLE trigger_rule ADD COLUMN last_outcome text;
ALTER TABLE trigger_rule ADD COLUMN last_reason text;
ALTER TABLE trigger_rule ADD COLUMN last_run_id uuid REFERENCES workflow_run (id) ON DELETE SET NULL;
ALTER TABLE trigger_rule ADD CONSTRAINT trigger_rule_shape CHECK (
    (kind = 'ISSUE' AND label IS NOT NULL)
    OR (kind = 'SCHEDULE' AND cron IS NOT NULL AND time_zone IS NOT NULL
        AND work_item_title IS NOT NULL));

CREATE INDEX trigger_rule_due ON trigger_rule (next_fire_at)
    WHERE kind = 'SCHEDULE' AND enabled;

-- La cola: las ejecuciones que esperan hueco en su repositorio, por orden de llegada.
CREATE INDEX workflow_run_queued ON workflow_run (repository_id, created_at) WHERE status = 'PENDING';
CREATE INDEX workflow_run_running ON workflow_run (repository_id) WHERE status = 'RUNNING';
