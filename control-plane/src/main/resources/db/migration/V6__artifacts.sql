-- Git, tests y artefactos (M5): lo que produce cada invocación y la verificación independiente de
-- su worktree.

-- Comando de validación del repositorio. Sin comando no hay verificación.
ALTER TABLE repository
    ADD COLUMN validation_command  text,
    -- Globs de los informes JUnit XML, relativos al worktree.
    ADD COLUMN test_report_paths   text[] NOT NULL DEFAULT '{}';

-- Ejecución del comando de validación en el worktree de un agente. No cambia el estado del agente
-- ni el de su ejecución: es lo que Skynet comprueba, frente a lo que declara el agente.
CREATE TABLE verification_run (
    id              uuid PRIMARY KEY,
    agent_run_id    uuid        NOT NULL REFERENCES agent_run (id),
    workspace_id    uuid        NOT NULL REFERENCES workspace (id),
    runner_id       uuid        NOT NULL REFERENCES runner (id),
    -- AUTO (al completarse una invocación) o MANUAL (reejecutada desde la web).
    trigger         text        NOT NULL,
    command         text        NOT NULL,
    -- QUEUED, RUNNING, PASSED, FAILED o ERROR.
    status          text        NOT NULL,
    exit_code       integer,
    signal          text,
    error           text,
    tests_total     integer,
    tests_failed    integer,
    tests_errors    integer,
    tests_skipped   integer,
    created_at      timestamptz NOT NULL,
    started_at      timestamptz,
    finished_at     timestamptz
);

CREATE INDEX verification_run_agent_idx ON verification_run (agent_run_id);
-- Bloqueo de escritores: una verificación viva ocupa su worktree.
CREATE INDEX verification_run_live_idx ON verification_run (workspace_id)
    WHERE status IN ('QUEUED', 'RUNNING');

-- Artefacto de una invocación o de una verificación. El contenido vive en el BlobStore,
-- direccionado por su sha256 (uri); aquí solo lo necesario para listarlo.
CREATE TABLE artifact (
    id                   uuid PRIMARY KEY,
    workflow_run_id      uuid        NOT NULL REFERENCES workflow_run (id),
    stage_run_id         uuid        NOT NULL REFERENCES stage_run (id),
    agent_run_id         uuid        NOT NULL REFERENCES agent_run (id),
    verification_run_id  uuid REFERENCES verification_run (id),
    type                 text        NOT NULL,
    name                 text        NOT NULL,
    media_type           text        NOT NULL,
    size                 bigint      NOT NULL,
    sha256               text        NOT NULL,
    uri                  text        NOT NULL,
    -- Se recortó por superar el tamaño máximo.
    truncated            boolean     NOT NULL DEFAULT false,
    metadata             jsonb       NOT NULL DEFAULT '{}',
    created_at           timestamptz NOT NULL,
    -- Reenviar un artefacto no lo duplica.
    UNIQUE NULLS NOT DISTINCT (agent_run_id, verification_run_id, type, name)
);

CREATE INDEX artifact_agent_idx ON artifact (agent_run_id);
