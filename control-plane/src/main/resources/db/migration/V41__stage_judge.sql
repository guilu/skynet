-- VA-B: el juez con rúbrica de las fases verification.

-- Qué hace una invocación en su fase: NULL para el agente de siempre, 'judge' para un juez.
ALTER TABLE agent_run
    ADD COLUMN role text CHECK (role IN ('judge'));

-- El estado del juez de una fase: la ronda en curso, si corrige y con qué invocación, y el
-- resultado de la última ronda, que leen las fases siguientes (<fase>.score, .passed…).
CREATE TABLE stage_judge (
    stage_run_id     uuid PRIMARY KEY REFERENCES stage_run (id),
    workflow_run_id  uuid        NOT NULL REFERENCES workflow_run (id),
    status           text        NOT NULL
        CHECK (status IN ('CHECKING', 'JUDGING', 'FIXING', 'PASSED', 'FAILED')),
    round            integer     NOT NULL DEFAULT 1,
    -- El worktree que se juzga; los jueces trabajan en copias suyas.
    workspace_id     uuid        NOT NULL,
    fix_agent_run_id uuid REFERENCES agent_run (id),
    score            numeric(4, 2),
    passed           boolean,
    approvals        integer,
    judges           integer,
    findings         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    criteria         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    error            text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

CREATE INDEX stage_judge_run ON stage_judge (workflow_run_id);

-- El veredicto de cada juez en cada ronda: su nota por criterio y sus hallazgos.
CREATE TABLE stage_verdict (
    id              uuid PRIMARY KEY,
    stage_run_id    uuid        NOT NULL REFERENCES stage_run (id),
    workflow_run_id uuid        NOT NULL REFERENCES workflow_run (id),
    round           integer     NOT NULL,
    judge           text        NOT NULL,
    lens            text,
    agent_run_id    uuid        NOT NULL REFERENCES agent_run (id),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    score           numeric(4, 2),
    passed          boolean,
    criteria        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    findings        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    summary         text,
    error           text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (stage_run_id, round, judge)
);

CREATE INDEX stage_verdict_stage ON stage_verdict (stage_run_id, round);
CREATE INDEX stage_verdict_run ON stage_verdict (workflow_run_id);
