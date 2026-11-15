-- MV-E: el curador de la memoria (docs/memoria.md, «El curador»). Una fase con `memory: curate`
-- lee la memoria, escribe páginas de la wiki y deja un informe con propuestas de archivar o
-- fusionar entradas, que decide una persona.
ALTER TABLE agent_run DROP CONSTRAINT agent_run_memory_check,
    ADD CONSTRAINT agent_run_memory_check CHECK (memory IN ('READ', 'WRITE', 'CURATE'));
ALTER TABLE memory_token DROP CONSTRAINT memory_token_mode_check,
    ADD CONSTRAINT memory_token_mode_check CHECK (mode IN ('READ', 'WRITE', 'CURATE'));

-- Cada curación: lo que hizo una invocación del curador.
CREATE TABLE memory_curation (
    id              uuid PRIMARY KEY,
    project_id      uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    repository_id   uuid REFERENCES repository (id) ON DELETE SET NULL,
    workflow_run_id uuid REFERENCES workflow_run (id) ON DELETE SET NULL,
    agent_run_id    uuid UNIQUE REFERENCES agent_run (id) ON DELETE SET NULL,
    summary         text        NOT NULL,
    -- Las páginas que escribió ([{slug, title, version, changed}]) y las que no valían
    -- ([{slug, reason}]).
    pages           jsonb       NOT NULL,
    rejected        jsonb       NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX memory_curation_project ON memory_curation (project_id, created_at DESC);

-- Sus propuestas: archivar entradas o fusionarlas en una. Ninguna se aplica sola.
CREATE TABLE memory_proposal (
    id           uuid PRIMARY KEY,
    curation_id  uuid        NOT NULL REFERENCES memory_curation (id) ON DELETE CASCADE,
    -- El orden en que las devolvió el curador.
    position     integer     NOT NULL,
    project_id   uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    action       text        NOT NULL CHECK (action IN ('ARCHIVE', 'MERGE')),
    -- Las entradas que se archivan; al fusionar, las que se quedan dentro de `into_id`.
    observation_ids uuid[]   NOT NULL,
    into_id      uuid REFERENCES memory_observation (id) ON DELETE CASCADE,
    reason       text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    decided_by   text,
    decided_at   timestamptz,
    created_at   timestamptz NOT NULL,
    CHECK ((action = 'MERGE') = (into_id IS NOT NULL))
);
CREATE INDEX memory_proposal_project ON memory_proposal (project_id, status, created_at DESC);
