-- EV-C: el banco de evaluaciones del juez. Un caso es un cambio ya juzgado que se guarda para
-- volver a juzgarlo, con otro juez o con otro modelo, y ver si acierta.
CREATE TABLE eval_case (
    id               uuid PRIMARY KEY,
    repository_id    uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    -- La ejecución de la que sale: su fase de juez da la rúbrica y los jueces de siempre.
    source_run_id    uuid        NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    definition_id    uuid        NOT NULL,
    stage_key        text        NOT NULL,
    escape_id        uuid REFERENCES judge_escape (id) ON DELETE SET NULL,
    -- Lo que se juzga: el worktree en head_sha, con el diff contra base_sha.
    base_sha         text        NOT NULL,
    head_sha         text        NOT NULL,
    work_item_key    text        NOT NULL,
    title            text        NOT NULL,
    description      text,
    -- Lo que debería decir un buen juez: suspender un escape, aprobar un cambio que se quedó.
    expected         text        NOT NULL CHECK (expected IN ('debe-suspender', 'debe-aprobar')),
    note             text,
    created_by       text        NOT NULL,
    created_at       timestamptz NOT NULL,
    -- Un caso por ejecución.
    UNIQUE (source_run_id)
);
CREATE INDEX eval_case_repository ON eval_case (repository_id, created_at DESC);

-- Una repetición: un juez con el caso. Las dos de un A/B comparten `pair_id`.
CREATE TABLE eval_run (
    id               uuid PRIMARY KEY,
    case_id          uuid        NOT NULL REFERENCES eval_case (id) ON DELETE CASCADE,
    pair_id          uuid,
    judge            text        NOT NULL,
    lens             text,
    provider         text,
    model            text,
    -- La ejecución de evaluación que la lleva y la invocación del juez.
    workflow_run_id  uuid REFERENCES workflow_run (id) ON DELETE SET NULL,
    agent_run_id     uuid REFERENCES agent_run (id) ON DELETE SET NULL,
    status           text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    score            numeric(4, 2),
    passed           boolean,
    findings         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    summary          text,
    -- Si dijo lo esperado: suspendió un `debe-suspender` o aprobó un `debe-aprobar`.
    correct          boolean,
    error            text,
    created_by       text        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);
CREATE INDEX eval_run_case ON eval_run (case_id, created_at DESC);
CREATE INDEX eval_run_workflow_run ON eval_run (workflow_run_id);
CREATE INDEX eval_run_agent_run ON eval_run (agent_run_id);

-- La ejecución que lleva una repetición: `eval` no sigue su workflow ni sale en las listas.
ALTER TABLE workflow_run ADD COLUMN purpose text CHECK (purpose IN ('eval'));

-- El juez de una repetición.
ALTER TABLE agent_run DROP CONSTRAINT agent_run_role_check;
ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_role_check CHECK (role IN ('judge', 'skeptic', 'guide', 'eval'));
