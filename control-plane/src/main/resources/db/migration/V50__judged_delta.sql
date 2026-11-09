-- AU-B: lo que se fusiona es lo juzgado.

-- El commit que vio cada juez (con lo que no estaba confirmado, la instantánea del runner) y el de
-- la ronda que aprobó la fase.
ALTER TABLE stage_verdict ADD COLUMN head_sha text;
ALTER TABLE stage_judge ADD COLUMN judged_sha text;

-- Mientras se juzga el delta, la fase github-check espera a sus jueces.
ALTER TABLE github_ci_wait DROP CONSTRAINT github_ci_wait_status_check;
ALTER TABLE github_ci_wait ADD CONSTRAINT github_ci_wait_status_check
    CHECK (status IN ('WAITING', 'FIXING', 'PUSHING', 'JUDGING', 'PASSED', 'FAILED'));

-- Lo que cambió entre lo juzgado y lo que va a fusionarse, cuando la CI pasa en una fase
-- github-check: su nivel de riesgo y, si se juzgó, el veredicto. Los veredictos de sus jueces van
-- en stage_verdict con la fase github-check.
CREATE TABLE judged_delta (
    stage_run_id       uuid PRIMARY KEY REFERENCES stage_run (id),
    workflow_run_id    uuid        NOT NULL REFERENCES workflow_run (id),
    judge_stage_run_id uuid        NOT NULL REFERENCES stage_run (id),
    judged_sha         text        NOT NULL,
    head_sha           text        NOT NULL,
    status             text        NOT NULL
        CHECK (status IN ('UNCHANGED', 'SKIPPED', 'JUDGING', 'PASSED', 'FAILED')),
    tier               smallint CHECK (tier BETWEEN 0 AND 2),
    risk               jsonb,
    score              numeric(4, 2),
    approvals          integer,
    judges             integer,
    findings           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    error              text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE INDEX judged_delta_run ON judged_delta (workflow_run_id);
