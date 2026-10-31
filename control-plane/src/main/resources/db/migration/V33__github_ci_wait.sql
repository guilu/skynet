-- EN-B: la espera de la CI de una fase github-check. Una por intento de la fase.
--   WAITING  esperando a que la CI del SHA termine
--   FIXING   la CI falló y la fase `fix` está corrigiendo
--   PUSHING  la corrección terminó y el runner sube la rama
--   PASSED   la CI pasó (o no hay CI y la fase lo admite)
--   FAILED   la CI falló sin más rondas, o no se pudo corregir
CREATE TABLE github_ci_wait (
    stage_run_id      uuid PRIMARY KEY REFERENCES stage_run (id),
    workflow_run_id   uuid        NOT NULL REFERENCES workflow_run (id),
    pull_request_id   uuid        NOT NULL REFERENCES github_pull_request (id),
    repository_id     uuid        NOT NULL REFERENCES repository (id),
    head_sha          text        NOT NULL,
    status            text        NOT NULL
        CHECK (status IN ('WAITING', 'FIXING', 'PUSHING', 'PASSED', 'FAILED')),
    round             integer     NOT NULL DEFAULT 0,
    waiting_since     timestamptz NOT NULL,
    next_check_at     timestamptz,
    fix_agent_run_id  uuid REFERENCES agent_run (id),
    push_agent_run_id uuid REFERENCES agent_run (id),
    checks            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    error             text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE INDEX github_ci_wait_due ON github_ci_wait (next_check_at) WHERE status = 'WAITING';
CREATE INDEX github_ci_wait_sha ON github_ci_wait (repository_id, head_sha) WHERE status = 'WAITING';
CREATE INDEX github_ci_wait_run ON github_ci_wait (workflow_run_id);
