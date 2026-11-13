-- Los escapes del juez (EV-A): un cambio que el juez aprobó, se fusionó y luego se revirtió en la
-- rama por defecto, o que una persona marcó porque se le escapó un fallo.
CREATE TABLE judge_escape (
    id              uuid PRIMARY KEY,
    repository_id   uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    workflow_run_id uuid        NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    pull_request_id uuid        NOT NULL REFERENCES github_pull_request (id) ON DELETE CASCADE,
    merged_sha      text,
    origin          text        NOT NULL CHECK (origin IN ('revert-pr', 'revert-push', 'manual')),
    -- Dónde se vio: la PR del revert (#N) o el commit, con su enlace.
    seen_ref        text,
    seen_url        text,
    note            text,
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL,
    -- Un escape por ejecución: el revert por PR y su push son el mismo escape.
    UNIQUE (workflow_run_id)
);
CREATE INDEX judge_escape_repository ON judge_escape (repository_id, created_at DESC);
CREATE INDEX judge_escape_created ON judge_escape (created_at DESC);
