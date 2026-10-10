-- W2-A: motor de workflows. Una ejecución guarda con qué se lanzó (repositorio, datos de entrada y
-- límites) para que el motor pueda arrancar cada fase cuando le llegue el turno, aunque sea tras un
-- reinicio.
ALTER TABLE workflow_run
    ADD COLUMN repository_id uuid REFERENCES repository (id),
    ADD COLUMN inputs        jsonb NOT NULL DEFAULT '{}',
    -- Límites pedidos al lanzar, ya dentro de la política del repositorio: los usan los agentes
    -- que no fijan los suyos.
    ADD COLUMN launch_limits jsonb NOT NULL DEFAULT '{}';

UPDATE workflow_run r SET repository_id = (
    SELECT a.repository_id FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id
    WHERE s.workflow_run_id = r.id ORDER BY a.created_at LIMIT 1);

-- Ejecuciones que el motor tiene que evaluar (outbox): se inserta en la misma transacción que el
-- cambio que lo provoca. Como mucho hay una por ejecución; un cambio mientras se evalúa sube
-- `generation`, y entonces el trabajo no se borra al terminar y se evalúa otra vez.
CREATE TABLE workflow_job (
    workflow_run_id  uuid PRIMARY KEY REFERENCES workflow_run (id) ON DELETE CASCADE,
    generation       bigint      NOT NULL DEFAULT 0,
    run_after        timestamptz NOT NULL,
    -- Alquiler del worker que lo procesa: si el control plane cae, vence y otro lo retoma.
    locked_until     timestamptz,
    attempts         integer     NOT NULL DEFAULT 0,
    last_error       text,
    created_at       timestamptz NOT NULL
);
CREATE INDEX workflow_job_due_idx ON workflow_job (run_after);
