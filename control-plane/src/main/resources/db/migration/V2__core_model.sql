-- Modelo MVP (docs/implementation-plan.md §5). Las tablas de runner, workspace y artefactos
-- llegan con los hitos que las usan (M2 y M5).

CREATE TABLE project (
    id               uuid PRIMARY KEY,
    key              text        NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name             text        NOT NULL,
    description      text,
    work_item_seq    integer     NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL,
    version          bigint      NOT NULL
);

CREATE TABLE repository (
    id               uuid PRIMARY KEY,
    project_id       uuid        NOT NULL REFERENCES project (id),
    name             text        NOT NULL,
    local_path       text        NOT NULL,
    remote_url       text,
    default_branch   text        NOT NULL,
    created_at       timestamptz NOT NULL,
    version          bigint      NOT NULL,
    UNIQUE (project_id, name)
);

CREATE TABLE work_item (
    id               uuid PRIMARY KEY,
    project_id       uuid        NOT NULL REFERENCES project (id),
    number           integer     NOT NULL,
    key              text        NOT NULL UNIQUE,
    title            text        NOT NULL,
    description      text,
    type             text        NOT NULL,
    external_ref     text,
    status           text        NOT NULL,
    created_at       timestamptz NOT NULL,
    version          bigint      NOT NULL,
    UNIQUE (project_id, number)
);

CREATE TABLE workflow_definition (
    id               uuid PRIMARY KEY,
    key              text        NOT NULL,
    version          integer     NOT NULL,
    source_yaml      text        NOT NULL,
    created_at       timestamptz NOT NULL,
    UNIQUE (key, version)
);

-- Workflow implícito de la Fase 1: una única fase con un agente.
INSERT INTO workflow_definition (id, key, version, source_yaml, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'adhoc', 1,
        E'id: adhoc\nversion: 1\nstages:\n  - id: agent\n    type: agent\n', now());

CREATE TABLE workflow_run (
    id               uuid PRIMARY KEY,
    work_item_id     uuid        NOT NULL REFERENCES work_item (id),
    definition_id    uuid        NOT NULL REFERENCES workflow_definition (id),
    status           text        NOT NULL,
    created_at       timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    version          bigint      NOT NULL
);
CREATE INDEX workflow_run_work_item_idx ON workflow_run (work_item_id, created_at DESC);

CREATE TABLE stage_run (
    id               uuid PRIMARY KEY,
    workflow_run_id  uuid        NOT NULL REFERENCES workflow_run (id),
    stage_key        text        NOT NULL,
    status           text        NOT NULL,
    attempt          integer     NOT NULL,
    created_at       timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    version          bigint      NOT NULL,
    UNIQUE (workflow_run_id, stage_key, attempt)
);

CREATE TABLE agent_run (
    id                   uuid PRIMARY KEY,
    stage_run_id         uuid        NOT NULL REFERENCES stage_run (id),
    parent_agent_run_id  uuid REFERENCES agent_run (id),
    repository_id        uuid        NOT NULL REFERENCES repository (id),
    kind                 text        NOT NULL,
    status               text        NOT NULL,
    provider             text        NOT NULL,
    provider_session_id  text,
    model                text,
    runner_id            uuid,
    process_id           bigint,
    created_at           timestamptz NOT NULL,
    started_at           timestamptz,
    last_activity_at     timestamptz,
    finished_at          timestamptz,
    exit_code            integer,
    num_turns            integer,
    input_tokens         bigint,
    output_tokens        bigint,
    -- Claude Code reporta el coste acumulado de la sesión (docs/claude-code-stream-json.md);
    -- cost_usd es el de esta invocación.
    cost_usd             numeric(12, 6),
    cost_usd_cumulative  numeric(12, 6),
    error                text,
    version              bigint      NOT NULL
);
CREATE INDEX agent_run_stage_run_idx ON agent_run (stage_run_id);

CREATE TABLE prompt (
    id               uuid PRIMARY KEY,
    agent_run_id     uuid        NOT NULL REFERENCES agent_run (id),
    role             text        NOT NULL,
    content          text        NOT NULL,
    sha256           text        NOT NULL,
    created_at       timestamptz NOT NULL
);
CREATE INDEX prompt_agent_run_idx ON prompt (agent_run_id);

-- Registro append-only. `sequence` es global, sin huecos y en orden de commit: se asigna con
-- event_sequence bloqueada hasta el commit, de modo que leer `sequence > N` nunca se salta un
-- evento confirmado más tarde. Por eso la propia tabla hace de outbox para el streaming.
CREATE TABLE event_sequence (
    id               smallint PRIMARY KEY CHECK (id = 1),
    last_value       bigint   NOT NULL
);
INSERT INTO event_sequence (id, last_value) VALUES (1, 0);

CREATE TABLE event (
    sequence         bigint PRIMARY KEY,
    event_id         uuid        NOT NULL UNIQUE,
    source_event_id  text UNIQUE,
    workflow_run_id  uuid REFERENCES workflow_run (id),
    aggregate_type   text        NOT NULL,
    aggregate_id     uuid        NOT NULL,
    event_type       text        NOT NULL,
    payload          jsonb       NOT NULL,
    occurred_at      timestamptz NOT NULL,
    recorded_at      timestamptz NOT NULL
);
CREATE INDEX event_workflow_run_idx ON event (workflow_run_id, sequence);
CREATE INDEX event_aggregate_idx ON event (aggregate_id, sequence);

CREATE FUNCTION event_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'event es append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER event_append_only
    BEFORE UPDATE OR DELETE ON event
    FOR EACH ROW EXECUTE FUNCTION event_is_append_only();
