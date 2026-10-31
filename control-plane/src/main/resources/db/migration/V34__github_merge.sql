-- EN-C: la fusión de la PR de una fase github-merge, una por intento de la fase.
--   PENDING  falta fusionarla (o GitHub no respondió y se reintenta)
--   MERGED   fusionada, por Skynet o a mano en GitHub
--   FAILED   no se pudo fusionar (el motivo, en error)
CREATE TABLE github_merge (
    stage_run_id    uuid PRIMARY KEY REFERENCES stage_run (id),
    workflow_run_id uuid        NOT NULL REFERENCES workflow_run (id),
    pull_request_id uuid        NOT NULL REFERENCES github_pull_request (id),
    method          text        NOT NULL CHECK (method IN ('squash', 'merge', 'rebase')),
    delete_branch   boolean     NOT NULL,
    expected_sha    text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'MERGED', 'FAILED')),
    merged_sha      text,
    branch_deleted  boolean     NOT NULL DEFAULT false,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    error           text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE INDEX github_merge_pending ON github_merge (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX github_merge_run ON github_merge (workflow_run_id);

-- La PR puede acabar fusionada o cerrada, por Skynet o a mano en GitHub.
ALTER TABLE github_pull_request DROP CONSTRAINT github_pull_request_status_check;
ALTER TABLE github_pull_request ADD CONSTRAINT github_pull_request_status_check
    CHECK (status IN ('PUSHING', 'PUSHED', 'OPEN', 'FAILED', 'MERGED', 'CLOSED'));
ALTER TABLE github_pull_request ADD COLUMN merged_sha text;
CREATE INDEX github_pull_request_number ON github_pull_request (repository_id, number);
