-- Interacción con sesiones (M4): reanudar, reintentar y bifurcar necesitan saber dónde trabajó cada
-- invocación y con qué límites se lanzó.

-- Worktree de una invocación en la máquina de su runner. Lo anuncia el runner con
-- agent.workspace.ready; una reanudación vuelve a usar el de su padre.
CREATE TABLE workspace (
    id             uuid PRIMARY KEY,
    repository_id  uuid        NOT NULL REFERENCES repository (id),
    runner_id      uuid        NOT NULL REFERENCES runner (id),
    path           text        NOT NULL,
    branch         text        NOT NULL,
    base_commit    text,
    created_at     timestamptz NOT NULL,
    UNIQUE (runner_id, path)
);

ALTER TABLE agent_run
    ADD COLUMN workspace_id     uuid REFERENCES workspace (id),
    -- Límites efectivos de la invocación (los elegidos al lanzar o los predeterminados), para que
    -- un reintento o una reanudación repitan los mismos.
    ADD COLUMN max_turns        integer,
    ADD COLUMN max_budget_usd   numeric(12, 6),
    ADD COLUMN timeout_seconds  bigint;

-- Bloqueo de escritores por worktree y cadena de invocaciones de una sesión.
CREATE INDEX agent_run_workspace_idx ON agent_run (workspace_id) WHERE workspace_id IS NOT NULL;
CREATE INDEX agent_run_parent_idx ON agent_run (parent_agent_run_id)
    WHERE parent_agent_run_id IS NOT NULL;
