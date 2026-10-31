-- EN-A: la PR que abre una fase github-pr. Una por ejecución y repositorio: los reintentos y las
-- rondas de corrección suben a la misma rama (skynet/<clave>) y actualizan la misma PR.
--   PUSHING  el runner está subiendo la rama
--   PUSHED   la rama está subida y falta abrir o actualizar la PR
--   OPEN     la PR está abierta
--   FAILED   no se pudo abrir la PR (el motivo, en error)
CREATE TABLE github_pull_request (
    id              uuid PRIMARY KEY,
    workflow_run_id uuid        NOT NULL REFERENCES workflow_run (id),
    repository_id   uuid        NOT NULL REFERENCES repository (id),
    stage_run_id    uuid        NOT NULL REFERENCES stage_run (id),
    agent_run_id    uuid        NOT NULL REFERENCES agent_run (id),
    full_name       text        NOT NULL,
    branch          text        NOT NULL,
    base            text,
    title           text        NOT NULL,
    body            text        NOT NULL,
    draft           boolean     NOT NULL DEFAULT false,
    labels          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status          text        NOT NULL
        CHECK (status IN ('PUSHING', 'PUSHED', 'OPEN', 'FAILED')),
    head_sha        text,
    number          integer,
    url             text,
    error           text,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (workflow_run_id, repository_id)
);

CREATE INDEX github_pull_request_pending ON github_pull_request (next_attempt_at)
    WHERE status = 'PUSHED';
